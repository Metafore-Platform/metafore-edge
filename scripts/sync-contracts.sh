#!/usr/bin/env bash
# Pin the platform's MQTT contract schemas into this repo, byte for byte.
#
#   scripts/sync-contracts.sh <metafore-api commit sha>
#
# Copies packages/contracts/mqtt/{route_command,route_result}.schema.json from Metafore-Platform/metafore-api at that
# commit into src/test/resources/schemas/ and rewrites src/test/resources/schemas/PIN (the commit and each file's
# sha256). RouteCommandContractTest fails when the files and the PIN disagree, so a schema is never edited by hand.
# Needs the GitHub CLI signed in with read access to metafore-api.
set -euo pipefail
sha="${1:?usage: scripts/sync-contracts.sh <metafore-api commit sha>}"
[[ "$sha" =~ ^[0-9a-f]{40}$ ]] || { echo "the commit must be a full 40-character sha" >&2; exit 2; }
dir="$(cd "$(dirname "$0")/.." && pwd)/src/test/resources/schemas"
repo="Metafore-Platform/metafore-api"
files=(route_command.schema.json route_result.schema.json)
for f in "${files[@]}"; do
  gh api -H "Accept: application/vnd.github.raw" "repos/$repo/contents/packages/contracts/mqtt/$f?ref=$sha" > "$dir/$f"
done
{
  echo "# Pinned MQTT contract schemas. Change them only with scripts/sync-contracts.sh (never by hand)."
  echo "source_repo=$repo"
  echo "source_commit=$sha"
  for f in "${files[@]}"; do echo "$f=$(shasum -a 256 "$dir/$f" | cut -d' ' -f1)"; done
} > "$dir/PIN"
echo "pinned $repo@$sha"
cat "$dir/PIN"
