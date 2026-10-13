#!/usr/bin/env bash
# Pin the platform broker's own configuration for the mTLS integration test, byte for byte.
#
#   scripts/sync-broker-config.sh <metafore-api commit sha>
#
# Copies deploy/helm/metafore/files/emqx/{emqx.conf,acl.conf} from Metafore-Platform/metafore-api at that commit into
# tests/mtls/ and rewrites tests/mtls/PIN (the commit and each file's sha256). BrokerConfigPinTest fails when a copy and
# the PIN disagree. tests/mtls/mtls.overlay.conf is the one hand-kept piece: the certificate listener block of the chart's
# templates/emqx.yaml (an overlay on emqx.conf), whose source lines are named in its header.
set -euo pipefail
sha="${1:?usage: scripts/sync-broker-config.sh <metafore-api commit sha>}"
[[ "$sha" =~ ^[0-9a-f]{40}$ ]] || { echo "the commit must be a full 40-character sha" >&2; exit 2; }
dir="$(cd "$(dirname "$0")/.." && pwd)/tests/mtls"
repo="Metafore-Platform/metafore-api"
files=(emqx.conf acl.conf)
for f in "${files[@]}"; do
  gh api -H "Accept: application/vnd.github.raw" "repos/$repo/contents/deploy/helm/metafore/files/emqx/$f?ref=$sha" > "$dir/$f"
done
{
  echo "# Pinned broker configuration of the platform chart. Change it only with scripts/sync-broker-config.sh."
  echo "source_repo=$repo"
  echo "source_commit=$sha"
  for f in "${files[@]}"; do echo "$f=$(shasum -a 256 "$dir/$f" | cut -d' ' -f1)"; done
} > "$dir/PIN"
echo "pinned $repo@$sha"
cat "$dir/PIN"
