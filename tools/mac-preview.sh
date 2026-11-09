#!/bin/bash
# SPDX-License-Identifier: AGPL-3.0-only
# Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
# Copyright 2026 Bipe contributors

# Called only after the main workflow's universal builds and native tests pass.
set -euo pipefail
: "${GH_REPO:?}" "${GITHUB_SHA:?}"
[[ "$GITHUB_SHA" =~ ^[0-9a-f]{40}$ ]]
sha256sum --check Bipe-Mac.zip.sha256

cat > notes.md <<EOF
Native Bipe preview for macOS 15 or later, on Apple silicon and Intel.

Download Bipe-Mac.zip, extract it and move Bipe.app to Applications. Link an existing account from Bipe on your phone.

Personal and work feature parity is in progress: https://github.com/bipe-chat/bipe/issues/1018
This preview uses an ad hoc signature and is not notarized by Apple. macOS may require approval in System Settings > Privacy & Security after the first opening attempt.

Source: $GITHUB_SHA. Both Mac build configurations and the native tests passed before publication. The SHA-256 checksum is attached.
EOF

if published=$(gh release view mac-preview --repo "$GH_REPO" --json body --jq .body); then
  source=$(printf '%s\n' "$published" | sed -nE 's/^Source: ([0-9a-f]{40})\..*/\1/p')
  [[ "$source" =~ ^[0-9a-f]{40}$ ]] || { echo 'Published preview has no unambiguous source; refusing to replace it.' >&2; exit 1; }
  relation=$(gh api "repos/$GH_REPO/compare/$source...$GITHUB_SHA" --jq .status)
  case "$relation" in
    ahead|identical) ;;
    behind|diverged) echo "::notice::Skipping superseded Mac preview $GITHUB_SHA (published $source)."; exit 0 ;;
    *) echo "Unknown preview source relation: $relation" >&2; exit 1 ;;
  esac
  # Fail before touching assets if the tag cannot advance. This also guards a
  # rerun of an older main workflow after a newer preview was published.
  gh api --method PATCH "repos/$GH_REPO/git/refs/tags/mac-preview" -f sha="$GITHUB_SHA" -F force=false >/dev/null
  gh release upload mac-preview Bipe-Mac.zip Bipe-Mac.zip.sha256 --clobber --repo "$GH_REPO"
  gh release edit mac-preview --prerelease --title 'Bipe (Mac preview)' --notes-file notes.md --repo "$GH_REPO"
else
  gh release create mac-preview Bipe-Mac.zip Bipe-Mac.zip.sha256 --target "$GITHUB_SHA" --prerelease \
    --title 'Bipe (Mac preview)' --notes-file notes.md --repo "$GH_REPO"
fi
