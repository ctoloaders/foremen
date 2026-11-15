# Carrier spike: DPD and GLS

Task 2.2, requirements 8.1 and 8.2. Run on 2026-11-15 from a developer machine.

**Method.** About a dozen HTTPS GET requests in total (robots files and a few probes per carrier), sequential and
mostly 3 or more seconds apart, with one repeat of the DPD probe. Every request sent
`User-Agent: ForemenSpike/1.0 (+contact: dev@foremen.example; research, low volume)`.
Only synthetic, non-existent tracking numbers were used, so no real parcel data was read. No cookies were
kept and no bot protection or login was bypassed.

Terms of use were **not read or verified** (requirement 8.2 states the spec does not verify them). The
robots and API-registration observations below are the only access-policy evidence collected.

## Summary

| Carrier | Mechanism found | Verdict |
|---|---|---|
| DPD (PL) | HTML page parsing only; page served a "technical break" notice to every probe | `MANUAL_ONLY` |
| GLS (PL) | Open REST endpoint behind the page, but it redirects to an API-access registration page | `MANUAL_ONLY` |

---

## DPD (Poland)

### Mechanism

Page parsing. The public tracking site `tracktrace.dpd.com.pl` is a server-rendered HTML form
(`/findParcel`) with a result page `/parcelDetails`. No documented unauthenticated JSON endpoint was found.
DPD's documented tracking APIs (DPD Poland web services, DPD Group APIs) need a customer account and
credentials, which are out of scope (spec: "courier APIs requiring credentials").

### URL pattern

Tracking page link (can be built from the number, so link-out works):

```
https://tracktrace.dpd.com.pl/parcelDetails?typ=1&p1=<NUMBER>
```

Form entry point: `https://tracktrace.dpd.com.pl/findParcel`.

### Number pattern

Not confirmed by a real sample. DPD Poland parcel numbers are commonly 14 characters: 13 digits and a
trailing letter (for example `0000000000000A` format, as used in the probe). Other lengths exist for
international DPD parcels. This is background knowledge, not an observation. The soft `matches()` check
should therefore stay permissive (`[A-Z0-9]{9,20}`), which fits the spec's "accepted with a warning flag"
rule.

### Sample response (redacted)

Both probes to `/parcelDetails?typ=1&p1=<synthetic number>` returned `200 text/html`, 9805 bytes,
identical in both calls. The body was the generic page shell (banners, language switch, "Wyszukaj ponownie")
followed by a maintenance notice:

```
HTTP/1.1 200
content-type: text/html;charset=UTF-8

... "Zlokalizuj przesyłkę" ... "Wyszukaj ponownie" ...
Przerwa techniczna
W chwili obecnej prowadzone są prace serwisowe. Prosimy spróbować później.
```

No parcel result markup, no embedded JSON and no XHR endpoint appeared in the HTML. It is not known whether
the notice is a real outage, or a response to a synthetic number or a non-browser client. A parser fixture of a
real result page could not be recorded.

### Terms of use and robots

- `https://tracktrace.dpd.com.pl/robots.txt`: `User-agent: *` with `Allow: /`, and a Cloudflare-style
  `Content-Signal: search=yes, ai-train=no, use=reference`. Named AI crawlers (GPTBot, ClaudeBot, CCBot,
  Google-Extended, anthropic-ai and others) are `Disallow: /`. A generic server-side client is not blocked
  by robots, but the signals say content may be used for reference and not for AI training.
- `https://www.dpd.com/robots.txt`: only `/wp-admin/` disallowed. Does not cover the tracking host.
- Terms of use: not verified.

### Rate limits observed

None observed in the few requests sent, spaced 3 to 5 seconds apart (no 429, no `Retry-After`). Too few requests to
conclude anything. The `www.dpd.com` host sits behind Cloudflare (`__cf_bm` cookie), so bot scoring may
apply at higher volume.

### Verdict: `MANUAL_ONLY`

Reasons: only HTML parsing is available, the only response seen was a maintenance page so no parser can be
built or fixture recorded (requirement 8.3), and the fragile page-scrape approach has no upside over
link-out. Keep `DPD` selectable, build the tracking link from the number, and accept manual data.
Revisit if a real result page can be captured later by a person using a real parcel number.

---

## GLS (Poland / GLS Group)

### Mechanism

The public page `https://gls-group.com/PL/pl/sledzenie-paczek/?match=<NUMBER>` (112 KB HTML, single page app
style) loads data from a REST path family under `/app/service/`. Two path prefixes appeared in the page
source:

- `/app/service/open/rest/PL/pl/...`
- `/app/service/closed/rest/PL/pl/rslg002` (closed, requires login)

The widely known open tracking call is `/app/service/open/rest/<CC>/<lang>/rstt001?match=<NUMBER>`. Calling it
directly with `Accept: application/json` did **not** return data. It answered `303 See Other` to
`https://gls-group.eu/DE/en/register-api-access/`. The "open" REST path is therefore gated by API-access
registration for non-browser use. The 303 also came with `Access-Control-Allow-Origin: https://gls-group.eu`,
which restricts browser cross-origin callers, but the redirect is the decisive signal for a server client.

### URL pattern

Tracking page link (can be built from the number):

```
https://gls-group.com/PL/pl/sledzenie-paczek/?match=<NUMBER>
```

The request without a trailing slash redirected (301/302) to the slash form and returned `200 text/html`.

### Number pattern

Not confirmed by a real sample. GLS parcel numbers are numeric, typically 11 or 12 digits in Poland
(`NNNNNNNNNNN[N]`), with longer forms for international parcels. Background knowledge, not observed. Soft
`matches()` should accept `[0-9]{8,14}`.

### Sample response (redacted)

Direct REST probe:

```
GET /app/service/open/rest/PL/pl/rstt001?match=<synthetic number>
Accept: application/json

HTTP/1.1 303 See Other
Location: https://gls-group.eu/DE/en/register-api-access/
Access-Control-Allow-Origin: https://gls-group.eu
Cache-Control: max-age=0, no-cache, no-store, private
(empty body)
```

Page probe: `200 text/html`, 112214 bytes, an HTML application shell; the result is rendered client-side, so
the HTML holds no parcel data to parse.

### Terms of use and robots

- `https://gls-group.com/robots.txt`: `User-agent: *` disallows only `/survey/`; `EmailCollector` is blocked.
  `/app/service/` and `/PL/pl/sledzenie-paczek/` are not disallowed.
- The redirect to a "register API access" page is an explicit statement that automated access to the REST
  endpoint is meant to go through registration. Under requirement 8.2 ("a carrier whose terms forbid
  automated access SHALL get `MANUAL_ONLY`") this is treated as the same signal. Terms text itself: not
  verified.

### Rate limits observed

None observed in the few requests sent, spaced 3 to 5 seconds apart. No 429 and no `Retry-After`. The `Cache-Control:
no-store` on the REST call means no caching help from the carrier side.

### Verdict: `MANUAL_ONLY`

Reasons: the data endpoint is gated behind API-access registration, and the HTML page carries no parcel data
(client-rendered). A working adapter would need either a registered API client (credentials, out of scope)
or headless-browser scraping (against the spec's outbound-safety rules). Keep `GLS` selectable, build the
tracking link from the number, and accept manual data.

---

## Notes for task 2.4 (consolidation)

- Neither DPD nor GLS adapters should be built in task 4.1.
- Link-out URL templates to keep in the registry's `trackingPageUrl(number)` for `MANUAL_ONLY` carriers:
  - `DPD`: `https://tracktrace.dpd.com.pl/parcelDetails?typ=1&p1={number}`
  - `GLS`: `https://gls-group.com/PL/pl/sledzenie-paczek/?match={number}`
- Future upgrade path through the same SPI (not built here, per requirement 8.4): a registered GLS API
  client, DPD Poland web services, or an aggregator adapter.
- Gaps: no real parcel number was used, so number patterns and result formats are unobserved, and terms of
  use were not read.
