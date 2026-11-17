---
id: 2026-11-17-interno-recovery-race
date: 2026-11-17
kind: fixed
platforms: [ios, android]
refs: [bipe#1471]
---

**Interno keeps its history when two of your devices set it up at the same time.** Before, the second device could save a key that did not open the message backup, and a third device then opened Interno without the earlier messages.
