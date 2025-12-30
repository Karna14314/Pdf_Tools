# PDF Toolkit - Project Context

## Project Overview

**PDF Toolkit** is a comprehensive Android application for PDF manipulation, built with Kotlin and Jetpack Compose. The app provides 25+ PDF tools including merge, split, compress, convert, security, and annotation features. All operations work completely offline with no cloud dependencies.

## Technical Stack

### Core Technologies
- **Language**: Kotlin
- **UI Framework**: Jetpack Compose with Material Design 3
- **Min SDK**: 26 (Android 8.0)
- **Target SDK**: 35 (Android 15)
- **Compile SDK**: 35
- **Build System**: Gradle with Kotlin DSL
- **Architecture**: MVVM with Jetpack Compose

### Key Dependencies
- **PDF Manipulation**: `com.tom-roush:pdfbox-android:2.0.27.0` (Apache 2.0)
- **UI**: Jetpack Compose BOM 2023.10.01
  - Material3
  - Navigation Compose
  - ViewModel Compose
- **Image Processing**: 
  - Coil for image loading
  - Glide for advanced image operations
  - uCrop for image cropping
- **Camera/Scanning**: CameraX 1.3.1
- **OCR**: ML Kit Text Recognition
- **Office Documents**: Apache POI 4.1.2 (DOCX, XLSX, PPTX)
- **Coroutines**: kotlinx-coroutines-android 1.7.3

## Project Structure

```
app/src/main/java/com/yourname/pdftoolkit/
├── data/
│   ├── FileManager.kt              # SAF file operations
│   └── SafUriManager.kt            # URI handling utilities
├── domain/
│   ├── PdfBoxInitializer.kt        # PDFBox initialization
│   └── operations/                 # PDF operation implementations
│       ├── HtmlToPdfConverter.kt
│       ├── ImageConverter.kt
│       ├── PdfAnnotator.kt
│       ├── PdfCompressor.kt
│       ├── PdfFlattener.kt
│       ├── PdfFormFiller.kt
│       ├── PdfMerger.kt
│       ├── PdfMetadataManager.kt
│       ├── PdfOcrProcessor.kt
│       ├── PdfOrganizer.kt
│       ├── PdfPageNumberer.kt
│       ├── PdfRedactor.kt
│       ├── PdfRepairer.kt
│       ├── PdfRotator.kt
│       ├── PdfScanner.kt
│       ├── PdfSecurityManager.kt
│       ├── PdfSigner.kt
│       ├── PdfSplitter.kt
│       ├── PdfUnlocker.kt
│       ├── PdfWatermarker.kt
│       └── TextExtractor.kt
├── ui/
│   ├── components/
│   │   ├── CommonComponents.kt     # Reusable UI components
│   │   └── SaveComponents.kt       # Save file dialogs
│   ├── navigation/
│   │   ├── AppNavigation.kt        # Main navigation setup
│   │   └── Screen.kt               # Screen routes/sealed class
│   ├── screens/                    # All feature screens
│   │   ├── HomeScreen.kt           # (Legacy, redirects to ToolsScreen)
│   │   ├── ToolsScreen.kt          # Main tools grid
│   │   ├── FilesScreen.kt          # File browser
│   │   ├── SettingsScreen.kt
│   │   ├── PdfViewerScreen.kt      # PDF viewer
│   │   ├── DocumentViewerScreen.kt # Office document viewer
│   │   ├── MergeScreen.kt
│   │   ├── SplitScreen.kt
│   │   ├── CompressScreen.kt
│   │   ├── ConvertScreen.kt        # Image to PDF
│   │   ├── PdfToImageScreen.kt
│   │   ├── ExtractScreen.kt
│   │   ├── RotateScreen.kt
│   │   ├── OrganizeScreen.kt       # Reorder/delete pages
│   │   ├── SecurityScreen.kt       # Password protection
│   │   ├── UnlockScreen.kt
│   │   ├── MetadataScreen.kt
│   │   ├── PageNumberScreen.kt
│   │   ├── WatermarkScreen.kt
│   │   ├── FlattenScreen.kt
│   │   ├── SignPdfScreen.kt
│   │   ├── FillFormsScreen.kt
│   │   ├── AnnotationScreen.kt
│   │   ├── HtmlToPdfScreen.kt
│   │   ├── ExtractTextScreen.kt
│   │   ├── ScanToPdfScreen.kt
│   │   ├── OcrScreen.kt
│   │   └── ImageToolsScreen.kt
│   └── theme/
│       ├── Color.kt
│       ├── Theme.kt
│       └── Type.kt
├── util/
│   ├── CacheManager.kt
│   ├── CropHelper.kt
│   ├── ExceptionHandler.kt
│   ├── FileOpener.kt
│   ├── ImageProcessor.kt
│   ├── ImageViewerUtils.kt
│   └── OutputFolderManager.kt
├── MainActivity.kt                  # Main entry point
└── PdfToolkitApplication.kt         # Application class
```

## Key Features

### PDF Operations (25+ Tools)

#### Organize Category
- **Merge PDFs**: Combine multiple PDFs into one
- **Split PDF**: Split by pages, ranges, or every N pages
- **Organize Pages**: Reorder or delete pages
- **Rotate Pages**: Rotate all or specific pages (90°, 180°, 270°)
- **Extract Pages**: Select and extract specific pages

#### Convert Category
- **Images to PDF**: Convert multiple images to a single PDF
- **PDF to Images**: Convert PDF pages to images (PNG/JPEG)
- **HTML to PDF**: Convert web pages or HTML to PDF
- **Extract Text**: Extract text content to TXT file
- **Scan to PDF**: Scan documents with camera
- **OCR**: Make scanned PDFs searchable with ML Kit
- **Image Tools**: Resize, compress, convert images

#### Markup Category
- **Sign PDF**: Add signatures to PDFs
- **Fill Forms**: Fill PDF form fields
- **Annotate PDF**: Add highlights, notes, stamps

#### Security Category
- **Add Security**: Password protect PDFs
- **Unlock PDF**: Remove password protection

#### Optimize Category
- **Compress PDF**: Reduce file size (Light/Medium/Aggressive)
- **Repair PDF**: Fix corrupted PDF files
- **Page Numbers**: Add page numbers
- **View/Edit Metadata**: Update PDF properties
- **Add Watermark**: Add text or image watermarks
- **Flatten PDF**: Merge annotations to content

## Architecture Patterns

### File Handling
- Uses **Storage Access Framework (SAF)** for Android 10+ compliance
- Files are accessed via URIs, not direct file paths
- `FileManager.kt` handles SAF operations with proper permission handling
- Temporary files stored in app cache when needed
- `SafUriManager.kt` manages URI persistence and access

### State Management
- **ViewModels** for screen-level state
- **StateFlow/Flow** for reactive state
- **Coroutines** for async operations (IO dispatcher for file operations)
- Navigation state managed by Jetpack Compose Navigation

### PDF Operations
- All PDF operations use **PDFBox-Android** library
- Operations run on IO dispatcher
- Progress callbacks for long-running operations
- Proper error handling with Result types
- Memory-efficient processing for large files

### Navigation
- **Jetpack Compose Navigation** with type-safe routes
- Screen sealed class in `Screen.kt` defines all routes
- Deep linking support for PDF files
- Arguments passed via navigation parameters (encoded URIs)

## Important Conventions

### Package Naming
- Package name: `com.yourname.pdftoolkit`
- Should be updated to actual package name for production

### File Access
- **Never use direct File paths** - always use URIs
- Use `ContentResolver` to open InputStream from URIs
- Use SAF for file picking and saving
- Handle persistable permissions for long-term access

### Error Handling
- Operations return `Result<T>` type
- User-friendly error messages
- Log technical details for debugging
- Handle memory errors gracefully (large PDFs)

### UI Guidelines
- Material Design 3 theming
- Dark mode support
- Loading states for all operations
- Progress indicators for long operations
- Snackbars for success/error messages
- Dialogs for confirmations

### Performance
- Use `LazyColumn` for lists
- Generate thumbnails asynchronously
- Clean up resources (close PDDocument, InputStreams)
- Implement proper coroutine cancellation
- Use memory-efficient processing for large files

## Build Configuration

### Release Build
- **R8 full mode** enabled (code shrinking)
- **Resource shrinking** enabled
- **ProGuard rules** in `proguard-rules.pro` for PDFBox
- **Signing**: Release keystore configured via `keystore.properties`
- **Bundle**: AAB generation for Play Store

### Debug Build
- Application ID suffix: `.debug`
- Version name suffix: `-debug`
- Minification disabled
- Debug symbols enabled

## Version Information
- **Current Version**: 1.2.4
- **Version Code**: 9
- **Latest Release**: Available in `release/` and `release_builds/` directories

## Development Notes

### PDFBox Initialization
- Must initialize PDFBox in `Application.onCreate()`
- See `PdfBoxInitializer.kt` and `PdfToolkitApplication.kt`
- Initialization happens before any PDF operations

### Intent Handling
- App handles `ACTION_VIEW` and `ACTION_SEND` intents
- Supports opening PDFs and Office documents from other apps
- Intent filters in AndroidManifest.xml

### Office Document Support
- Supports DOCX, XLSX, PPTX via Apache POI
- Files can be converted/opened but PDF operations are PDF-specific
- Document viewer for preview

### Camera/Scanning
- Uses CameraX for document scanning
- ML Kit for OCR (on-device, ~40MB model download on first use)
- Image processing pipeline for quality enhancement

## Common Tasks

### Adding a New PDF Operation
1. Create operation class in `domain/operations/`
2. Create corresponding screen in `ui/screens/`
3. Add screen to `Screen.kt` sealed class
4. Add route in `AppNavigation.kt`
5. Add tool card in `ToolsScreen.kt`
6. Handle errors and show progress

### Debugging Issues
- Check PDFBox initialization logs
- Verify URI permissions and access
- Check memory usage (large files)
- Verify ProGuard rules for release builds
- Test on multiple Android versions (API 26+)

### Testing
- Test with various PDF sizes (small, medium, large)
- Test with PDFs containing images, forms, annotations
- Verify file operations work on Android 10+ (scoped storage)
- Test error cases (corrupted files, insufficient storage)

## Future Enhancements (See WISHLIST.md)
- Batch operations
- Cloud storage integration (optional)
- Advanced annotation tools
- PDF comparison
- Form field detection improvements

