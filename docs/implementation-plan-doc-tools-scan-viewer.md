# Implementation Plan — Doc Tools, Scan Polish & Viewer Top Space

Date: 2026-09-05 | Status: planned (not started) | App: PDF Toolkit (15k users, ~100 installs/day via ASO)

Three workstreams, ordered by value/effort. Doc-to-PDF decision: **GO** (10+ Play reviews requesting it; ~4–7MB cost; viewer + convert only, no editing).

---

## A. Doc viewer + Doc-to-PDF (new tool)

**Goal:** Open/preview `.docx` and convert to PDF. No `.docx` editing (10x harder, out of scope). Legacy `.doc` (OLE2/HWPF) explicitly out of scope for v1 — show "unsupported format" message.

**Findings**
- Reusable engine exists in Omnisuite: `core/engine/document/OfficeConverter.kt` (`convertDocxToPdf` via `poi-ooxml:5.2.5` → PdfBox A4, keeps bold/italic/headings/color, tables + borders, embedded images, pagination). Copy ~95% verbatim.
- pdf_tools has **no** POI dep (`app/build.gradle.kts` only `pdfbox-android`); `proguard-rules.pro` keeps `poi` but unused. `minSdk 26` OK (Omnisuite is 30).
- POI is Apache-2.0 → safe for `fdroid`/`opensource` flavors. On-demand Play delivery was **rejected** — static link for all flavors.

**Steps**
1. `app/build.gradle.kts`: add `poi-ooxml:5.2.5` (skip `poi-scratchpad` — `.doc` unsupported) + POI `packaging.excludes` for `META-INF/*` (copy from Omnisuite `app/build.gradle.kts:99-114`).
2. New `domain/operations/OfficeConverter.kt`: copy `convertDocxToPdf()` only (drop xlsx/pptx helpers).
3. New `ui/screens/DocToPdfScreen.kt`: simplify Omnisuite's 422-line screen onto `ToolScaffold` pattern — picker (`msword` + `ooxml` MIME), Convert, page-1 preview via `PdfRenderer`, Share / Save-custom. Repository writes to app output dir + `HistoryManager` + `SafUriManager.addRecentFile`.
4. New `DocxViewerScreen` (read-only): render `.docx` paragraphs/tables via `XWPFDocument` into a Compose scroll view (reuse converter's traversal; images via Coil/Bitmap). "Convert to PDF" CTA at top routes to (3).
5. Register both in `ToolsScreen.kt` + `NavGraph`; add strings (EN + rely on fallback for other locales); add tool icons/screenshots for ASO.
6. Tests: `OfficeConverterTest` (headings, table, image docx fixtures) + bump `APP_VERSION_CODE/NAME`, F-Droid metadata, fastlane changelog, whatsnew ("Open, view & convert Word files").

**Acceptance**
- `.docx` with headings/table/images converts; output opens in viewer; size sane.
- Release APK growth ≤ 7MB vs previous release build.
- `lintFdroidDebug` clean; works offline; no Play Services refs in `fdroid` flavor.

**Cost:** 3–5 days (M). **Risk:** large image-heavy docs → RAM spike; cap with background thread + progress (same pattern as compressor).

---

## B. Scan-to-PDF polish (port Omnisuite UX)

**Goal:** Keep our CameraX+gallery+uCrop engine (FOSS-safe), adopt Omnisuite's higher-converting UX. Auto edge-detect stays Play Services-only.

**Findings**
- Omnisuite's "automatic editable borders" = Google ML Kit `GmsDocumentScanner` (`SCANNER_MODE_FULL`, `DocumentScannerWrapper.kt`) — not portable to `fdroid` flavor.
- Portable, dependency-free wins in `ScanToPdfScreen.kt` (Omnisuite): header explainer card, single big CTA, success card with **first-page preview** (`android.graphics.pdf.PdfRenderer`), Open / Share / Save-copy-to-custom-location actions, processing overlay copy.

**Steps**
1. `playstore` flavor only: add `play-services-mlkit-document-scanner` (~1MB thin SDK) as primary scan path with `SCANNER_MODE_FULL` + gallery import + PDF result; graceful fallback when Play Services missing.
2. `fdroid`/`opensource`: keep existing flow unchanged (engine stays).
3. All flavors — restyle `ui/screens/ScanToPdfScreen.kt`: header card, one CTA, result preview card (render page 1, downscaled), Open/Share/Save-copy row, processing overlay. Reuse existing strings where possible.

**Acceptance**
- Playstore build scans with auto borders; fdroid build flow unchanged; preview renders for 1–100 page scans.
- No `com.google.mlkit` references in `fdroid` source set (`lintFdroidDebug`).

**Cost:** 3–4 days (M: ~1 extraction + ~2–3 UX/flavor wiring). **Risk:** ML Kit needs Play Services + first-run model download → handle offline/failure messaging.

---

## C. Viewer top blank-space fix

**Goal:** Remove wasted space above page 1 in `PdfViewerScreen`.

**Findings** (`ui/screens/PdfViewerScreen.kt`)
- `LazyColumn` has `contentPadding top = 8.dp` **plus** each page `padding(vertical = 4.dp)` → constant ~12dp gap above page 1.
- `Scaffold` + `enableEdgeToEdge` (`MainActivity.kt:71`) with default `contentWindowInsets`: when the auto-hiding top bar (`AnimatedVisibility`, nested-scroll `showControls`) collapses, `paddingValues.top` snaps, causing jump/re-layout rather than smooth reclaim.
- Zoom path uses `transformOrigin(0.5, 0)` top-anchor + `extraBottomPaddingDp` — bottom only, not the culprit, but verify no interaction.

**Steps**
1. Reproduce on device (portrait + landscape, controls shown vs auto-hidden, scale 1x/2x); screenshot + measure gap in dp.
2. Set list top `contentPadding` to `0.dp` (keep 4dp page padding); make first-item top flush.
3. Stabilize hide/show: give the `AnimatedVisibility` top bar a fixed-height container or animate `paddingValues` consumption so content doesn't jump when controls hide.
4. Verify edge-to-edge: content must draw under status bar correctly when top bar hidden, no overlap when shown; test cutout + gesture-nav devices.
5. Regression: pinch-zoom pan bounds, page indicator, save overlay, note pins unaffected.

**Acceptance**
- Gap above page 1 ≤ 4dp in all states; no jump when toolbar auto-hides; no overlap with status bar.

**Cost:** 0.5–1 day (S). **Risk:** low; purely layout.

---

## Order & rollout

1. **C** (½–1d) → 2. **A** (3–5d, the ASO/ratings driver — reply to the 10 reviews on release) → 3. **B** (3–4d).
2. Each ships with version bump + whatsnew + reply-to-reviews; measure tool-open rate post-release.
