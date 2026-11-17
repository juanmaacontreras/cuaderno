# Status

Updated: 2026-11-16. Read with `CLAUDE.md` at the start of a session; update at the end.

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

- Tests: core 15, kit 2, app 11 (page in points at any zoom, fixed width, fingers never
  write, pen button and eraser end, next page, saved and reloaded, unreadable file set
  aside, recordings replay, cost per pen sample). `tools/screens.sh` draws the screen.
- On an Android 34 emulator at the tablet's size, with adb's simulated stylus and finger:
  writing, undo, redo, eraser, finger scroll (exact to the pixel), reopening after a forced
  stop with the strokes still there, and the exported PDF (one A4 page, opened and looked at).
- The light paper change (colours only) was checked by tests and renders, not on the emulator.
- **Not confirmed on the tablet yet**: the real S-Pen (button, palm while writing), pinch zoom (adb
  cannot pinch), how it feels, battery.

## Next

1. Juanma tries v10 (spring-back zoom, coasting, eraser circle, full screen) and says how
   it compares; pinch and hover were only checked by tests, not with real fingers and pen.
2. Rest of phase 1: straight line / circle when the pen is held at the end of a stroke
   (he asked for it), highlighter, partial eraser, a rendering cache (tiles) before notebooks
   get long, light PDF export if he prints.
3. Still open: the name, what else he really uses in
   Samsung Notes (`plan.md` section 3 has what he said so far), and a real pen recording
   (menu, "Grabar trazos") for the tests.
