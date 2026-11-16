# Status

Updated: 2026-11-16. Read with `CLAUDE.md` at the start of a session; update at the end.

## Where it is

**Phase 0 (bases), first half done and waiting for Juanma on the tablet.**

- Repo with three modules (`core`, `kit`, `app`), 19 tests, CI workflow, release script.
- File format 1: written (`docs/format.md`), implemented, tested, fixture kept.
- The bench app: one page, three ink engines to switch between, each engine's own delay
  on screen, pen recorder ("Grabar trazos" then share to the PC).

## Verified, and not

- Tests: 19 (format, kit, own ink through Robolectric), plus the screens to PNG.
- **The three engines ran on an Android 34 emulator at the tablet's size** (2304x1440,
  software GPU, finger input through adb): each one writes; Jetpack Ink also erased and
  undid; each shows its delay. The emulator's delays mean nothing (no real GPU, no pen).
- **Nothing has run on the tablet**: pressure, palm rejection with the real S-Pen, the pen
  button and, above all, how it feels are untested there. If an engine fails to start, the
  bench says so and falls back to Clásico.
- Released from the first push on (GitHub Releases, signed with the Cuaderno key).

## Next

1. Juanma picks the writing screen layout (A, B or C) and the paper (dark or light).
   That closes phase 0.
2. Phase 1 on the chosen engine (own stroke, front buffer), strokes in `core` in page
   points; the bench and Jetpack Ink go away.
3. Still open: the name, what he really uses in Samsung Notes (`plan.md` section 3), and a
   real pen recording from the tablet for the tests.
