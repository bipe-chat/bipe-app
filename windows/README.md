# Windows OAuth delivery

`oauth-client.json` is the one public metadata source for the Windows native client. It contains no client secret. The state is **pending**: this file does not create or prove a deployed MAS client. Live registration/callback verification stays in [bipe#1456](https://github.com/bipe-chat/bipe/issues/1456).

The own infra source loads MAS client records from the `bipe-mas-clients` Secret, key `clients.yaml`, through `ess/production.yaml`. Repository metadata alone does not establish whether a Windows record is already present on the server. No cluster secret, production session or live MAS configuration was read/changed for this checkpoint. Inspect only the required public fields in the authorized deployment step, then prepare a reviewable registration using the current MAS format. Do not create a duplicate client.

After the dedicated public record, native callback ownership and grant metadata are verified, set its public `clientId`, `verifiedOn` and `status: verified` together. The environment `BIPE_WINDOWS_OAUTH_CLIENT_ID`, if supplied, must match that reviewed record. It cannot turn a pending record into a configured client. The known Mac client id is refused. Missing/extra private fields, changed origins, callbacks and grants fail validation. No Mac/web/dynamic-registration fallback is used.

`tools/windows-oauth.mjs --check` validates source metadata. `--release` also requires verified registration and is a dependency of every Compose distribution task, including a distributable image/uberjar. Source compilation is allowed with pending metadata; it packages a null id and cannot start a phone link. Compiling and distributing are separate gates. These commands do not build, deploy or register anything themselves.

`--kotlin <directory>` generates the SDK's public name, issuer, homeserver and callback constants. `tools/desktop-resources.mjs` packages the reviewed id. The SDK builder and package metadata therefore read one source. Native installation/protocol association, signing/update and Windows11/released-phone acceptance remain open; no installer is delivered by this gate.

10 November 2026: MZ's no-build/no-push hold remains active. Do not dispatch CI, publish source branches or change the live registration while that hold remains. Windows stays planned in the parity matrix; Mac feature work remains paused under ADR0288.
