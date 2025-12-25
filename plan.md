for implementing this plan structure it for the ai code editor to proceed with validation across each stage # 🎯 FINAL AI-ASSISTED BUILD PLAN
## 📋 Executive Summary
**Goal:** Build a production-ready Android PDF toolkit using AI code editors with maximum efficiency and minimal errors.
**Timeline:** 4-6 weeks of AI-assisted development  
**Output:** Polished Android app ready for Play Store  
**Strategy:** Incremental builds with AI validation at each checkpoint
---
## 🏗️ PHASE-BY-PHASE EXECUTION PLAN
---
## **PHASE 0: Foundation Setup** (Days 1-2)
### Objectives
- ✅ Initialize project structure
- ✅ Configure dependencies correctly
- ✅ Set up build configurations
- ✅ Verify library compatibility
### AI Code Editor Tasks
#### **Task 0.1: Project Initialization**
**Prompt for AI:**
```
Create a new Android project with these specifications:
PROJECT SETUP:
- Name: PDF Toolkit
- Package: com.yourname.pdftoolkit
- Minimum SDK: 24
- Target SDK: 34
- Kotlin DSL for Gradle
- Jetpack Compose for UI
- Material3 design system
STRUCTURE:
app/
├── src/main/
│   ├── java/com/yourname/pdftoolkit/
│   │   ├── ui/              (Compose UI)
│   │   ├── domain/          (Business logic)
│   │   ├── data/            (File operations)
│   │   └── MainActivity.kt
│   ├── res/
│   └── AndroidManifest.xml
└── build.gradle.kts
GRADLE CONFIGURATION:
- Enable R8 full mode
- Enable resource shrinking
- Set up ProGuard rules for PDFBox
- Configure 16KB page size compatibility
Generate the complete project structure with all necessary files.
```
#### **Task 0.2: Dependency Integration**
**Prompt for AI:**
```
Add these dependencies to build.gradle.kts (app module):
PRIMARY LIBRARIES:
1. PdfBox-Android (2.0.27.0) - Apache 2.0 license
   - PDF manipulation engine
   - Merge, split, extract operations
2. Alamin5G-PDF-Viewer (1.0.16) - MIT license
   - PDF viewing with zoom/gestures
   - 16KB page size compatible
JETPACK COMPOSE:
- Compose BOM (2024.12.01)
- Material3
- Navigation Compose
- Activity Compose
- ViewModel Compose
COROUTINES:
- kotlinx-coroutines-android
- kotlinx-coroutines-core
FILE ACCESS:
- AndroidX DocumentFile
IMPORTANT CONFIGURATIONS:
- Add JitPack repository
- Configure packaging options to exclude duplicate libraries
- Set up ProGuard rules to keep PDFBox classes
- Enable 16KB compatibility flags
Include complete build.gradle.kts with all necessary configurations.
Also provide proguard-rules.pro file.
```
#### **Task 0.3: Permissions & Manifest**
**Prompt for AI:**
```
Configure AndroidManifest.xml with:
PERMISSIONS:
- Read external storage (conditional on API level)
- Write external storage (conditional on API level)
- Internet (for potential future features)
APPLICATION CONFIGURATION:
- Enable hardware acceleration
- Support for 16KB page size
- File provider for sharing PDFs
- Intent filters for PDF file opening
Also create res/xml/file_paths.xml for FileProvider.
Generate complete AndroidManifest.xml with proper API level conditionals.
```
---
## **PHASE 1: Core Infrastructure** (Days 3-5)
### Objectives
- ✅ File picker working
- ✅ PDFBox initialized correctly
- ✅ Basic PDF viewer integrated
- ✅ Navigation structure ready
### AI Code Editor Tasks
#### **Task 1.1: File Picker Implementation**
**Prompt for AI:**
```
Create a robust file picker system for Android using Storage Access Framework (SAF):
FILE: data/FileManager.kt
REQUIREMENTS:
1. Pick single PDF file
2. Pick multiple PDF files
3. Save PDF file with custom name
4. Get file URI, name, size
5. Handle permissions properly for API 21+
6. Use ActivityResultContracts API (modern approach)
FEATURES:
- Error handling for permission denied
- File validation (check if actually PDF)
- Size validation (warn if > 50MB)
- Copy URI content to app cache if needed
- Clean up cache files properly
Return complete FileManager.kt with:
- Sealed class FilePickerResult
- Error types enum
- Extension functions for Context
- Proper coroutine support
Include usage example in comments.
```
#### **Task 1.2: PDFBox Initialization**
**Prompt for AI:**
```
Create a PDFBox initialization system:
FILE: domain/PdfBoxInitializer.kt
REQUIREMENTS:
1. Initialize PDFBoxResourceLoader with Application context
2. Handle initialization errors
3. Create singleton instance
4. Verify initialization success
5. Log initialization status
Also create:
FILE: PdfToolkitApplication.kt (Application class)
- Initialize PDFBox in onCreate()
- Set up crash handlers
- Configure strict mode for debug builds
Ensure initialization happens before any PDFBox operations.
Include error handling and logging.
```
#### **Task 1.3: PDF Viewer Integration**
**Prompt for AI:**
```
Integrate Alamin5G-PDF-Viewer into Compose UI:
FILE: ui/viewer/PdfViewerScreen.kt
CREATE COMPOSABLE:
@Composable
fun PdfViewerScreen(
    pdfUri: Uri,
    onBack: () -> Unit,
    onError: (String) -> Unit
)
FEATURES:
1. Display PDF with Alamin5G-PDF-Viewer
2. Show loading indicator while PDF loads
3. Show page counter (current page / total pages)
4. Zoom controls (+ / - buttons)
5. Page navigation (previous / next)
6. Error handling with user-friendly messages
7. Back button
COMPOSE INTEGRATION:
- Use AndroidView to wrap PDFView
- Handle lifecycle properly (remember/DisposableEffect)
- Manage viewer state in ViewModel
- Clean up resources on dispose
Include:
- PdfViewerViewModel.kt
- Error handling sealed class
- Loading states
Ensure smooth integration with Compose navigation.
```
#### **Task 1.4: Navigation Structure**
**Prompt for AI:**
```
Create complete navigation structure with Jetpack Compose Navigation:
FILE: ui/navigation/NavGraph.kt
SCREENS NEEDED:
1. HomeScreen - Tool categories
2. PdfViewerScreen - View PDFs
3. MergeScreen - Merge multiple PDFs
4. SplitScreen - Split PDF pages
5. ExtractScreen - Extract specific pages
6. RotateScreen - Rotate pages
7. CompressScreen - Compress PDF
8. SettingsScreen - App settings
NAVIGATION SETUP:
- Define sealed class for routes
- Create NavHost with all destinations
- Pass arguments (URIs, settings) between screens
- Handle back stack properly
- Deep linking support for PDF files
CREATE FILES:
1. navigation/AppNavigation.kt - NavHost setup
2. navigation/Screen.kt - Screen sealed class
3. MainActivity.kt - Complete implementation with navigation
Include proper state management and argument passing.
```
---
## **PHASE 2: Core Operations** (Days 6-12)
### Objectives
- ✅ Merge PDFs working
- ✅ Split PDFs working
- ✅ Extract pages working
- ✅ All operations tested & validated
### AI Code Editor Tasks
#### **Task 2.1: Merge Operation**
**Prompt for AI:**
```
Implement PDF merge functionality with complete UI:
FILE 1: domain/operations/MergeOperation.kt
CREATE CLASS:
class PdfMerger {
    suspend fun mergePdfs(
        inputUris: List<Uri>,
        outputUri: Uri,
        context: Context,
        progressCallback: (Int) -> Unit
    ): Result<Unit>
}
REQUIREMENTS:
1. Use PDFBox PDFMergerUtility
2. Process URIs with ContentResolver
3. Show progress (0-100%)
4. Handle errors gracefully
5. Clean up resources
6. Work on IO dispatcher
7. Validate input files
8. Check output space available
FILE 2: ui/merge/MergeScreen.kt
CREATE UI:
- File picker button (select multiple PDFs)
- List of selected files (reorderable drag-drop)
- Preview thumbnails (first page of each PDF)
- Merge button
- Progress bar during merge
- Success/error messages
- Option to view merged PDF
FILE 3: ui/merge/MergeViewModel.kt
STATE MANAGEMENT:
- Selected files list
- Loading states
- Error states
- Progress tracking
- Success handling
Include complete error handling and user feedback.
```
#### **Task 2.2: Split Operation**
**Prompt for AI:**
```
Implement PDF split functionality with multiple modes:
FILE 1: domain/operations/SplitOperation.kt
CREATE CLASS:
class PdfSplitter {
    // Split into individual pages
    suspend fun splitAllPages(
        inputUri: Uri,
        outputDir: Uri,
        context: Context,
        progressCallback: (Int) -> Unit
    ): Result<List<Uri>>
    
    // Split by page ranges
    suspend fun splitByRanges(
        inputUri: Uri,
        ranges: List<PageRange>,
        outputDir: Uri,
        context: Context
    ): Result<List<Uri>>
    
    // Split every N pages
    suspend fun splitEveryNPages(
        inputUri: Uri,
        n: Int,
        outputDir: Uri,
        context: Context
    ): Result<List<Uri>>
}
data class PageRange(val start: Int, val end: Int)
REQUIREMENTS:
1. Use PDFBox Splitter class
2. Handle large PDFs (100+ pages)
3. Memory-efficient processing
4. Proper file naming (input_page_001.pdf)
5. Progress tracking
6. Cleanup on error
FILE 2: ui/split/SplitScreen.kt
CREATE UI WITH TABS:
Tab 1: Split All (one file per page)
Tab 2: Split by Range (custom ranges)
Tab 3: Split Every N (batch split)
FEATURES:
- PDF preview with page selector
- Range input (visual selector)
- Output folder picker
- Split button
- Progress indicator
- Result summary (X files created)
Include ViewModel and complete state management.
```
#### **Task 2.3: Extract Pages Operation**
**Prompt for AI:**
```
Implement page extraction with visual page selector:
FILE 1: domain/operations/ExtractOperation.kt
CREATE CLASS:
class PageExtractor {
    suspend fun extractPages(
        inputUri: Uri,
        pageNumbers: List<Int>, // 0-indexed
        outputUri: Uri,
        context: Context,
        progressCallback: (Int) -> Unit
    ): Result<Unit>
}
REQUIREMENTS:
1. Use PDFBox page extraction
2. Validate page numbers
3. Maintain page order
4. Copy metadata
5. Handle annotations
6. Memory-efficient
FILE 2: ui/extract/ExtractScreen.kt
CREATE UI:
- PDF viewer showing all pages as thumbnails
- Multi-select checkboxes on each page
- Select All / Deselect All buttons
- Page counter (X of Y selected)
- Preview selected pages
- Extract button
- Save location picker
FEATURES:
- Grid layout for thumbnails (2-3 columns)
- Lazy loading for large PDFs
- Selection state persistence
- Reorder selected pages (drag & drop)
Include PageThumbnailGenerator utility class.
```
#### **Task 2.4: Operation Testing Framework**
**Prompt for AI:**
```
Create testing utilities for all PDF operations:
FILE: domain/testing/OperationValidator.kt
CREATE UTILITIES:
1. validatePdfFile(uri) - Check if valid PDF
2. comparePdfPages(original, result) - Verify operation
3. checkPdfIntegrity(uri) - Ensure not corrupted
4. measureOperationTime(operation) - Performance tracking
5. simulateOperation(type, input) - Dry run
FILE: domain/testing/TestDataGenerator.kt
CREATE TEST HELPERS:
1. generateSamplePdf(pages) - Create test PDF
2. generateCorruptedPdf() - Test error handling
3. generateLargePdf(pages) - Performance testing
4. createPageRanges(count) - Test split operations
Include unit tests for each operation class.
```
---
## **PHASE 3: Advanced Operations** (Days 13-18)
### Objectives
- ✅ Rotate pages
- ✅ Reorder pages
- ✅ Delete pages
- ✅ Compress PDF
- ✅ Images ↔ PDF
### AI Code Editor Tasks
#### **Task 3.1: Rotate Pages**
**Prompt for AI:**
```
Implement page rotation with visual preview:
FILE 1: domain/operations/RotateOperation.kt
CREATE CLASS:
class PageRotator {
    suspend fun rotatePages(
        inputUri: Uri,
        rotations: Map<Int, Int>, // pageIndex -> degrees (90, 180, 270)
        outputUri: Uri,
        context: Context
    ): Result<Unit>
    
    suspend fun rotateAllPages(
        inputUri: Uri,
        degrees: Int,
        outputUri: Uri,
        context: Context
    ): Result<Unit>
}
FILE 2: ui/rotate/RotateScreen.kt
UI FEATURES:
- PDF page thumbnails in grid
- Rotate buttons on each page (90°, 180°, 270°)
- Bulk rotate all pages option
- Live preview of rotation
- Undo/redo rotation
- Apply button
Include rotation state management and preview rendering.
```
#### **Task 3.2: Reorder & Delete Pages**
**Prompt for AI:**
```
Implement drag-drop page reordering and deletion:
FILE 1: domain/operations/ReorderOperation.kt
CREATE CLASS:
class PageReorderer {
    suspend fun reorderPages(
        inputUri: Uri,
        newOrder: List<Int>, // new page sequence
        outputUri: Uri,
        context: Context
    ): Result<Unit>
    
    suspend fun deletePages(
        inputUri: Uri,
        pagesToDelete: Set<Int>,
        outputUri: Uri,
        context: Context
    ): Result<Unit>
}
FILE 2: ui/reorder/ReorderScreen.kt
UI FEATURES:
- Draggable page thumbnails (LazyVerticalGrid)
- Use Compose drag-and-drop APIs
- Delete checkbox on each page
- Multi-select for bulk delete
- Reorder indicator (visual feedback)
- Preview final order
- Undo changes
COMPOSE DRAG-DROP:
- Use Modifier.draggable or reorderable library
- Animate page movements
- Show drop zones
- Handle scroll while dragging
Include complete drag-drop implementation with animations.
```
#### **Task 3.3: Compress PDF**
**Prompt for AI:**
```
Implement PDF compression with quality options:
FILE 1: domain/operations/CompressOperation.kt
CREATE CLASS:
enum class CompressionLevel {
    LOW,      // Slight compression, high quality
    MEDIUM,   // Balanced
    HIGH,     // Maximum compression, lower quality
    CUSTOM    // User-defined settings
}
class PdfCompressor {
    suspend fun compressPdf(
        inputUri: Uri,
        outputUri: Uri,
        level: CompressionLevel,
        context: Context,
        progressCallback: (Int, Long) -> Unit // progress, current size
    ): Result<CompressionResult>
}
data class CompressionResult(
    val originalSize: Long,
    val compressedSize: Long,
    val compressionRatio: Float,
    val timeTaken: Long
)
COMPRESSION TECHNIQUES:
1. Image downsampling (reduce image DPI)
2. JPEG compression for images
3. Remove duplicate resources
4. Flatten layers
5. Remove metadata (optional)
6. Optimize font embedding
FILE 2: ui/compress/CompressScreen.kt
UI FEATURES:
- File size display (before)
- Compression level selector (slider)
- Preview quality
- Estimated size reduction
- Compress button
- Result: before/after comparison
- Option to compare visually
Include image processing and quality preview.
```
#### **Task 3.4: Images ↔ PDF Conversion**
**Prompt for AI:**
```
Implement bidirectional image-PDF conversion:
FILE 1: domain/operations/ImageConversion.kt
CREATE CLASSES:
class ImageToPdfConverter {
    suspend fun convertImagesToPdf(
        imageUris: List<Uri>,
        outputUri: Uri,
        pageSize: PageSize, // A4, Letter, Custom
        quality: Int, // 1-100
        context: Context
    ): Result<Unit>
}
class PdfToImageConverter {
    suspend fun convertPdfToImages(
        inputUri: Uri,
        outputDir: Uri,
        format: ImageFormat, // PNG, JPEG
        dpi: Int, // 72, 150, 300
        context: Context,
        progressCallback: (Int) -> Unit
    ): Result<List<Uri>>
}
enum class PageSize { A4, LETTER, LEGAL, CUSTOM }
enum class ImageFormat { PNG, JPEG, WEBP }
FILE 2: ui/convert/ImageToPdfScreen.kt
UI FEATURES:
- Image picker (multiple)
- Draggable image list (reorder)
- Page size selector
- Quality slider
- Preview layout
- Convert button
FILE 3: ui/convert/PdfToImageScreen.kt
UI FEATURES:
- PDF selector
- Format choice (PNG/JPEG)
- DPI selector (quality)
- Page range selector (all or specific)
- Output folder picker
- Progress with page count
Include image processing with proper quality handling.
```
---
## **PHASE 4: Polish & Production** (Days 19-24)
### Objectives
- ✅ Error handling perfected
- ✅ Progress indicators everywhere
- ✅ Settings screen
- ✅ Performance optimization
- ✅ Memory leak fixes
### AI Code Editor Tasks
#### **Task 4.1: Comprehensive Error Handling**
**Prompt for AI:**
```
Create app-wide error handling system:
FILE 1: domain/error/AppError.kt
CREATE SEALED CLASS:
sealed class AppError {
    data class FileError(val message: String, val uri: Uri?) : AppError()
    data class PermissionError(val permission: String) : AppError()
    data class OperationError(val operation: String, val cause: Throwable) : AppError()
    data class StorageError(val requiredSpace: Long, val availableSpace: Long) : AppError()
    data class CorruptedFileError(val uri: Uri) : AppError()
    data class NetworkError(val message: String) : AppError()
    object UnknownError : AppError()
}
FILE 2: ui/components/ErrorDialog.kt
CREATE COMPOSABLE:
@Composable
fun ErrorDialog(
    error: AppError,
    onDismiss: () -> Unit,
    onRetry: (() -> Unit)? = null
)
FEATURES:
- User-friendly error messages
- Technical details (expandable)
- Retry button for recoverable errors
- Copy error log button
- Report issue button (email intent)
FILE 3: domain/error/ErrorHandler.kt
CREATE UTILITY:
object ErrorHandler {
    fun handleError(error: Throwable): AppError
    fun logError(error: AppError)
    fun shouldRetry(error: AppError): Boolean
    fun getUserMessage(error: AppError): String
}
Include logging and crash reporting setup.
```
#### **Task 4.2: Progress Indicators System**
**Prompt for AI:**
```
Create unified progress tracking system:
FILE 1: ui/components/OperationProgress.kt
CREATE COMPOSABLES:
1. LinearProgressWithLabel(progress: Float, label: String)
2. CircularProgressWithPercentage(progress: Float)
3. IndeterminateProgress(message: String)
4. StepProgress(currentStep: Int, totalSteps: Int, stepNames: List<String>)
FILE 2: domain/progress/ProgressTracker.kt
CREATE CLASS:
class ProgressTracker {
    private val _progress = MutableStateFlow<ProgressState>(ProgressState.Idle)
    val progress: StateFlow<ProgressState> = _progress.asStateFlow()
    
    fun updateProgress(current: Int, total: Int, message: String)
    fun setIndeterminate(message: String)
    fun complete()
    fun error(message: String)
}
sealed class ProgressState {
    object Idle : ProgressState()
    data class Loading(val progress: Int, val total: Int, val message: String) : ProgressState()
    data class Indeterminate(val message: String) : ProgressState()
    object Complete : ProgressState()
    data class Error(val message: String) : ProgressState()
}
Integrate progress tracking into all operations.
```
#### **Task 4.3: Settings Screen**
**Prompt for AI:**
```
Create comprehensive settings screen:
FILE 1: data/preferences/AppPreferences.kt
CREATE DATASTORE:
class AppPreferences(context: Context) {
    private val dataStore = context.createDataStore("settings")
    
    val defaultCompressionLevel: Flow<CompressionLevel>
    val defaultImageQuality: Flow<Int>
    val autoCleanupCache: Flow<Boolean>
    val showTutorials: Flow<Boolean>
    val theme: Flow<ThemeMode>
    
    suspend fun updateSetting(key: String, value: Any)
}
enum class ThemeMode { LIGHT, DARK, SYSTEM }
FILE 2: ui/settings/SettingsScreen.kt
CREATE UI WITH SECTIONS:
SECTION 1: OPERATIONS
- Default compression level
- Default image quality
- Default page size for conversions
- Keep original files after operation
SECTION 2: STORAGE
- Auto-cleanup temporary files
- Cache size limit
- Default output location
SECTION 3: APPEARANCE
- Theme (Light/Dark/System)
- Show operation tutorials
- Animation preferences
SECTION 4: ADVANCED
- Enable crash reporting
- Developer options (logging)
- Clear cache button
- Reset settings button
SECTION 5: ABOUT
- App version
- Libraries used (with licenses)
- Privacy policy
- Rate app
- Contact support
Include preferences persistence and immediate UI updates.
```
#### **Task 4.4: Performance Optimization**
**Prompt for AI:**
```
Optimize app performance comprehensively:
FILE 1: domain/optimization/MemoryManager.kt
CREATE UTILITY:
object MemoryManager {
    fun checkAvailableMemory(): Long
    fun cleanupCache(context: Context)
    fun getOptimalCacheSize(): Int
    fun monitorMemoryUsage(): Flow<MemoryState>
}
data class MemoryState(
    val used: Long,
    val available: Long,
    val total: Long,
    val isLowMemory: Boolean
)
FILE 2: domain/optimization/BitmapPool.kt
CREATE BITMAP RECYCLING:
class BitmapPool {
    fun getBitmap(width: Int, height: Int): Bitmap
    fun recycleBitmap(bitmap: Bitmap)
    fun clear()
}
FILE 3: Performance Optimizations Checklist
APPLY THESE OPTIMIZATIONS:
1. COMPOSE OPTIMIZATIONS:
   - Add @Stable annotations
   - Use remember and derivedStateOf
   - Implement LazyColumn item keys
   - Use immutable collections
2. COROUTINE OPTIMIZATIONS:
   - Use appropriate dispatchers
   - Cancel jobs on screen exit
   - Use supervisorScope for parallel ops
   - Implement proper flow collection
3. MEMORY OPTIMIZATIONS:
   - Recycle bitmaps after use
   - Use WeakReference for caches
   - Implement LRU cache for thumbnails
   - Cleanup temp files regularly
4. FILE I/O OPTIMIZATIONS:
   - Use buffered streams
   - Process large files in chunks
   - Implement streaming for operations
   - Use memory-mapped files where appropriate
5. UI OPTIMIZATIONS:
   - Use LazyColumn instead of Column
   - Implement pagination for large lists
   - Use AsyncImage for thumbnails
   - Enable hardware acceleration
Create optimized versions of all operation classes.
Include benchmarking tools to measure improvements.
```
#### **Task 4.5: Memory Leak Detection & Fixes**
**Prompt for AI:**
```
Audit entire codebase for memory leaks and fix them:
FILE 1: domain/testing/MemoryLeakDetector.kt
CREATE TESTING UTILITY:
object MemoryLeakDetector {
    fun checkForLeaks(context: Context)
    fun logLargeObjects()
    fun profileMemoryUsage()
}
FILE 2: Leak Fixes Checklist
COMMON LEAK SOURCES TO FIX:
1. COMPOSABLE LEAKS:
   - Ensure DisposableEffect cleanup
   - Cancel coroutines in onDispose
   - Remove listeners in cleanup
   - Clear references in remember blocks
2. VIEWMODEL LEAKS:
   - Cancel all coroutine jobs
   - Clear Flow collectors
   - Remove observers
   - Null out heavy objects
3. CONTEXT LEAKS:
   - Use applicationContext not activity context
   - Avoid storing activity references
   - Use WeakReference when needed
   - Clear static references
4. BITMAP LEAKS:
   - Call bitmap.recycle() when done
   - Clear all bitmap caches
   - Remove view references to bitmaps
   - Use BitmapPool for reuse
5. FILE LEAKS:
   - Close all InputStreams
   - Close all OutputStreams
   - Close PDDocument objects
   - Clean up temp files
6. PDFBOX LEAKS:
   - Close all PDDocument instances
   - Close PDFRenderer instances
   - Clear page caches
   - Cleanup resource loader
Audit and fix all files in the codebase.
Add LeakCanary for debugging builds.
```
---
## **PHASE 5: Testing & Validation** (Days 25-28)
### Objectives
- ✅ All operations tested with real PDFs
- ✅ Edge cases covered
- ✅ Performance benchmarked
- ✅ User testing completed
### AI Code Editor Tasks
#### **Task 5.1: Integration Testing Suite**
**Prompt for AI:**
```
Create comprehensive integration tests:
FILE: app/src/androidTest/IntegrationTests.kt
CREATE TEST SUITE:
TEST CATEGORY 1: OPERATION TESTS
- testMergeTwoPdfs()
- testMergeMultipleLargePdfs()
- testSplitPdfAllPages()
- testSplitPdfByRanges()
- testExtractSpecificPages()
- testRotateAllPages()
- testRotateSpecificPages()
- testReorderPages()
- testDeletePages()
- testCompressPdf()
- testImagesToPdf()
- testPdfToImages()
TEST CATEGORY 2: ERROR HANDLING
- testCorruptedPdfHandling()
- testInvalidFileFormatHandling()
- testInsufficientStorageHandling()
- testPermissionDeniedHandling()
- testOutOfMemoryHandling()
TEST CATEGORY 3: EDGE CASES
- testEmptyPdfHandling()
- testSinglePagePdfOperations()
- testLargePdf100PlusPages()
- testPasswordProtectedPdf()
- testPdfWithFormsAndAnnotations()
- testPdfWithImages()
TEST CATEGORY 4: PERFORMANCE
- testMerge10PdfsUnder5Seconds()
- testSplit50PagePdfUnder10Seconds()
- testCompressPdfUnder30Seconds()
- testMemoryUsageUnder100MB()
Include test helpers and mock data generators.
```
#### **Task 5.2: UI Testing**
**Prompt for AI:**
```
Create UI tests for all screens:
FILE: app/src/androidTest/UiTests.kt
CREATE TEST SUITE:
SCREEN TESTS:
- testHomeScreenNavigation()
- testFilePickerFlow()
- testMergeScreenFlow()
- testSplitScreenFlow()
- testExtractScreenFlow()
- testViewerScreenFunctionality()
- testSettingsScreenUpdates()
INTERACTION TESTS:
- testDragAndDropReordering()
- testMultipleFileSelection()
- testProgressIndicatorDisplay()
- testErrorDialogDisplay()
- testSuccessSnackbarDisplay()
Use Compose testing APIs:
- ComposeTestRule
- onNodeWithText
- onNodeWithTag
- performClick, performTextInput
- assertIsDisplayed, assertTextEquals
Include screenshot tests for visual regression.
```
#### **Task 5.3: Real-World Testing Protocol**
**Prompt for AI:**
```
Create testing protocol document:
FILE: docs/TESTING_PROTOCOL.md
MANUAL TESTING CHECKLIST:
PHASE 1: BASIC OPERATIONS (Day 25)
- [ ] Merge 2 small PDFs (< 1MB each)
- [ ] Merge 5 medium PDFs (1-5MB each)
- [ ] Merge 10 large PDFs (5-20MB each)
- [ ] Split 10-page PDF into individual pages
- [ ] Split 50-page PDF by custom ranges
- [ ] Extract pages 1, 5, 10 from 20-page PDF
- [ ] Extract all even pages
- [ ] Rotate all pages 90° clockwise
- [ ] Rotate specific pages 180°
- [ ] Reorder pages in 10-page PDF
- [ ] Delete pages from middle of PDF
- [ ] Compress 20MB PDF with high quality
- [ ] Compress 20MB PDF with maximum compression
- [ ] Convert 5 images to single PDF
- [ ] Convert 10-page PDF to PNG images
PHASE 2: STRESS TESTING (Day 26)
- [ ] Merge 20 PDFs (500+ pages total)
- [ ] Split 300-page PDF into individual pages
- [ ] Extract 100 random pages from 500-page PDF
- [ ] Compress 100MB PDF
- [ ] Convert 50 images to PDF
- [ ] Convert 100-page PDF to images
- [ ] Process while battery < 20%
- [ ] Process while on mobile data
- [ ] Process while storage nearly full
PHASE 3: EDGE CASES (Day 27)
- [ ] Process PDF with forms
- [ ] Process PDF with annotations
- [ ] Process PDF with embedded fonts
- [ ] Process PDF with hyperlinks
- [ ] Process scanned PDF (images only)
- [ ] Process rotated PDF
- [ ] Process encrypted PDF (if supported)
- [ ] Process corrupted PDF
- [ ] Process empty PDF
- [ ] Process single-page PDF
PHASE 4: REAL USER SCENARIOS (Day 28)
- [ ] Student: Merge lecture notes from 10 files
- [ ] Professional: Extract invoice pages from 50-page statement
- [ ] Teacher: Split exam paper into individual questions
- [ ] Business: Compress presentation from 50MB to <10MB
- [ ] Designer: Convert portfolio images to PDF
- [ ] Photographer: Convert PDF catalog to images for web
VALIDATION CRITERIA:
- Operation completes without crash
- Output file is valid and opens correctly
- File size is reasonable
- Operation time is acceptable
- Memory usage stays below 200MB
- No data loss or corruption
- Error messages are clear
- Progress indicator is accurate
Include bug report template.
```
---
## **PHASE 6: Final Polish** (Days 29-30)
### Objectives
- ✅ App icon & branding
- ✅ Splash screen
- ✅ Onboarding tutorial
- ✅ App store assets prepared
- ✅ Final APK built
### AI Code Editor Tasks
#### **Task 6.1: App Icon & Branding**
**Prompt for AI:**
```
Create branding assets:
FILE 1: Design specifications
APP ICON REQUIREMENTS:
- Adaptive icon (foreground + background)
- Support for Android 8+ adaptive icons
- Monochrome icon for themed icons (Android 13+)
- Legacy icon for older devices
- Multiple densities (mdpi, hdpi, xhdpi, xxhdpi, xxxhdpi)
ICON CONCEPT:
- PDF document symbol
- Tools/gear iconography
- Modern, minimalist design
- Colors: Primary color #2196F3 (blue), Accent #FF5722 (orange)
Create res/mipmap-* folders with:
- ic_launcher.png (legacy)
- ic_launcher_foreground.xml (vector)
- ic_launcher_background.xml (vector)
- ic_launcher_monochrome.xml (vector)
FILE 2: res/values/colors.xml
Define complete color palette:
- Primary, secondary, tertiary colors
- Light and dark theme colors
- Error