# Tesseract Migration Audit and Plan

## 1. Audit of `tess-two` / `TessBaseAPI` Usage

The following usages of `tess-two` and `TessBaseAPI` were found in the codebase:

### Dependencies
- `app/build.gradle.kts:259`: `"fdroidImplementation"("com.rmtheis:tess-two:9.1.0")`
- `app/build.gradle.kts:266`: `"opensourceImplementation"("com.rmtheis:tess-two:9.1.0")`

### Source Files
**1. `app/src/opensource/java/com/yourname/pdftoolkit/domain/operations/OcrEngine.kt`**
- Import: `import com.googlecode.tesseract.android.TessBaseAPI`
- Variable: `private var tessBaseAPI: TessBaseAPI? = null`
- Instantiation: `tessBaseAPI = TessBaseAPI()`

**2. `app/src/fdroid/java/com/yourname/pdftoolkit/domain/operations/OcrEngine.kt`**
- Import: `import com.googlecode.tesseract.android.TessBaseAPI`
- Variable: `private var tessBaseAPI: TessBaseAPI? = null`
- Instantiation: `tessBaseAPI = TessBaseAPI()`

## 2. Source Sets Identification

The `tess-two` dependency is appropriately scoped to:
- `fdroid`
- `opensource`

It is **not** used in the `playstore` source set (which uses Google ML Kit for OCR according to project context).

## 3. Native Lifecycle Calls and TrainedData Logic

### Native Lifecycle Calls (in `OcrEngine.kt`)
- **Init**: `tessBaseAPI?.init(context.filesDir.absolutePath, "eng")`
  - *Tesseract4Android 4.9.0 provides `boolean init(String datapath, String language)` which uses the default OCR Engine Mode. It functions identically to the old one.*
- **Set Image**: `tessBaseAPI?.setImage(bitmap)`
  - *Tesseract4Android 4.9.0 provides `void setImage(Bitmap bmp)`. It functions identically to the old one. We are currently passing a Bitmap, not a Pix.*
- **Get Result**: `tessBaseAPI?.utF8Text`
  - *Functions identically.*
- **Recycle/Stop/End**: `tessBaseAPI?.end()` (in `close()` method)
  - *Tesseract4Android no longer uses `end()`. The correct method to use to clean up native memory is `recycle()`. The plan will include replacing `end()` with `recycle()`.*

*Note: I have run a full grep for `TessBaseAPI` and `tessBaseAPI` across the codebase. The above are the ONLY calls being made to the API. There are no usages of `stop()`, listeners, or other methods.*

### TrainedData Path Logic (in `OcrEngine.kt`)
- `tessdata` directory is created in `context.filesDir`: `File(context.filesDir, "tessdata")`
- The model `eng.traineddata` is copied from `assets/tessdata/eng.traineddata` to the local `tessdata` directory.
- `init` is called with the parent directory path (`context.filesDir.absolutePath`), matching Tesseract's requirement that the provided path must contain a `tessdata` subdirectory containing `*.traineddata`.

## 4. Migration Plan to Tesseract4Android

**Active Fork:** `adaptech-cz/Tesseract4Android` is actively maintained.

### A. Gradle Dependency Swap
- Remove:
  ```kotlin
  "fdroidImplementation"("com.rmtheis:tess-two:9.1.0")
  "opensourceImplementation"("com.rmtheis:tess-two:9.1.0")
  ```
- Add (in `app/build.gradle.kts`):
  ```kotlin
  "fdroidImplementation"("cz.adaptech:tesseract4android:4.9.0")
  "opensourceImplementation"("cz.adaptech:tesseract4android:4.9.0")
  ```
- *Note:* We will use the standard artifact `cz.adaptech:tesseract4android:4.9.0`, NOT the `-openmp` variant, to save APK size and due to OpenMP's multithreading not being strictly required for our single-instance use case on mobile.
- Ensure `maven { url = uri("https://jitpack.io") }` is added to `settings.gradle.kts` under `dependencyResolutionManagement`.

### B. API Differences vs tess-two
- Update `close()` method in both `opensource` and `fdroid` versions of `OcrEngine.kt`:
  - Change `tessBaseAPI?.end()` to `tessBaseAPI?.recycle()`.
- The `init()` and `setImage(Bitmap)` signatures match exactly what we are currently using.

### C. ABI Coverage & 16KB Page-Size Alignment
- `Tesseract4Android` covers modern ABIs: `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`.
- Tesseract4Android version 4.9.0 includes modern NDK builds that natively support 16KB page-size alignment required for Android 15+.

### D. TrainedData Path/Format Changes
- The path logic in the current code (`context.filesDir` pointing to `context.filesDir/tessdata`) is correct and compatible with `Tesseract4Android`.
- The existing model in assets will be used.

### E. Verification and Testing
- Run test builds for both `fdroid` and `opensource` flavors to verify compilation.
- **Runtime Native Loading Smoke Test:** The most critical failure vector for NDK libraries is at runtime (e.g. `UnsatisfiedLinkError`). To ensure we've fully solved the underlying issue and are successfully loading the native libraries on modern ABIs, we must execute an instrumentation test on-device.
- I will create or locate a basic instrumentation test for `OcrEngine.kt` and run it via `./gradlew connectedFdroidDebugAndroidTest` (or similar command) on an active emulator/device to verify that `tessBaseAPI?.init()` and `tessBaseAPI?.recognizeText()` execute without native crashes.
