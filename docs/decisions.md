# Decisions

Newest first. "origen" says who decided: Juanma, or Claude (a suggestion until he confirms).
The plan (`plan.md`) is corrected here when reality contradicts it.

## 2026-11-16

- **Fluency settled on the tablet with v11** (origen: Juanma: "va re fluido", the readout
  shows 90 frames per second at 90 Hz, now and then slightly less). The redraw through the
  library is enough for now; tiles wait until notebooks get long.

- **"Not as fluid as Samsung Notes" when the page moves** (origen: Juanma, on v10). Not
  measured on the tablet, so the cause is a guess (origen: Claude) and three cheap things
  went in: the window asks for the screen's fastest mode (a surface fed irregularly can be
  left at 60 Hz); a stroke's path is built once, not on every frame; strokes off screen
  are skipped. Menu, "Medir fluidez", shows frames per second, the slowest frame and the
  screen's rate while the page moves: **his numbers decide the next step**. If frames stay
  well under the screen's rate, the redraw through the library is the limit and the page
  needs cached tiles or an ordinary view for the settled notebook.

- **What Juanma noticed using v9 next to Samsung Notes, and what was done** (origen: Juanma):
  - Zoom out follows the fingers below the screen's width (down to half) and springs back
    to the full sheet on release. Zoom in stays.
  - The page coasts after a flick instead of stopping dead (`OverScroller`, one step per
    frame, nothing running once still); a touch stops it.
  - The eraser shows a circle of the size it rubs out, while rubbing and while the pen
    hovers with the eraser on. It is an ordinary view above the page, not the front buffer.
  - The widths panel closes when the pen touches the page.
  - Full screen: the status bar and the gesture line are hidden; a swipe from the edge
    shows them for a moment.
  - **Later, asked for**: hold the pen still at the end of a stroke to turn it into a
    straight line or a clean circle (shape recognition). He said it can wait.

- **Paper is light by default, dark by choice** (origen: Juanma: light is what he is used
  to, and he wants to be able to choose). Menu, "Papel oscuro / Papel claro"; an app
  setting, not part of the file. Plain ink follows the paper (near-black on light,
  near-white on dark) when shown; the file keeps the colour written. The PDF comes out on
  the paper in use. The tool bar stays dark. This replaces "dark by default" below.

- **Writing screen: layout C, tools down the left edge** (origen: Juanma: it is how he has
  Samsung Notes). The title, page number and widths that do not fit go elsewhere: widths
  open by tapping the pen in use.
- **Pages are A4** (origen: Juanma: what he always used, and it exports cleanly to PDF).
  The notebook is A4 sheets one under the other; writing on the last adds the next.
- **The line has a fixed width; pressure is not drawn** (origen: Juanma: he turns pressure
  off in Samsung Notes). Pressure, tilt and time are still stored with each sample.
- **Paper: dark, squared, by default** (origen: Claude; he did not answer dark or light).
  The colour is the app's, not the file's: format 1 stores the pattern only. An exported
  PDF comes out dark too; a light export for printing is open.
- **The pen writes; fingers move and zoom** (origen: Claude, he did not object). A finger
  that lands while the pen is down is the hand and is ignored until it lifts; a finger
  drag that the pen interrupts is put back.
- **`CanvasFrontBufferedRenderer`, not `LowLatencyCanvasView`** (origen: Claude, found on
  the emulator). The second keeps one image and only adds to it: nothing redraws on undo,
  erase or scroll. The first has two layers (stroke in progress in front, the notebook
  behind). So the bench's "Baja latencia" in v5 could not show undo or the eraser; its
  writing delay, which is what was compared, came from the same front-buffer path.
  Redraws of the notebook are limited to one per frame and one in flight: the library
  stalls when every finger sample asks for one.
- **Phase 1, first slice shipped**: pen, eraser by stroke (tool, pen button, eraser end),
  undo/redo, move and zoom, autosave, PDF export. Not yet: highlighter, partial eraser,
  rendering cache for long notebooks (every redraw draws all visible strokes).

- **Engine chosen: our own stroke through the front buffer ("Baja latencia")** (origen:
  Juanma, on Claude's recommendation). Jetpack Ink and "Clásico" leave the app in phase 1.
- **Battery** (origen: Juanma): the app may spend what writing needs, but nothing
  exaggerated; to be watched as it grows. For whoever builds (origen: Claude): draw only
  when there is input, no work while the pen is still, no wake locks, nothing in the
  background, autosave on a pause in writing instead of a fast timer.
- **Fingers after the pen** (origen: Juanma, observed on v5): once the pen has come near,
  fingers and palm no longer write, even with the pen away. That is the bench's palm
  rejection, on purpose. In the writing screen (origen: Claude, to confirm) the pen writes
  and fingers only move and zoom the page; "write with a finger" would be a setting.
- **Writing screen: three layouts proposed as images** (`DesignTest`, `tools/screens.sh`):
  A bar on top, B floating pill, C bar on the left; plus A on light paper for the open
  paper decision. Waiting for his pick.

- **Bench result on the tablet (v5)** (origen: Juanma): he cannot tell the three engines
  apart by feel. By the delay each shows, "Baja latencia" is lowest, then Jetpack Ink, then
  "Clásico". The figures themselves were not written down.
- **Recommended engine: our own stroke through the front buffer ("Baja latencia")** (origen:
  Claude, waiting for his OK). With feel tied, the measured delay decides, and it also keeps
  the drawing path ours: testable without a device (Jetpack Ink's native code does not load
  in the tests), no dependence on a non-public probe, strokes already in our own model. The
  cost is writing the partial eraser and the lasso geometry ourselves, in `core`, with tests.
  Caveat: the two delays are not measured at exactly the same point; slow-motion video is
  the tie-breaker if it ever matters.

- **Public GitHub repo created** (origen: Juanma, "sí, creá el repo"), for the updater.
  Signing key generated in `~/.config/cuaderno/signing/`: losing it means reinstalling.
- **Phase 0 started without the plan being formally approved** (origen: Juanma, "quiero que
  encares el proyecto"). The four open decisions of `plan.md` section 10 are still his.
- **Built by Fable in this session**, not by another model as the plan assumed (origen:
  Juanma asked this session to do it). The plan's working rules stay.
- **Three engines on the bench, not two** (origen: Claude). "Clásico" is Enlace's board
  stroke unchanged: the one he said feels like Samsung Notes, so it is the bar. "Baja
  latencia" is the same stroke through the front buffer (`LowLatencyCanvasView`). "Jetpack
  Ink" is `androidx.ink` 1.0.0 with motion prediction. Our own front-buffer engine does not
  predict: drawing guessed points into a buffer that cannot be erased leaves marks until
  the stroke ends. If he prefers it, prediction is the next thing to try there.
- **The delay shown is each engine's own**: pen sample to drawing calls finished. It leaves
  out the display, so it compares engines and is not what the eye sees. For Jetpack Ink it
  comes from the library's latency probe (`LatencyData`, the render helper's
  `finishesDrawCalls`), which is not public API: bench only. The number
  that counts is slow-motion video of pen against ink (plan, "Se mide").
- **Versions**: Gradle 8.9, AGP 8.7.3, Kotlin 2.0.21 (what `androidx.ink` 1.0.0 was built
  with), compileSdk 35, minSdk 29. `androidx.ink` 1.0.0 stable, not the 1.1.0 alphas.
- **Three modules** (origen: Claude). `core` is plain Kotlin (model, file format, pen
  recordings): no Android, tests in seconds. `kit` is what his Android apps share (Nord `Ui`,
  `Updater`). `app` is the Android app.
- **`kit` is still a copy** of Enlace's `Ui.kt` and `Updater.kt` (the updater now takes the
  repo as a parameter). The plan says to stop copying; making it one shared library means a
  new repo and changing Enlace and Bitácora, which is his call (new remote). Until then the
  module boundary keeps the copy in one place, ready to move out.
- **File format 1 written and tested before any screen saves** (`format.md`). Strokes are
  binary inside the zip, not JSON: a page of 5,000 strokes is about half a million samples.
- **Pen recordings** are a text file (`cuaderno.core.Recording`). The only one so far is
  synthetic; a real one from the tablet replaces it as the reference.
