# Status

Updated: 2026-11-17. Read with `CLAUDE.md` at the start of a session; update at the end.

## Where it is

**Phase 0 closed. Phase 1 (write a page), first slice released.**

The app opens on one notebook of A4 squared sheets, light (dark from the menu). Tools down the left edge: pen
(tap it again for the three widths), eraser, five colours, undo, redo, menu (export to PDF,
paper light or dark, record pen strokes). The pen writes a fixed-width line; fingers move and zoom. Everything
is saved 1.5 s after a change and when leaving the screen, in `files/notebooks/
cuaderno.cuaderno` (format 1, atomic), with the last file that opened well kept as `.bak`.

Code: `core` (`Document`: pages, undo, eraser), `app/page` (`PageView`: input, zoom, the two
display paths; `Render`: pages and strokes in points, shared by screen and PDF), `Store`,
`PdfExport`, `WriteActivity`.

## Verified, and not

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

1. Fluency is fine on the tablet with v11 (90 frames per second, his reading). Battery is
   not measured: he compares a class day against Samsung Notes in Android's battery screen.
   He has features in mind beyond Samsung Notes (some from GoodNotes, some new): write
   them in `plan.md` when he lists them, do not build them yet.
2. He tries holding the pen on the tablet (v14): too slow, too eager, shapes he misses.
3. Rest of phase 1: highlighter, partial eraser, a rendering cache (tiles) before notebooks
   get long, light PDF export if he prints.
4. Still open: the name, what else he really uses in
   Samsung Notes (`plan.md` section 3 has what he said so far), and a real pen recording
   (menu, "Grabar trazos") for the tests.
