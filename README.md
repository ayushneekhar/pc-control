# pc-control

An Android app for managing Ayush's always-on Ubuntu laptop (`ayushlive-hp-pavilion`) from his phone over
Tailscale, plus the server pieces behind it. Nothing leaves the tailnet: no Telegram, no FCM/Google push,
no third-party services.

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
- **Settings**: root-approval enrollment, the background listener (notifications), the battery exemption,
  the server URL and app updates.

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

## Install

### 1. User side (no sudo; already done on the laptop)

```sh
~/code/pc-control/server/install-user.sh
```

This installs `rootctl` to `~/.local/bin`, installs and starts the `pc-api` user service (127.0.0.1:1990),
and adds the tailnet route `tailscale serve --bg --set-path /pc http://127.0.0.1:1990/pc`.

### 2. The app

On the phone, open `https://ayushlive-hp-pavilion.tail65e4a4.ts.net/pc/app.apk` (with Tailscale on) and install
it. Later updates show up in Settings → Connection.

In the app: Settings → **Background listener** on, then allow notifications and the battery-optimization
exemption.

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
scripts/build-apk.sh [--bump]   # signed release APK -> ~/.local/share/pc-control/app.apk (served at /pc/app.apk)
```

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

Layout: `App.tsx` + `src/` (screens, API, SSE, payload code), `modules/pc-native` (Kotlin: keystore +
biometric signing, foreground SSE listener service, boot receiver, battery helpers), `server/` (rootd, pc-api,
rootctl, units, install scripts, tests).
