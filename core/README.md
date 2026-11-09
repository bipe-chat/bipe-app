# bipe-core

The shared Rust core of ADR 0267. Spike: bipe-chat/bipe#1172.

- `crates/bipe-core`: the crate. Pure logic: no network, files, clock, secrets or Matrix session.
  - `badge.rs`: badges against a flat key list (`badges.json`).
  - `issuer.rs`: badges from several issuers against the issuer registry, with `person` and `org` schemes (`badge-issuers.json`, bipe-chat/bipe#637).
  - `notice.rs`: which links in an official notice are clickable (`notice-links.json`).
  - `pix.rs`: the BR Code in shared text, its CRC and the Pix key type (`pix-check.json`, field `code`). The keys in the text and the warning stay in the apps until scam matching moves.
  - `pix_marks.rs`: the lines of a Pix request card from its mark events, the txid and the mark content (`pix-marks.json`).
  - `theft.rs`: theft marks against the badge keys (`theft-marks.json`).
  - `signed_list.rs`: the operator list and the issuer registry (`signed-lists.json`, bipe-chat/bipe#645).
- `harness/`: loads each packaged artifact and runs `bipe-shared/test-vectors/badges.json`, `badge-issuers.json` and `theft-marks.json` through it.
- `tools/uniffi-bindgen`: the bindings generator, with its own lockfile.
- `spike/combined`: one library with the core and `matrix-sdk-ffi`.

CI builds all of it (`.github/workflows/core.yml`). Agents do not build it locally (`bipe/docs/agents/builds.md`).
