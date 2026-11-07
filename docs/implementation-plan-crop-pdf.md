# Implementation Plan — Crop PDF tool

Branch: `feature/crop-pdf` (fork `danilo-dellorco/Pdf_Tools` → PR against `Karna14314/Pdf_Tools:master`).

## Goal

New **Crop Pages** tool (Organize section) where the user draws rectangles on rendered pages:

| Mode | Behaviour |
|---|---|
| Same for all pages | One rectangle applied to every page |
| Per page | One rectangle per page (copy-to-remaining, reset), or **odd/even** sub-mode with two rectangles |
| Split page in two | Either one split line (vertical/horizontal) or two free rectangles; each page becomes two pages |

## Technical approach

- **Lossless**: set `PDPage.cropBox` with PdfBox-Android. No rasterization, text stays selectable.
- Optional switch **"Also shrink page size"** (off by default) also sets the MediaBox for tools that ignore CropBox.
- Rectangles are stored normalized (0..1, top-left origin) in *displayed* page space. A pure function maps them into
  PDF user space, accounting for the current CropBox, `/Rotate` (0/90/180/270) and the y-up PDF coordinate system.
- **Split**: shallow-copy the page dictionary (content streams/resources shared), give each copy its own CropBox.
  Annotations are dropped from the second copy (shared annotation dictionaries cannot belong to two pages).
  Order: first/second half, with a "reverse order" option for right-to-left documents.
- Privacy note in the UI: content outside the crop is hidden, not removed (use Redact for that).
- No new dependencies → all flavors (`playstore`, `fdroid`, `opensource`) stay unchanged/FOSS.

## Files

New:
- `domain/operations/PdfCropper.kt` — `NormalizedRect`, geometry mapping, `cropPages`, `splitPages`
- `ui/screens/CropViewModel.kt` — state (file, mode, rects, current page, bitmap), save
- `ui/screens/CropScreen.kt` — Scaffold in the style of `RotateScreen`
- `ui/components/CropOverlay.kt` — draggable rectangle / split line, dimmed outside area
- `app/src/test/.../domain/operations/PdfCropperTest.kt` — geometry + Robolectric tests

Changed: `Screen.kt`, `AppNavigation.kt`, `ToolsScreen.kt`, `HistoryManager.OperationType` (`CROP`),
`values*/strings.xml` (all 15 locales translated), `README.md`, `CHANGELOG.md`.

Not changed: `gradle.properties` versions and F-Droid metadata (maintainer handles releases).

## Steps

- [x] 1. `PdfCropper` + tests
- [x] 2. `CropOverlay` + `CropScreen` (all-pages mode) + registration
- [x] 3. Per-page and odd/even modes
- [x] 4. Split mode (line + two rectangles)
- [x] 5. Strings (15 locales), README, CHANGELOG
- [~] 6. `assembleFdroidDebug`, `assemblePlaystoreDebug`, `lintFdroidDebug`, `testFdroidDebugUnitTest`, manual test

## Manual test checklist

- [ ] Crop all pages; open result in app viewer and an external viewer
- [ ] Page with `/Rotate 90` crops the area actually drawn
- [ ] PDF that already has a CropBox crops relative to the visible area
- [ ] Per-page: different rects per page; copy-to-remaining; odd/even
- [ ] Split vertical line → 2N pages, correct halves, correct order; reversed order
- [ ] Split with two rectangles
- [ ] "Also shrink page size" on → MediaBox equals CropBox
- [ ] Screen rotation keeps drawn rectangles
- [ ] Password-protected PDF shows an error, not a crash

## Status (2026-11-07)

- Both flavors build (`assembleFdroidDebug`, `assemblePlaystoreDebug`); lint: 0 errors.
- `PdfCropperTest`: 16/16 pass. Full unit suite: 79 run, 3 failures in `PrintUtilsTest` (2) and
  `PdfViewerViewModelTest` (1) — unrelated to this feature: `PrintUtilsTest` fails identically on `master`
  (b29dd5f); `PdfViewerViewModelTest` passes when re-run on this branch (timing-dependent).
- Remaining lint warnings on new strings: 5 × `PluralsCandidate` (kept as simple format strings to avoid
  per-locale plural rules across 15 languages).
- Manual device test: pending (checklist below).
