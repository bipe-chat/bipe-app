#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
# Copyright 2026 Bipe contributors

# The pinned bipe-core web bundle (core/release/release.json, ADR 0267, bipe-chat/bipe#1457) into web/src/core:
# the ubrn wasm2 TypeScript bindings and bipe_core.wasm. A client takes bipe-core from a GitHub release, never from a
# CI workspace (core/release/README.md). The SHA-256 must match the pin. Run from web/: scripts/fetch-core.sh
set -euo pipefail
cd "$(dirname "$0")/.."
manifest=../core/release/release.json
tag=$(node -p "require('$manifest').tag")
version=$(node -p "require('$manifest').version")
tgz="bipe-core-web-$version.tgz"
sum=$(node -p "require('$manifest').assets['$tgz'] ?? ''")
test -n "$sum" || { echo "release.json has no $tgz" >&2; exit 1; }
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
curl -fsSL "https://github.com/bipe-chat/bipe-app/releases/download/$tag/$tgz" -o "$tmp/$tgz"
echo "$sum  $tmp/$tgz" | shasum -a 256 -c -
rm -rf src/core
mkdir -p src/core
tar -xzf "$tmp/$tgz" -C src/core
ls src/core
