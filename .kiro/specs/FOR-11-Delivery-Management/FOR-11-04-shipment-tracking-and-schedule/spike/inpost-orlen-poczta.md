# Carrier spike: InPost, Orlen Paczka, Poczta Polska / Pocztex

Task 2.1, Requirements 8.1 and 8.2. Date of spike: 2026-11-15.

## Method and limits

- About 25 plain `curl` GET requests in total, spaced at least 3 s apart, no retries, no parallelism.
- Client identified with `User-Agent: ForemenSpike/1.0 (+contact: dev@foremen.example; carrier-tracking-feasibility)`.
- No real tracking number was available. Probes used obviously fake numbers (`000...0`, `2000000000000`), so
  **no real carrier payload with data was observed**. Where a response shape is described below it is marked
  "observed" (seen in this spike) or "documented" (from public documentation, not seen).
- Terms of use were **not read in full**. Per Requirement 8.2 this spec does not verify them. Where robots
  or terms could not be confirmed, the verdict falls to `MANUAL_ONLY`.
- Rate limits: no `X-RateLimit-*` or `Retry-After` headers were seen on any host. With so few requests, no
  throttling could be provoked, and none was attempted. "No limit observed" is not "no limit".

## Summary

| Carrier | Mechanism | Verdict |
|---|---|---|
| InPost | Documented ShipX tracking endpoint, JSON, no token needed | `ADAPTER` (provisional, see conditions) |
| Orlen Paczka | Undocumented JSONP endpoint behind own page, HTML fragment in JSON | `MANUAL_ONLY` |
| Poczta Polska / Pocztex | JS widget with reCAPTCHA, Imperva bot protection | `MANUAL_ONLY` |

---

## 1. InPost

**Mechanism.** Public, unauthenticated JSON endpoint of the ShipX API (the same host used for the
documented ShipX integration). Not page parsing.

**URL pattern.** `GET https://api-shipx-pl.easypack24.net/v1/tracking/{trackingNumber}`
Human link-out: `https://inpost.pl/sledzenie-przesylek?number={trackingNumber}` (page returned 200 HTML;
link pattern taken from the carrier's public convention, not tested for prefilling).

**Number pattern.** Parcel locker / courier numbers are 24 digits (`^\d{24}$`). Other InPost products
(for example Allegro InPost, international) may use other forms; validate loosely (`^\d{20,26}$`) and let
the carrier answer 404 for the rest.

**Observed requests.**

1. `GET /` on the host: `200`, `{"version":"2.10.9"}` (service is up, Cloudflare in front).
2. `GET /v1/tracking/000000000000000000000000`: `404`, `application/json;charset=UTF-8`.

Redacted 404 body (observed):

```json
{"trackingNumber":"<REDACTED-24-DIGITS>","message":"Tracking information about <REDACTED-24-DIGITS> shipment has not been found."}
```

The unknown-number answer is a clean 404 with JSON, no authentication challenge. That confirms the endpoint
works without a token for tracking.

**Success shape (documented, NOT observed).** Expected fields: `tracking_number`, `service`, `type`,
`status`, `custom_attributes` (`size`, `target_machine_id`, `end_of_week_collection`),
`expected_flow`, `tracking_details[]` (each `status`, `origin_status`, `agency`, `datetime`), `created_at`,
`updated_at`. The recorded fixture for task 4.1 must come from a real parcel; whoever has one must record it
and redact the number and any locker/address data. Until then the parser tests have no real fixture.

**robots.** `https://inpost.pl/robots.txt` is a standard CMS file (Allow/Disallow for `/core/`, `/admin/`,
`/search/` and similar). It does not disallow `/sledzenie-przesylek`. The API host returned no robots file
(it is a JSON API, robots does not apply in the usual sense).

**Terms.** Not read. InPost documents ShipX as an API for integrators; its developer portal also announces
an OAuth 2.0 based API (`developers.inpost-group.com`). Whether the unauthenticated tracking endpoint may be
used by third-party software without a contract is **not confirmed**. Requirement 8.4 already leaves a token
adapter out of scope.

**Rate limits.** None observed in headers (2 requests). Cloudflare is in front, so aggressive polling may be
challenged. The scheduler cadence (4 times per working day per open shipment, back-off, manual refresh limit)
is far below any plausible limit.

**Verdict: `ADAPTER` (provisional).** Conditions before task 4.1 ships it enabled:
1. A real fixture is recorded and redacted.
2. The product owner accepts the terms risk (unauthenticated endpoint, not read in full terms).
3. Master switch `foremen.procurement.tracking.enabled` stays `false` until then (already the default).

If the terms are found to forbid automated use, switch to `MANUAL_ONLY` per Requirement 8.2.

---

## 2. Orlen Paczka

**Mechanism.** The tracking page `https://www.orlenpaczka.pl/sledz-paczke/` is a WordPress page whose script
(`.../themes/orlenpaczka/dist/track.dist.js`) calls an **undocumented JSONP endpoint** on a different host
(`nadaj.orlenpaczka.pl`). The `nadajUrl` value is set inline in the page. The response is JSON wrapped in a
`callback(...)` call and contains a pre-rendered HTML table (`historyHtml`) beside a `history` array.

**URL pattern.** `GET https://nadaj.orlenpaczka.pl/parcel/api-status?id={number}` (the page adds
`&jsonp=...` / `callback`; the endpoint answered plain GET with `callback(...)`).
Human link-out: `https://www.orlenpaczka.pl/sledz-paczke/?numer={number}` (taken from the page script).

**Number pattern (from the page script).** Accepted by the front end: 13 digits starting with `2` or `3`,
or exactly 7 digits, or exactly 10 digits. Anything else shows "Błędny numer paczki".

**Observed requests.**

1. `GET https://www.orlenpaczka.pl/sledz-paczke/`: `200 text/html`, 71 kB.
2. `GET .../track.dist.js`: `200`, 94 kB (read to find the endpoint).
3. `GET https://nadaj.orlenpaczka.pl/parcel/api-status?id=2000000000000`: `200`,
   `application/javascript`. Redacted body (observed, fake number so empty history):

```
callback({"status":"OK","number":"<REDACTED-13-DIGITS>","full":true,
 "historyHtml":"<table class=\"table\"> ... <tbody class=\"gradientTableRow\"></tbody></table>",
 "history":[],"label":"","return":false,"truckNo":"Brak danych","returnTruck":"Brak danych"})
```

Note that an unknown number still yields `status: OK` with an empty history. The shape of a populated
`history` entry was not observed.

**robots.** `https://orlenpaczka.pl/robots.txt` and `https://www.orlenpaczka.pl/robots.txt` both return
**403 Forbidden** (Apache). Robots rules cannot be read, so permission cannot be inferred.

**Terms.** Not found or read. The site links "Cennik i regulaminy" (parcel service terms), which do not
address automated tracking access as far as the page text shows (not read in detail).

**Rate limits.** None observed (3 requests). Server returned no rate-limit headers.

**Why `MANUAL_ONLY`.** The endpoint is an internal, undocumented page helper on a separate host, returns
JSONP with an HTML fragment, and the host denies even `robots.txt`. It can change without notice and nothing
signals that automated use is permitted. Requirement 8.2 says to prefer link-out when terms are not
clearly acceptable.

**Verdict: `MANUAL_ONLY`.** Link-out: `https://www.orlenpaczka.pl/sledz-paczke/?numer={number}` with the number
URL-encoded. Revisit if Orlen publishes a tracking API or confirms the endpoint may be used.

---

## 3. Poczta Polska / Pocztex

**Mechanism.** Page and widget only. `https://emonitoring.poczta-polska.pl/` (eMonitoring) loads
`/widget/widget.tracking.min.js`, which renders a **Google reCAPTCHA** (`renderWTraReCaptcha`) before returning
data. `www.pocztex.pl` and `www.poczta-polska.pl` sit behind **Imperva (Incapsula)** bot protection (cookies
`visid_incap_*`, `incap_ses_*`, `/_Incapsula_Resource` script injected into pages and even into a 404).
The `pocztex.pl/sledzenie-przesylek/` page returned 200 HTML, but contains no direct tracking JSON endpoint
(only a generic search form and a list of foreign carrier links). The official Poczta Polska tracking web
service (SOAP "Sledzenie") needs registered credentials, which this spec excludes (no per-carrier contract).

**URL pattern.** Link-out only: `https://emonitoring.poczta-polska.pl/?numer={number}` (the page's
mobile link uses `numer=`; prefilling by query not verified). Pocztex page:
`https://www.pocztex.pl/sledzenie-przesylek/`.

**Number pattern.** Domestic registered letters and parcels: 20 digits (`^\d{20}$`) or 13-character
UPU S10 codes (`^[A-Z]{2}\d{9}[A-Z]{2}$`). Pocztex courier numbers use a `PX`-style or digit form; not
confirmed in this spike.

**Observed requests.**

1. `GET https://www.pocztex.pl/robots.txt`: `200`, content is `User-Agent: *` / `Allow: /` (allows everything).
2. `GET https://www.poczta-polska.pl/robots.txt`: `404` (served by nginx behind Imperva).
3. `GET https://emonitoring.poczta-polska.pl/robots.txt`: `200`, lists named bots (Googlebot, msnbot, Slurp,
   Teoma) with empty `Disallow`; the generic `*` rules were not read to the end (only first 8 lines).
4. `GET https://www.pocztex.pl/sledzenie-przesylek/`: `200`, 42 kB HTML.
5. `GET https://emonitoring.poczta-polska.pl/`: `200`, 96 kB HTML.
6. `GET .../widget/widget.tracking.min.js` and `.../js/script.js`: `200`.

No tracking payload sample exists: reaching data needs a reCAPTCHA token, which must not be bypassed.

**Terms.** Not read. A reCAPTCHA plus Imperva is a clear signal that automated access is not intended.

**Rate limits.** Not observable. Imperva would likely block or challenge repeated automated requests.

**Verdict: `MANUAL_ONLY`.** Link-out to eMonitoring, manual ETA and driver data. Revisit only if the business
signs up for Poczta Polska's official tracking API, which is out of scope for this spec.

---

## Consequences for the adapter set (input for task 2.4)

- Build in 4.1: **InPost** adapter (provisional, needs a real redacted fixture and owner acceptance).
- Link-out builder only (no fetch): **Orlen Paczka**, **Poczta Polska / Pocztex**.
- Cleanup: temporary files were written to `/tmp` only. No repository files other than this one were changed.
