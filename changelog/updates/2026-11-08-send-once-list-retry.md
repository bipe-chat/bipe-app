---
id: 2026-11-08-send-once-list-retry
date: 2026-11-08
kind: fixed
platforms: [ios, android]
refs: []
---

**A quick second tap no longer sends a message or a photo twice, and the chat list no longer spins for ever when it cannot load.** On iPhone, a work-chat reply sent with a double tap went out twice. On Android, a second tap on Send in the photo preview sent every photo again. When the chat list could not load at start, it showed the spinner until you closed the app. Now it tries again on its own.
