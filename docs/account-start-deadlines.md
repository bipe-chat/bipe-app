# Account start deadlines

The reported `Abrindo sua conta…` hang has no confirmed device-level cause yet. A session left in the iOS keychain after reinstalling, without its SDK database, is one possible cause. The existing missing-store check handles that case. Its presence in the earlier device build does not prove that it caused or resolved the reported hang.

Account discovery and pending-signup storage now run away from the UI thread, with the same 20-second deadline as SDK steps. Swift uses a dispatch timer and detached work. Android waits on a result from an independent IO worker. Neither implementation joins unfinished work after cancellation. Tests use synthetic blocking operations, never actual account storage.

A missing database permits fresh sign-in. A timed-out restore retains the saved session and database and skips that account for this attempt, since the SDK might still hold the database. Other accounts can open. If none opens, the app shows the existing failure and retry action.

Device acceptance: launch with a saved account, reinstall without its SDK store, launch offline, retry after a timeout, and switch between accounts. Read only the `account-start` step names and outcomes. Never collect tokens, passwords, account IDs or SDK error bodies. A successful process launch alone is not evidence that account start reached `ready`.
