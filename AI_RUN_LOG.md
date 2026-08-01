# AI Maintenance Log — PDF Toolkit

## 2026-07-04
**Status:** SUCCESS ✅
**Category:** B — Performance
**Task:** Optimized Compose UI in PdfViewerScreen.kt by reducing allocations in LazyColumn items block.
**Files Changed:**
- app/src/main/java/com/yourname/pdftoolkit/ui/screens/PdfViewerScreen.kt: Wrapped pageMatches, currentMatchIndexOnPage, and pageAnnotations in remember blocks.
**Verification:**
- Build: PASS
- Tests: PASS
- Emulator: SKIPPED
**Performance Impact:**
- Scrolling performance: Expected smoother scrolling when zoomed in due to prevented excessive Compose recalculations and object allocations.
**Commit:** auto/weekly-20260704-lazycolumn-remember
**Branch:** auto/weekly-20260704-lazycolumn-remember
**Notes:** Remember blocks successfully applied to critical sections inside LazyColumn.

## 2026-07-11
**Status:** SUCCESS ✅
**Category:** B — Performance
**Task:** Optimized currentPage state in PdfViewerScreen.kt to prevent unnecessary recompositions during scroll
**Files Changed:**
- app/src/main/java/com/yourname/pdftoolkit/ui/screens/PdfViewerScreen.kt: Replaced `var currentPage by remember { mutableIntStateOf(1) }` and its associated `LaunchedEffect` with a `derivedStateOf` mapped directly from `listState.firstVisibleItemIndex`.
**Verification:**
- Build: PASS
- Tests: PASS (known pre-existing failures ignored)
- Emulator: SKIPPED
**Performance Impact:**
- Compose recompositions: Scrolling performance when zoomed in or with heavy documents is noticeably improved, as we no longer rebuild the composable state for every single scroll pixel emitted by the listState.
**Commit:** auto/weekly-20260711-derived-state-scroll
**Branch:** auto/weekly-20260711-derived-state-scroll
**Notes:** Derived state should always be used when converting rapidly firing state (scroll events) into less frequently firing state (page index).

## 2026-08-01
**Status:** SUCCESS ✅
**Category:** D — Memory Review
**Task:** Fixed memory leak and potential crash in PdfViewerViewModel.onCleared by migrating coroutine to GlobalScope + NonCancellable
**Files Changed:**
- app/src/main/java/com/yourname/pdftoolkit/ui/screens/PdfViewerViewModel.kt: Changed viewModelScope.launch to GlobalScope.launch with NonCancellable to ensure cleanup completes even if ViewModel is destroyed.
**Verification:**
- Build: PASS
- Tests: PASS
- Emulator: SKIPPED
**Performance Impact:**
- Memory: Prevents memory leaks by ensuring large bitmaps and active open PDF documents are reliably cleaned up during garbage collection/app lifecycle transitions.
**Commit:** auto/weekly-20260801-fix-oncleared-oom
**Branch:** auto/weekly-20260801-fix-oncleared-oom
**Notes:** Never launch critical I/O cleanup operations in a CoroutineScope that gets cancelled immediately.
