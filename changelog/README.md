# Changelog updates

A change a tester can see in the iOS or Android app gets one file in
`changelog/updates/`. The changelog groups the files by build. It shows on
the site (`bipe.chat/versoes/`, built by bipe-web) and opens from
Configurações › Ajuda › Histórico de versões in the app.

## Write one

Name it `YYYY-MM-DD-short-slug.md`, with today's date and a slug no other
PR would pick (the PR or issue number helps): `2026-11-02-pr360.md`.
One file is one change. Two changes are two files.

```markdown
---
id: 2026-11-02-pr360
date: 2026-11-02
kind: fixed
platforms: [ios, android]
refs: [bipe-app#360, bipe#965]
---

**Upload preview: the keyboard no longer pushes Send off screen.** Optional
detail: what a person notices.
```

- `id` is the file name without `.md`.
- `kind` is `new` (a person could not do it before), `improved` (it works or
  reads better), `fixed` (it was broken), or `removed`.
- `platforms` lists `ios`, `android` or both.
- `refs` lists PRs and issues, or `[]`.
- Leave `build` out. The build is the one CI makes of the commit that adds
  the file (`tools/version.mjs`: version.json build plus the commit count).
- The body starts with a bold headline in English. The page is public: no
  internal paths, no people's names.

A change nobody sees (CI, tools, docs, generated code, a refactor, web/)
needs no update.

## Commands

```sh
node tools/changelog.mjs                    # the changelog, newest build first
node tools/changelog.mjs --since 330        # the builds after 330, for tester notes
node tools/changelog.mjs --check            # every file is well formed (CI)
node tools/changelog.mjs --json --ref origin/main   # what bipe-web renders
node tools/changelog.mjs --backfill <range> # draft files from commits, then edit them
```

## Backfill

The 223 updates up to build 344 (2 November 2026) were drafted from the PR
titles by `--backfill` and carry `build` and `commit`. A change belongs to the
first build at or after its commit: before `tools/version.mjs` that is the
next `builds/next/<number>` tag.
