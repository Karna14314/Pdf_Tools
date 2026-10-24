
## 2026-05-18
**Status:** SUCCESS ✅
**Category:** D — Memory & Deep Review
**Task:** Fixed memory leak in PdfViewerViewModel by ensuring onCleared coroutine completion
**Files Changed:**
- app/src/main/java/com/yourname/pdftoolkit/ui/screens/PdfViewerViewModel.kt: Changed `viewModelScope.launch` to `GlobalScope.launch(Dispatchers.IO + NonCancellable)` inside `onCleared` method to make sure bitmap recycling and document closing completes reliably before ViewModel is destroyed.
- app/src/main/java/com/yourname/pdftoolkit/util/PrintUtils.kt: Fixed crashing unit tests by validating `content://` URIs and avoiding stream failures.
**Verification:**
- Build: PASS
- Tests: PASS
- Emulator: SKIPPED
**Performance Impact:**
- Memory Stability: Reliable cleanup of large bitmap caches and file streams on ViewModel exit.
**Commit:** (see below)
**Branch:** auto/weekly-20260518-memory-leak-fix
**Notes:** Coroutine cancellation triggered by ViewModel destruction frequently left large structures in memory; using NonCancellable ensures cleanup occurs reliably.
