# Carrier spike: consolidated verdicts (task 2.4)

Requirements: 8.1, 8.2, 8.5. Sources: `spike/inpost-orlen-poczta.md` (2.1), `spike/dpd-gls.md` (2.2), `spike/dhl-ups-fedex.md` (2.3). Spike date: 2026-11-15.

## Open decisions for the product owner

Relay these to the product owner. Nothing in task 4.1 depends on an answer except where stated.

1. **InPost: accept the terms risk?** The unauthenticated ShipX tracking endpoint (`GET https://api-shipx-pl.easypack24.net/v1/tracking/{number}`) works without a token, but InPost's terms were not read and it is not confirmed that third-party software may use it without a contract. Accept: the `InPostAdapter` is built and may be enabled. Decline: InPost becomes `MANUAL_ONLY` (link-out only). The master switch `foremen.procurement.tracking.enabled` stays `false` either way until this is answered.
2. **InPost: provide one real tracking number.** The success response shape is from documentation only. Someone with a real parcel must record the JSON and redact the number and any locker or address data. The parser tests need it as a fixture (Requirement 8.3).
3. **DHL, UPS, FedEx: register Foremen as an API client?** All three have free official APIs (DHL: API key; UPS and FedEx: OAuth 2.0 client credentials). Production access takes a few days, and FedEx may require a FedEx account number. Yes: build three adapters, and the owner supplies credentials as configuration secrets. No (or not yet): all three are link-out only. This can be decided per carrier.
4. **Orlen, Poczta Polska, DPD, GLS: revisit later?** They are link-out only. A future upgrade path exists through the same SPI (registered GLS API client, DPD Poland web services, official Poczta Polska tracking web service, or an aggregator). Not built now (Requirement 8.4). No answer needed unless the owner wants to pursue one.
5. **Real result pages for DPD.** DPD only returned a "technical break" notice, so no result markup was seen. This does not change the verdict, but if the owner wants DPD revisited, a person must capture a real result page.

## Verdict table

`ADAPTER` = automated tracking through an adapter. `MANUAL_ONLY` = no fetch; the registry only builds a tracking link and the user enters status and ETA by hand.

| Carrier | Mechanism | Verdict | Notes |
|---|---|---|---|
| InPost | Unauthenticated JSON endpoint of ShipX API (`/v1/tracking/{number}`) | `ADAPTER` (provisional) | Observed: 404 JSON for unknown number. Success shape is documented only, not observed. Needs a real redacted fixture and owner acceptance of the terms risk (decisions 1 and 2). Number pattern `^\d{24}$`, validate loosely `^\d{20,26}$`. Cloudflare in front. Link-out: `https://inpost.pl/sledzenie-przesylek?number={number}`. |
| DHL | Official Shipment Tracking Unified API, `DHL-API-Key` header | `ADAPTER` only if credentials are registered, otherwise `MANUAL_ONLY` | 401 without key (observed). Docs (not verified live): 250 calls/day and 1 call per 5 s per key. Website robots unreadable (connection reset), so the website is not used. |
| UPS | Official Tracking API, OAuth 2.0 client credentials | `ADAPTER` only if credentials are registered, otherwise `MANUAL_ONLY` | Website and its internal JSON are behind Akamai bot protection (Access Denied), so they must not be scraped. Token cached about 4 h. Quota not published. Number `^1Z[0-9A-Z]{16}$`, check digit can be validated locally. |
| FedEx | Official Track API, OAuth 2.0 client credentials (`apis.fedex.com`) | `ADAPTER` only if credentials are registered, otherwise `MANUAL_ONLY` | Website scraping conflicts with robots (`Disallow: /*?*`). Returns 429 when limited, so back off. Production Track access may need a FedEx account number. |
| Orlen Paczka | Undocumented JSONP endpoint on `nadaj.orlenpaczka.pl`, HTML fragment inside JSON | `MANUAL_ONLY` | Endpoint is an internal page helper that can change without notice. Host returns 403 for `robots.txt`. Unknown numbers still return `status: OK` with empty history. Link-out: `https://www.orlenpaczka.pl/sledz-paczke/?numer={number}` (URL-encode the number). |
| Poczta Polska / Pocztex | JS widget with reCAPTCHA, Imperva bot protection | `MANUAL_ONLY` | Data is reachable only with a reCAPTCHA token, which must not be bypassed. Official SOAP service needs registered credentials. Link-out: `https://emonitoring.poczta-polska.pl/?numer={number}` (prefill by query not verified). |
| DPD (PL) | HTML page parsing only | `MANUAL_ONLY` | Only a maintenance notice was ever returned, so no parser or fixture can be built (Requirement 8.3). Link-out: `https://tracktrace.dpd.com.pl/parcelDetails?typ=1&p1={number}`. |
| GLS (PL) | REST endpoint behind the page | `MANUAL_ONLY` | Endpoint answers 303 to an "API access registration" page, which is treated as the sign that automated access needs registration (Requirement 8.2). Page is client-rendered, so HTML holds no data. Link-out: `https://gls-group.com/PL/pl/sledzenie-paczek/?match={number}`. |

## Adapters task 4.1 builds

| Carrier | Build | Condition |
|---|---|---|
| InPost | `InPostAdapter` (fetch, pure parse, status mapping) | Provisional. Ships disabled until decisions 1 and 2 are answered and a real fixture is recorded. Per-carrier enable property off by default. If the terms are found to forbid automated use, switch to `MANUAL_ONLY`. |
| DHL | `DhlAdapter` | Only if the owner registers API credentials (decision 3). Otherwise link-out only. |
| UPS | `UpsAdapter` | Same condition. |
| FedEx | `FedexAdapter` | Same condition. |
| Orlen Paczka, Poczta Polska / Pocztex, DPD, GLS | No adapter | Registry entry with `trackingPageUrl(number)` link builder only, no fetch. |

Rules for 4.1 that follow from the spike:

- Carriers without a built adapter or without credentials fall back to `MANUAL_ONLY`.
- Credentials (DHL key, UPS and FedEx client id and secret) live in configuration secrets, never in code, fixtures or logs. Each credentialed adapter stays disabled when its secret is missing.
- Poll only a few times per day per active shipment. Throttle DHL to 1 call per 5 s and respect its daily cap. Back off on `429` and `5xx`, and count consecutive failures as in Requirement 7.
- Validate the number locally before any call. Use permissive "soft" checks (accept with a warning flag): InPost `^\d{20,26}$`, DHL `^(\d{10}|\d{12}|\d{20}|JJD\d{18,}|JVGL\d{10,})$`, UPS `^1Z[0-9A-Z]{16}$`, FedEx `^(\d{12}|\d{15}|\d{20}|\d{22}|\d{34})$`, DPD `[A-Z0-9]{9,20}`, GLS `[0-9]{8,14}`, Poczta Polska `^\d{20}$` or `^[A-Z]{2}\d{9}[A-Z]{2}$`, Orlen 7, 10 or 13 digits (13 start with 2 or 3).
- Status mapping to the generic set: DHL `pre-transit`/`transit`/`delivered`/`failure`; UPS `M`/`I`/`D`/`X`/`P`; FedEx `PU`/`IT`/`OD`/`DL`/`DE`; InPost from `tracking_details[].status`.
- Adapter tests run over recorded fixtures only and never call real carriers. For DHL, UPS and FedEx the fixtures must be built from the carriers' published sample responses, since no live data was observed.

## Gaps and caveats

- No real tracking number was used in any spike. Success payloads for InPost, Orlen, DHL, UPS and FedEx were not observed live. Items marked "docs, not verified live" in the source files must be re-checked on the carriers' developer portals before the adapters are written.
- Terms of use were not read for any carrier (Requirement 8.2 says the spec does not verify them). Verdicts rest on robots files, bot protection and registration redirects.
- Rate limits were not observed on any host. Request volumes were too small to provoke throttling.
- Source spike files stay in `spike/` as evidence, including request logs.
