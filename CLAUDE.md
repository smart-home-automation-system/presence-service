# presence-service

Monitors which household members are at home. Presence is derived from the devices people
carry as they appear on and disappear from the home Wi-Fi, read from the UniFi Network API;
a resident counts as present while at least one of their registered devices is seen on the
network. Built under the *Household presence monitoring* epic (HAS-147) — HAS-148 created
the skeleton, HAS-149 added the UniFi client, HAS-151 the detection engine with its own
database, HAS-152 the first reporting API (current presence, per-resident report); the
statistics and the retention job arrive in HAS-153 and HAS-154.

Part of the smart-home-automation-system organization — org-wide conventions, the
repository map and working rules come from the workspace-level context
(`organization-repository/claude/organization.md`). The user writes the code in this
repository themselves; Claude's default role here is analysis, code review and security
review.

## Role in the system

- Talks to: the UniFi Network Integration API on the gateway (outbound, HTTPS over the LAN)
  and `database-service` over k8s DNS (`GET /home/household`, the household registry). Both
  are polled once a minute. Nothing calls this service yet — it has **no route in
  `api-gateway-service`**. When the route is added it covers `/home/presence/residents/**`
  only: `GET /home/presence/clients` is diagnostic, lists the MAC addresses of every device on
  the network and must stay unreachable from outside. No RabbitMQ.
- Owns the presence history in its own database (`home-automation-presence`); the members and
  their devices stay in `database-service` and are never copied here.
- Uses libraries: `cholewa-commons` and `smart-home-sdk` — the latter only for the registry
  models (`HouseholdMember`, `MemberPhoneDetails`), a cross-service contract. The presence
  models are service-local by design; no `shelly-client`.
- Integrates with hardware: none directly. The UniFi controller is the only external system,
  and it is reached over HTTP.

## Build & run

- Build + tests: `mvn verify`
- Local run: `home,local` Spring profiles, port `6009` (Actuator `8009`); in-cluster port
  `6200`, Actuator `8200`.
- Needs a PostgreSQL database (`database.*`, Flyway runs at startup) and, to produce
  anything, a reachable `database-service` (`registry.base-url`, `http://localhost:6005` in
  the `local` profile). No broker. The `unifi.*` group is validated at bind time, so the service
  does not start without `UNIFI_API_KEY` (never in `application.yaml` — the repo is public)
  and `UNIFI_HOST`; in the cluster both come from the manifest in `deployment-tools`. Neither
  has a default outside the `test` document on purpose: with a `localhost` default a pod that
  lost the variable would become Ready and answer 502 on every call.
- Spring Boot **4.1.1** — the first service past the org target of 4.1.0. The rule since
  HAS-149: a service takes the newest Boot together with the newest own libraries whenever it
  is worked on; the others follow with their next task.

## Specifics

- **One detection pass a minute** (`PresenceCron` → `PresenceEngine.detect()`): connected
  clients from `UnifiClient`, active members from `HouseholdClient`, decided by
  `PresenceTracker`, written by `PresenceStatusStore`. The split is deliberate — the tracker
  is a plain class with no I/O, so the whole state machine is unit-tested without a context.
- **The tracker decides, the store writes, and the status moves only on `commit()`** after a
  successful write. A failed write is therefore simply decided again by the next pass; do not
  let `evaluate()` change a status. Each member is written on its own with its own
  `onErrorResume`, so one failed write does not cancel the members after it.
- **A pass never fails and never guesses.** Gateway down, database down, or the registry down
  with nothing read before → the pass is skipped and the state stays. With a registry read
  earlier, an outage of `database-service` falls back to that last list — an outage must not
  read as a household without members.
- **Absence needs the grace period, counted from the first pass that missed the member** —
  not from the last sighting — **and it starts over when passes were skipped in between**
  (two evaluated passes more than 3 minutes apart, `MAX_OBSERVATION_GAP`). The period is time
  the member was watched and not seen; while the gateway was down nobody was looking. That is
  what keeps an outage of the gateway — before or inside a grace period —, a restart of the
  service and a client skipped by the paging (see below) from turning anyone absent. While
  waiting, **nothing is written**: a PRESENT row is only ever checked by a pass that saw the
  member, so its `last_checked_at` is the last sighting — which is where the ABSENT row starts
  when the period runs out, and what a restart restores as the last sighting.
- **Inside the tracker time is an `Instant`; only what goes to the store is a local
  date-time** (the org convention for tables). The grace period is a duration: measured on the
  local wall clock it shrinks to minutes when the clocks go forward and stretches past an hour
  when they go back. For the same reason "the latest row of a member" is the highest `id`,
  never the latest `started_at`, which can run backwards on that night. The stored local times
  stay ambiguous for that one hour a year — a reporting task that needs exact durations across
  it would have to move the columns to `TIMESTAMPTZ`.
- **Members that left the registry are forgotten** (removed or deactivated): their last row
  simply stops being checked, and if they return, a new row is opened. A reader of "current
  presence" must therefore combine the latest row with the active registry, or look at how old
  `last_checked_at` is.
- **The reporting API reads, it never asks the tracker** (`ResidentController` →
  `PresenceReportService`). Current presence is the latest row of every **active** member of
  the registry, so it needs `database-service` and answers 502 without it (a fixed message:
  the API is meant to be routed, and the exception names what is behind this service) — the engine's
  last-known registry is deliberately not reused, the answer would silently be stale. A report
  asks the registry only for a resident without any row (empty report for an active member, 404
  otherwise), so a history is answered while the registry is down, also for a member who left.
- **Intervals come from `PresenceIntervalCalculator` alone** — a plain class like the tracker;
  HAS-153 is meant to reuse it, not to re-derive. A PRESENT row is the period `started_at` …
  `last_checked_at` for a closed row as well (that is the last sighting, where the absence
  starts), cut to the range; nothing is added between rows. `open` is the member's latest row,
  and only when its last check lies inside the range. It means "nothing closed this period",
  not "watched right now": the row of a member who left the registry while present stays open
  forever, and the calculator cannot know — it has no clock on purpose. One thing the task did
  not foresee: an outage **inside** a presence is not a gap — after the restart the same row
  is confirmed again, so it reads as one continuous interval. A gap shows only when the status
  changed across the outage.
- **A report is read with one statement** (`findForReport`): the periods touching the range
  plus the member's newest row, which is always the last one answered and tells which period
  is open; no row at all means no history. Read as two queries, a status stored in between
  made them disagree. The range rule in the SQL is only a pre-filter — the calculator owns it
  and is what the tests exercise; change the rule there first and keep the SQL at least as
  wide.
- **The range of a report is local date-time, compared as it is** with the stored local times
  (no zone conversion; the clock-change hour is ambiguous here as everywhere in the table). It
  is closed at its start and open at its end; a period that ended exactly at the start is left
  out, so day-by-day ranges do not count it twice. The parameters are bound with a pattern,
  not `ISO.DATE_TIME`, which accepts an offset and silently drops it (Spring still falls back
  to `LocalDateTime.parse`, so `T00:00` and fractions pass — an offset does not). The limit of
  a year is 366 calendar days counted on epoch days, because `plusYears` on a bound taken from
  the request can overflow and throw (and Sonar rejects a `Duration` between local date-times). On the night the clocks go back a row can be stored with
  `last_checked_at` before `started_at`; the calculator does not repair that, the interval
  comes out as stored — HAS-153 has to guard its sums or the columns move to `TIMESTAMPTZ`. The status in the query
  is a literal, because nothing in the repository runs against a database; the SQL was run by
  hand against the real one.
- **The API shares the pool of 2 with the engine and the health indicator**, and its queries
  have no timeout of their own beyond the pool's acquire and validation bounds. Fine for a
  handful of requests; a frontend polling per open profile is the moment to give the service a
  third connection (the budget has room) rather than to find out from skipped passes.
- **The passes run with `fixedDelay` and a 50 s timeout.** For a method returning a `Mono`
  Spring waits for the previous run only with a fixed delay; at a fixed rate a slow pass
  overlaps the next one and both store the same change. The timeout is what keeps a call that
  never answers from stopping the detection for good. (`WaterSensorCron`, the pattern this was
  copied from, still uses `fixedRate`.)
- **Last seen is tracked per member, not per device** (the task asked for per device). The
  outcome is the same — a member is present while any device is seen — and the state stays one
  entry per member. The only difference: a device removed from the registry keeps its member
  present until the grace period ends.
- **Exactly one instance may run.** Two would poll in parallel and could store the same change
  twice, so the Deployment uses `strategy: Recreate` instead of a rolling update (which also
  keeps a rollout from opening a second connection pool). Do not scale it.
- **The current state lives in memory** (single replica) and is rebuilt from the latest row of
  every member before the first pass; a failed rebuild is retried by the next pass. A restart
  inside a grace period starts that period again (at most one threshold of delay), but the
  absence still starts at the stored last sighting.
- **Rows are keyed by `member_name`, not by an id.** The registry API identifies members by
  name and the SDK model carries no id; the name is unique there. A rename in the registry
  starts a new history. There is no foreign key (another database), so rows of a removed
  member stay until the retention job (HAS-154).
- **Insert only on a status change** — a confirming pass only moves `last_checked_at` of the
  member's latest row (`touchLatest`). Keep it that way: a row per poll would be 1440 rows per
  member per day. The SQL (`DISTINCT ON`, the update of the latest row) was verified on a real
  PostgreSQL 17, but no test in the repository runs against a database.
- **`database.pool.max-size` is 2** (3 until 0.3.0): 16 of the 22 backend connections of the
  managed database are allotted (heating 4 / database 6 / water 4 / presence 2), 6 are free.
  Two are enough here — the engine stores one member at a time (`concatMap`), the second
  connection is for the health indicator. The free ones are not a luxury: Flyway takes a JDBC
  connection at every start and a database tool opens one per session, and with a single spare
  slot a few IDE queries exhausted the server (`53300 remaining connection slots are
  reserved`, 2026-10-03).
- **The gateway is logged without bodies, everything else with them.** One answer of the
  client list is tens of kilobytes every minute — above the 16 KB at which the container
  runtime splits a log line. `AppConfig` therefore builds a second `Logbook` for the UniFi
  `HttpClient` with `WithoutBodyStrategy`, from the autoconfigured correlation id, header
  filter and sink. It must not become a bean: a `Logbook` bean makes the autoconfigured one
  back off and the server-side logging disappears. `UnifiLogbookTest` guards the key
  obfuscation and the missing body.
- **`PresenceCron` is switched off in the `test` profile** (`presence.detection-enabled`), or
  every context started by a test would poll a gateway that is not there.
- **`UnifiClient.getConnectedClients()` returns `Flux<ConnectedClient>`** — the contract the
  engine builds on.
- **`ConnectedClient` is the service's own model, `model.unifi.*` is the wire format.** They
  have the same field names, so swapping one for the other compiles — and then Jackson
  builds `ConnectedClient` straight from the response, its constructor rejects the first
  client without a MAC address (VPN, Teleport) and the whole list is lost. Keep the mapping
  step. The MAC is lowercased in the constructor, matching the registry in
  `database-service` (lowercase with colons, enforced there by a CHECK constraint).
- **The gateway's certificate is pinned, not trusted blindly.** It is self-signed, so
  `AppConfig` trusts exactly the certificate whose SHA-256 fingerprint is in
  `unifi.certificate-fingerprint` (not a secret). Hostname verification is switched off for
  this client, because the certificate does not name the LAN address — and that has to be
  `setEndpointIdentificationAlgorithm("")`: the JDK ignores `null` there and keeps verifying.
  `AppConfigTlsTest` covers both directions against an HTTPS server; do not replace the
  pinning with `InsecureTrustManagerFactory`. A replaced certificate on the gateway shows up
  as `502 UniFi unreachable: CertificateException` on every call.
- **Errors of the gateway never carry its body.** `UnifiCallException` holds a status and a
  message built here: 502 for an error answer (a rejected key included), an unreachable
  gateway, a foreign certificate, a dropped connection or an undecodable answer, 504 for a
  read timeout, 500 for an unknown site. The mapping has to catch **everything**, not only
  `WebClientRequestException`: WebClient raises that one only up to the response headers,
  and a stall or a drop in the middle of the body, or a `DecodingException`, would otherwise
  leave as a plain 500. The site id is resolved once and cached; a failed lookup is not
  cached.
- **The clients endpoint is paged by 200** and the home network already holds about 170
  clients, so the paging is not theoretical. Offset paging over a live list is not a
  snapshot: a client that disconnects between two page fetches shifts the rest by one, so
  one client can be skipped (or, on a connect, returned twice). The engine is immune by
  design: it collects the MAC addresses into a set and needs the whole grace period of missed
  passes before a member turns absent.
- **`responseTimeout` aborts one UniFi call, and with it the whole pass** — which is the
  intended outcome: a pass on a partial client list would read as people leaving (the
  `boiler-service` lesson, answered the other way round here).
- **Test dependencies are `mockwebserver3` and `okhttp-tls`**, not the legacy `mockwebserver`,
  which drags JUnit 4 onto the classpath — a JUnit 4 test compiles, is never run by surefire
  and leaves the build green. The API differs: `MockResponse.Builder`, `close()`.
- **Observability is already wired**, unlike in the services that were retrofitted: Actuator
  on the management port with `health,info,prometheus`, logstash JSON logs in the cluster
  (plain text in `local` and `test`), and Micrometer tracing over the Brave bridge with no
  exporter — the trace lives in the logs, followed by `traceId`. Sampling is pinned at 1.0.
- **The pooled `ConnectionFactory` comes from `cholewa-commons`** via the `database.*` group —
  do not hand-write a `DbConfig`, and do not add `@EnableR2dbcRepositories` (it breaks
  `@WebFluxTest` slices; Boot's auto-configuration finds the repository on its own).
- **The `test` profile document exists only to undo the JSON logging**, and surefire
  activates it for every class via `spring.profiles.active`. A test class carrying its own
  `@ActiveProfiles` overrides that and will log JSON.
