# keycloak-graph-email

Keycloak `EmailSenderProvider` SPI that sends via Microsoft Graph
`POST /users/{sender}/sendMail` using OAuth2 client credentials.
Replaces the built-in SMTP sender; no MX relay or SMTP port needed.

## Provider ID

`graph` — registered in `META-INF/services/org.keycloak.email.EmailSenderProviderFactory`.

## Selecting this provider in Keycloak

Add this env var to the Keycloak pod (chart opt-in, see chart PR):

```
KC_SPI_EMAIL_SENDER_PROVIDER=graph
```

The `from`-address check in KC 26.1 lives only in `DefaultEmailSenderProvider`
(confirmed: `keycloak-services-26.1.0.jar` `DefaultEmailSenderProvider.class`
constant-pool string "No sender address configured in the realm settings for
emails"). `FreeMarkerEmailTemplateProvider` does not check it. The realm therefore
does NOT need `smtpServer.from` configured when this plugin is active.

## Configuration env vars

All values are synced by External Secrets Operator from
`metafore-dev/platform-email` in Secrets Manager:

| Env var | SM field | Required |
|---|---|---|
| `KC_SPI_EMAIL_SENDER_GRAPH_AZURE_TENANT_ID` | `tenant_id` | yes |
| `KC_SPI_EMAIL_SENDER_GRAPH_CLIENT_ID` | `client_id` | yes |
| `KC_SPI_EMAIL_SENDER_GRAPH_CLIENT_SECRET` | `client_secret` | yes |
| `KC_SPI_EMAIL_SENDER_GRAPH_SENDER` | `sender` | yes |
| `KC_SPI_EMAIL_SENDER_GRAPH_DISPLAY_NAME` | `display_name` | no |
| `KC_SPI_EMAIL_SENDER_GRAPH_REPLY_TO` | `reply_to` | no |

If any required field is empty or missing the plugin throws
`EmailException("Graph email: not configured")` on every send attempt —
KC shows an error to the user, nothing is silently dropped.

## Config changes require a pod restart

Credentials are read once at `init()` (Keycloak startup). After Said saves
new settings in the platform UI, ESO syncs the Secrets Manager value to the
`platform-email` Kubernetes Secret. The Keycloak pod must restart to pick up
the new values. The chart adds a Reloader annotation on the `platform-email`
Secret when `keycloak.graphEmail.enabled` is true; a change to the Secret
triggers an automatic pod rollout.

## Token cache

The Azure AD access token is cached until its `expires_in` minus 60 s. Cache
state lives in the factory singleton (loaded once per KC pod). On a 401 from
Graph the provider drops the cache and retries once with a fresh token before
failing. All caching is in-memory; a pod restart clears it.

## Docker image and kc.sh build

The Dockerfile runs `kc.sh build` so KC's Quarkus augmentation
runs at image build time and the provider is registered before startup.

**Important:** the chart starts Keycloak with `start-dev` (not `--optimized`),
so KC re-augments at startup regardless. The `kc.sh build` step in the image
is still useful to validate provider registration at build time, but it does
not skip startup augmentation. The readiness probe is set to
`initialDelaySeconds: 60` to allow for it.

If this chart ever moves to `start --optimized`, `kc.sh build` MUST be run
with the same env vars as the live pod (KC_DB, KC_HTTP_RELATIVE_PATH, etc.)
to avoid a startup failure.

## Build and publish

The image is built in `development.yml`'s `publish` job, which runs on
push to `development` after all other jobs pass. It runs when both `vars.EDGE_PUBLISH_ENABLED == 'true'` (the publish
job's top-level gate, also required for the edge image) and
`vars.KC_PUBLISH_ENABLED == 'true'` are set on the repo. It pushes a
SHA-tagged image to
`359469026479.dkr.ecr.us-east-1.amazonaws.com/metafore-dev/keycloak`
using the OIDC role `vars.EDGE_PUBLISH_ROLE_ARN`. Tags are immutable;
the chart pins by digest. The pushed digest is printed to the publish
job's step summary.

## Upgrading Keycloak

Bumping the base image version requires rebuilding and republishing the
plugin image. Update `FROM quay.io/keycloak/keycloak:<version>` in
`Dockerfile`, bump `keycloak.version` in `pom.xml` to match, run
`mvn package -B` to verify it still compiles, open a PR, and update the
chart's `keycloak.image.digest` once the new image is pushed.
