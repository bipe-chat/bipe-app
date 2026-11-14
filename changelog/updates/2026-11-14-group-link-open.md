---
id: 2026-11-14-group-link-open
date: 2026-11-14
kind: fixed
platforms: [ios, android]
refs: [bipe#744]
---

**Group links open the group again when Bipe was closed, and each admin keeps their own link.** On Android, tapping a bipe.chat/g/ link while Bipe was closed now opens the group preview after the app starts, and Android 12 and later open these links in Bipe, not in the browser. On both phones, a group with several admins now shows each admin their own link, so a newer link from another admin no longer hides yours or gets revoked by mistake.
