# pc-control

An Android app for managing Ayush's always-on Ubuntu laptop (`ayushlive-hp-pavilion`) from his phone over
Tailscale, plus the server pieces behind it. Everything runs over the tailnet, with one exception: **notification
text** goes through Firebase Cloud Messaging (FCM) so the phone gets notifications without a background
connection. FCM payloads hold only a title, a short body and an id. A root request says "Root approval needed" and
the reason, never the command. Approving still happens in the app, with a biometric signature, over the tailnet.

The app has five tabs:

- **Routes**: every `tailscale serve` route (cams, vitals, music, recordings, Seerr, Radarr, Sonarr, Bazarr, …)
  as cards that open in the browser. The list is read live, so new routes appear by themselves.
  API-only routes (backends used by another page, like the Cat cams page's `/rec`, `/music`, `/ptz` and
  `/detect`) are hidden on the server. To hide a new one, or to show a hidden one, edit
  `~/.config/pc-control/routes.json`; it is read live, so there's no restart and no app rebuild:
  `{"/new-api": {"hidden": true}, ":9696": {"name": "Prowlarr", "hidden": false}}`
  (keys are the path for the main HTTPS host, or `":<port>"` for other ports; you can also set `name`, `icon`
  (a Material Community icon name), `subtitle` and `kind` (`"api"` hides a route)).
- **Vitals**: CPU, temperatures, RAM, GPU, network, disks and battery from `/vitals/api`. It polls only while the
  tab is visible, and processes are sampled only when you switch them on.
- **Root**: the approval inbox. Each request shows the exact command and the reason, with **Approve
  (fingerprint)** and **Deny** buttons. Below it is the history, with exit codes and output.
- **Services**: your systemd user units with their status and logs, and a restart button.
- **Settings**: root-approval enrollment, **Notifications** (where each kind of notification goes: Telegram,
  the app, or both), push status with **Test app** / **Test Telegram** buttons, the server URL, and **App
  updates** (APK releases + over-the-air JS updates).

## Notification router

pc-api decides where every unsolicited notification goes. Each category has two switches, **Telegram** (the
Qwen bot's chat) and **App** (FCM push), plus one master switch per channel (Settings → Notifications). The
settings live on the laptop in `~/.local/share/pc-control/notify-settings.json`.

| Category | Sent by | Telegram | App |
|---|---|---|---|
| Cat alerts (photo in Telegram, big picture in the app) | catdetect | on | off |
| Download started | Radarr/Sonarr webhook (On Grab) | on | off |
| Available to watch | Seerr webhook (media available) | on | off |
| Download problems | Seerr (failed), Radarr/Sonarr (manual interaction), import-watch | on | on |
| Qwen downloads finished | qBittorrent hook `notify_done.py` | on | off |
| Backups & system alerts | config-backup, kernel-fix-watch, rec-digest | on | on |
| Root approvals | rootd via pc-api | off | on |
| Command results | rootd via pc-api | off | on |
| Other messages | anything else sent to `/pc/api/notify` | off | on |

The defaults keep what went to Telegram before the router. On the app side, only the things you shouldn't miss
are on. The Qwen bot's replies to your own messages (`/status`, searches, repo runs…) don't go through here.

- Local scripts call the helper `~/remote-pc/lib/pcnotify.py` (Python `pcnotify.notify(category, title, body,
  telegram_text=, image=, link=)` or shell `python3 -I ~/remote-pc/lib/pcnotify.py <category> <title> [body]
  [--image F] [--text T]`). It **fails open**: if pc-api is down, errors, or its Telegram send failed (HTTP
  502), the helper sends straight to Telegram as before, so alerts are never lost.
- Radarr, Sonarr and Seerr post to `http://127.0.0.1:1990/pc/api/hook/{radarr,sonarr,seerr}` with the shared
  secret from `~/.config/pc-control/hook-secret` (Radarr/Sonarr: Basic auth password, Seerr: Authorization
  header `Bearer …`). Without it pc-api answers 401.
- Laptop-specific values (Telegram chat id, Seerr's public URL) are in `~/.config/pc-control/pcapi.json`
  (`telegram_chat`, `seerr_url`); the bot token is read from the Qwen bot's token file. None of it is in git.

## Notifications (FCM)

- The app gets an FCM token and registers it with pc-api (`POST /pc/api/push/register`, over the tailnet,
  checked like every other call). It registers again on every app start and whenever FCM rotates the token.
  pc-api keeps the tokens in `~/.local/share/pc-control/push-tokens.json` (mode 600, at most 10).
- pc-api sends through the FCM HTTP v1 API with the service-account key in
  `~/.config/pc-control/fcm-service-account.json` (never in the repo, never logged). It signs a JWT, exchanges
  it for an OAuth access token and caches that token for about an hour.
- pc-api sends **data-only, high-priority** messages, and the app shows them itself, so channels and tap actions
  work in the background too: new root request → "Root approval needed" + reason (tapping opens it in the
  Root tab); command finished → "Command finished / failed (exit N)" + reason; decided, expired or cancelled →
  a silent dismiss. Tokens that FCM reports as `UNREGISTERED`/404 (or as invalid) are dropped.
- Anything local can send a notification through the router (see above):
  `curl -s localhost:1990/pc/api/notify -H 'Content-Type: application/json' -d '{"category":"system","title":"Backup done","body":"42 GB"}'`.
- Router notifications use one Android channel per category, so you can also tune them in Android settings.
  A picture (cat alerts, Seerr posters) is fetched from pc-api over the tailnet when the notification arrives
  (`/pc/api/notify/image/<id>`); FCM itself only carries the text and that path.
- No foreground service, no pinned notification, no battery-optimization exemption. While the app is open,
  the Root tab still updates live over SSE (`/pc/api/events`).
- Build-time Firebase config: `app.config.js` uses `~/.config/pc-control/google-services.json` (or
  `$PCC_GOOGLE_SERVICES`). It is never copied into the repo, only into the generated `android/`, which is
  gitignored.

## Security model, in plain words

The AI agent on the laptop runs as `ayushlive` and has no sudo. Sometimes it needs root. The rule:

> A root command runs only after Ayush approves **that exact command** with his fingerprint on his phone.
> There is no password anywhere in the flow, and nothing on the laptop can approve or enroll by itself.

How that is enforced:

1. **rootd** is a small root daemon (`/usr/local/lib/rootd/rootd.py`, root-owned and copied out of the repo).
   It listens on `/run/rootd/rootd.sock` and accepts connections only from `ayushlive` and root, checked with
   `SO_PEERCRED`.
2. The agent submits `{argv, reason, timeout}` (with `rootctl`). rootd stores the request with a random id, a
   random nonce and a 180-second expiry. It resolves the command against a fixed root `PATH`, so the phone
   shows e.g. `/usr/bin/apt`, not `apt`. It rejects invisible or confusable characters: control characters
   other than tab and newline, bidi overrides and zero-width characters.
3. The phone holds an **Android Keystore EC P-256 key**. The key never leaves the phone's secure hardware
   (StrongBox when available), and every use needs a strong biometric through `BiometricPrompt`. Adding a new
   fingerprint on the phone destroys the key.
4. To approve, the phone builds this payload **itself from the command it is displaying** and signs it:
   `rootd-v1\napprove\n<id>\n<nonce>\n<expires>\n<sha256(JSON.stringify(argv))>`.
   rootd recomputes the payload from the argv **it** stored. If anything differs (the command, the decision,
   the id, the nonce or the expiry), the signature fails. A request can't be swapped behind the screen.
5. Approvals are single-use, so a replay finds the request already decided. Pending requests are capped at 5.
   Commands run with a clean environment, cwd `/`, stdin `/dev/null`, a timeout that kills the whole process
   group, and capped output. Everything is logged to `/var/log/rootd/audit.jsonl`.
6. **Enrollment needs root.** The app creates the key and proposes its public key. rootd only marks it
   *pending*. Ayush runs `sudo rootd-admin enroll` and **types the code the phone displays**. The phone computes
   that code from its own key; it never comes from the server. The agent can propose keys but can never
   confirm one, revoke one, or see the list of pending codes.

**pc-api** (the app's HTTP backend) runs as `ayushlive`, so rootd treats it as untrusted. It only relays the
phone's signature, and the signature is what counts. pc-api also accepts tailnet requests only from Ayush's
Tailscale login (the `Tailscale-User-Login` header that `tailscale serve` adds).

### What this does *not* protect against (read this)

- **App updates are trusted code.** The APK is built on this laptop by the agent's user, and the release
  keystore (`~/.config/pc-control/`) is readable by that user. A malicious app update signed with the same key
  would inherit access to the approval key, and it could show one command while signing another. Install app
  updates only when you expect them.
- **The repo is user-writable.** `install-rootd.sh` copies `server/rootd.py` from here. Before running it with
  sudo (first install or upgrade), check what you are installing: `git -C ~/code/pc-control log -p server/`
  or compare the sha256 the installer prints.
- The agent can deny service: fill the pending queue, or flood enrollment proposals. It cannot get a command run.
- **Notification text goes through Google.** The `--reason` of every root request (and any `/pc/api/notify`
  text) is visible to FCM. Don't put secrets in reasons. The agent can also send you any notification text it
  likes, but a notification can't approve anything: approving means checking the command in the app and
  signing it with your fingerprint.

## Install

### 1. User side (no sudo; already done on the laptop)

```sh
~/code/pc-control/server/install-user.sh
```

This installs `rootctl` to `~/.local/bin`, installs and starts the `pc-api` user service (127.0.0.1:1990),
and adds the tailnet route `tailscale serve --bg --set-path /pc http://127.0.0.1:1990/pc`.

### 2. The app

On the phone, open https://github.com/ayushneekhar/pc-control/releases/latest and install `pc-control.apk`.
Later APK releases and over-the-air updates show up in Settings → App updates (and as an "App update available"
card when you open the app).

Open the app and allow notifications. Then check Settings → Push notifications: it should say **Registered with
the laptop**. Tap **Send test notification** to confirm.

### 3. Root daemon (Ayush, once, with sudo)

```sh
sudo ~/code/pc-control/server/install-rootd.sh
```

The script installs rootd into root-owned paths and installs and starts `rootd.service`. It then asks for
enrollment: on the phone, go to Settings → Root approvals → **Set up** and type the 16-character code it shows.
Running the script again upgrades rootd (it skips enrollment if a phone is already enrolled; `--enroll` adds
another).

Admin commands (root only):

```sh
sudo rootd-admin enroll           # enroll a phone (type the code shown in the app)
sudo rootd-admin devices          # list enrolled phones
sudo rootd-admin revoke <code>    # remove one
sudo rootd-admin status           # daemon + current requests
sudo ~/code/pc-control/server/uninstall-rootd.sh [--purge]
```

## How the agent uses root: `rootctl`

```sh
rootctl run --reason "install ffmpeg for camrec" -- apt-get install -y ffmpeg
```

This submits the request, prints `waiting for Ayush to approve on his phone (id …, expires in 180s)`, waits,
then prints the command's stdout and stderr and exits with the command's exit code. Other exit codes:
**126** denied, **125** expired (not approved within 180 s), **124** timed out, **3** rootd not installed or
not running.

```sh
rootctl submit --reason "..." -- cmd args   # just submit, print the id
rootctl wait <id>                           # wait for it like `run`
rootctl list | history | cancel <id> | status
```

Tips for the agent: give a clear `--reason` (Ayush reads it on his lock screen), pass a real argv (no
`sh -c` unless needed), and default `--timeout` is 600 s (max 3600).

## Development

```sh
yarn test           # rootd + pc-api + rootctl tests, incl. end-to-end with a software key as the phone
yarn typecheck && yarn lint
scripts/build-apk.sh [--bump]   # build + sign the release APK locally (no publishing)
scripts/release-apk.sh ["notes"]  # native release: build, sign, publish to GitHub Releases + matching OTA
scripts/ota-publish.sh ["notes"]  # JS-only change: signed over-the-air update for the current build
```

### Releases: native changes → new APK, JS-only → OTA

- **Native change** (anything in `modules/*/android`, `plugins/`, `app.json`, `app.config.js`,
  `package.json`/`yarn.lock`, a new native dependency): bump `version` and `versionCode` in `app.json` (or
  `scripts/build-apk.sh --bump`), commit, push, then `scripts/release-apk.sh "notes"`. It builds and signs on the
  laptop (the keystore never goes to GitHub), creates the GitHub release `v<version>` with `pc-control.apk` and
  `version.json`, and publishes the matching OTA snapshot. Push the counter commit it makes.
- **JS/TS-only change** (`App.tsx`, `src/`): commit, then `scripts/ota-publish.sh "what changed"`, then push the
  counter commit. It refuses if native files changed since the installed build's tag `v<version>`.
- OTA works like Apex's (react-native-nitro-ota 0.14.0): the app checks on launch and when it returns to the
  foreground, shows **Later / Update**, downloads, and restarts into the new bundle. If the app crashes right
  after an update, it rolls back. Differences from Apex: the snapshot is the rolling GitHub release
  `ota-android` (`bundle.zip` + `ota.version.json`, public, no auth), not a branch; and bundles are **signed**.
- Each bundle targets one exact build (`<version>+<versionCode>`). Installing a new APK drops all OTA data.
  react-native-nitro-ota has no signing of its own, so `scripts/ota/build-nitro-ota.mjs` writes `ota.manifest`
  (target build + SHA-256 of every file) and `ota.sig` (ECDSA P-256 with `~/.config/pc-control/ota-signing-key.pem`,
  laptop only). `MainApplication` (generated through `plugins/withNitroOta.js`) checks both with
  `OtaVerifier.kt` on every cold start, before React Native gets the bundle. A bundle that isn't signed, is for
  another build, or has any file changed is refused, and the APK's own JS runs. Lose that key and OTA stops
  working until a new APK ships with a new public key in `OtaVerifier.kt`.
- OTA versions are plain counters (`scripts/ota/ota-bundle-version.json`). The APK's embedded JS knows its own
  number, so only strictly newer bundles are offered.

- `server/tests/test_rootd.py`: checks payload canonicalization against the app's own TypeScript
  (`src/lib/rootPayload.ts`, run through Node) with quotes, backslashes, unicode, emoji, `\n` and `\t`. Also
  covers signature checks (good, wrong key, wrong argv, wrong decision, wrong nonce or expiry, replay,
  expired, revoked), peer-uid rejection, enrollment needing the admin uid, output cap, process-group kill and
  clean env.
- `server/tests/test_e2e.py`: runs rootd (test mode, unprivileged), pc-api over HTTP/SSE and `rootctl` against
  `fakephone.py`. Enrollment happens through `rootd-admin enroll` with the typed code, then approve, deny,
  attack and replay attempts.
- rootd test mode: `ROOTD_TEST_DIR`, `ROOTD_TEST_UID` and `ROOTD_TEST_ADMIN_UID` override paths and uids **only
  when not running as root**. rootd refuses to start as root with any of them set.
- Release keystore: `~/.config/pc-control/release.jks` with its passwords in `keystore.env` (mode 600), outside
  the repo. Keep it: every update must be signed with the same key. `plugins/withReleaseSigning.js` reads it
  from the environment at build time.

Layout: `App.tsx` + `src/` (screens, API, SSE, payload code, OTA), `modules/pc-native` (Kotlin: keystore +
biometric signing, FCM push service + notification display, OTA signature check), `plugins/` (release signing,
nitro-ota MainApplication wiring), `scripts/` (build, release, OTA), `server/` (rootd, pc-api incl. the FCM
sender and notification router, rootctl, units, install scripts, tests).
