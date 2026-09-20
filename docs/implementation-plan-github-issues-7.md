# Implementation Plan — 7 GitHub Issues (easy → hard)

Source: `gh issue list --state open` on `Karna14314/Pdf_Tools` (2026-09-20).
Pulled to `0505047` (v1.3.224). No device connected — user will manual-test at the end from a checklist.

## Scope (7 in, 4 deferred)

Do now: #142, #130, #141, #135, #122, #121, #144.
Deferred: #115 (needs profiling), #120 (OpenCV weight), #129 (jpegli native), #96 (Word-edit out of scope).

Constraints (AGENTS.md): keep `fdroid`/`opensource` FOSS (no Play Services in shared code), static versions in `gradle.properties`, `fdroid rewritemeta` clean, don't break pinch-zoom / highlight MULTIPLY / fragment lifecycle.

## Categories (each category = one build + one manual-test pass)

### Cat A — Viewer trust, easy (do first)
- [ ] A1 #142 password-PDF renders blank/garbled — VERIFY fix from #143 (`bd2751a`), add regression coverage if gap found
- [ ] A2 #130 saved signature reuse — backend `PdfSigner.getSavedSignatures()` exists, wire UI (reuse/delete) in `SignPdfScreen`
- [ ] A3 #141 H/V pan sensitivity differs when zoomed — unify in `PdfPagesContent` (`PdfViewerScreen.kt:1329-1387`)

### Cat B — Input + persistence + scan quality, medium (do second)
- [ ] B1 #135 OCR image input — backend `PdfOcrProcessor.extractTextFromImage()` exists, add `image/*` picker + ViewModel branch in `OcrScreen`
- [ ] B2 #122 remember tool settings — new `ToolSettingsStore` (DataStore) + persist for Merge/Compress/Scan/OCR first, expand later
- [ ] B3 #121 scan contrast/BW artifacting — replace fixed `threshold=128` + `contrast=1.2` in `PdfScanner` with user threshold slider + safer default (Otsu/auto)

### Cat C — New feature, harder (do last)
- [ ] C1 #144 Cut & Stack booklet (RTL/LTR) in N-Up — new `NUpLayoutMode {STANDARD, CUT_STACK_LTR, CUT_STACK_RTL}` in `ImpositionModel`, ordering in `ImpositionEngine.calculateNUpLayout`, dropdown in `PrintImpositionStudioScreen`, exporter follows engine, unit tests

## Sequential change log (append every step — do not lose track)

| Step | Issue | Files touched | Status | Notes |
|------|-------|---------------|--------|-------|
| 0 | — | `docs/implementation-plan-github-issues-7.md` (this file) | done | plan stored |
| A1 | #142 | none (verified #143 fix covers it) | done | PDFBox-forced render for encrypted docs |
| A2 | #130 | `SignPdfScreen.kt`, `PdfSigner.kt` (already had backend) | done | saved/reuse/delete UI wired |
| A3 | #141 | `PdfViewerScreen.kt` | done | V-delta divided by zoom scale |
| B1 | #135 | `OcrScreen.kt`, `PdfOcrProcessor.kt` (+`makeImageSearchable`) | done | image picker + branches |
| B2 | #122 | `util/ToolSettingsStore.kt` (new), `ScanToPdfScreen.kt`, `OcrScreen.kt` | done | Scan (7 fields) + OCR mode persisted |
| B3 | #121 | `PdfScanner.kt` (Otsu + strength), `ScanToPdfScreen.kt` (sliders) | done | fixed `PdfScannerTest` reflection |
| C1 | #144 | `ImpositionModel.kt`, `ImpositionEngine.kt`, `PrintImpositionStudioScreen.kt`, `ImpositionEngineCutStackTest.kt` | done | 2x2 only, -1 blanks, exporter-safe |
| build | all | `app-playstore-debug.apk` (~80MB) | done | `compilePlaystoreDebugKotlin` + `assemblePlaystoreDebug` OK; local unit-test runner broken (JDK space path), skipped per user |

## Manual-test checklist (for user, after all code done — device needed)

Cat A:
- A1: open password PDF directly (no unlock-save roundtrip) → content renders, wrong password shows error, rotation/reopen keeps working
- A2: draw signature → Save → close/reopen Sign tool → reuse saved signature without redrawing → delete works
- A3: zoom 2-4x on vertical doc + wide plan → H and V drag feel equal, no jump, pinch-zoom unbroken, reset-zoom works
Cat B:
- B1: OCR → pick JPG/PNG → Extract Text works; Make Searchable from image works; PDF flow unregressed (fdroid + playstore)
- B2: set Merge/Compress/Scan options → kill app → reopen → settings remembered; Clear keeps working
- B3: Scan→B/W + contrast on shadowed photo → readable, no big black blobs; slider changes output; Color/Gray unregressed
Cat C:
- C1: N-Up 2x2 → Standard vs Cut&Stack LTR vs RTL → sheet order matches issue tables (1-8), duplex long-edge, export opens, blank padding for non-multiple-of-8
General: fdroidDebug + playstoreDebug assemble, no Play API in fdroid path, highlight MULTIPLY + overlays untouched.
