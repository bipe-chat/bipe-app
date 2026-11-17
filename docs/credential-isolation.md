# Credential storage in the own client

ADR 0147 allows the iOS client to read the legacy app group and permits mutations only in its own group.

`BipeOwnedKeychainGroup` in the app Info.plist is expanded from `$(BIPE_APP_GROUP)` (`group.chat.bipe`, bipe-chat/bipe#531). The code never supplies a default unscoped mutation query. A missing or unexpanded own group disables writes and deletes. `BipeLegacyKeychainReadGroups` lists the permitted read-only groups.

Credential replacement updates the own item in place, or adds an own item when absent. An update/add failure preserves existing own and legacy items. Every mutation includes the own group, including all-account deletes and pending-operation cleanup. Passwords remain device-only. Recovery tokens remain synchronizable.

Reads prefer an own item over a legacy item with the same service/account, regardless of modification date. Other legacy accounts remain discoverable. Pending signup and recovery operations are specific to the current app and have no legacy fallback. Discovery also exposes the selected record's creation date for the oldest-account default in the multi-account client.

CI regression tests use an injected in-memory keychain with synthetic records. They cover duplicate own/legacy records, creation-date discovery, replacement, add/update failures, single/all-account deletion, pending operations, protection attributes, missing configuration and recovery exclusion. They never inspect a person's keychain.

On Android, `BipeAccountVault` stores the encrypted vault under the supplied application context's `noBackupFilesDir` and uses Android Keystore for its encryption key. The app has the application ID `chat.bipe.app` (ADR 0294). It does not import a legacy vault: a vault file it never wrote is set aside, not read. No Android storage change is needed for this issue.

Own-group exclusion markers prevent a deleted password or rejected token from reappearing through legacy fallback. They stay on this device. A successful explicit credential save clears the corresponding marker. Account discovery goes through this store, so signing out cannot silently sign the account back in from the preserved legacy item. Multi-account discovery must use `passwordsWithCreationDates()` to preserve this rule and the oldest-account default.
