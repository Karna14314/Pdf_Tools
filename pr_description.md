**[PDF Toolkit] Play Store Review Fixes — Audit-Based Patch**

### Summary
Audit-first pass on 8 reported issues from Play Store reviews. Only confirmed bugs were patched; already-correct code was left untouched. 4 bugs confirmed and fixed, 4 items audited and found correct.

### Audit Results

| # | Issue | Verdict | Action |
|---|-------|---------|--------|
| 1 | Black images in viewer | Already correct | Skipped — `PdfViewerViewModel.kt` correctly sets `Bitmap.Config.ARGB_8888` and has robust recycling logic |
| 2 | PDF flattening broken | Already correct | Skipped — `PdfFlattener.kt` calls `acroForm.flatten()` before save and handles null AcroForm gracefully |
| 3 | Zoom one direction only | Bug confirmed | Fixed — `PdfViewerScreen.kt`: removed erroneous `listState.dispatchRawDelta(-panChange.y)` that swallowed vertical pan; now correctly updates both `offsetX` and `offsetY` during pinch-to-zoom |
| 4 | Close button in status bar | Bug confirmed (partial) | Fixed — `ScanToPdfScreen.kt` camera UI was missing `systemBarsPadding()`; added. All other overlays (Compress, Split, etc.) already use `Scaffold` padding correctly |
| 5 | Image-to-PDF orientation + reorder | Already correct | Skipped — EXIF rotation applied via `matrix.postRotate()` in `ImageConverter`; drag reorder already present in `ReorderScreen` |
| 6 | Search bar for PDF tools | Bug confirmed (missing feature) | Added — `OutlinedTextField` at top of `ToolsScreen.kt` with `derivedStateOf` filter on tool title and description |
| 7 | Scanner white/sideways/missing pages | Already correct | Skipped — ML Kit integration and result page collection confirmed correct; EXIF rotation applied to scanner output |
| 8 | Garbled/scattered text rendering | Bug confirmed | Fixed — `PdfViewerViewModel.kt`: swapped renderer priority so MuPDF/PDFBox is primary, `androidPdfRenderer` is fallback only |

### Files Changed
| File | Reason |
|------|--------|
| `PdfViewerScreen.kt` | Fixed zoom gesture to update both X and Y pan offsets |
| `ScanToPdfScreen.kt` | Added `systemBarsPadding()` to camera UI container |
| `ToolsScreen.kt` | Added search/filter bar above tools grid |
| `PdfViewerViewModel.kt` | Swapped renderer priority — MuPDF first, Android PdfRenderer fallback |

### Files Audited but Unchanged
`PdfFlattener.kt`, `PdfViewerViewModel.kt` (bitmap config), `ImageConverter.kt`, `PdfScanner.kt`, `ReorderScreen.kt`, `CompressScreen.kt`, `SplitScreen.kt` and remaining dialog/overlay screens.

### Testing Notes
- **Zoom fix:** Open any PDF → pinch zoom in → verify pan works in all directions, not just horizontal
- **Status bar fix:** Open scanner camera UI on a device without gesture navigation → verify close button is tappable and not hidden behind status bar
- **Search bar:** Open tools screen → type partial tool name → verify list filters in real time; clear with ✕ button
- **Renderer fix:** Open a PDF with complex fonts or heavy text layout → verify text renders in readable lines, not scattered characters

---
*Note for QA: The renderer swap (Item 8) is the highest-risk change since it touches all PDF rendering. Please give it extra attention. Test with the heaviest PDFs, password-protected files, and the edge cases that previously hit the MuPDF fallback path to make sure nothing regresses there.*
