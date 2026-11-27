# pc-control

A native Android app (Kotlin + Jetpack Compose) for managing Ayush's always-on Ubuntu laptop
(`ayushlive-hp-pavilion`) over Tailscale, paired with the Python `pc-api` / `rootd` server stack.

Everything runs directly over the tailnet, with one exception: **notification text** travels through
Firebase Cloud Messaging (FCM) so the phone receives alerts without maintaining an always-on
background socket. FCM payloads carry only a title, short body, and identifier — a root request
sends *"Root approval needed"* and the reason, never the command. Approving always happens inside
the app with a hardware-backed biometric signature over the tailnet.

---

## App Overview (v2.0.0 — Native Kotlin + Jetpack Compose)

- **Home**: live server glance with a WebRTC Cat cams hero, attention banners (failed/inactive
  units, low disk space, stalled torrents, Radarr/Sonarr manual imports), active transfers &
  downloads, Jellyfin shortcut, and live `tailscale serve` routes. Routes can be customized or
  hidden live via `~/.config/pc-control/routes.json` (no restart or rebuild needed).
- **System**:
  - **Vitals**: live CPU per-core bars and load, 5-minute history graphs (CPU, memory, CPU temp,
    GPU, network RX/TX), memory & swap, thermal sensors, GPU telemetry, disk usage & I/O, battery,
    and a filterable/sortable process table (`/vitals/api`).
  - **Services**: systemd user units with status indicators, log inspection, and restart controls.
- **Chat**: mobile client for T3 Code and laptop coding agents — streaming turns with monospace
  activity steps, model & provider picker, mid-turn stop (`POST /pc/api/chat/stop`), queued-message
  steering, markdown rendering with JetBrains Mono inline code, image attachments & viewer, and
  inline replies directly from Android notifications.
- **Cams**:
  - **Live**: low-latency native WebRTC (`org.jitsi:webrtc`) directly to `go2rtc` over UDP
    (`:8555`) with hardware MediaCodec decode, independent audio & Talk backchannel (hold or latch),
    pinch-to-zoom, fullscreen landscape, snapshots to `Pictures/PC Control`, PTZ pan/tilt, and Cat
    alerts toggle. Includes a crash guard / safe mode marker and automatic backoff reconnect.
  - **Replay**: 24-hour continuous VOD HLS playlists from `camrec` rendered via native AndroidX
    Media3 ExoPlayer with synchronized multi-cam wall clock, 24h overview + zoomable scrub ribbon,
    cat-sighting markers, 1×/4×/16× playback, and full-res frame capture.
  - **Detections**: recent cat detection snapshots gallery.
  - **Music**: `cam-music` YouTube playback controller, including Android **Share → PC Control**
    (`ACTION_SEND`) support.
- **Seerr, Space & Downloads**: native media discovery & request management (Seerr), storage
  cleanup & Google Drive offload (`Space`), and live qBittorrent / Radarr / Sonarr transfer views.
- **Root**: biometric root-approval inbox showing the exact resolved command, reason, timeout, and
  script snapshot hash, with **Approve (biometric)** and **Deny** actions plus execution history.
- **Settings**: root-approval device enrollment, **Notifications** router (per-category Telegram /
  App switches), push registration status & test buttons, **Agent Usage** & **AgentView** rules,
  connection configuration, and **App updates** (GitHub Releases primary with automatic Tailnet
  mirror fallback, SHA-256 verification, and in-app APK installer).

---

## Notification Router & FCM

`pc-api` routes every unsolicited alert across **Telegram** and **App (FCM)** with per-category and
master switches stored in `~/.local/share/pc-control/notify-settings.json`.

| Category | Sent by | Telegram | App |
|---|---|---|---|
| Cat alerts (photo in Telegram, big picture in app) | `catdetect` | on | off |
| Download started | Radarr/Sonarr webhook (On Grab) | on | off |
| Available to watch | Seerr webhook (media available) | on | off |
| Download problems | Seerr / Radarr / Sonarr / import-watch | on | on |
| Qwen downloads finished | qBittorrent hook `notify_done.py` | on | off |
| Backups & system alerts | `config-backup`, `kernel-fix-watch`, `rec-digest` | on | on |
| Service down | `pc-api` unit watcher | on | on |
| Root approvals | `rootd` via `pc-api` | off | on |
| Command results | `rootd` via `pc-api` | off | on |
| Other messages | `/pc/api/notify` | off | on |
| Live download progress | Radarr/Sonarr Grab webhook, Qwen downloads | — (app only) | on |

- **FCM**: data-only, high-priority messages sent via FCM HTTP v1 using
  `~/.config/pc-control/fcm-service-account.json` (never committed). Images (cat alerts, posters)
  are fetched directly over the tailnet (`/pc/api/notify/image/<id>`).
- **Live download progress**: while qBittorrent downloads are active, a short-lived `dataSync`
  foreground service (`LiveDownloadsService.kt`) polls `GET /pc/api/downloads` and posts a promoted
  Android 16 `ProgressStyle` Live Update (status bar chip / Samsung One UI Now Bar), falling back to
  a standard ongoing progress notification on older Android versions.
- **Local scripts**: call `~/remote-pc/lib/pcnotify.py` or `POST /pc/api/notify` with the local
  bearer token in `~/.config/pc-control/local-token`. Radarr/Sonarr/Seerr webhooks authenticate via
  `~/.config/pc-control/hook-secret`.

---

## Security Model (`rootd`)

The AI agent on the laptop runs as `ayushlive` without sudo.

> A root command runs only after Ayush approves **that exact command** with his fingerprint on his
> phone. There is no password anywhere in the flow, and nothing on the laptop can approve or enroll
> by itself.

1. **`rootd`** (`/usr/local/lib/rootd/rootd.py`, root-owned) listens on `/run/rootd/rootd.sock` and
   accepts connections only from `ayushlive` and `root`, verified via `SO_PEERCRED`.
2. Submitting `{argv, reason, timeout}` via `rootctl` assigns a random id, random nonce, and 180 s
   expiry, resolves `argv[0]` against a fixed root `PATH`, and rejects invisible/confusable
   characters (control chars except tab/newline, bidi overrides, zero-width chars, lone surrogates).
3. The phone holds an **Android Keystore EC P-256 key** (`BiometricSigner.kt`, StrongBox when
   available) requiring strong biometric authentication via `BiometricPrompt` for every signature;
   enrolling a new fingerprint on the phone invalidates the key.
4. `BiometricSigner.kt` / `RootPayload.kt` constructs and signs the `rootd-v2` payload directly from
   the exact command and metadata displayed in `BiometricPrompt`:
   `rootd-v2\napprove\n<id>\n<nonce>\n<expires>\n<timeout>\n<sha256(reason)>\n<snap_sha256>\n<sha256(canonical_argv)>`.
   `rootd` recomputes the payload from its stored request; any mismatch fails verification.
5. Approvals are single-use. Pending requests are capped at 5, `argv` at 8 KB, and submissions are
   rate-limited. User-writable executables/scripts are snapshotted to `/var/lib/rootd/snap/<id>`
   (`0700`), hashed into `snap_sha256`, executed from the snapshot, and deleted afterward. Commands
   run with a clean environment, cwd `/`, stdin `/dev/null`, process-group timeout kill, and
   audit logging (`/var/log/rootd/audit.jsonl`).
6. **Enrollment requires root**: the app generates its keypair and submits the public key as
   *pending*; Ayush runs `sudo rootd-admin enroll` and enters the 16-character fingerprint computed
   on the phone.

### Threat-model notes

- **App updates are trusted code**: install APK updates only from trusted releases signed with your
  release keystore.
- **The repo is user-writable**: review `git log -p server/` before running `sudo server/install-rootd.sh`.
- **Snapshotted scripts can still read sibling files**: only the script file itself is frozen and
  hashed.
- **FCM carries notification text**: avoid placing secrets in `--reason` or `/pc/api/notify` bodies.

---

## Install

### 1. Laptop user services (no sudo)

```sh
~/code/pc-control/server/install-user.sh
```

Installs `rootctl` to `~/.local/bin`, enables the `pc-api` user unit (`127.0.0.1:1990`), and sets up
`tailscale serve --bg --set-path /pc http://127.0.0.1:1990/pc`.

### 2. Android app

Download and install `pc-control.apk` from
[GitHub Releases](https://github.com/ayushneekhar/pc-control/releases/latest).
Subsequent releases are detected automatically in **Settings → App updates** via `version.json`
(with SHA-256 verification and automatic Tailnet mirror fallback at `/pc/api/ota/apk/*`).

Open the app, grant notifications permission, and verify **Settings → Push notifications** reports
**Registered with the laptop**.

### 3. Root daemon (once, with sudo)

```sh
sudo ~/code/pc-control/server/install-rootd.sh
```

Installs `rootd` and prompts for phone enrollment (in the app: **Settings → Root approvals → Set up**).

Admin commands:

```sh
sudo rootd-admin enroll           # enroll a phone using the code displayed in the app
sudo rootd-admin devices          # list enrolled phones
sudo rootd-admin revoke <code>    # revoke a device
sudo rootd-admin status           # daemon + pending requests
sudo ~/code/pc-control/server/uninstall-rootd.sh [--purge]
```

---

## Using `rootctl`

```sh
rootctl run --reason "install ffmpeg for camrec" -- apt-get install -y ffmpeg
```

Exit codes: command exit code on approval, **126** denied, **125** expired (180 s), **124** timed
out, **3** `rootd` unavailable.

```sh
rootctl submit --reason "..." -- cmd args   # submit and print request id
rootctl wait <id>                           # wait for decision + output
rootctl list | history | cancel <id> | status
```

---

## Development & Releases

### Server tests (Python)

```sh
python3 -I -m unittest discover -s server/tests -v
```

Covers `rootd` canonicalization & protocol hardening, end-to-end `rootd` + `pc-api` + `rootctl`
flows (`fakephone.py`), Chat/T3 adapters, Space manager, Google Drive offload, AgentView, Bazarr
triggers, cat detection snapshots, and WireGuard watchdogs.

### Android builds & GitHub Releases (GitHub Actions)

All Android unit tests (`./gradlew testDebugUnitTest`), signed release APK builds
(`./gradlew assembleRelease`), and GitHub Releases are executed in GitHub Actions via
[`.github/workflows/release.yml`](.github/workflows/release.yml):

- **CI on push to `main`**: runs automatically whenever `android/**` or the workflow changes,
  executing unit tests, building the signed release APK, and uploading `pc-control-release-apk` as a
  workflow artifact.
- **Publishing a release**:
  1. Bump `versionCode` and `versionName` in `android/app/build.gradle.kts` and push to `main`.
  2. Trigger the **Android Build & Release** workflow (`workflow_dispatch`) with
     `publish_release=true` and optional release `notes`:
     ```sh
     gh workflow run release.yml --ref main -f publish_release=true -f notes="Release summary"
     ```
  3. The workflow verifies the tag `v<versionName>` doesn't exist, restores the release keystore and
     `google-services.json` from repository secrets (`PCC_KEYSTORE_BASE64`, `PCC_KEY_ALIAS`,
     `PCC_STORE_PASSWORD`, `PCC_KEY_PASSWORD`, `GOOGLE_SERVICES_JSON`), runs tests + release build,
     computes SHA-256 + size, and publishes `v<versionName>` with `pc-control.apk` and
     `version.json` attached.

---

## Repository Layout

- [`android/`](android/) — Native Kotlin + Jetpack Compose Android application (`minSdk 30`,
  `targetSdk 35`, `arm64-v8a`), Gradle Kotlin DSL, and JVM unit tests (`android/app/src/test/`).
- [`server/`](server/) — Python `pc-api`, `rootd`, `rootctl`, systemd units, install scripts, and
  unittest suite (`server/tests/`).
- [`docs/`](docs/) — Architecture specification and design notes.
- [`.github/workflows/release.yml`](.github/workflows/release.yml) — Unified Android CI and GitHub
  Release pipeline.
