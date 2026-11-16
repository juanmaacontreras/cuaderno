# Cuaderno

Juanma's own handwriting notes app for the Galaxy Tab S10 FE with S-Pen, meant to replace
Samsung Notes for university work. Part of the ecosystem:
https://claude.ai/artifact/PHQmVerxfbkkCipzxGaF2d

**Status: plan only, no code yet.** Read `docs/plan.md` (in Spanish, so Juanma can read and
approve it) before anything else. It is the premise and the order of work, not a closed spec:
items marked "origen: Claude" are unconfirmed suggestions.

- **Does:** notebooks of pages to write and draw on with the stylus, PDF annotation, export.
- **Does not:** edit text documents, manage tasks, replace the brain or read books (Atril).
  A separate app; linking it to other devices comes only after it works (phase 6).
- Start each phase in plan mode with rendered designs (dark theme) and wait for his OK.
- One session, one change, one PR. Every decision goes in `docs/decisions.md`, the state in
  `docs/status.md`; create both in phase 0. What stays only in the chat is lost.
- Never lose a note: atomic autosave, our own documented file format with a version number,
  always exportable to PDF. See "Formato y guardado" in the plan.
- Code, comments and commits in English. UI text in Rioplatense Spanish. Look: Nord.
- The starting point and the bar for stroke feel is Enlace's board:
  `~/projects/enlace/app/src/main/java/com/enlace/app/modules/board/BoardView.kt`.
- Build tools and release flow: copy the approach of `~/projects/enlace` (`tools/toolchain.sh`,
  `tools/release.sh`, `tools/screens.sh`); move the shared updater and UI kit to a library
  instead of copying them a third time.
- The repo is local only. Creating a GitHub remote is Juanma's decision: ask first.
