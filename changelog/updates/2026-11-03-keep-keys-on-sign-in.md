---
id: 2026-11-03-keep-keys-on-sign-in
date: 2026-11-03
kind: fixed
platforms: [ios, android]
refs: [bipe#909]
---

**Signing in again on the same phone keeps its keys.** After a PIN recovery or an
expired session, the phone keeps its device and its encryption keys, so linking
a computer and reading old messages still work.
