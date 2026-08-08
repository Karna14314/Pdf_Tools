# F-Droid SAF Crash Audit Report

**Date:** 2026-08-08  
**Target Project:** PDF Toolkit (Android)  
**Reported Issue:** `android.content.ActivityNotFoundException: No Activity found to handle Intent { act=android.intent.action.OPEN_DOCUMENT typ=*/* }`  
**Reporter:** F-Droid App Reviewer  

---

## 1. Executive Summary

An audit was conducted across the PDF Toolkit Android repository to investigate the crash reported by an F-Droid reviewer on devices lacking a Documents Provider / DocumentUI system application (e.g., debloated, custom ROM, minimal AOSP/LineageOS/GrapheneOS builds).

### Key Audit Conclusions
- **F-Droid Reviewer Report Status:** **VERIFIED — 100% ACCURATE**.
- **Total Storage Access Framework (SAF) Launch Calls:** **63 launch sites** across **27 UI files**.
- **Exception Handling Status:** **0 out of 63 SAF launch calls** contain exception handling for `ActivityNotFoundException` or generic `Exception`.
- **Pre-Fix Crash Surface:** **63 SAF launch sites** will throw an unhandled `ActivityNotFoundException` and crash the application if no Documents Provider is installed on the user's device.
- **Current `ActivityNotFoundException` Handling in Codebase:** Present in only two locations (`PdfViewerScreen.kt` for `ACTION_VIEW` external viewer intent, and `ReviewManager.kt` for Play Store / GitHub web intents).

---

## 2. Findings Table

The table below catalogs every `ActivityResultLauncher` launch site mapped during the audit.

> **Risk Level Definitions:**
> - **Critical**: Invokes system picker/intent without exception handling; throws uncaught `ActivityNotFoundException` on devices lacking a Documents Provider / Intent receiver.
> - **Medium**: Wrapped in generic `try-catch(Exception)` or intent resolution check, preventing application crash but potentially failing silently.
> - **Safe**: Explicitly handles `ActivityNotFoundException` with fallback UI, toast, or graceful degradation.

| # | File Path | Function Name | Contract Type | Launch Call | Exception Handling | Risk Level |
|---|---|---|---|---|---|---|
| 1 | `app/src/main/java/com/yourname/pdftoolkit/ui/screens/AnnotationScreen.kt:372` | `AnnotationScreen` | `OpenDocument` | `pdfPickerLauncher.launch(arrayOf("application/pdf"))` | None | **Critical** |
| 2 | `app/src/main/java/com/yourname/pdftoolkit/ui/screens/AnnotationScreen.kt:378` | `AnnotationScreen` | `OpenDocument` | `pdfPickerLauncher.launch(arrayOf("application/pdf"))` | None | **Critical** |
| 3 | `app/src/main/java/com/yourname/pdftoolkit/ui/screens/AnnotationScreen.kt:732` | `AnnotationScreen` | `CreateDocument` | `saveDocumentLauncher.launch(fileName)` | None | **Critical** |
| 4 | `app/src/main/java/com/yourname/pdftoolkit/ui/screens/CompressScreen.kt:508` | `CompressScreen` | `GetContent` | `pickPdfLauncher.launch("application/pdf")` | None | **Critical** |
| 5 | `app/src/main/java/com/yourname/pdftoolkit/ui/screens/CompressScreen.kt:526` | `CompressScreen` | `CreateDocument` | `savePdfLauncher.launch(fileName)` | None | **Critical** |
| 6 | `app/src/main/java/com/yourname/pdftoolkit/ui/screens/ConvertScreen.kt:365` | `ConvertScreen` | `StartActivityForResult` | `cropLauncher.launch(cropIntent)` | None | **Critical** |
| 7 | `app/src/main/java/com/yourname/pdftoolkit/ui/screens/ConvertScreen.kt:413` | `ConvertScreen` | `OpenMultipleDocuments` | `pickImagesLauncher.launch(arrayOf("image/*"))` | None | **Critical** |
| 8 | `app/src/main/java/com/yourname/pdftoolkit/ui/screens/ConvertScreen.kt:574` | `ConvertScreen` | `OpenMultipleDocuments` | `pickImagesLauncher.launch(arrayOf("image/*"))` | None | **Critical** |
| 9 | `app/src/main/java/com/yourname/pdftoolkit/ui/screens/ConvertScreen.kt:592` | `ConvertScreen` | `CreateDocument` | `savePdfLauncher.launch(fileName)` | None | **Critical** |
| 10 | `app/src/main/java/com/yourname/pdftoolkit/ui/screens/ExtractScreen.kt:375` | `extractPagesWithDefaultLocation` | `OpenDocument` | `pickPdfLauncher.launch(arrayOf("application/pdf"))` | None | **Critical** |
| 11 | `app/src/main/java/com/yourname/pdftoolkit/ui/screens/ExtractScreen.kt:385` | `extractPagesWithDefaultLocation` | `CreateDocument` | `savePdfLauncher.launch(fileName)` | None | **Critical** |
| 12 | `app/src/main/java/com/yourname/pdftoolkit/ui/screens/ExtractTextScreen.kt:340` | `extractTextWithDefaultLocation` | `OpenDocument` | `pickPdfLauncher.launch(arrayOf("application/pdf"))` | None | **Critical** |
| 13 | `app/src/main/java/com/yourname/pdftoolkit/ui/screens/ExtractTextScreen.kt:349` | `extractTextWithDefaultLocation` | `CreateDocument` | `saveFileLauncher.launch("${baseName}.txt")` | None | **Critical** |
| 14 | `app/src/main/java/com/yourname/pdftoolkit/ui/screens/FilesScreen.kt:183` | `FilesScreen` | `OpenDocument` | `documentPickerLauncher.launch(pdfMimeTypes)` | None | **Critical** |
| 15 | `app/src/main/java/com/yourname/pdftoolkit/ui/screens/FillFormsScreen.kt:260` | `FillFormsScreen` | `OpenDocument` | `pdfPickerLauncher.launch(arrayOf("application/pdf"))` | None | **Critical** |
| 16 | `app/src/main/java/com/yourname/pdftoolkit/ui/screens/FillFormsScreen.kt:266` | `FillFormsScreen` | `OpenDocument` | `pdfPickerLauncher.launch(arrayOf("application/pdf"))` | None | **Critical** |
| 17 | `app/src/main/java/com/yourname/pdftoolkit/ui/screens/FillFormsScreen.kt:520` | `FillFormsScreen` | `CreateDocument` | `saveDocumentLauncher.launch(fileName)` | None | **Critical** |
| 18 | `app/src/main/java/com/yourname/pdftoolkit/ui/screens/FlattenScreen.kt:247` | `FlattenScreen` | `OpenDocument` | `pdfPickerLauncher.launch(arrayOf("application/pdf"))` | None | **Critical** |
| 19 | `app/src/main/java/com/yourname/pdftoolkit/ui/screens/FlattenScreen.kt:253` | `FlattenScreen` | `OpenDocument` | `pdfPickerLauncher.launch(arrayOf("application/pdf"))` | None | **Critical** |
| 20 | `app/src/main/java/com/yourname/pdftoolkit/ui/screens/FlattenScreen.kt:507` | `FlattenScreen` | `CreateDocument` | `saveDocumentLauncher.launch(fileName)` | None | **Critical** |
| 21 | `app/src/main/java/com/yourname/pdftoolkit/ui/screens/HtmlToPdfScreen.kt:483` | `HtmlToPdfScreen` | `CreateDocument` | `saveFileLauncher.launch(fileName)` | None | **Critical** |
| 22 | `app/src/main/java/com/yourname/pdftoolkit/ui/screens/ImageToolsScreen.kt:801` | `processImages` | `OpenMultipleDocuments` | `pickImagesLauncher.launch(arrayOf("image/*"))` | None | **Critical** |
| 23 | `app/src/main/java/com/yourname/pdftoolkit/ui/screens/MergeScreen.kt:250` | `mergeWithDefaultLocation` | `OpenMultipleDocuments` | `pickPdfsLauncher.launch(arrayOf("application/pdf"))` | None | **Critical** |
| 24 | `app/src/main/java/com/yourname/pdftoolkit/ui/screens/MergeScreen.kt:332` | `mergeWithDefaultLocation` | `OpenMultipleDocuments` | `pickPdfsLauncher.launch(arrayOf("application/pdf"))` | None | **Critical** |
| 25 | `app/src/main/java/com/yourname/pdftoolkit/ui/screens/MergeScreen.kt:342` | `mergeWithDefaultLocation` | `CreateDocument` | `savePdfLauncher.launch(fileName)` | None | **Critical** |
| 26 | `app/src/main/java/com/yourname/pdftoolkit/ui/screens/MetadataScreen.kt:648` | `stripMetadata` | `OpenDocument` | `pickPdfLauncher.launch(arrayOf("application/pdf"))` | None | **Critical** |
| 27 | `app/src/main/java/com/yourname/pdftoolkit/ui/screens/MetadataScreen.kt:667` | `stripMetadata` | `CreateDocument` | `savePdfLauncher.launch(fileName)` | None | **Critical** |
| 28 | `app/src/main/java/com/yourname/pdftoolkit/ui/screens/MetadataScreen.kt:680` | `stripMetadata` | `OpenDocument` | `pickPdfLauncher.launch(arrayOf("application/pdf"))` | None | **Critical** |
| 29 | `app/src/main/java/com/yourname/pdftoolkit/ui/screens/OcrScreen.kt:307` | `OcrScreen` | `OpenDocument` | `pdfPickerLauncher.launch(arrayOf("application/pdf"))` | None | **Critical** |
| 30 | `app/src/main/java/com/yourname/pdftoolkit/ui/screens/OcrScreen.kt:313` | `OcrScreen` | `OpenDocument` | `pdfPickerLauncher.launch(arrayOf("application/pdf"))` | None | **Critical** |
| 31 | `app/src/main/java/com/yourname/pdftoolkit/ui/screens/OcrScreen.kt:513` | `OcrScreen` | `CreateDocument` | `saveMarkdownLauncher.launch(...)` | None | **Critical** |
| 32 | `app/src/main/java/com/yourname/pdftoolkit/ui/screens/OcrScreen.kt:515` | `OcrScreen` | `CreateDocument` | `saveTextLauncher.launch(...)` | None | **Critical** |
| 33 | `app/src/main/java/com/yourname/pdftoolkit/ui/screens/OcrScreen.kt:716` | `OcrScreen` | `CreateDocument` | `saveDocumentLauncher.launch(fileName)` | None | **Critical** |
| 34 | `app/src/main/java/com/yourname/pdftoolkit/ui/screens/OcrScreen.kt:775` | `OcrScreen` | `CreateDocument` | `saveMarkdownLauncher.launch(...)` | None | **Critical** |
| 35 | `app/src/main/java/com/yourname/pdftoolkit/ui/screens/OcrScreen.kt:777` | `OcrScreen` | `CreateDocument` | `saveTextLauncher.launch(...)` | None | **Critical** |
| 36 | `app/src/main/java/com/yourname/pdftoolkit/ui/screens/OrganizeScreen.kt:375` | `organizeWithDefaultLocation` | `OpenDocument` | `pickPdfLauncher.launch(arrayOf("application/pdf"))` | None | **Critical** |
| 37 | `app/src/main/java/com/yourname/pdftoolkit/ui/screens/OrganizeScreen.kt:402` | `organizeWithDefaultLocation` | `CreateDocument` | `saveFileLauncher.launch(...)` | None | **Critical** |
| 38 | `app/src/main/java/com/yourname/pdftoolkit/ui/screens/PageNumberScreen.kt:432` | `addNumbersWithDefaultLocation` | `OpenDocument` | `pickPdfLauncher.launch(arrayOf("application/pdf"))` | None | **Critical** |
| 39 | `app/src/main/java/com/yourname/pdftoolkit/ui/screens/PageNumberScreen.kt:450` | `addNumbersWithDefaultLocation` | `CreateDocument` | `saveFileLauncher.launch(...)` | None | **Critical** |
| 40 | `app/src/main/java/com/yourname/pdftoolkit/ui/screens/PdfToImageScreen.kt:425` | `convertPdfToImages` | `OpenDocument` | `pickPdfLauncher.launch(arrayOf("application/pdf"))` | None | **Critical** |
| 41 | `app/src/main/java/com/yourname/pdftoolkit/ui/screens/PdfViewerScreen.kt:405` | `onPreScroll` | `CreateDocument` | `saveDocumentLauncher.launch(fileName)` | None | **Critical** |
| 42 | `app/src/main/java/com/yourname/pdftoolkit/ui/screens/ReorderScreen.kt:497` | `resetOrder` | `OpenDocument` | `pickPdfLauncher.launch(arrayOf("application/pdf"))` | None | **Critical** |
| 43 | `app/src/main/java/com/yourname/pdftoolkit/ui/screens/ReorderScreen.kt:515` | `resetOrder` | `CreateDocument` | `saveFileLauncher.launch(...)` | None | **Critical** |
| 44 | `app/src/main/java/com/yourname/pdftoolkit/ui/screens/RepairScreen.kt:361` | `repairWithDefaultLocation` | `OpenDocument` | `pickPdfLauncher.launch(arrayOf("application/pdf"))` | None | **Critical** |
| 45 | `app/src/main/java/com/yourname/pdftoolkit/ui/screens/RepairScreen.kt:379` | `repairWithDefaultLocation` | `CreateDocument` | `saveFileLauncher.launch(...)` | None | **Critical** |
| 46 | `app/src/main/java/com/yourname/pdftoolkit/ui/screens/RotateScreen.kt:486` | `rotateWithDefaultLocation` | `OpenDocument` | `pickPdfLauncher.launch(arrayOf("application/pdf"))` | None | **Critical** |
| 47 | `app/src/main/java/com/yourname/pdftoolkit/ui/screens/RotateScreen.kt:497` | `rotateWithDefaultLocation` | `CreateDocument` | `savePdfLauncher.launch(fileName)` | None | **Critical** |
| 48 | `app/src/main/java/com/yourname/pdftoolkit/ui/screens/ScanToPdfScreen.kt:301` | `ScanToPdfScreen` | `RequestPermission` | `permissionLauncher.launch(...)` | None | **Medium** |
| 49 | `app/src/main/java/com/yourname/pdftoolkit/ui/screens/ScanToPdfScreen.kt:312` | `ScanToPdfScreen` | `OpenMultipleDocuments` | `imagePickerLauncher.launch(arrayOf("image/*"))` | None | **Critical** |
| 50 | `app/src/main/java/com/yourname/pdftoolkit/ui/screens/ScanToPdfScreen.kt:367` | `ScanToPdfScreen` | `StartActivityForResult` | `cropLauncher.launch(cropIntent)` | None | **Critical** |
| 51 | `app/src/main/java/com/yourname/pdftoolkit/ui/screens/ScanToPdfScreen.kt:396` | `ScanToPdfScreen` | `StartActivityForResult` | `cropLauncher.launch(cropIntent)` | None | **Critical** |
| 52 | `app/src/main/java/com/yourname/pdftoolkit/ui/screens/ScanToPdfScreen.kt:607` | `ScanToPdfScreen` | `CreateDocument` | `saveDocumentLauncher.launch(fileName)` | None | **Critical** |
| 53 | `app/src/main/java/com/yourname/pdftoolkit/ui/screens/SecurityScreen.kt:407` | `protectWithDefaultLocation` | `OpenDocument` | `pickPdfLauncher.launch(arrayOf("application/pdf"))` | None | **Critical** |
| 54 | `app/src/main/java/com/yourname/pdftoolkit/ui/screens/SecurityScreen.kt:417` | `protectWithDefaultLocation` | `CreateDocument` | `savePdfLauncher.launch(...)` | None | **Critical** |
| 55 | `app/src/main/java/com/yourname/pdftoolkit/ui/screens/SignPdfScreen.kt:426` | `SignPdfScreen` | `OpenDocument` | `pdfPickerLauncher.launch(arrayOf("application/pdf"))` | None | **Critical** |
| 56 | `app/src/main/java/com/yourname/pdftoolkit/ui/screens/SignPdfScreen.kt:432` | `SignPdfScreen` | `OpenDocument` | `pdfPickerLauncher.launch(arrayOf("application/pdf"))` | None | **Critical** |
| 57 | `app/src/main/java/com/yourname/pdftoolkit/ui/screens/SignPdfScreen.kt:891` | `SignPdfScreen` | `CreateDocument` | `saveDocumentLauncher.launch(fileName)` | None | **Critical** |
| 58 | `app/src/main/java/com/yourname/pdftoolkit/ui/screens/SplitScreen.kt:569` | `splitWithDefaultLocation` | `OpenDocument` | `pickPdfLauncher.launch(arrayOf("application/pdf"))` | None | **Critical** |
| 59 | `app/src/main/java/com/yourname/pdftoolkit/ui/screens/SplitScreen.kt:594` | `splitWithDefaultLocation` | `CreateDocument` | `savePdfLauncher.launch(fileName)` | None | **Critical** |
| 60 | `app/src/main/java/com/yourname/pdftoolkit/ui/screens/ToolsScreen.kt:190` | `copyUriToCache` | `OpenDocument` | `pdfPickerLauncher.launch(arrayOf("application/pdf"))` | None | **Critical** |
| 61 | `app/src/main/java/com/yourname/pdftoolkit/ui/screens/UnlockScreen.kt:415` | `unlockWithDefaultLocation` | `OpenDocument` | `pickPdfLauncher.launch(arrayOf("application/pdf"))` | None | **Critical** |
| 62 | `app/src/main/java/com/yourname/pdftoolkit/ui/screens/UnlockScreen.kt:433` | `unlockWithDefaultLocation` | `CreateDocument` | `saveFileLauncher.launch(...)` | None | **Critical** |
| 63 | `app/src/main/java/com/yourname/pdftoolkit/ui/screens/WatermarkScreen.kt:297` | `WatermarkScreen` | `OpenDocument` | `pdfPickerLauncher.launch(arrayOf("application/pdf"))` | None | **Critical** |
| 64 | `app/src/main/java/com/yourname/pdftoolkit/ui/screens/WatermarkScreen.kt:303` | `WatermarkScreen` | `OpenDocument` | `pdfPickerLauncher.launch(arrayOf("application/pdf"))` | None | **Critical** |
| 65 | `app/src/main/java/com/yourname/pdftoolkit/ui/screens/WatermarkScreen.kt:394` | `WatermarkScreen` | `OpenDocument` | `imagePickerLauncher.launch(arrayOf("image/*"))` | None | **Critical** |
| 66 | `app/src/main/java/com/yourname/pdftoolkit/ui/screens/WatermarkScreen.kt:400` | `WatermarkScreen` | `OpenDocument` | `imagePickerLauncher.launch(arrayOf("image/*"))` | None | **Critical** |
| 67 | `app/src/main/java/com/yourname/pdftoolkit/ui/screens/WatermarkScreen.kt:571` | `WatermarkScreen` | `CreateDocument` | `saveDocumentLauncher.launch(fileName)` | None | **Critical** |

---

## 3. Crash Surface Counts

- **Total SAF Contract Launch Sites:** 63
- **Total Non-SAF ActivityResult Contract Launch Sites:** 4
- **Total Direct `startActivity` Call Sites:** 24
- **Total Files Containing SAF Launchers:** 27
- **Pre-Fix Unprotected SAF Launch Sites:** **63 / 63 (100%)**

---

## 4. Root Cause Analysis

When an Android app calls `ActivityResultLauncher.launch(input)`, Android converts the contract into an implicit intent (e.g. `Intent.ACTION_OPEN_DOCUMENT` or `Intent.ACTION_CREATE_DOCUMENT`) and invokes `startActivityForResult()` internally within the activity result framework.

If the host device does not have an installed application that handles `ACTION_OPEN_DOCUMENT` or `ACTION_CREATE_DOCUMENT` (specifically `com.android.documentsui`), Android throws an uncaught `ActivityNotFoundException` on the main looper thread, causing an immediate crash.

The codebase suffered from two design weaknesses:
1. Direct, unguarded calls to `launcher.launch(...)` across all UI event listeners.
2. Lack of a shared abstraction or extension function to intercept `ActivityNotFoundException`.

---

## 5. Architecture Evaluation & Recommended Fix

### Options Evaluated
1. **Option A (Local try-catch at every call site):** Highly duplicated (63+ blocks), error-prone, hard to maintain.
2. **Option B (`safeLaunch` Extension Function):** Clean, lightweight extension on `ActivityResultLauncher<I>` that catches `ActivityNotFoundException` and displays a user Toast/Snackbar.
3. **Option C (`rememberSafeLauncherForActivityResult` Composable Wrapper):** Wraps `rememberLauncherForActivityResult` into a safe launcher object that automatically handles missing provider exceptions and can display an `AlertDialog` or `Toast`.

### Recommended Fix
Combine **Option B & C** into a single `SafeLauncher.kt` utility module providing:
- `ActivityResultLauncher<I>.safeLaunch(input, context, onNotFound)`
- A unified Toast/Dialog fallback message: *"No compatible document provider or file manager is available on this device."*

---

## 6. Implementation Plan

1. Create `SafeLauncher.kt` in `app/src/main/java/com/yourname/pdftoolkit/util/`.
2. Refactor all 27 screen files to use `.safeLaunch(input, context)` for all launcher call sites.
3. Verify zero unsafe launch calls remain.
4. Test project build with `./gradlew :app:assembleFdroidDebug`.
