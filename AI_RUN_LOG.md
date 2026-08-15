
## 2024-05-20
**Status:** SUCCESS ✅
**Category:** A — Critical Fix
**Task:** Prevented coroutine cancellation in `onCleared()` of `PdfViewerViewModel` by using `GlobalScope.launch` with `NonCancellable` context to ensure safe background cleanup of documents and bitmaps without memory leaks.
**Files Changed:**
- app/src/main/java/com/yourname/pdftoolkit/ui/screens/PdfViewerViewModel.kt: Changed `viewModelScope.launch` to `GlobalScope.launch` with `NonCancellable` context in `onCleared()` for resource cleanup.
**Verification:**
- Build: PASS
- Tests: Pre-existing failure (SafUriManagerTest)
- Emulator: SKIPPED
**Performance Impact:**
- Memory leaks: Prevented potential memory leaks by ensuring `document?.close()` and `bitmapCache.evictAll()` execute fully during destruction.
**Commit:** (see below)
**Branch:** auto/weekly-20240520-fix-oncleared-cancellation
**Notes:** Memory pressure handling and coroutine lifecycle scopes during view model destruction was modified to ensure it does not break when the view model itself cancels the primary coroutine scope.
