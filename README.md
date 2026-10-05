# presence-service

[![CI](https://github.com/smart-home-automation-system/presence-service/actions/workflows/CI.yml/badge.svg)](https://github.com/smart-home-automation-system/presence-service/actions/workflows/CI.yml)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=smart-home-automation-system_presence-service&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=smart-home-automation-system_presence-service)
[![Vulnerabilities](https://sonarcloud.io/api/project_badges/measure?project=smart-home-automation-system_presence-service&metric=vulnerabilities)](https://sonarcloud.io/summary/new_code?id=smart-home-automation-system_presence-service)

![GitHub Release Date - Published_At](https://img.shields.io/github/release-date/smart-home-automation-system/presence-service?style=plastic)
![GitHub Release](https://img.shields.io/github/v/release/smart-home-automation-system/presence-service?style=plastic)

---

![GitHub top language](https://img.shields.io/github/languages/top/smart-home-automation-system/presence-service?style=plastic)
![Java](https://img.shields.io/badge/java-21-yellow?style=plastic)
![SpringBoot](https://img.shields.io/badge/SpringBoot-4.1.1-blue?style=plastic)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=smart-home-automation-system_presence-service&metric=coverage)](https://sonarcloud.io/summary/new_code?id=smart-home-automation-system_presence-service)
[![Lines of Code](https://sonarcloud.io/api/project_badges/measure?project=smart-home-automation-system_presence-service&metric=ncloc)](https://sonarcloud.io/summary/new_code?id=smart-home-automation-system_presence-service)

![GitHub issues](https://img.shields.io/github/issues/smart-home-automation-system/presence-service?style=plastic)
![GitHub contributors](https://img.shields.io/github/contributors/smart-home-automation-system/presence-service?style=plastic)
![GitHub pull requests](https://img.shields.io/github/issues-pr-raw/smart-home-automation-system/presence-service?style=plastic)

![GitHub last commit](https://img.shields.io/github/last-commit/smart-home-automation-system/presence-service?style=plastic)
![GitHub commit activity](https://img.shields.io/github/commit-activity/m/smart-home-automation-system/presence-service?style=plastic)

---

# Description

Monitors which household members are at home. Presence is derived from the devices people
carry — phones, watches — as they appear on and disappear from the home Wi-Fi, read from the
**UniFi Network API**; a resident counts as present while at least one of their registered
devices is seen on the network.

**Status: presence is detected, stored and reported.** Every minute the service reads the
clients connected to the home network from the UniFi gateway, matches them against the devices
of the active household members and records who is at home. The API answers who is at home
now, when one resident was, their daily statistics and when the house as a whole was occupied
or empty; the history retention job arrives with the last task of the *Household presence
monitoring* epic. The household members and their devices are kept by `database-service`, not here — this
service reads that registry and owns only the presence history.

## How presence is decided

- A member is **present** as soon as any of their registered devices is seen on the network.
- A member becomes **absent** only after all their devices stayed unseen for
  `presence.absence-threshold` (10 minutes by default) — phones drop off the Wi-Fi while
  asleep. The absence is then recorded as starting at the moment the member was last seen, not
  at the end of the waiting period.
- The waiting period is counted in passes that actually ran: a pass skipped because the
  gateway, the registry or the database did not answer changes nothing, and a restart of the
  service never turns anyone absent by itself.
- The history is one row per period of an unchanged status (`presence_status`: member, status,
  `started_at`, `last_checked_at`). A row is written only when the status changes; a pass that
  confirms it just moves `last_checked_at`, which therefore also shows how fresh the data is.
  For a present member it is the last time one of their devices was actually seen.
- Members are identified by their **name** in the registry. Renaming a member there starts a
  new history under the new name. A member removed or deactivated there is no longer watched:
  their last row stops being checked, so "who is at home now" is the latest row of each
  **active** member.
- Exactly one instance may run — the current state is kept in memory.

## Run locally

```bash
mvn verify                                  # build and tests
mvn spring-boot:run -Dspring-boot.run.profiles=home,local
```

| | Application | Actuator |
|---|---|---|
| local (`local` profile) | 6009 | 8009 |
| cluster (`home` profile) | 6200 | 8200 |

The Actuator exposes `health`, `info` and `prometheus`; the cluster logs are JSON (logstash),
locally they stay plain text. No message broker is needed. To start, the service needs a
PostgreSQL database (Flyway creates the schema), the UniFi settings below — a missing one fails
the startup and names the property — and, for a meaningful result, a running `database-service`
(locally on port 6005); while that one is unreachable and was never read, the passes are
skipped.

## Configuration

| Property | Environment variable | Default | Purpose |
|---|---|---|---|
| `database.host` / `port` / `name` / `username` / `password` | `database-host`, `database-port`, `database-name`, `database-user`, `database-password` | local placeholders | The service's own PostgreSQL database; in the cluster from the `database` secret |
| `spring.flyway.url` | `flyway-url` | built from `database.*` | JDBC URL for the migrations |
| `database.pool.max-size` | — | `2` | This service's share of the connection budget of the managed database |
| `registry.base-url` | `REGISTRY_BASE_URL` | `http://database-service:6200` (`http://localhost:6005` in `local`) | Where the household registry is read from |
| `registry.response-timeout` | `REGISTRY_RESPONSE_TIMEOUT` | `PT5S` | Time allowed for one answer of `database-service` |
| `presence.absence-threshold` | `PRESENCE_ABSENCE_THRESHOLD` | `PT10M` | How long every device of a member has to stay unseen before the member is absent |

The `unifi.*` group, validated at startup:

| Property | Environment variable | Default | Purpose |
|---|---|---|---|
| `unifi.host` | `UNIFI_HOST` | — (required) | Address of the UniFi gateway on the LAN; not committed, injected by the deployment |
| `unifi.api-key` | `UNIFI_API_KEY` | — (required) | API key, sent as `X-API-Key`. A secret: environment only, never committed, masked in the logs |
| `unifi.site` | `UNIFI_SITE` | `Default` | Site name (or internal reference); its id is resolved once through the API |
| `unifi.certificate-fingerprint` | `UNIFI_CERTIFICATE_FINGERPRINT` | set in `application.yaml` | SHA-256 fingerprint of the gateway's certificate |
| `unifi.connect-timeout` | `UNIFI_CONNECT_TIMEOUT` | `PT5S` | TCP connect timeout |
| `unifi.response-timeout` | `UNIFI_RESPONSE_TIMEOUT` | `PT10S` | Time allowed for one answer of the gateway |

**The API key** is created by hand in the UniFi console, in the *Integrations* section of the
Network application's settings. It is shown only once.

**TLS.** The gateway serves a self-signed certificate, so the client trusts exactly one
certificate, pinned by its SHA-256 fingerprint — nothing else, and no global trust-all.
Hostname verification is off for this client, because the certificate does not name the LAN
address and the pinned fingerprint is the stronger check. When the gateway replaces its
certificate (a firmware update can do that), every call fails with `502 UniFi unreachable:
CertificateException`; read the new fingerprint and update the property:

```bash
openssl s_client -connect <gateway>:443 </dev/null 2>/dev/null | openssl x509 -noout -fingerprint -sha256
```

The client uses the official local **UniFi Network Integration API**
(`/proxy/network/integration/v1`): `GET /sites` to resolve the site and
`GET /sites/{siteId}/clients`, paged by 200. Tested against **UniFi Network 10.6.106** on a
UCG Ultra — re-check the response shape after a major Network upgrade. Should the API ever be
unavailable, the legacy
`/proxy/network/api/s/default/stat/sta` is the documented fallback — not implemented.

## API

Base path `/home/presence` (`spring.webflux.base-path`). `api-gateway-service` routes the
reports by an allowlist — each endpoint is listed there by path and method, so a new one is
unreachable from outside the cluster until it is added. `/clients` lists the MAC address of
every device on the network and is deliberately not on that list.

| Method | Path | Description |
|---|---|---|
| GET | `/home/presence/residents/presence` | Who is at home now: every **active** member of the registry, ordered by name (Polish collation) — `name`, `present`, `since` (start of the current status), `lastCheckedAt` (the last pass that confirmed it, i.e. how fresh the answer is). A member nothing is stored for yet is listed with `present: false` and both times `null` |
| GET | `/home/presence/residents/{name}/report?from=&to=` | When one resident was at home within a range: `name`, `from`, `to` and `intervals` (`from`, `to`, `open`), oldest first |
| GET | `/home/presence/residents/{name}/report/daily?from=&to=` | The days of one resident within a range: per day `secondsAtHome`, `firstArrival`, `lastDeparture`, `presencePercentage` |
| GET | `/home/presence/house/report?from=&to=` | The house as a whole within a range: `intervals` (`from`, `to`, `occupied`) as one timeline, and per day `secondsOccupied`, `secondsEmpty`, `wasEmpty` |
| GET | `/home/presence/clients` | Diagnostic: the clients currently connected to the network — `macAddress` (lowercase), `name`, `type` (`WIRED`, `WIRELESS`, …), `connectedAt`. Clients without a MAC address (VPN, Teleport) are left out |

**The report.**

- `{name}` is the member's name in the registry, percent-encoded when needed. `from` and `to`
  are both required, local date-times without an offset (`2026-10-01T00:00:00`), read in the
  zone the service runs in — the same local time the history is stored in. A date alone, or a
  value with `Z` or an offset, is answered with 400 rather than read with the offset dropped.
  `from` has to lie before `to`, and the range may span at most 366 days (the retention
  horizon of a year).
- The range includes its start and excludes its end, so adjacent ranges (day by day) never
  report the same moment twice.
- An interval is a stored period of presence, cut to the range. It ends at the last moment the
  resident was seen; `open: true` marks the last period of the history when nothing closed it,
  whose end is the last check and not a departure. A period the range cuts off at its end is
  not marked open — its end is the edge of the range. Open does not promise the resident is
  being watched right now: for a member who left the registry, or while the detection is down,
  the end of the interval simply stops moving — compare it with the clock.
- Nothing is interpolated. Time the service did not watch (it was down, or the gateway was)
  shows as a gap between intervals when the status changed across it; a period of presence that
  simply continued after the outage is one interval.
- The history is answered for anyone who has one, also a member who has since left the
  registry. A resident with no history is answered with an empty `intervals` list when they
  are an active member, and with 404 otherwise.

```json
{
  "name": "Anna",
  "from": "2026-10-03T00:00:00",
  "to": "2026-10-05T00:00:00",
  "intervals": [
    { "from": "2026-10-03T00:00:00", "to": "2026-10-03T08:10:00", "open": false },
    { "from": "2026-10-03T17:45:00", "to": "2026-10-04T12:00:00", "open": true }
  ]
}
```

**The daily statistics and the house report.** The range follows the same rules as the report
above; the days are calendar days in the zone the service runs in, the first and the last one
cut to the range.

- **Both end where the history does**, at `observedUntil`: the last check (of the resident, or
  of anyone for the house), or the end of the range when that comes first. Time nobody has
  looked at yet — the rest of today, a range reaching into the future — is neither presence nor
  absence and is not counted, so `presencePercentage` and `secondsEmpty` of the running day
  refer to the part of it that has passed. `observedUntil` is `null`, with no days, when
  nothing was observed inside the range.
- **The house report also starts where the history does**, at `observedFrom`: the first status
  ever stored, or the start of the range when that comes later. The daily statistics of a
  resident have no such bound — days before their first row are reported with 0 %.
- **A resident who is not seen for a moment keeps the house occupied.** While the absence
  threshold runs the service still says "present", and so does the house report; when the
  resident turns out to have left, the absence is dated back to the last sighting and shows in
  the next report. So the last minutes of a running report (the threshold plus a few) can
  still turn from occupied to empty — never the other way round.
- `firstArrival` and `lastDeparture` are real ones: a presence carried over midnight is no
  arrival, a presence still going on (or cut off by the range) no departure. Both are `null` on
  a day spent entirely at home — and on a day spent entirely away.
- The house is **occupied** while at least one resident is at home — the union of everyone's
  intervals, members who have since left the registry included — and **empty** otherwise.
  `wasEmpty` says the house stood empty at some point of that day.
- As in the report, nothing is interpolated: **time the service did not watch in the middle of
  the history reads as empty**. Check a surprising empty stretch against the gaps in the
  residents' reports before acting on it.
- A day is as long as it really was: 23 and 25 hours on the two days the clocks change.

```json
{
  "from": "2026-10-05T00:00:00",
  "to": "2026-10-06T00:00:00",
  "observedFrom": "2026-10-05T00:00:00",
  "observedUntil": "2026-10-05T18:30:00",
  "intervals": [
    { "from": "2026-10-05T00:00:00", "to": "2026-10-05T08:10:00", "occupied": true },
    { "from": "2026-10-05T08:10:00", "to": "2026-10-05T16:45:00", "occupied": false },
    { "from": "2026-10-05T16:45:00", "to": "2026-10-05T18:30:00", "occupied": true }
  ],
  "days": [
    { "date": "2026-10-05", "secondsOccupied": 35700, "secondsEmpty": 30900, "wasEmpty": true }
  ]
}
```

Errors are answered in the org error format:

| Status | When |
|---|---|
| 400 | `from` or `to` missing or not a local date-time, `from` not before `to`, or a range longer than 366 days |
| 404 | The resident has no history and is not an active member of the registry (report and daily statistics) |
| 502 | The household registry in `database-service` could not be read — always for the current presence, for a report only when the resident has no history; answered with a fixed message, the cause is in the log only. On `/clients`: the gateway answered with an error (a rejected API key included), could not be reached, presented a certificate other than the pinned one, dropped the connection, or sent an answer that is not the expected JSON |
| 504 | `/clients`: the gateway did not answer within `unifi.response-timeout` — before the response or in the middle of it |
| 500 | `/clients`: no site matches `unifi.site` |
