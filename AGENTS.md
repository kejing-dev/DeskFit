# Desk Fit（工位校准）— agent notes

PICO OS 6 spatial app, Kotlin + Spatial UI, PICO Spatial SDK 6.1.9 (BOM). Package `io.embodit.deskfit`, `versionName 0.1.0`.
ONE `DefaultStage` in **Full Space, Mixed** (passthrough; manifest `pico.spatial.stage.style = 1`). No WindowContainer.
Design package (binding): `../spatial-app-factory/apps/desk-fit/design-package.json`; sample page `../spatial-app-factory/apps/desk-fit/sample.html`.
Built from the same skeleton as Spatial Ruler (`../SpatialRuler`): head tracker, plane tracker, two-sided plane colliders +
backstop shell, system tap / drag conversion, pre-built panel pool (`PanelRig`), tape pieces, Noto fonts, debug broadcast.

What it does: sit at your real desk, press [Ready] → 1.4 s eye-height sample (HMD above the FLOOR plane) → tap the desk under the
screen → a steel pole rises; drag the yellow bar to the screen's top edge (thumbstick turns it) → a forearm bar appears in the
desk edge; level your arm, drag the bar to match → result card: chair ↑/↓, screen height ↑/↓, screen distance push/pull, all drawn
on the pole (green target band, target line, eye tick, tape) and at the desk edge (tape). Results are saved locally (last 5).

## Source map (`app/src/main/java/io/embodit/deskfit/`)

- `Main.kt` — `DefaultStage { DeskFitTheme { DeskScreen() } }`.
- `domain/model/` — `Vec3` / `Quat4`, `PlaneSurface` + `SurfaceKind`, `DeskModel.kt` (`Phase` S1–S16, `DeskPick`, `DeskEdge`,
  `Judgement`, `SavedResult`, settings enums).
- `domain/usecase/` (pure, unit-tested in `app/src/test`): `EyeSampleUseCase` (rules.eye), `DeskRulesUseCase` (floor zero point,
  desk pick + near edge, posture range, tap range), `JudgeUseCase` (chair / screen height / distance / side, rounding to the unit),
  `FormatUseCase`, `HitSurfaceUseCase` (two-sided ray / polygon), `AdaptiveGain` (1:1 → 1:4 after 400 ms rest).
- `data/ResultsRepository.kt` — SharedPreferences JSON: last 5 results + language / unit.
- `platform/` — `HeadTracker` (HMDTrackingProvider, lost 1.5 s, blockReason read live), `PlaneTracker` (PlaneTrackingManager →
  Stage-metre quads; `hideTables` QA hook), `ControllerInput` (ControllerTrackingProvider: ray, trigger, `thumbstickValue.x`), `DeskGraph`.
- `ui/deskfit/` — `DeskUiState` (+ `DeskEvent`), `DeskViewModel` (**the whole state machine**; timers run from `onFrame`, frozen by
  `hold`), `DeskScreen` (wiring), `components/` (StepPanel 400×248, ResultPanel 400×496, Plates 144×40 + ArcLabel 56×24, MoreMenu
  256×200, Overlays = blocked dialog 400×236 + snackbar 560×56, DeskStage = SpatialView + panel pool, Strings = runtime language,
  Probe = QA control names), `scene/` (DeskScene = entities + per-frame system + taps/drags + panel placement; PanelRig,
  PlaneColliders, SceneKit (materials / MeshFactory / Strip), Tape, FacePanel).
- `app/src/debug/.../DebugCommandReceiver.kt` — the QA broadcast (below).

## Architecture rules

- One direction: scene callbacks / panel controls / QA hooks → `DeskViewModel.onEvent` → `DeskUiState` → `DeskScene.render` +
  panels. **Per-frame work only in `DeskScene.onFrame`** (one registered `System`); nothing in Compose drives 3D. Sampling collects
  head poses in the scene and hands ONE `SampleFinished` to the ViewModel. A drag draws the bar in the scene and reports the final
  height (`BarMoved`).
- All 2D UI is SpatialUI inside `PicoTheme` (`DeskFitTheme`: all 16 roles assigned, 5 fixed to design values). No Material / Material3
  (`grep -rEf ../spatial-app-factory/bin/material-grep.txt app/src` must be empty; use `/usr/bin/grep`, `ugrep` chokes on `\b`).
- Colour literals only in `ui/theme/DeskColors.kt`. Text on glass = `labelPrimary`; on `plateInk` = `labelPrimaryLight`; on the
  semantic fills (`interaction` yellow, `passable` green) = `tapeInk`.
- Attachment panels: declared once with fixed sizes, shown / hidden through `PanelRig` (holder disabled + stowed; content composed
  only after the panel is in view; hidden slot never on a line of sight). Dialog and snackbar are pre-built panels: `AlertDialog`
  and `SnackbarHost` do nothing in a Stage (Spatial Ruler finding).
- Heights: the FLOOR plane under the head is the zero point (never the Stage origin). `DeskUiState` stores absolute Stage y for 3D
  things (`barY`, `forearmY`, `desk.topY`) and `floorY`; rules get heights above the floor. `deskH` / edge lock when S6 is entered.
- Surface tap targets (QA q1-1 / profile D24): `PlaneColliders` builds a thin box only for TABLE planes and keeps them
  **disabled** except in S6 with an automatic desk (`DeskScene.onFrame` sets `colliders.active`); the manual plane's touch box
  exists only in S11b / S6-manual. So a panel standing in front of (or inside) a real wall or cabinet always gets the pointer.
  The backstop shell (9 m) stays. `cmd colliders` prints what exists and whether it is live.
- Every start has a stop: trackers in `DeskScene.attach` / `detach`, panels released before the root is destroyed, shared
  resources (`toGlobal`) closed in `detach`.
- Factory default interaction D1: thumbstick X turns the bar on the pole about the pole's vertical axis (90°/s, dead zone 0.2),
  snaps back to the square pose within 8°; the step panel shows「摇杆左右转动」in S7. Hand / gaze taps and drags work as before.

## States (design `states[]`) → how to reach them

| id | phase | reach |
|---|---|---|
| S1 starting | STARTING | launch |
| S2 scanning | SCANNING | no FLOOR / desk yet (emulator 20–45 s); 20 s no floor → snackbar「低头看看地面」; 30 s no desk → S11 |
| S3 sit | SIT | floor + TABLE found; desk outline flashes 1 s |
| S4 sampling | SAMPLING | [坐好了]; ring 0.45 m ahead fills 1.4 s |
| S5 eye-locked | EYE_LOCKED | stable sample; 1 s → S6 |
| S6 mark-screen | MARK_SCREEN | arcs on the desk; tap a qualifying TABLE (30–150 cm from the sampled head) |
| S7 align-top | ALIGN_TOP | pole up, bar at desk + 45 cm, breathing; drag bar; thumbstick turns |
| S8 forearm | FOREARM | [对好了]; forearm bar at eye − 53 cm (or last eye-to-elbow) in the desk edge |
| S9 / S10 result | RESULT (`allGood`) | [对好了]; card + marks + tapes + plates |
| S11 desk-manual | DESK_MANUAL_A / _B | 30 s no desk, or [桌子不对]; a: drag glass plate; b: tap the edge |
| S12 unstable | UNSTABLE | tail < 0.5 s or < 8 samples; ring shakes, snackbar |
| S13 posture | POSTURE (STANDING / LOW) | eye > 150 or eye − desk > 68 → standing; eye < 95 or eye − desk < 20 → low |
| S14 more | `menuOpen` | More key; first tap elsewhere only closes it |
| S15 lost | `trackingLost` | 1.5 s without a pose |
| S16 blocked | BLOCKED | HMD support state bad for 2 s |
| S17 last | SIT + `lastResult` | a saved result exists |

## Debug broadcast (debug build; QA screenshot hooks, design `qaHooks`)

```
adb -s emulator-5554 shell "am broadcast -a io.embodit.deskfit.DEBUG -p io.embodit.deskfit --es cmd <cmd> [extras]"
# or: tools/dev.sh cmd <cmd> [extras]
```

| cmd | extras | effect |
|---|---|---|
| `state` | `--es id S1…S17` (`S13A` standing / `S13B` low, `S11B`) | jump to that state with plausible values (S4 / S5 / S12 also set `hold`) |
| `eye` | `--ef cm 118` | inject eye height (skips sampling); out of range → S13 |
| `tap` | `--ef x 0 --ef z 70` | pole at desk-local cm from the sampled head (x right, z into the desk) |
| `bar` / `forearm` / `plate` | `--ef cm 112` | set that bar's height above the floor |
| `lost` | `--ez on true\|false` | S15 on / off |
| `hold` | `--ez on true\|false` | freeze transients |
| `nodesk` | `--ez on true\|false` | hide TABLE planes (→ S11 by timeout) |
| `reset` | | clear saved results (S3 without「上次」) |
| `lang` / `unit` | `--es id zh\|en\|system` / `cm\|in` | settings |
| `ui` | `--es id primary\|secondary\|back\|more\|menu_restart\|menu_exit\|lang_zh\|unit_in\|blocked_retry\|blocked_exit` | press a control |
| `pointer` / `trigger` / `stick` | `--es a "ox,oy,oz,dx,dy,dz"` / — / `--ef x 0.8` | synthetic ray (desk reticle), its press, thumbstick |
| `dump` / `pose` / `planes` / `panels` / `colliders` / `fps` | | read-backs (`DUMP {json}`; `COLLIDERS n= active=`) |

Screenshot checklist (QA): S1 right after launch · S2 before planes · S3 (`state S3`, flash) · S17 (after one result) · S4 / S5 /
S12 with hold · S6 (`state S6`, arcs + reticle via `pointer`) · S7 (`state S7`) · S8 · S9 (`state S9`) · S10 (`state S10`) ·
S11 a/b (`state S11`, `state S11B`) · S13A / S13B · S14 (`state S14`) · S15 (`lost --ez on true`) · S16 (`state S16`).
Screenshots: `adb -s emulator-5554 exec-out screencap -p > x.png` (30–90 s on this emulator, 2160×2158) then
`sips --resampleWidth 1400 x.png --out x_small.png`; or `tools/dev.sh shot NAME`. Before installing / capturing: `./gradlew --stop`
(host memory is tight; one Gradle daemon, `org.gradle.workers.max=2`).

## Build / run (mac1)

```bash
tools/dev.sh build && tools/dev.sh stop && tools/dev.sh install && tools/dev.sh relaunch
tools/dev.sh log 'STATE|PLANES|EYE|TAP|DRAG|RESULT' 60
```
JAVA_HOME = `~/.pico/primer-cli/jdk/jdk-21.0.12.1+1/Contents/Home`, `local.properties` points at the Android SDK and `~/Library/PICO/sdk`.

## Log (tag `DeskFit`)

`STATE <id>` · `PLANES add|n=` · `DESK manual …` · `EYE sampled|unstable|posture` · `POLE up` · `TAP plane|manualPlane|ghost|elsewhere|ray|ignored` ·
`DRAG start|move|end` · `BAR <kind> y=` · `RESULT eye= desk= forearm= top= dist= side= chair= screen= distDelta= off= allGood=` ·
`TRACK lost|recovered|hmd|controller` · `BLOCKED reason=` · `SETTINGS` · `UI <control>` · `CMD recv` · `PANEL follow` · `SCENE attached|detached`.

## Rework log

- Round 1 (QA #1 q1-1): plane colliders were built for every plane and always live, so a panel in front of a wall lost the press.
  Now TABLE-only and live only in S6 (auto desk). Also fixed: manual edge line used the wrong mesh (k1), grid / pole / forearm
  roots were never disabled at start (k2), S2 spinner (k3), `hold` now freezes the ring and the snackbar timer (k4), blocked
  dialog's Chinese Retry (k5), S17 last-result line typography (k6). Panel presses log `UI <name>`.

## Known limits (v0.1.0)

- Desk hover reticle only with a tracked ray (controller mode / `pointer`): the system gaze pointer has no hover.
- Arcs are not clipped to the TABLE quad; S4 sample window is 1.4 s wall time (at idle fps the sample count may fall under 8 → S12).
- Chinese glyphs come from a bundled Noto Sans SC Regular (one weight); Latin from Noto Sans 400/500/600/700.
