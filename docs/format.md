# The notebook file (format 1)

One notebook is one file, `<name>.cuaderno`: a zip. The file is the truth; anything else the
app keeps (library index, thumbnails) is a cache it can rebuild by reading the files.
Code: `core/src/main/kotlin/cuaderno/core/` (`NotebookFile`, `StrokeCodec`). Tests and the
kept fixture: `core/src/test/` (`FormatTest`, `resources/v1.cuaderno`).

```
manifest.json
pages/<page id>.strokes
attachments/<name>          (a background PDF, images; phase 3 and 4)
```

## manifest.json

```json
{
  "format": 1,
  "app": "cuaderno",
  "id": "nb-1",
  "title": "Teoría de Circuitos II",
  "created": "2026-11-16T18:00:00-03:00",
  "modified": "2026-11-16T19:30:00-03:00",
  "pages": [
    {"id": "p1", "width": 595.0, "height": 842.0, "paper": "grid", "strokes": "pages/p1.strokes"}
  ]
}
```

- `format`: a whole number. A reader refuses a file with a higher number than it knows,
  instead of opening it and dropping what it does not understand on the next save.
- `pages`: in reading order. `id` is `[A-Za-z0-9_-]{1,64}`, unique in the notebook.
- `width`, `height`: page points, 1/72 inch (A4 is 595 x 842). Every position in the file is
  in page points, never screen pixels, so a page looks the same on any device.
- `paper`: `blank`, `ruled`, `grid` or `dotted`.
- A reader ignores manifest keys it does not know.

## pages/<id>.strokes

Binary, little-endian.

| Bytes | What |
|---|---|
| 4 | `CDNS` |
| 2 | version, unsigned: 1 |
| 4 | number of strokes |

Then each stroke, in drawing order (later ones on top):

| Bytes | What |
|---|---|
| 1 | tool: 0 pen, 1 highlighter |
| 4 | colour, ARGB |
| 4 | width in page points, float |
| 1 | channels: which values each sample carries after x and y. Bit 0 pressure (0..1), bit 1 time (ms since the stroke began), bit 2 tilt (radians), bit 3 orientation (radians). Bits 4 to 7 must be 0 |
| 4 | number of samples, at least 1 |
| n x 4 x (2 + channels set) | per sample: x, y, then the channels present in bit order, all floats |

A channel whose value is 0 in every sample is left out and reads back as 0. Everything the
pen reported is kept even when the brush that drew it ignored it: a later brush can use it.

A truncated entry, bytes after the last stroke, an unknown tool or channel bit: the reader
fails with `FormatException`. It never returns half a page.

## Saving

`NotebookFile.write` writes the whole zip to `<name>.cuaderno.tmp`, forces it to disk and
renames it over the notebook in one step. A crash or a full disk at any point leaves the
previous file whole (`FormatTest.aSaveThatFailsLeavesThePreviousFileWhole`).

## Changing the format

1. Raise `FORMAT` (or the strokes `VERSION`) and keep reading every older one.
2. Save a fixture of the version being left (`CUADERNO_FIXTURE=<path> gradle :core:test`
   writes one) into `core/src/test/resources/` and add a test that opens it.
3. Update this file in the same PR.

## Not in format 1 yet

Saved versions and trash (phase 1 and 2), PDF backgrounds and images (3 and 4), text boxes
(5). Each is expected to add manifest keys or entries, with a format bump when an older
reader would lose it.
