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

**Status: the UniFi client is in place, the presence logic is not.** The service reads the
clients currently connected to the home network from the UniFi gateway and exposes that list
on a diagnostic endpoint. Deciding who is at home, persisting it and reporting on it arrive
with the remaining tasks of the *Household presence monitoring* epic: the detection engine,
the reporting API and the history retention job. The household members and their devices are
kept by `database-service`, not here.

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
locally they stay plain text. The service needs no database and no message broker, but it does
not start without the UniFi settings below — a missing one fails the startup and names the
property.

## Configuration

The `unifi.*` group, validated at startup:

| Property | Environment variable | Default | Purpose |
|---|---|---|---|
| `unifi.host` | `UNIFI_HOST` | `localhost` | Address of the UniFi gateway on the LAN; the real one is injected by the deployment |
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
`GET /sites/{siteId}/clients`, paged by 200. Should it ever be unavailable, the legacy
`/proxy/network/api/s/default/stat/sta` is the documented fallback — not implemented.

## API

Base path `/home/presence` (`spring.webflux.base-path`). The service has **no route in
`api-gateway-service`**, so nothing here is reachable from outside the cluster.

| Method | Path | Description |
|---|---|---|
| GET | `/home/presence/clients` | Diagnostic: the clients currently connected to the network — `macAddress` (lowercase), `name`, `type` (`WIRED`, `WIRELESS`, …), `connectedAt`. Clients without a MAC address (VPN, Teleport) are left out |

Failures of the gateway are answered in the org error format:

| Status | When |
|---|---|
| 502 | The gateway answered with an error (a rejected API key included), could not be reached, or presented a certificate other than the pinned one |
| 504 | The gateway did not answer within `unifi.response-timeout` |
| 500 | No site matches `unifi.site` |
