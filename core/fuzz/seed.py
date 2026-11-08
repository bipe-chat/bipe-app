# SPDX-License-Identifier: AGPL-3.0-only
# Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
# Copyright 2026 Bipe contributors

# Seeds the fuzz corpora from bipe-shared test-vectors/badges.json: each envelope as text, each payload as bytes.
# Usage: python3 seed.py <badges.json> <corpus dir>
import base64, json, os, sys

vectors = json.load(open(sys.argv[1]))["vectors"]
for target in ("envelope", "payload"):
    os.makedirs(os.path.join(sys.argv[2], target), exist_ok=True)
for i, vector in enumerate(vectors):
    envelope = vector["envelope"]
    with open(os.path.join(sys.argv[2], "envelope", f"v{i}"), "w") as f:
        json.dump(envelope, f)
    payload = envelope.get("payload") if isinstance(envelope, dict) else None
    if isinstance(payload, str):
        try:
            data = base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4))
        except ValueError:
            continue
        with open(os.path.join(sys.argv[2], "payload", f"v{i}"), "wb") as f:
            f.write(data)
