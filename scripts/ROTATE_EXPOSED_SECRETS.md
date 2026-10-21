# Rotate the credentials that were published in `frit-android`

**Status: ACTION REQUIRED — do this before anything else.**

`uchechukwu090/frit-android` is a **PUBLIC** GitHub repo (created 2026-03-11). Its git
history contains a committed `.env` file whose values are **byte-identical to the live
`server/.env` on this host**:

| Key | Exposed in history | Still live? |
|---|---|---|
| `AUTH_TOKEN` | yes (48 chars) | **YES — identical to `server/.env`** |
| `MISTRAL_API_KEY` | yes (32 chars) | **YES — identical** |
| `TWELVE_DATA_KEY` | yes (32 chars) | **YES — identical** |

Anyone who clones the repo can read them:

```bash
git clone https://github.com/uchechukwu090/frit-android.git
cd frit-android
git cat-file -p 257c3d4bad24ff2b7c7ab34483c9608f70a8c570   # the .env blob
```

A second (smaller) leak was fixed in this pass: `frit.db.backup-tmp` — a 929 KB runtime
SQLite snapshot — was tracked because `.gitignore` only listed the exact names `frit.db`,
`frit.db-shm`, `frit.db-wal`, `*.db-journal`. It is now untracked and the ignore rules
cover the whole family. The blob is still in the OLD history. The snapshot itself was
mostly empty (59 tables, 1,929 rows, `user_credits` 2 rows, `credit_txns` 3 rows, all
position/wallet/journal tables empty), so the personal-data exposure was minor — but the
file was still a published database.

## 1. Rotate the three keys (do this first — it works even if you skip step 2)

Rotating is the fix that takes effect immediately. Rewriting history only removes the
old copies; it does not un-publish them, because anyone who cloned in the meantime
still has them.

**`AUTH_TOKEN`** — the FRIT master API token. Every client authenticates with it.

```powershell
# 1. generate a new token
node -e "console.log(require('crypto').randomBytes(24).toString('hex'))"

# 2. put it in server/.env
#    AUTH_TOKEN=<new value>
#    (keep the OLD value around until the clients have re-registered — see step 3)

# 3. redeploy the server
```

**`MISTRAL_API_KEY`** — paid Mistral API key. Rotate in the Mistral console, then update
`server/.env`.

**`TWELVE_DATA_KEY`** — market-data key. Rotate in the Twelve Data console, then update
`server/.env`.

Also rotate anything else that has ever been in `server/.env` and might be in an older
commit: check with

```bash
cd server
git log --all --oneline -- .env
git rev-list --all --objects | grep -E ' \.env$'
```

## 2. Decide what to do about the public repo

Pick one:

**(a) Make it private** (fastest, safest). The repo is a backup + working copy; nothing
in the app needs it to be public.

```bash
# GitHub UI: repo -> Settings -> Danger Zone -> Change visibility -> Private
# or:
gh repo edit uchechukwu090/frit-android --visibility private
```

**(b) Purge the history** (keeps it public, removes the old copies). This REWRITES
history — every existing clone becomes stale and needs re-cloning or a hard reset.

```bash
cd server
pip install git-filter-repo
git filter-repo --path .env --invert-paths        # drop the .env blob
git filter-repo --path frit.db.backup-tmp --invert-paths
git remote add origin-new https://github.com/uchechukwu090/frit-android.git
git push --force origin-new main
```

Note: `git filter-repo` also rewrites commit SHAs, so the `frit_andriod` submodule
pointer to this repo must be re-pointed afterwards:

```bash
cd ..
git -C server fetch origin
git add server
git commit -m "Point the server submodule at the rewritten history"
git push origin main
```

**(c) Leave it public and just rotate.** Accept that the old values stay readable and
rely on the rotation. Fine ONLY if the rotation actually happened.

## 3. After rotating — make sure the clients still work

`AUTH_TOKEN` is the master token. The Android app ships it via
`BuildConfig.FRIT_AUTH_TOKEN` (baked from `server/.env` at build time) and per-install keys
are issued by `POST /auth/install`. So:

- Build a new APK with the NEW token baked in, and
- Re-register installs (the app does this automatically via `ensureInstallKey()`), or
- Keep the server accepting the OLD token for a grace period.

`server/.env` itself was never committed as a live file — only the one historical `.env`
blob above. `server/.env` is in `.gitignore` and stays that way. Verify before making
anything public:

```bash
cd server
git ls-files | grep -E '\.env$|frit\.db'      # should list ONLY .env.example
```

## 4. Preventing a repeat

- `server/.gitignore` now ignores `.env` AND the whole `frit.db*` / `*.db` family.
- Add a pre-commit secret scan (gitleaks / trufflehog) to the CI workflow.
- Keep `frit_andriod` and `sandbox` PRIVATE (they already are).
