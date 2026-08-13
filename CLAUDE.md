# presence-service

Monitors which household members are at home. Presence is derived from the devices people
carry as they appear on and disappear from the home Wi-Fi, read from the UniFi Network API;
a resident counts as present while at least one of their registered devices is seen on the
network. Built under the *Household presence monitoring* epic (HAS-147) — HAS-148 created
this skeleton, the functionality arrives in HAS-149…HAS-154.

Part of the smart-home-automation-system organization — org-wide conventions, the
repository map and working rules come from the workspace-level context
(`organization-repository/claude/organization.md`). The user writes the code in this
repository themselves; Claude's default role here is analysis, code review and security
review.

## Role in the system

- Talks to: nothing yet. Planned — the UniFi Network API (outbound, over the LAN) and
  `api-gateway-service` (inbound, once the service exposes endpoints; it has no gateway
  route while it has no API). No RabbitMQ.
- Uses libraries: `cholewa-commons` only. The presence models are service-local by design,
  so there is no `smart-home-sdk` or `shelly-client` dependency — do not add them without a
  cross-service contract to justify it.
- Integrates with hardware: none directly. The UniFi controller is the only external system,
  and it is reached over HTTP.

## Build & run

- Build + tests: `mvn verify`
- Local run: `home,local` Spring profiles, port `6009` (Actuator `8009`); in-cluster port
  `6200`, Actuator `8200`.
- No database, no broker, no credentials are needed to start the current skeleton.

## Specifics

- **Skeleton.** No endpoints, no scheduled jobs, no persistence. The only test is the
  context-loads test.
- **Observability is already wired**, unlike in the services that were retrofitted: Actuator
  on the management port with `health,info,prometheus`, logstash JSON logs in the cluster
  (plain text in `local` and `test`), and Micrometer tracing over the Brave bridge with no
  exporter — the trace lives in the logs, followed by `traceId`. Sampling is pinned at 1.0.
- **The persistence stack is deliberately absent.** It arrives with the resident and device
  registry (HAS-150). When it does, the pooled `ConnectionFactory` comes from
  `cholewa-commons` via the `database.*` group — do not hand-write a `DbConfig`, and do not
  add `@EnableR2dbcRepositories` (it breaks `@WebFluxTest` slices). `database.pool.max-size`
  has to be pinned to this service's share of the 22 backend connections the managed
  database allows, which today are fully allotted (heating 8 / database 6 / water 4) and
  will need re-cutting.
- **The `test` profile document exists only to undo the JSON logging**, and surefire
  activates it for every class via `spring.profiles.active`. A test class carrying its own
  `@ActiveProfiles` overrides that and will log JSON.
