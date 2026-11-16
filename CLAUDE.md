# Cuaderno

Juanma's own handwriting notes app for the Galaxy Tab S10 FE with S-Pen, meant to replace
Samsung Notes for university work. Part of the ecosystem:
https://claude.ai/artifact/PHQmVerxfbkkCipzxGaF2d

**Start by reading `docs/status.md`** (where it is, what is unverified, what is next), then
`docs/plan.md` (in Spanish; the premise and the order of work, not a closed spec: items marked
"origen: Claude" are unconfirmed) and `docs/decisions.md`.

- **Does:** notebooks of pages to write and draw on with the stylus, PDF annotation, export.
- **Does not:** edit text documents, manage tasks, replace the brain or read books (Atril).
  A separate app; linking it to other devices comes only after it works (phase 6).
- **Priority one is how writing feels** (Juanma, 2026-11-15): minimum stroke delay and a
  comfortable pen come before any feature. A change that makes writing worse does not ship.
  See "Prioridad número uno" in the plan.
- Start each phase in plan mode with rendered designs (dark theme) and wait for his OK.
- One session, one change, one PR. Every decision goes in `docs/decisions.md`, the state in
  `docs/status.md`. What stays only in the chat is lost.
- Modules: `core` (plain Kotlin: model, file format, pen recordings; most logic belongs here),
  `kit` (Nord `Ui` and `Updater`, shared with his other apps; never style a view by hand),
  `app` (Android; text in `Strings.kt`). Until phase 1 the app is only the bench
  (`BenchActivity`: three ink engines to compare).
- Never lose a note: atomic autosave, our own documented file format with a version number,
  always exportable to PDF. Read `docs/format.md` before touching `core`'s file code.
- Code, comments and commits in English. UI text in Rioplatense Spanish. Look: Nord.
- The starting point and the bar for stroke feel is Enlace's board:
  `~/projects/enlace/app/src/main/java/com/enlace/app/modules/board/BoardView.kt`.
- Session tools (JDK 17, Gradle 8.9, Android SDK 35) go in the scratchpad:
  `eval "$(tools/toolchain.sh <scratchpad>/tc)"`. Nothing global.
- Releases are cut on this PC (`tools/release.sh`, key in `~/.config/cuaderno/signing/`, never
  in git or Drive) and reach the tablet through the app's update button.
- The repo is public (github.com/juanmaacontreras/cuaderno) so the app can update itself:
  nothing secret or personal goes in it, and notes never do. Branch + PR per change; a merged
  change that touches the app is followed by a release.

## Check

```bash
gradle test assembleDebug     # format, kit, own ink (Robolectric); builds the APK
tools/screens.sh              # real screens to PNG, no device
claude-budget                 # startup context, goal < 3000 tokens
```
