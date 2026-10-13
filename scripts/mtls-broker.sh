#!/usr/bin/env bash
# A throw-away EMQX with the platform chart's own certificate listener, topic rules and sign-in settings, and the
# certificates the mTLS integration test (MqttMtlsIT) needs.
#
#   scripts/mtls-broker.sh start     # certificates + broker; prints the MTLS_* settings (and exports them to $GITHUB_ENV)
#   scripts/mtls-broker.sh certs     # the certificates only (no Docker)
#   scripts/mtls-broker.sh stop      # remove the container
#
# The broker config is tests/mtls/emqx.conf (pinned from the chart) + tests/mtls/mtls.overlay.conf, with tests/mtls/acl.conf
# (pinned) as its topic rules, mounted where the chart mounts them. The image is the chart's (emqx/emqx:5.8).
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
dir="${MTLS_DIR:-$root/target/mtls}"
name="${MTLS_CONTAINER:-metafore-mtls-emqx}"
image="${EMQX_IMAGE:-emqx/emqx:5.8}"
api_key="mtls-it"
api_secret="mtls-it-secret-0123456789abcdef"

newkey() { openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$1" 2>/dev/null; }   # PKCS#8

ca() {            # ca <name> <common name>
  newkey "$dir/$1.key"
  openssl req -x509 -new -key "$dir/$1.key" -sha256 -days 2 -subj "/CN=$2" \
    -addext "basicConstraints=critical,CA:TRUE" -addext "keyUsage=critical,keyCertSign,cRLSign" -out "$dir/$1.crt"
}

leaf() {          # leaf <name> <common name> <ca name> <extensions>
  newkey "$dir/$1.key"
  openssl req -new -key "$dir/$1.key" -subj "/CN=$2" -out "$dir/$1.csr"
  printf '%b\n' "$4" > "$dir/$1.ext"
  openssl x509 -req -in "$dir/$1.csr" -CA "$dir/$3.crt" -CAkey "$dir/$3.key" -CAcreateserial -sha256 -days 2 \
    -extfile "$dir/$1.ext" -out "$dir/$1.crt" 2>/dev/null
}

certs() {
  rm -rf "$dir"; mkdir -p "$dir/broker" "$dir/etc/acl" "$dir/etc/auth" "$dir/etc/api-key"
  local client="extendedKeyUsage=clientAuth"
  ca ca "metafore-mtls-test-ca"
  ca stranger-ca "stranger-ca"
  # the broker's own key as cert-manager writes an ECDSA key: SEC1 ("EC PRIVATE KEY")
  openssl ecparam -name prime256v1 -genkey -noout -out "$dir/server.key" 2>/dev/null
  openssl req -new -key "$dir/server.key" -subj "/CN=emqx" -out "$dir/server.csr"
  printf '%b\n' "subjectAltName=DNS:emqx,DNS:localhost,IP:127.0.0.1\nextendedKeyUsage=serverAuth" > "$dir/server.ext"
  openssl x509 -req -in "$dir/server.csr" -CA "$dir/ca.crt" -CAkey "$dir/ca.key" -CAcreateserial -sha256 -days 2 \
    -extfile "$dir/server.ext" -out "$dir/server.crt" 2>/dev/null
  leaf edge 'acme\/acme-managed' ca "$client"                   # the edge under test
  leaf other 'acme\/other-managed' ca "$client"                  # another edge of the same tenant
  leaf platform 'platform' ca "$client"                          # the platform (commands edges, reads telemetry and replies)
  leaf stranger 'acme\/acme-managed' stranger-ca "$client"       # the right name, from a CA the broker does not trust
  # the same key written the other way cert-manager does for ECDSA: SEC1, "EC PRIVATE KEY", with the curve and public point
  openssl ecparam -name prime256v1 -genkey -noout -out "$dir/edge-sec1.key" 2>/dev/null
  openssl req -new -key "$dir/edge-sec1.key" -subj '/CN=acme\/acme-managed' -out "$dir/edge-sec1.csr"
  printf '%b\n' "$client" > "$dir/edge-sec1.ext"
  openssl x509 -req -in "$dir/edge-sec1.csr" -CA "$dir/ca.crt" -CAkey "$dir/ca.key" -CAcreateserial -sha256 -days 2 \
    -extfile "$dir/edge-sec1.ext" -out "$dir/edge-sec1.crt" 2>/dev/null
  # what the broker mounts (the names the chart's emqx-tls Secret has)
  cp "$dir/ca.crt" "$dir/broker/ca.crt"; cp "$dir/server.crt" "$dir/broker/tls.crt"; cp "$dir/server.key" "$dir/broker/tls.key"
  chmod -R a+rX "$dir"; chmod a+r "$dir"/*.key
  echo "certificates in $dir: $(openssl x509 -in "$dir/edge.crt" -noout -subject)"
}

start() {
  command -v docker >/dev/null || { echo "docker is needed to start the broker" >&2; exit 2; }
  certs
  cat "$root/tests/mtls/emqx.conf" "$root/tests/mtls/mtls.overlay.conf" > "$dir/emqx.conf"
  cp "$root/tests/mtls/acl.conf" "$dir/etc/acl/acl.conf"
  printf 'user_id,password,is_superuser\nplatform,not-used-the-platform-signs-in-with-its-certificate,false\n' > "$dir/etc/auth/users.csv"
  printf '%s:%s\n' "$api_key" "$api_secret" > "$dir/etc/api-key/api_keys.txt"
  chmod -R a+rX "$dir/etc"; chmod a+r "$dir/emqx.conf"
  docker rm -f "$name" >/dev/null 2>&1 || true
  for attempt in 1 2 3; do docker pull -q "$image" >/dev/null && break || sleep $((attempt * 5)); done
  docker run -d --name "$name" -p 127.0.0.1:8883:8883 -p 127.0.0.1:18083:18083 \
    -v "$dir/emqx.conf:/opt/emqx/etc/emqx.conf:ro" \
    -v "$dir/etc/acl/acl.conf:/opt/emqx/etc/acl/acl.conf:ro" \
    -v "$dir/etc/auth:/opt/emqx/etc/auth:ro" \
    -v "$dir/etc/api-key:/opt/emqx/etc/api-key:ro" \
    -v "$dir/broker:/opt/emqx/etc/certs/mqtt:ro" \
    "$image" >/dev/null
  for i in $(seq 1 60); do
    if curl -sf -u "$api_key:$api_secret" "http://127.0.0.1:18083/api/v5/listeners/ssl:default" | grep -q '"running":true'; then
      echo "broker ready after ${i}s"; break
    fi
    if [ "$i" = 60 ]; then echo "the broker did not come up:" >&2; docker logs --tail 80 "$name" >&2; exit 1; fi
    sleep 1
  done
  {
    echo "MTLS_DIR=$dir"; echo "MTLS_BROKER=ssl://127.0.0.1:8883"; echo "MTLS_API=http://127.0.0.1:18083"
    echo "MTLS_API_KEY=$api_key"; echo "MTLS_API_SECRET=$api_secret"
  } | tee "$dir/env" > "${GITHUB_ENV:-/dev/null}"
}

stop() { docker rm -f "$name" >/dev/null 2>&1 || true; }

case "${1:-}" in
  start) start ;;
  certs) certs ;;
  stop) stop ;;
  *) sed -n 2,9p "$0"; exit 2 ;;
esac
