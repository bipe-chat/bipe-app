---
id: 2026-11-07-mac-images-links
date: 2026-11-07
kind: improved
platforms: [macos]
refs: [bipe#1018, bipe#1162, bipe#1164]
---

**Mac conversations have images and clickable links.** Send a photo with a caption or image description, open received images and save the original. Images use the Matrix encrypted media APIs and follow the account's preview preference. Standard photos keep the phone's 2048 px limit and remove camera and location metadata; high quality keeps the image dimensions. GIFs keep their animation, with still images under Reduce Motion.

Formatted messages and links use Bipe's shared parser and scam checks, including destinations hidden behind a link label. App navigation and the conversation list now share one sidebar.
