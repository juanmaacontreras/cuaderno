# Decisions

Newest first. "origen" says who decided: Juanma, or Claude (a suggestion until he confirms).
The plan (`plan.md`) is corrected here when reality contradicts it.

## 2026-11-16

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
