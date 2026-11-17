---
id: 2026-11-15-push-every-account
date: 2026-11-15
kind: fixed
platforms: [ios, android]
refs: [bipe#1519]
---

**Every account on the phone gets notifications, not only the open one.** Each account and each Interno session keeps its own push registration, and signing out of one account leaves the others alone.
