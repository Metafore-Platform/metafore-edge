# metafore-edge changelog

## Unreleased (development, v2) — an mTLS integration test against the platform's own broker

- **Add `scripts/mtls-broker.sh`, `tests/mtls/` and `MqttMtlsIT`** (Compass 9a26adcd). CI job `mtls` of `development.yml` starts EMQX 5.8 with the platform chart's own `emqx.conf` and `acl.conf` (pinned byte for byte from metafore-api by `scripts/sync-broker-config.sh`; `BrokerConfigPinTest` fails offline on a hand edit) plus the chart's certificate listener (`tests/mtls/mtls.overlay.conf`), makes throw-away certificates with openssl, and runs the edge's real `MqttSecurity` against it: the broker names the client by its certificate's common name on `ssl:default`; the topic rules confine it; a stranger's certificate, no certificate and an untrusted broker are refused; the same factory presents whatever is on disk at the next connect. Runs on `development` only; `mvn verify` locally skips it unless `MTLS_DIR` is set.

## Unreleased (development, v2) — MQTT sign-in: the edge never connects anonymously

- **Add `mqtt/MqttSecurity` and `mqtt/ReloadingSslSocketFactory`** (Compass 9a26adcd, adr-059). The edge signs in to the broker with its client certificate (`METAFORE_MQTT_TLS_ENABLED`, `_CA_CERT`, `_CERT`, `_KEY`: PEM files, normally mounted at `/etc/metafore/mqtt`) and sends **no username and no password**: the broker maps the certificate's common name, which must be `<TENANT_ID>/<CONTROLLER_ID>`, to the username its topic rules key on. The pair is read again at every connect, so a certificate the platform renews on disk is used without a restart; a pair caught mid-rotation keeps the last one that loaded. PKCS#8, SEC1 and PKCS#1 keys load (bcpkix).
- **Without a certificate**, `METAFORE_MQTT_USERNAME` (exactly `<TENANT_ID>/<CONTROLLER_ID>`) and `METAFORE_MQTT_PASSWORD` are required. **An edge with neither does not start** (before, it connected anonymously). `METAFORE_MQTT_TLS_INSECURE` is refused.
- `AccessController` registers one `paho` component with these settings; every route's endpoint inherits them. No route URI changed.

## 1.1.0 — 2026-05-16 — Phase 14.9 edge topology advertisement

- **Add `EdgeRuntime` enum** (`docker | native | docker-host-network | unknown`) classifying the surrounding process topology. Wire form is kebab-case (matches `contracts/mqtt/registration.schema.json` enum).
- **Add `RuntimeProbe`** — layered auto-detection (`EDGE_RUNTIME` env override → `/.dockerenv` file → `/proc/1/cgroup` markers → `HOSTNAME` heuristic → host-network upgrade via `/proc/net/dev` → JVM `os.name` fallback → `UNKNOWN`). Injectable `Probes` record makes the probe unit-testable without spelunking the host filesystem.
- **Extend `EdgeConfig`** with `runtime()` + `runtimeHints()` accessors. The probe runs once at construction; result is cached. Hints whitelist 5 keys (`os_name`, `java_version`, `hostname`, `docker_env_file_present`, `cgroup_signature`) — no env-var dump.
- **Extend `MessageFactory.registration`** with a 7-arg overload taking `runtime` + `runtimeHints`. The legacy 5-arg overload is preserved; it omits both fields (back-compat invariant — older payloads still validate against the additive schema).
- **`RegistrationRoute`** now passes `config.runtime()` + `config.runtimeHints()` so every edge registration MQTT payload advertises its topology.
- **Bump `EDGE_VERSION`** default `1.0.0 → 1.1.0` to signal the additive wire-protocol extension. No client-side gating; older cores ignore the new fields.

Anchor incident: 2026-05-16 — a Source authored against `host.docker.internal` survived an AC restart that switched from Docker to native Java; the JDBC connection silently failed because `host.docker.internal` is a Docker-engine hosts-file alias that doesn't exist on a native Windows JVM. Surfacing the runtime as part of registration lets metafore-core flag the topology mismatch at mount time (see brief `BRIEF_PHASE_14_9_EDGE_TOPOLOGY_ADVERTISEMENT.md`).

Pairs with `mount-test-before-commit` (Brief 14.12) — Phase 14.9 produces the runtime signal that 14.12's pre-commit connectivity check consumes.
