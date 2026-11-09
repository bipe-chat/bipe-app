# bipe-core

The shared Rust core of ADR 0267. No production client uses it yet. Spike: bipe-chat/bipe#1172.

- `crates/bipe-core`: the crate. Pure logic: no network, files, clock, secrets or Matrix session.
  - `badge.rs`: badges against a flat key list (`badges.json`).
  - `issuer.rs`: badges from several issuers against the issuer registry, with `person` and `org` schemes (`badge-issuers.json`, bipe-chat/bipe#637).
  - `signed_list.rs`: the operator list and the issuer registry (`signed-lists.json`, bipe-chat/bipe#645).
- `harness/`: loads each packaged artifact and runs `bipe-shared/test-vectors/badges.json` through it.
- `tools/uniffi-bindgen`: the bindings generator, with its own lockfile.
- `spike/combined`: one library with the core and `matrix-sdk-ffi`.

CI builds all of it (`.github/workflows/core.yml`). Agents do not build it locally (`bipe/docs/agents/builds.md`).
