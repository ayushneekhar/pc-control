# pc-control: spec (agreed with Ayush on Telegram, 8 Nov 2026)

An Android app to manage Ayush's always-on Ubuntu laptop (`ayushlive-hp-pavilion`, tailnet
`https://ayushlive-hp-pavilion.tail65e4a4.ts.net`), plus the server pieces behind it.
Ayush uses it from his phone over Tailscale. Everything stays on the tailnet, except notification TEXT, which goes
through Firebase Cloud Messaging (changed 9 Nov 2026, see section 3). No Telegram, no other third parties.

## 1. App features
- **Routes**: list every `tailscale serve` route (cams, /vitals, /music, /rec, Seerr :5055, Bazarr :6767,
  Radarr :7878, Sonarr :8989, …) as tappable cards with friendly names + icons that open in the browser.
  Read the live list from the server (`tailscale serve status --json`), so new routes show up by themselves.
- **Vitals**: CPU/temp/RAM/GPU/net/battery summary from the existing `GET /vitals/api` (see
  ~/remote-pc/vitals/vitals.py; `?procs=1` only when a process list is shown). Poll only while visible.
- **Services**: status of the user's own systemd user units (~/.config/systemd/user/*.service) with restart.
- **Root approvals** (the main feature, see section 2): inbox of pending requests showing the exact argv and
  reason, Approve (fingerprint) / Deny, and a history with exit code + output.
- **Notifications** via FCM push (section 3).
- Settings: server URL (default above), device enrollment for root approvals, push status (FCM token
  registered yes/no) + "Send test notification".

Phone-first, clean, dark + light. Ayush is picky about polish.

## 2. Root approvals: security design (do not weaken)
Goal: the AI agent on the laptop (runs as user `ayushlive`, no sudo) can get a root command run ONLY when Ayush
approves that exact command with his fingerprint. No password exists in the flow at all.

- `rootd` (server/rootd.py, a first draft is already there: review and fix it): root systemd service,
  Unix socket `/run/rootd/rootd.sock`, peer uid checked via SO_PEERCRED (only `ayushlive` and root).
- Agent submits `{argv, reason, timeout}`; rootd stores it with random id + nonce + expiry (180 s).
- Phone signs a payload it builds ITSELF from the argv it displays:
  `rootd-v1\n{approve|deny}\n{id}\n{nonce}\n{expires}\n{sha256hex(utf8(JSON.stringify(argv)))}`.
  rootd verifies against the argv IT stored, so a request can't be swapped behind the screen.
  (argv with control chars other than \t \n is rejected, so Python json.dumps(ensure_ascii=False,
  separators=(",",":")) matches JS JSON.stringify. Test this with tricky strings: quotes, backslashes,
  unicode, emoji, \n, \t.)
- Phone key: Android Keystore EC P-256 (secp256r1), SHA256withECDSA, PURPOSE_SIGN,
  setUserAuthenticationRequired(true) + setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG) (API 30+),
  setInvalidatedByBiometricEnrollment(true), StrongBox when available. Every signature goes through
  BiometricPrompt with a CryptoObject. Key never leaves the phone. Handle KeyPermanentlyInvalidatedException
  (tell Ayush to re-enroll).
- Enrollment: app creates the key and sends the public key (PEM SPKI) -> rootd keeps it PENDING and shows
  a short fingerprint code. Only root can confirm it: Ayush runs `sudo rootd-admin enroll` and checks the
  code matches the one shown in the app. The agent must never be able to enroll a key.
- Approvals are single-use, expire, max 5 pending, full audit log in /var/log/rootd/audit.jsonl,
  commands run with a clean env, cwd /, stdin /dev/null, timeout, output capped.
- `rootd-admin devices|revoke` for root.
- Agent-side CLI `rootctl` (user-level): `rootctl run [--reason "..."] [--timeout N] -- cmd args...`
  submits, waits for the decision and result, prints stdout/stderr, exits with the command's exit code.
  Also `rootctl submit`, `rootctl wait <id>`, `rootctl list`. Install for the user at ~/.local/bin/rootctl.
- `install-rootd.sh`: one script Ayush runs with sudo. Copies rootd to /usr/local/lib/rootd (root-owned,
  0755/0644, NOT a symlink into the user-writable repo), installs `rootd-admin` in /usr/local/sbin,
  a hardened-where-possible systemd unit, enables it, then runs the enrollment step. Idempotent, also used
  to upgrade. Also `uninstall-rootd.sh`.
- Do NOT touch sudoers, PAM, sudo-rs, ssh, firewall or Tailscale ACLs.
- You (the coding agent) can't use sudo and must not try. Test rootd as an unprivileged process: make paths
  and the allowed uid overridable for tests only (env vars or flags), and write real tests: payload
  canonicalization vs the app's JS, signature verify (good, wrong key, wrong argv, wrong decision, replay,
  expired), peer-uid rejection, enrollment needing uid 0.

## 3. Server API + notifications
- `pc-api` (server/pcapi.py): user-level systemd service on 127.0.0.1:1990, exposed with
  `tailscale serve --bg --set-path /pc http://127.0.0.1:1990/pc` (same style as /vitals; see
  ~/.config/systemd/user/vitals.service). Endpoints under /pc/api: routes, services (+restart),
  root/pending, root/history, root/approve, root/deny (relay the phone's signature to rootd; the signature
  is what counts), enroll, enroll/status, events (SSE: new root request, request updates, heartbeat ~25 s).
  It must work (with root features shown as "not installed") before rootd is installed.
  APKs are published to GitHub Releases (changed 9 Nov 2026; /pc/app.apk is legacy and goes away).
- Notifications (changed 9 Nov 2026: FCM replaces the old foreground-service SSE listener, which was removed
  together with the boot receiver, battery-exemption prompt and foreground-service permissions):
  - App: native FirebaseMessagingService in pc-native. It registers its FCM token with pc-api
    (`POST /pc/api/push/register`, tailnet-authenticated like every call) on app start and on token refresh.
    pc-api stores the tokens in its state dir.
  - pc-api: wherever it publishes rootd events to SSE, it also sends FCM HTTP v1 (service-account JWT →
    OAuth2 access token, cached). Messages are data-only, high priority, and the app displays them itself
    (its own channels + tap actions). Root request: only "Root approval needed" + the reason, plus the request
    id; tapping opens the approval screen. Approval stays biometric-signed over the tailnet. No commands,
    output, nonces or secrets go in FCM payloads. Tokens FCM reports as UNREGISTERED/404 are dropped.
  - `POST /pc/api/push/test` sends a test notification; `POST /pc/api/notify` is the notification router
    (9 Nov 2026): per-category Telegram (Qwen bot) / app switches, see README "Notification router".
  - `/pc/api/events` (SSE) stays only for the open app's live Root tab.

## 4. Tech + build
- Expo (match ~/code/Apex: Expo SDK 57, RN 0.86, TypeScript, yarn, Node 22), Android only. Native bits as
  a local Expo module in ./modules (Kotlin): keystore key + biometric signing, the FCM
  messaging service. androidx.biometric.
- Build locally: JDK at ~/tools/jdk-21, Android SDK at ~/tools/android-sdk (~/.gradle is warm).
  `npx expo prebuild -p android` then a signed release APK. Release keystore OUTSIDE the repo at
  ~/.config/pc-control/release.jks (generate it, password in ~/.config/pc-control/keystore.env, chmod 600).
  Keep the same key for every build so updates install over the old app.
- Typecheck + lint must pass. Python: stdlib + `cryptography` (system python3 has it), run with `python3 -I`.
- Firebase: project `pc-control-neekhar`. `google-services.json` and the FCM service-account key live in
  ~/.config/pc-control/ (mode 600) and are never committed.
- Git: this folder is the repo. Ayush approved a new PRIVATE GitHub repo `ayushneekhar/pc-control`; push there.
  Never commit keystores, secrets or the APK.
- Long builds: run them in the background, don't block on them.
- You can't test on Ayush's phone. Test the server parts end to end on the laptop with a software EC key
  standing in for the phone (a test script that signs like the app does).

## 5. Done means
- pc-api running as a user service with the /pc tailnet route, APK built locally and published to GitHub Releases; JS-only changes as signed nitro-ota updates.
- rootd + install script ready for Ayush to run with sudo (not run by you).
- README with: what it is, the security model in plain words, install steps, how the agent uses rootctl.
- A short final report: what works, what's untested (anything needing the real phone), the exact command
  Ayush must run, and anything you'd flag.
