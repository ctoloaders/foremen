#!/usr/bin/env bash
# Salt Edge (API v6) helper: create Fake-provider connections to move the client account
# from Pending to Test status (Salt Edge requires >= 10 fake connections made via the API).
#
# Credentials are read from .secrets/saltedge.env (git-ignored). Secrets are passed to curl via
# stdin config (-K -), so they never appear in the process list or in this script's output.
#
# Usage:
#   scripts/saltedge-fake-connections.sh check            # verify keys work (GET /countries)
#   scripts/saltedge-fake-connections.sh providers        # list Fake/Sandbox providers (country XF)
#   scripts/saltedge-fake-connections.sh customer         # create (or reuse) the test customer
#   scripts/saltedge-fake-connections.sh connect [N]      # open N Connect sessions (default 10)
#   scripts/saltedge-fake-connections.sh status           # list connections + count active ones
#   scripts/saltedge-fake-connections.sh delete <id>|all  # remove connection(s) to free fake slots
#
# `connect` opens each Connect URL in the browser and waits for you to finish it in the widget
# (credentials for fake banks are shown in the widget's "Instructions" field).

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENV_FILE="${SALTEDGE_ENV_FILE:-$ROOT/.secrets/saltedge.env}"
STATE_FILE="$ROOT/.secrets/saltedge.state"

die() { echo "ERROR: $*" >&2; exit 1; }

command -v curl >/dev/null || die "curl is required"
command -v jq >/dev/null || die "jq is required"
[ -f "$ENV_FILE" ] || die "missing $ENV_FILE (copy the template and fill SALTEDGE_APP_ID / SALTEDGE_SECRET)"

set -a
# shellcheck disable=SC1090
. "$ENV_FILE"
set +a

[ -n "${SALTEDGE_APP_ID:-}" ] || die "SALTEDGE_APP_ID is empty in $ENV_FILE"
[ -n "${SALTEDGE_SECRET:-}" ] || die "SALTEDGE_SECRET is empty in $ENV_FILE"
BASE="${SALTEDGE_BASE_URL:-https://www.saltedge.com/api/v6}"
RETURN_TO="${SALTEDGE_RETURN_TO:-http://localhost:3000/}"
read -r -a PROVIDERS <<< "${SALTEDGE_FAKE_PROVIDERS:-fakebank_simple_xf fakebank_oauth_xf fake_demobank_xf fake_business_demobank_xf}"

# api METHOD PATH [JSON_BODY] -> prints response body; exits on HTTP >= 400.
api() {
  local method="$1" path="$2" body="${3:-}" out code resp
  out="$(mktemp)"
  local args=(-sS -o "$out" -w '%{http_code}' -X "$method"
    -H "Accept: application/json" -H "Content-type: application/json")
  [ -n "$body" ] && args+=(-d "$body")
  code="$(printf 'header = "App-id: %s"\nheader = "Secret: %s"\n' \
            "$SALTEDGE_APP_ID" "$SALTEDGE_SECRET" \
          | curl -K - "${args[@]}" "$BASE$path")" || { rm -f "$out"; die "request failed: $method $path"; }
  resp="$(cat "$out")"; rm -f "$out"
  if [ "$code" -ge 400 ]; then
    echo "HTTP $code on $method $path" >&2
    echo "$resp" | jq -r '.error | "\(.class // "?"): \(.message // "")"' 2>/dev/null >&2 || echo "$resp" >&2
    exit 1
  fi
  printf '%s' "$resp"
}

days_ago() { date -v-"$1"d +%F 2>/dev/null || date -d "-$1 days" +%F; }

customer_id() {
  if [ -f "$STATE_FILE" ] && grep -q '^CUSTOMER_ID=' "$STATE_FILE"; then
    sed -n 's/^CUSTOMER_ID=//p' "$STATE_FILE"
    return
  fi
  local ident resp cid
  ident="foremen-dev-$(date +%Y%m%d%H%M%S)"
  resp="$(api POST /customers "$(jq -nc --arg i "$ident" '{data:{identifier:$i}}')")"
  cid="$(echo "$resp" | jq -r '.data.customer_id // .data.id')"
  [ -n "$cid" ] && [ "$cid" != "null" ] || die "could not read customer_id from response"
  printf 'CUSTOMER_ID=%s\nCUSTOMER_IDENTIFIER=%s\n' "$cid" "$ident" > "$STATE_FILE"
  chmod 600 "$STATE_FILE"
  echo "$cid"
}

cmd_check() {
  local n
  n="$(api GET /countries | jq '.data | length')"
  echo "OK: credentials accepted, $n countries visible."
}

cmd_providers() {
  api GET "/providers?country_code=XF&include_sandboxes=true&exclude_inactive=true" \
    | jq -r '.data[] | [.code, .mode, .name] | @tsv' | column -t -s $'\t'
}

cmd_customer() {
  echo "customer_id: $(customer_id)"
}

cmd_connect() {
  local total="${1:-10}" cid from i provider body url
  cid="$(customer_id)"
  from="$(days_ago 89)"
  echo "Customer $cid — creating $total Connect sessions (providers: ${PROVIDERS[*]})"
  for ((i = 1; i <= total; i++)); do
    provider="${PROVIDERS[$(( (i - 1) % ${#PROVIDERS[@]} ))]}"
    body="$(jq -nc --arg cid "$cid" --arg from "$from" --arg p "$provider" --arg ret "$RETURN_TO" '{
      data: {
        customer_id: $cid,
        consent: { scopes: ["accounts", "transactions"], from_date: $from },
        attempt: { fetch_scopes: ["accounts", "balance", "transactions"], fetch_from_date: $from, return_to: $ret },
        widget: { skip_provider_selection: true },
        provider: { code: $p, include_sandboxes: true }
      }
    }')"
    url="$(api POST /connections/connect "$body" | jq -r '.data.connect_url')"
    [ -n "$url" ] && [ "$url" != "null" ] || die "no connect_url returned"
    echo
    echo "[$i/$total] $provider"
    echo "  $url"
    if command -v open >/dev/null; then open "$url"; fi
    read -r -p "  Finish the connection in the browser, then press Enter (s = skip, q = quit): " ans
    case "${ans:-}" in q|Q) break ;; esac
  done
  echo
  cmd_status
}

# Non-interactive: print one Connect URL for the given provider (for browser automation).
cmd_url() {
  local provider="${1:-${PROVIDERS[0]}}" cid from body
  cid="$(customer_id)"
  from="$(days_ago 89)"
  body="$(jq -nc --arg cid "$cid" --arg from "$from" --arg p "$provider" --arg ret "$RETURN_TO" '{
    data: {
      customer_id: $cid,
      consent: { scopes: ["accounts", "transactions"], from_date: $from },
      attempt: { fetch_scopes: ["accounts", "balance", "transactions"], fetch_from_date: $from, return_to: $ret },
      widget: { skip_provider_selection: true },
      provider: { code: $p, include_sandboxes: true }
    }
  }')"
  api POST /connections/connect "$body" | jq -r '.data.connect_url'
}

cmd_status() {
  local cid from_id="" page all="[]"
  cid="$(customer_id)"
  while :; do
    page="$(api GET "/connections?customer_id=$cid${from_id:+&from_id=$from_id}")"
    all="$(jq -c --argjson acc "$all" '$acc + .data' <<< "$page")"
    from_id="$(jq -r '.meta.next_id // empty' <<< "$page")"
    [ -n "$from_id" ] || break
  done
  echo "$all" | jq -r '.[] | [.id, .provider_code, .status, .created_at] | @tsv' | column -t -s $'\t' || true
  echo "Total: $(jq 'length' <<< "$all"), active: $(jq '[.[] | select(.status == "active")] | length' <<< "$all")"
}

# Remove connections to free fake-connection slots (FakeConnectionsLimitReached).
#   delete <id>  - one connection;  delete all - every connection of the test customer.
cmd_delete() {
  local target="${1:-}" cid ids
  [ -n "$target" ] || die "usage: delete <connection_id>|all"
  if [ "$target" = "all" ]; then
    cid="$(customer_id)"
    ids="$(api GET "/connections?customer_id=$cid" | jq -r ".data[].id")"
  else
    ids="$target"
  fi
  for id in $ids; do
    api DELETE "/connections/$id" >/dev/null && echo "deleted: $id"
  done
}

case "${1:-}" in
  check) cmd_check ;;
  providers) cmd_providers ;;
  customer) cmd_customer ;;
  connect) cmd_connect "${2:-10}" ;;
  url) cmd_url "${2:-}" ;;
  status) cmd_status ;;
  delete) cmd_delete "${2:-}" ;;
  *) sed -n '2,18p' "$0"; exit 1 ;;
esac
