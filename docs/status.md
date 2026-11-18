# Status

Updated: 2026-11-17. Read with `CLAUDE.md` at the start of a session; update at the end.

## Where it is

**Phase 1 (write a page) built, waiting for his class-long trial. Phase 2 (notebooks) in
built: library, pages panel, pulling for a new page, trash; waiting for his week of classes
with it.**

The app opens on the last notebook; the bar's top button goes to the library (subjects down
the left, notebooks as covers, new notebook with its subject and paper). Sheets are A4, light
(dark from the menu). Tools down the left edge: library, pages, pen
(tap it again for the three widths), highlighter, eraser, five colours, undo, redo, menu (export to PDF,
paper light or dark, record pen strokes). The pen writes a fixed-width line; fingers move and zoom. Everything
is saved 1.5 s after a change and when leaving the screen, in `files/notebooks/<subject>/
<id>.cuaderno` (format 1, atomic; layout in `docs/format.md`), with the last file that opened
well kept as `.bak`.

Code: `core` (`Document`: pages, undo, eraser; `Library`: subjects and notebooks on disk), `app/page` (`PageView`: input, zoom, the two
display paths; `Tiles`: the notebook kept drawn in pictures; `Render`: pages and strokes in
points, shared by screen and PDF), `Store`,
`PdfExport`, `WriteActivity`, `LibraryActivity`, `PagesPanel`.

## Verified, and not

- Trash (v24): tested (core `TrashTest`: notebook and page there and back, subject gone,
  notebook gone, 30 days, a broken note kept; app: from the form and the panel, restore, asking
  before deleting for good, opening with the last notebook gone), three checks seen failing with
  the code broken on purpose. Emulator: a page and a notebook to the trash, both back where
  they were. Render 10. Not on the tablet.
- Pull past the last page for a new one (v23) and the paper redrawn at once (v22): tested
  (`PageViewTest.pullingPastTheLastPageAddsOne`, `DocumentTest`, `TilesTest`), each seen
  failing with the code broken on purpose, and on the emulator. Not on the tablet; a pinch at the
  end not pulling is read in the code, not tested.
- Pages panel (v21): tested (core: insert, duplicate, move, paper, each one undo; app
  `PagesPanelTest`: open, list without the blank end, tap goes to the page, the hold menu, drag
  to move, all saved), three checks seen failing with the code broken on purpose. Emulator:
  open, go to a page, hold, change the paper, new page, drag both ways, write with it open
  (thumbnail follows). Renders 8 and 9. Not tried on the tablet; dragging tried with adb's
  finger, not a real one.
- Notebooks and the library (v20): tested (core `LibraryTest`, app `LibraryActivityTest`: his
  notebook keeps opening, the bar goes to the library, a cover opens its notebook, a new one
  lands in the subject shown with the paper chosen, rename and move keep the ink, subjects,
  back), two checks seen failing with the code broken on purpose. Emulator: the whole way
  (his notebook opened, library, subject typed, ruled notebook created, written, back), with
  three fixes from it (keyboard, framed covers, light paper samples). Renders 6 and 7. Not tried
  on the tablet.
- Tiles (v18): the page moves at 59-61 frames a second with 1500 strokes a sheet and 54-60
  with 5000 on the emulator, against 23 and 10-12 before (`DenseNotebook`, "Medir fluidez").
  Tested: pieced together it matches drawing at once, and keeps matching after a pen stroke
  (painted on, nothing redrawn), highlighter, eraser, undo, redo, dark paper and a new zoom;
  each of those checks was seen failing with the code broken on purpose. On the emulator: a
  stroke across a seam, undo, dark paper. Not tried: pinch zoom on the tablet (adb cannot
  pinch), and the real fluency there (he reads "Medir fluidez" on a full page).
- Highlighter (v16): tested (seen through while writing and once lifted, the same within
  rounding; under the ink after) and tried on the emulator (front buffer see-through and
  even while the pen is down, ink sharp on top after). Renders: `tools/screens.sh`, 4 and 5.
  Not tried with the real S-Pen.
- Holding the pen still at the end of a stroke straightens a line or rounds a circle (v14):
  tested (core 16, app 21, kit 2 in all) and tried on the emulator with adb's stylus (line, line aimed
  after the hold, circle shown clean while the pen is down, undo). Not tried with the real
  S-Pen: whether 0.6 s and 4 dp feel right is his call.
- Earlier tests (page in points at any zoom, fixed width, fingers never
  write, pen button and eraser end, next page, saved and reloaded, unreadable file set
  aside, recordings replay, cost per pen sample). `tools/screens.sh` draws the screen.
- On an Android 34 emulator at the tablet's size, with adb's simulated stylus and finger:
  writing, undo, redo, eraser, finger scroll (exact to the pixel), reopening after a forced
  stop with the strokes still there, and the exported PDF (one A4 page, opened and looked at).
- The light paper change (colours only) was checked by tests and renders, not on the emulator.
- **Not confirmed on the tablet yet**: the real S-Pen (button, palm while writing), pinch zoom (adb
  cannot pinch), how it feels, battery.

## Next

0. **Phase 2, in slices** (designs `fase2-*.png` approved). Done: notebooks and the library
   (v20), the pages panel (v21), the paper redrawn at once (v22), pages added by pulling (v23),
   the trash (v24). Its test (plan): a week of classes alongside Samsung Notes. Then phase 3.
1. Fluency is fine on the tablet with v11 (90 frames per second, his reading). Battery is
   not measured: he compares a class day against Samsung Notes in Android's battery screen.
   He has features in mind beyond Samsung Notes (some from GoodNotes, some new): write
   them in `plan.md` when he lists them, do not build them yet.
2. Holding the pen (v14) "feels odd, it can be improved" (Juanma): ask what exactly (too
   slow, too eager, the line following the pen, how the shape appears) before touching it.
3. Rest of phase 1: light PDF export, only if he prints. Partial eraser dropped (he never
   uses it). Then phase 1 is his call: a whole class written in it without losing anything.
4. Still open: the name, what else he really uses in
   Samsung Notes (`plan.md` section 3 has what he said so far), and a real pen recording
   (menu, "Grabar trazos") for the tests.
