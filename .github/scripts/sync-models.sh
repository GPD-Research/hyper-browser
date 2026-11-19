#!/usr/bin/env bash
# Copy the physics-model viewers from their repos into docs/models/. Run by
# .github/workflows/sync-models.yml; also runnable locally:
#   .github/scripts/sync-models.sh [checkout-dir]
set -euo pipefail
cd "$(dirname "$0")/../.."
src="${1:-$(mktemp -d)}"
out=docs/models

# name | repo | folder in repo | file to serve as index.html | rsync excludes
models=(
  "ptolemy|Ptolemaic_model|viewer|index.html|"
  "string-scope|KKLT_String_Cosmology|src/UI|string_scope.html|index.html"
  "lambda-cdm|Lambda_CDM|viewer|index.html|tools"
)

mkdir -p "$out"
for m in "${models[@]}"; do
  IFS='|' read -r name repo dir entry exclude <<<"$m"
  if [ ! -d "$src/$repo" ]; then
    git clone -q --depth 1 "https://github.com/GPD-Research/$repo.git" "$src/$repo"
  fi
  rsync -a --delete ${exclude:+--exclude="$exclude"} "$src/$repo/$dir/" "$out/$name/"
  if [ "$entry" != index.html ]; then mv "$out/$name/$entry" "$out/$name/index.html"; fi
  echo "$name <- $repo/$dir ($(git -C "$src/$repo" rev-parse --short HEAD))"
done
