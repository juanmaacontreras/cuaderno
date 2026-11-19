# The notebook file (format 2)

One notebook is one file, `<name>.cuaderno`: a zip. The file is the truth; anything else the
app keeps (library index, thumbnails) is a cache it can rebuild by reading the files.
Code: `core/src/main/kotlin/cuaderno/core/` (`NotebookFile`, `StrokeCodec`). Tests and the
kept fixtures: `core/src/test/` (`FormatTest`, `resources/v1.cuaderno`, `resources/v2.cuaderno`).

```
manifest.json
pages/<page id>.strokes
attachments/<name>          (pictures' image files since format 2; a background PDF in phase 4)
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
- `pictures` (format 2, only on a page that has any): see below.
- A reader ignores manifest keys it does not know.

## Pictures (format 2)

```json
"pictures": [{"file": "attachments/pic-3290d660f1699474fef8.jpg", "x": 121.9, "y": 74.8, "width": 196.4, "height": 130.9}]
```

- In drawing order, all of them on the paper and under every stroke.
- `x`, `y`, `width`, `height`: page points, the picture's box; the image is stretched to it
  (the app keeps its proportions when placing and resizing).
- `file`: an entry in the zip, `attachments/` plus a name matching `[A-Za-z0-9_.-]{1,128}`. The
  app names a picture by its content (`pic-` and 20 hex digits of its SHA-256), so one image
  shown twice, on one page or several, is one file. Missing or a bad name: `FormatException`.
- The app writes JPEG, upright, at most 1600 pixels on its long side (`app/.../Pictures.kt`).
  A reader takes any image Android can decode; one it cannot shows as an empty frame.
- In memory each `Picture` carries its bytes, so a page copied anywhere (duplicated, to the
  trash and back, pasted into another notebook) takes its pictures with it.
- **A notebook without pictures is still written as format 1**, so an app from before pictures
  keeps opening it; only a notebook with at least one picture is written as 2.

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

## Where notebooks live

`files/notebooks/` in the app's private storage. A subject is a folder there and a notebook is
a file in it, `<subject>/<id>.cuaderno`; a notebook right in `notebooks/` has no subject (the
first one, `cuaderno.cuaderno`, from before subjects). Moving a notebook to another subject
moves its file; the notebook file itself does not say its subject, so format 1 is unchanged.
Code: `cuaderno.core.Library`.

`notebooks/library.json` adds only what the folders cannot say, and is written atomically:

```json
{"subjects": [{"name": "Circuitos", "color": -7815984}], "last": "Circuitos/nb-1731880000000.cuaderno"}
```

- `subjects`: their order and colour (ARGB). A folder missing here is still a subject, after
  these, with a colour picked by the app.
- `last`: the notebook opened last, relative to `notebooks/`.

Lost or unreadable, it is ignored and rebuilt: only colours, order and `last` go
(`LibraryTest.losingTheIndexLosesNoNotebook`).

## The trash

`files/trash/`, beside `notebooks/` (never inside it, so it is never taken for a subject).
Each deleted thing is two files with the same stem (`t<date digits>-<n>`):

- `<stem>.cuaderno`: a notebook file (format 1, or 2 with pictures). A deleted notebook is its own file, moved here; a deleted page is
  kept as a notebook of that one page.
- `<stem>.json`, written atomically before the notebook file moves:

```json
{"kind": "page", "title": "Teoría", "from": "Circuitos/nb-1.cuaderno", "page": 1, "deleted": "2026-11-18T10:00:00-03:00"}
```

`from` is where it was, relative to `notebooks/` (for a page, its notebook); `page` (pages only)
its place, 0 first. Restoring puts a notebook back in its subject (none if that is gone) and a
page back at its place in its notebook (a notebook of its own if that is gone too). After 30 days
both files are deleted when the library opens. A note without its file, or unreadable, is
skipped and its file kept. Code: `cuaderno.core.Trash`.

## Changing the format

1. Raise `FORMAT` (or the strokes `VERSION`) and keep reading every older one.
2. Save a fixture of the version being left (`CUADERNO_FIXTURE=<path> gradle :core:test`
   writes one) into `core/src/test/resources/` and add a test that opens it.
3. Update this file in the same PR.

## Not in the format yet

Saved versions (phase 1), PDF backgrounds (4), text boxes (5). Each is expected to add
manifest keys or entries, with a format bump when an older reader would lose it.
