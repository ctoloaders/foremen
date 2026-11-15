# Spike: DHL, UPS and FedEx (task 2.3)

Requirements: 8.1, 8.2. Same template as 2.1.

## Method and limits

- 9 requests in total, spaced at least 2 s apart, one pass, no retries beyond one repeat of the DHL robots request.
- Client identification: `User-Agent: ForemenSpike/1.0 (contact: dev@foremen.example)`.
- Only dummy or published sample numbers were sent (UPS `1Z999AA10123456784`, FedEx `794644790138`, DHL `00340434292135100186`). No real customer data.
- No attempt was made to bypass bot protection, rotate IPs or spoof a browser.
- Items marked **(docs, not verified live)** come from the carriers' public developer documentation as I know it. Re-check them against the developer portals before building the adapters.

## Summary

| Carrier | Mechanism found | Works without credentials | Verdict |
|---|---|---|---|
| DHL | Official Shipment Tracking Unified API, API key | No (401 without key) | ADAPTER (needs API key) |
| UPS | Official Tracking API (OAuth 2.0 client credentials) | No | ADAPTER (needs OAuth credentials); website JSON is MANUAL_ONLY |
| FedEx | Official Track API (OAuth 2.0 client credentials) | No | ADAPTER (needs OAuth credentials); website JSON is MANUAL_ONLY |

None of the three has an unauthenticated public endpoint that is acceptable to build on. All three have free official APIs, so the verdict depends on whether the product owner accepts registering Foremen as an API client with each carrier. If not, all three are `MANUAL_ONLY`.

## DHL

- **Mechanism.** Official "Shipment Tracking - Unified" API. Documented, key-based, free tier.
- **URL pattern.** `GET https://api-eu.dhl.com/track/shipments?trackingNumber={number}`, header `DHL-API-Key: {key}`. Optional query params: `service`, `language`, `requesterCountryCode`.
- **Number pattern.** Depends on the DHL division: DHL Express 10 digits; DHL eCommerce / Parcel (DE) 12 or 20 digits (often starting `0034...`); DHL Paket `JJD` / `JVGL` prefixes. Regex for validation: `^(\d{10}|\d{12}|\d{20}|JJD\d{18,}|JVGL\d{10,})$`. Treat as a hint only, let the API reject unknown numbers.
- **Observed.** Request without a key: `HTTP 401`, body `{"status":401,"title":"Unauthorized","detail":"Access to the resource is not allowed."}`. This confirms the endpoint is live and requires a key.
- **Sample response (docs, not verified live, redacted).**
  ```json
  {
    "shipments": [{
      "id": "<redacted>",
      "service": "express",
      "origin": {"address": {"addressLocality": "<city>"}},
      "destination": {"address": {"addressLocality": "<city>"}},
      "status": {"timestamp": "2025-01-01T10:00:00", "statusCode": "transit", "status": "TRANSIT", "description": "<text>"},
      "estimatedTimeOfDelivery": "2025-01-03T00:00:00",
      "events": [{"timestamp": "...", "statusCode": "transit", "description": "<text>", "location": {"address": {"addressLocality": "<city>"}}}]
    }]
  }
  ```
  Status codes: `pre-transit`, `transit`, `delivered`, `failure`, `unknown`.
- **Terms of use / robots.** `https://www.dhl.com/robots.txt` could not be fetched: two attempts and a page request failed with `HTTP/2 INTERNAL_ERROR` (connection reset, no HTTP status). Treated as unknown, and the website is not used. The API requires registering on developer.dhl.com and accepting the API terms. Tracking data is meant for the shipper's or recipient's own shipments.
- **Rate limits.** Observed: none (single request). **(docs, not verified live)** Default 250 calls/day and 1 call per 5 s per key; higher quotas by request.
- **Verdict: ADAPTER**, conditional on a DHL API key. Fit with the polling design: 250/day is enough for a small number of active shipments; the adapter must throttle to 1 call per 5 s and respect the daily cap. Unverified numbers can be raised with DHL.

## UPS

- **Mechanism.** Official UPS Tracking API (developer.ups.com), OAuth 2.0 client credentials. The website's internal JSON is not usable.
- **URL pattern.** Token: `POST https://onlinetools.ups.com/security/v1/oauth/token` (Basic auth, `grant_type=client_credentials`). Tracking: `GET https://onlinetools.ups.com/api/track/v1/details/{inquiryNumber}` with `Authorization: Bearer ...`, `transId`, `transactionSrc` headers. **(docs, not verified live)**
- **Number pattern.** `1Z` + 6-char shipper account + 2-digit service + 8 digits (last is a check digit). Regex: `^1Z[0-9A-Z]{16}$`. Also 9-digit and 12-digit forms (UPS Freight / Mail Innovations). The check digit can be validated locally, which gives early rejection without a network call.
- **Observed.**
  - `GET https://www.ups.com/robots.txt` returned an Akamai "Access Denied" page (body `Access Denied`, HTTP 200). Robots rules could not be read.
  - `GET https://www.ups.com/track?loc=en_PL&tracknum=1Z999AA10123456784` returned HTTP 200: a JavaScript single-page app shell, no tracking data in the HTML.
  - `POST https://www.ups.com/track/api/Track/GetStatus?loc=en_PL` (the endpoint the page calls) returned the Akamai "Access Denied" page. The site's internal API is protected by bot management and not meant for programmatic use.
- **Sample response (docs, not verified live, redacted).**
  ```json
  {"trackResponse": {"shipment": [{"package": [{
    "trackingNumber": "1Z...",
    "deliveryDate": [{"type": "DEL", "date": "20250103"}],
    "activity": [{"location": {"address": {"city": "<city>", "country": "PL"}},
                  "status": {"type": "I", "description": "<text>", "code": "<code>"},
                  "date": "20250101", "time": "100000"}]
  }]}]}}
  ```
  Status type: `M` manifest, `I` in transit, `D` delivered, `X` exception, `P` pickup.
- **Terms of use / robots.** Website: access blocked for the identified client, so the website is `MANUAL_ONLY` and must not be scraped. API: requires a UPS account, an app registration and acceptance of the UPS Developer Kit license. Use is limited to tracking one's own and customers' shipments.
- **Rate limits.** Observed: none (blocked before any limit applies). **(docs, not verified live)** The Tracking API is throttled per client; documented guidance is to avoid polling more often than needed and not to exceed a modest request rate. Exact quota is not published.
- **Verdict: ADAPTER**, conditional on UPS OAuth credentials (client id and secret kept in config, token cached until expiry, about 4 h). The website route is `MANUAL_ONLY`.

## FedEx

- **Mechanism.** Official FedEx Track API (developer.fedex.com), OAuth 2.0 client credentials. The website's internal JSON is not usable.
- **URL pattern.** Token: `POST https://apis.fedex.com/oauth/token`. Tracking: `POST https://apis.fedex.com/track/v1/trackingnumbers` with body `{"includeDetailedScans": true, "trackingInfo": [{"trackingNumberInfo": {"trackingNumber": "..."}}]}`. Sandbox host: `apis-sandbox.fedex.com`. **(docs, not verified live)**
- **Number pattern.** 12 digits (Express), 15 digits (Ground), 20 or 22 digits (SmartPost / Ground 96 barcode, often starting `96` or `92`), 34 digits (barcode form). Regex: `^(\d{12}|\d{15}|\d{20}|\d{22}|\d{34})$`.
- **Observed.**
  - `GET https://www.fedex.com/robots.txt` returned HTTP 200. `User-agent: *` with `Disallow: /*?*` (any URL with a query string), `Disallow: /libs/`, `Disallow: /content/fedex-com/*`. The tracking page is explicitly allowed only for a few listed URLs (for example `/fedextrack/?cntry_code=us&tab=1&tracknums=&clienttype=wtrk`). Fetching tracking data by number from the website needs a query string or POST, which robots disallows or does not list.
  - `POST https://api.fedex.com/track/v1/trackingnumbers` (wrong host, sent without a token) returned a SOAP fault `Policy Falsified` / "Service Not Found", so the real API host is `apis.fedex.com`. No tracking data returned.
- **Sample response (docs, not verified live, redacted).**
  ```json
  {"output": {"completeTrackResults": [{"trackingNumber": "...", "trackResults": [{
    "latestStatusDetail": {"code": "IT", "description": "<text>"},
    "dateAndTimes": [{"type": "ESTIMATED_DELIVERY", "dateTime": "2025-01-03T00:00:00"}],
    "scanEvents": [{"date": "2025-01-01T10:00:00+01:00", "eventDescription": "<text>",
                    "scanLocation": {"city": "<city>", "countryCode": "PL"}}]
  }]}]}}
  ```
  Status codes include `IT` in transit, `DL` delivered, `DE` delivery exception, `PU` picked up, `OD` out for delivery.
- **Terms of use / robots.** Website scraping conflicts with robots (`Disallow: /*?*`), so it is `MANUAL_ONLY`. API: requires a FedEx developer account, project and API key/secret, and acceptance of the FedEx API terms. Production access for Track needs a FedEx account number on newer projects.
- **Rate limits.** Observed: none. **(docs, not verified live)** Rate limits are enforced per API key; FedEx returns `429` when exceeded and recommends backoff. Exact figures are not published openly.
- **Verdict: ADAPTER**, conditional on FedEx API credentials. The website route is `MANUAL_ONLY`.

## Common adapter notes for task 4.1

- All three adapters need credentials stored as configuration secrets (never in code or logs). Without credentials the carrier falls back to `MANUAL_ONLY`: the user enters status by hand.
- Poll no more often than needed (a few times per day per active shipment). Back off on `429` and `5xx`, and count consecutive failures as in Requirement 7.
- Map carrier statuses to the generic status set: DHL `pre-transit`/`transit`/`delivered`/`failure`; UPS `M`/`I`/`D`/`X`; FedEx `PU`/`IT`/`OD`/`DL`/`DE`.
- Validate the number format locally (regexes above) before any call.

## Decision needed from the product owner

Register Foremen as an API client with DHL, UPS and FedEx (free, a few days to get production access)? Yes: build three `ADAPTER`s. No: all three are `MANUAL_ONLY`.

## Request log

| # | Request | Result |
|---|---|---|
| 1 | GET dhl.com/robots.txt | connection error (HTTP/2 INTERNAL_ERROR), no status |
| 2 | GET ups.com/robots.txt | Akamai Access Denied |
| 3 | GET fedex.com/robots.txt | 200, rules above |
| 4 | GET dhl.com/robots.txt (repeat) | connection error |
| 5 | GET api-eu.dhl.com/track/shipments?trackingNumber=... | 401 Unauthorized |
| 6 | GET dhl.com/pl-pl/home/tracking.html | connection error |
| 7 | GET ups.com/track?loc=en_PL&tracknum=... | 200, JS app shell |
| 8 | POST ups.com/track/api/Track/GetStatus | Akamai Access Denied |
| 9 | POST api.fedex.com/track/v1/trackingnumbers | SOAP fault, wrong host |
