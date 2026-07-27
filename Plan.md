# Plan

## Step 1: Update OCR Engine to return positions
- Update `OcrEngineResult` to `data class OcrEngineResult(val text: String, val blocks: List<OcrTextBlock>)`.
- `ML Kit (playstore)`: Extract bounding boxes (`element.boundingBox`) and map them to `OcrWord(element.text, OcrBoundingBox(left, top, right, bottom))`. Group them into `OcrTextLine` and `OcrTextBlock`.
- `Tesseract (fdroid/opensource)`: Tesseract `ResultIterator.getBoundingBox(PageIteratorLevel.RIL_WORD)` actually returns an `IntArray` or `Rect` depending on the version. According to tesseract4android 4.9.0, `getBoundingBox` returns `IntArray` of size 4 [left, top, right, bottom] or similar. Need to check its API. Based on common `tesseract4android` API: `val rect = iter.getBoundingBox(PageIteratorLevel.RIL_WORD)` -> `IntArray` or `android.graphics.Rect`. Let's test the return type if possible, or assume `android.graphics.Rect` since Android often uses `Rect` for bounding boxes. Actually `iter.getBoundingBox` in `tesseract4android` usually returns an `IntArray` of `[left, top, right, bottom]` or similar. Wait, it takes `PageIteratorLevel` and returns `IntArray` or `Rect`? Let's check `tesseract4android` source if we can't compile. Wait, `getBoundingBox` takes `level` and returns an `IntArray` with `[left, top, right, bottom]`. I will use `getBoundingBox` to get the word bounding box.

Let's assume `val rect = iter.getBoundingBox(PageIteratorLevel.RIL_WORD)` returns an `IntArray`. Or it might return `Rect` if it's the `cz.adaptech` fork. The best way is to do `val rect = iter.getBoundingBox(PageIteratorLevel.RIL_WORD)` and check its type in Android studio, but we don't have it.
Let's see if we can use a script to find out.

Let's do a quick compile test by writing `val a: IntArray = iter.getBoundingBox(PageIteratorLevel.RIL_WORD)` and see if it fails.

## Step 2: Update `PdfOcrProcessor.kt`
- Extract blocks and use them to put individual words at the correct locations.
- Calculate PDF points = pixels * 72f / dpi.
- Handle Y axis flip.

## Step 3: Implement Full-Page Rasterization in `PdfFlattener.kt`
- Add `rasterizeContent` to `FlattenConfig` and `FlattenUiState`.
- Update `PdfFlattener.kt` to render each page to an image and replace the page content stream with the image.
- Handle DPI safely.
- Add UI toggle in `FlattenScreen.kt`.
- Update result messaging.
