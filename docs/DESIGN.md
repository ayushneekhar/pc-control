# PC Control design spec (17 Nov 2026, direction "A · Native")

Ayush's verdict on the old UI: "way too AI and basic, like no thought was put into the UI".
Hard rules: no blue / purple / indigo, no gradients, no glow, no rainbow icon tiles, no ALL-CAPS section labels.
Reference mockup: `docs/design/variants.html` (frame "A") and `docs/design/index.html` (layouts for Home / Vitals / Services; IGNORE its colours and fonts, use the tokens below).

## Tokens (src/theme.ts)
Dark (default):
- bg `#000000` (true black, matches One UI), surface `#141414`, surfaceRaised `#1F1F1F`, hairline `#262626`
- text `#F5F5F5`, textDim `#A8A8A8`, textFaint `#707070`
- accent mint `#3DDC97`, accentSoft `rgba(61,220,151,0.16)`. Accent is used SPARINGLY: active tab icon + its pill, the live/online dot, primary button, progress fills that mean "good", selected segment. Never as large fills.
- ok = accent mint; warn `#F2B544`; bad `#F2685A` (each with a ~0.15 soft variant)
Light: bg `#F4F4F4`, surface `#FFFFFF`, surfaceRaised `#EBEBEB`, hairline `#E2E2E2`, text `#111111`, textDim `#555555`, textFaint `#8A8A8A`, accent `#159A63` (soft `rgba(21,154,99,0.12)`), warn `#B87800`, bad `#D2392B`.
Radius: 16 for cards/lists, 12 small, 999 pills. Spacing 4-pt grid; screen side padding 18.

## Type
- System font only (Roboto on Android, no custom fonts). Weights: 400 body, 500 labels/values, 600 titles. Never 700/800.
- Screen title 30 / 600 / letterSpacing -0.5, left-aligned, under a 12.5 / textDim status line with a 7px dot (e.g. "● hp-pavilion · up 10 days", "● Live · every 2 s", "● Checked 6 s ago").
- Section label: 12.5 / 500 / textDim, sentence case, sits OUTSIDE the card.
- Every number uses `fontVariant: ['tabular-nums']`; units are smaller (≈60%) and textDim, e.g. "13" + "%".
- Drop `monospace` everywhere except log/URL/code text.

## Components (src/components/ui.tsx)
- Card: surface fill, NO border, radius 16.
- List / ListRow (new): rows inside one surface card separated by hairlines; row = 20px filled icon (textDim) · title 15/500 · subtitle 12.5 textFaint · trailing value or chevron "›" textFaint. Min height 56. Ripple subtle.
- Stat strip (new): 3 equal columns in one card, divided by hairlines: label 11.5 textFaint, value 20/500 tabular.
- Pill: small, soft background, no dot unless it's a status.
- Button: primary = accent bg + black text; subtle = surfaceRaised + text; danger = badSoft + bad text. Height 44, radius 12.
- Segmented control (new): surface track, raised selected segment.
- Status dot: 7px circle (ok/warn/bad/faint).
- Icons: MaterialCommunityIcons FILLED variants (except unchecked checkboxes), monochrome (textDim; text when active). No coloured icon squares.

## Navigation
Bottom tabs: Home, Vitals, Services, Root, Settings. Bar bg = bg, hairline top border, height ~68. Active tab: icon in accentSoft pill (64x30), icon colour accent, label text colour; inactive: textFaint. Labels 11 / 500.
- "Routes" tab becomes **Home** (keep route name `Routes` in linking for deep links, or add an alias so `pccontrol://routes` still works).
- "Health" tab becomes **Services** (keep `pccontrol://health` working).

## Screens
- **Home**: status line + title; stat strip (CPU %, Memory used/total, CPU temp) from the vitals API, tapping opens Vitals; "Cameras" section with a cam wall card (1 big + 2 small JPEG snapshots, refreshed every few seconds while focused, labels bottom-left on a dark chip) + footer "3 live · Last cat seen N min ago" (from catdetect sightings), tap → Cams screen, right label "Replay · Music" opens those tabs; "Open" section = remaining routes as ListRows (Jellyfin, Agent view, …) and a Downloads row when downloads are active.
- **Vitals**: big CPU number (44) + sparkline in text colour; 2x2 grid Memory / Swap / GPU / VRAM with thin 4px bars (fill = text colour, warn colour when >85%); Temperatures as a list with tabular values; keep any existing detail (processes etc.) in the same visual language.
- **Services** (was Health): summary card (status ring + "All 24 running" / "2 problems") then segmented filter All / Media / Cams / Agents (group by unit name), list rows with status dot, friendly name, short subtitle, uptime on the right. Problems sorted first.
- **Root, Settings, Agent view, Agent rule, Service detail, Cams (Live/Replay/Music)**: same tokens + components; Settings = grouped lists with switches; Root requests = cards with clear Approve (primary) / Deny (subtle) buttons.
