# presence-service

[![CI](https://github.com/smart-home-automation-system/presence-service/actions/workflows/CI.yml/badge.svg)](https://github.com/smart-home-automation-system/presence-service/actions/workflows/CI.yml)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=smart-home-automation-system_presence-service&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=smart-home-automation-system_presence-service)
[![Vulnerabilities](https://sonarcloud.io/api/project_badges/measure?project=smart-home-automation-system_presence-service&metric=vulnerabilities)](https://sonarcloud.io/summary/new_code?id=smart-home-automation-system_presence-service)

![GitHub Release Date - Published_At](https://img.shields.io/github/release-date/smart-home-automation-system/presence-service?style=plastic)
![GitHub Release](https://img.shields.io/github/v/release/smart-home-automation-system/presence-service?style=plastic)

---

![GitHub top language](https://img.shields.io/github/languages/top/smart-home-automation-system/presence-service?style=plastic)
![Java](https://img.shields.io/badge/java-21-yellow?style=plastic)
![SpringBoot](https://img.shields.io/badge/SpringBoot-4.1.0-blue?style=plastic)
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

**Status: skeleton.** The service builds, starts and exposes its Actuator, but implements no
functionality yet — there are no endpoints, no UniFi calls and no persistence. It is born on
the target toolchain (Java 21 / Spring Boot 4.1.0) and on the org's observability scheme
(Prometheus metrics, logstash JSON logs, Brave tracing), so the first feature lands on a
finished foundation.

The functionality arrives with the remaining tasks of the *Household presence monitoring*
epic: the UniFi client, the resident and device registry, the detection engine, the reporting
API and the history retention job.

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
locally they stay plain text. The service needs no database, no message broker and no
credentials to start — it has no external dependency yet.

## API

Base path `/home/presence` (`spring.webflux.base-path`). No endpoints yet — this section grows
with the first feature.
