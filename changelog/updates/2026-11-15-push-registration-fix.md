---
id: 2026-11-15-push-registration-fix
date: 2026-11-15
kind: fixed
platforms: [ios, android]
refs: []
---

**Notifications come back on phones that stopped getting them.** The app sets this phone's push registration again each time it starts, so a registration the server dropped returns. An iPhone build installed from Xcode now registers with the right Apple push service.
