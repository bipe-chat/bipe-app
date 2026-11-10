# bipe-core

The shared Rust core of ADR 0267. Spike: bipe-chat/bipe#1172.

- `crates/bipe-core`: the crate. Pure logic: no network, files, clock, secrets or Matrix session.
  - `badge.rs`: badges against a flat key list (`badges.json`).
  - `cvm.rs`: the CVM lines of the company seal detail (`cvm-lines.json`).
  - `identity.rs`: handles, invite codes, phone numbers, social handles, profile checks and the name checks of security rule 8 (`handles.json`, `handle-suggestions.json`, `invite-codes.json`, `phone-numbers.json`, `social-handles.json`, `profile-checks.json`, `relationship-names.json`).
  - `issuer.rs`: badges from several issuers against the issuer registry, with `person` and `org` schemes (`badge-issuers.json`, bipe-chat/bipe#637).
  - `message_data.rs`: rules on Matrix event content: forward marks, cleared chats, assistant send, drafts and work notes (`forward-marks.json`, `cleared-chats.json`, `assistant-send.json`, `drafts.json`, `work-notes.json`).
  - `notice.rs`: which links in an official notice are clickable (`notice-links.json`).
  - `person.rs`: the person proofs of an account, strongest first, with the seal mark (`person-proofs.json`, ADR 0275).
  - `pix.rs`: the BR Code in shared text, its CRC and the Pix key type (`pix-check.json`, field `code`). With `pix_keys`, the Pix keys in the text outside the code (field `keys`).
  - `pix_marks.rs`: the lines of a Pix request card from its mark events, the txid and the mark content (`pix-marks.json`).
  - `profile_facts.rs`: the profile fact lines and notices of chat-request, person-profile and chat-timeline (`profile-facts.json`, ADR 0049).
  - `scam.rs`: scam match IDs and the one warning kind of a message (`scam-detection.json`, `scam-warnings.json`). The regexes come translated from bipe-shared (`docs/scam-matching.md`).
  - `text.rs`: `normalize()` with the name, host or no homoglyph map, and JavaScript whitespace (`normalization.json`).
  - `theft.rs`: theft marks against the badge keys (`theft-marks.json`).
  - `signed_list.rs`: the operator list and the issuer registry (`signed-lists.json`, bipe-chat/bipe#645).
- `harness/`: loads each packaged artifact and runs `bipe-shared/test-vectors/badges.json`, `badge-issuers.json` and `theft-marks.json` through it.
- `tools/uniffi-bindgen`: the bindings generator, with its own lockfile.
- `spike/combined`: one library with the core and `matrix-sdk-ffi`.

CI builds all of it (`.github/workflows/core.yml`). Agents do not build it locally (`bipe/docs/agents/builds.md`).
