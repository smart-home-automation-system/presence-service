# presence-service

Monitors which household members are at home. Presence is derived from the devices people
carry as they appear on and disappear from the home Wi-Fi, read from the UniFi Network API;
a resident counts as present while at least one of their registered devices is seen on the
network. Built under the *Household presence monitoring* epic (HAS-147) — HAS-148 created
the skeleton, HAS-149 added the UniFi client, the presence logic arrives in HAS-151…HAS-154.

Part of the smart-home-automation-system organization — org-wide conventions, the
repository map and working rules come from the workspace-level context
(`organization-repository/claude/organization.md`). The user writes the code in this
repository themselves; Claude's default role here is analysis, code review and security
review.

## Role in the system

- Talks to: the UniFi Network Integration API on the gateway (outbound, HTTPS over the LAN).
  Nothing calls it yet — it has **no route in `api-gateway-service`** on purpose: the only
  endpoint, `GET /home/presence/clients`, is diagnostic and lists the MAC addresses of every
  device on the network. The route arrives with the real API (HAS-152). No RabbitMQ.
- Uses libraries: `cholewa-commons` only. The presence models are service-local by design,
  so there is no `smart-home-sdk` or `shelly-client` dependency — do not add them without a
  cross-service contract to justify it.
- Integrates with hardware: none directly. The UniFi controller is the only external system,
  and it is reached over HTTP.

## Build & run

- Build + tests: `mvn verify`
- Local run: `home,local` Spring profiles, port `6009` (Actuator `8009`); in-cluster port
  `6200`, Actuator `8200`.
- No database and no broker. The `unifi.*` group is validated at bind time, so the service
  does not start without `UNIFI_API_KEY` (never in `application.yaml` — the repo is public);
  `UNIFI_HOST` overrides the `localhost` placeholder, in the cluster from the manifest in
  `deployment-tools`.
- Spring Boot **4.1.1** — the first service past the org target of 4.1.0. The rule since
  HAS-149: a service takes the newest Boot together with the newest own libraries whenever it
  is worked on; the others follow with their next task.

## Specifics

- **The UniFi client is the only functionality.** `UnifiClient.getConnectedClients()` returns
  `Flux<ConnectedClient>` — the contract the detection engine (HAS-151) builds on. No
  scheduled jobs and no persistence yet.
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
  gateway or a foreign certificate, 504 for a read timeout, 500 for an unknown site. The
  site id is resolved once and cached; a failed lookup is not cached.
- **The clients endpoint is paged by 200** and the home network already holds about 170
  clients, so the paging is not theoretical.
- **`responseTimeout` aborts one UniFi call, nothing more — today.** Once HAS-151 runs a
  detection pass on top of this client, check what a timeout cancels there (the
  `boiler-service` lesson).
- **Test dependencies are `mockwebserver3` and `okhttp-tls`**, not the legacy `mockwebserver`,
  which drags JUnit 4 onto the classpath — a JUnit 4 test compiles, is never run by surefire
  and leaves the build green. The API differs: `MockResponse.Builder`, `close()`.
- **Observability is already wired**, unlike in the services that were retrofitted: Actuator
  on the management port with `health,info,prometheus`, logstash JSON logs in the cluster
  (plain text in `local` and `test`), and Micrometer tracing over the Brave bridge with no
  exporter — the trace lives in the logs, followed by `traceId`. Sampling is pinned at 1.0.
- **The persistence stack is deliberately absent.** The household registry lives in
  `database-service` (HAS-150); this service gets its own persistence with the detection
  engine (HAS-151). When it does, the pooled `ConnectionFactory` comes from
  `cholewa-commons` via the `database.*` group — do not hand-write a `DbConfig`, and do not
  add `@EnableR2dbcRepositories` (it breaks `@WebFluxTest` slices). `database.pool.max-size`
  has to be pinned to this service's share of the 22 backend connections the managed
  database allows: 18 of 22 are allotted (heating 8 / database 6 / water 4), 4 are free, so
  no other pool needs re-cutting.
- **The `test` profile document exists only to undo the JSON logging**, and surefire
  activates it for every class via `spring.profiles.active`. A test class carrying its own
  `@ActiveProfiles` overrides that and will log JSON.
