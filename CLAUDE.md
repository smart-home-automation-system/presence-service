# presence-service

Monitors which household members are at home. Presence is derived from the devices people
carry as they appear on and disappear from the home Wi-Fi, read from the UniFi Network API;
a resident counts as present while at least one of their registered devices is seen on the
network. Built under the *Household presence monitoring* epic (HAS-147) — HAS-148 created
the skeleton, HAS-149 added the UniFi client, HAS-151 the detection engine with its own
database, HAS-152 the first reporting API (current presence, per-resident report), HAS-153
the daily statistics and the house report, HAS-154 the retention job — the epic's scope is
complete.

Part of the smart-home-automation-system organization — org-wide conventions, the
repository map and working rules come from the workspace-level context
(`organization-repository/claude/organization.md`). The user writes the code in this
repository themselves; Claude's default role here is analysis, code review and security
review.

## Role in the system

- Talks to: the UniFi Network Integration API on the gateway (outbound, HTTPS over the LAN)
  and `database-service` over k8s DNS (`GET /home/household`, the household registry). Both
  are polled once a minute. `api-gateway-service` routes the reports by an **allowlist**
  (path and method per endpoint), so **a new endpoint here needs its own entry there** or it
  answers 404 from outside — and `GET /home/presence/clients`, diagnostic, listing the MAC
  address of every device on the network, must never get one. No RabbitMQ.
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
- **The reporting API reads the table, not the tracker** (`ResidentController` →
  `PresenceReportService`) — with one exception, the undecided presence in the house report
  (below). Current presence is the latest row of every **active** member of
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
  `last_checked_at` before `started_at`; the interval calculator does not repair that, the
  interval comes out as stored — the statistics leave such an interval out and count overlapping
  ones once; the real fix would be `TIMESTAMPTZ` columns. The status in the query
  is a literal, because no test runs these reads against a database; the SQL was run by
  hand against the real one.
- **Statistics end at the last check, not at the time of the request**
  (`PresenceStatisticsService` → `PresenceStatisticsCalculator`, again a plain class). An open
  presence ends at its last check, up to a minute ago; measured against "now", every running
  day would end in a sliver of empty house and `wasEmpty` would be true every day. So the
  calculator has no clock (only the zone, for the length of a day) and the service passes
  `until`: the member's last check for the daily statistics, the newest `last_checked_at` of
  the table for the house — read **before** the rows, so a pass stored in between only adds
  presence beyond the end. The answer names it as `observedUntil`; null means nothing was
  observed in the range, which is not the same as an empty house. Both reports are bounded
  at the other end too (`observedFrom`: the first row of the member, the first row ever stored
  for the house). Both bounds are read with
  `ORDER BY … LIMIT 1`, not `min()`/`max()`: an aggregate over an empty table answers one row
  holding NULL, which cannot be emitted. The four reads of a house report run one after
  another on purpose — zipped, they would take both connections of the pool at once.
- **The last check of the table is not the last check of everyone.** A PRESENT row is not
  touched while its member's grace period runs, but the ABSENT rows of the others are confirmed
  every minute — so for up to the threshold the newest check lies after the row of the only
  one at home, and the stretch in between read as an empty house (`wasEmpty` flapping on a
  phone asleep). `undecidedPresence` carries such a row on to the end of the report: the latest
  row of a member, PRESENT, whom **the tracker** still counts as present
  (`PresenceTracker.presentMembers()`). Asking the tracker is deliberate — a first version
  worked it out from the age of the row (threshold + 3 min) and was wrong: the grace period
  starts at the first missed pass and starts over after a restart or skipped passes, so a row
  can stay unchecked for much longer. The tracker has also forgotten a member who left the
  registry while present, whose never-closed row would otherwise occupy the house for good.
  Right after a start, before the state is restored, nothing is carried on. The price: the last
  minutes of a running report can still turn empty once an absence is dated back.
- **`wasEmpty` and the seconds of a day are read from the timeline**, each on its own: the
  stored times are finer than a second, so a day can have an empty stretch in `intervals`,
  `wasEmpty: true` and `secondsEmpty: 0`. Do not derive one from the other.
- **What the statistics cannot see inside the history is counted as empty**: an outage across a
  status change. Nothing here knows whether the service was watching — the table stores only
  what was seen. Anything that acts on `wasEmpty` (heating) has to know that; a row of "not
  watched" periods would be the way to tell the two apart. Two smaller ones of the same kind: a
  row re-opened without a real arrival (a member re-activated in the registry, a confirm that
  found no row) reads as an arrival and a departure, and on the clocks-back night an hour of
  presence can be missing or counted double, because the stored local times repeat.
- **A day is measured in the zone** (`Duration` between zoned times), so it is 23 or 25 hours
  on the clock-change days, and Sonar's S8700 stays quiet. An arrival is the start of an
  interval inside the day unless it is the start of the range (possibly cut there); a departure
  the end of a closed interval unless it is the end of the range.
- **The house report lives at `/house/report`**, not `/home/report` as the task had it: under
  the base path that would have been `/home/presence/home/report`. Range validation is shared
  in `ReportRange`; who is an unknown resident is decided once, in
  `PresenceReportService.readHistory`, which the daily statistics build on.
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
  member stay until the retention job deletes them, a retention period after their last check.
- **Retention deletes by `last_checked_at`, never by `started_at`** (`PresenceRetentionCron` →
  `PresenceRetention`, daily at 03:00 — an hour that exists exactly once also on the nights the
  clocks change). That is what keeps the current row of every watched member: it is checked
  every minute, however long ago it started (a year away is one ABSENT row). Do not "protect
  the latest row per member" on top of it — the never-closed last row of a member who left the
  registry would then stay for good. The job is a class of its own, never fails (logs, and the
  next night makes up for it) and is bounded by a timeout, because the delete holds one of the
  two pooled connections the detection needs. Reactive `@Scheduled` methods do not block the
  scheduler thread, so it cannot hold the detection up either. In the `test` profile the cron
  is `-` (off); the schedule has its only default in `application.yaml`.
- **A reactive `@Scheduled` method is called once, not once per run.** Spring invokes it at
  startup, keeps the `Mono` and subscribes to it again every time
  (`ScheduledAnnotationReactiveSupport`). Anything computed while the `Mono` is built — a
  cutoff, "now", a value read from a property that may change — is frozen at the start of the
  pod. `PresenceRetention.purge()` first had its cutoff outside the chain: it would have
  deleted up to the same date every night until a restart, logging that date as if it were
  current. Everything time-dependent goes inside `Mono.defer`
  (`PresenceEngine.detect()` was safe only because its clock read sits in a lambda), and the
  test for it subscribes twice to the **same** `Mono` with the clock moved on — a fresh
  `purge()` per test cannot see the bug. Worth checking in every service with a reactive
  `@Scheduled`.
- **`presence.retention` cannot be made small enough to wipe the table**: `@DurationUnit(DAYS)`,
  `@DurationMin(days = 7)` and `@DurationMax(days = 3660)`, pinned by `PresencePropertiesTest`. Without the unit `365` binds
  as 365 ms, the cutoff is "now" and the nightly delete takes every row, the current ones
  included — the next pass would silently reopen each period dated now
  (`PresenceStatusStore.confirm`), every night.
- **The timeout of the purge only stops waiting.** It does not abort the statement on the
  server, and the cancelled connection goes back to the pool — the mechanism of the 2026-09-26
  outage; what makes that safe now is the pool's validation on acquire (`cholewa-commons`
  ≥ 1.5.0), not this timeout. Hence the log line says "did not complete", not "nothing was
  deleted", and carries the exception itself.
- **A kept row can start before the retention horizon** (one ABSENT row for a year away), while
  everything around it that ended earlier is deleted. So the first `started_at` is not where
  the observed history starts: `observedFrom` of both statistics is clamped to
  `PresenceRetention.horizon()` — the same value the purge deletes by — or the purged stretch
  would read as observed and nobody at home. The clamp does not know whether a purge has run
  (with the job off the statistics simply start there too), nor how far an earlier, shorter
  retention has purged: raising `presence.retention` re-exposes that stretch as empty. The
  interval report has no observed bounds and cannot say this; the range limit (366 days,
  `ReportRange`) is not derived from `presence.retention` either — change one, look at the
  other.
- **Insert only on a status change** — a confirming pass only moves `last_checked_at` of the
  member's latest row (`touchLatest`). Keep it that way: a row per poll would be 1440 rows per
  member per day. The SQL (`DISTINCT ON`, the update of the latest row) was verified on a real
  PostgreSQL 17, but no test in the repository runs it against a database.
- **One test does run SQL: `PresenceStatusRetentionTest`**, a `@DataR2dbcTest` slice on an
  in-memory H2 (`r2dbc-h2`, test scope) — the retention delete is destructive and portable, so
  it gets a real test with rows on both sides of the cutoff. Three things that made it work: the
  slice does not load the pooled `ConnectionFactory` of `cholewa-commons`, so
  `spring.r2dbc.url` alone points it at H2; the table is created by the test (Flyway is not in
  the slice, and `V1` is PostgreSQL DDL); and H2 needs `CASE_INSENSITIVE_IDENTIFIERS=TRUE`,
  because Spring Data quotes the table name in lower case while raw `@Query` SQL does not
  quote it at all. The PostgreSQL-only reads (`DISTINCT ON`) cannot be tested this way — that
  would take Testcontainers.
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
