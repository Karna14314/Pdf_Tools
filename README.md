# PDF Toolkit
A privacy-first, offline PDF utility for Android. Built with AI assistance.

Download on Play Store:https://play.google.com/store/apps/details?id=com.yourname.pdftoolkit

A powerful, privacy-first Android application for all your PDF manipulation needs. Built with modern Android technologies including Kotlin, Jetpack Compose, and Material Design 3.

## 🚀 Features

PDF Toolkit offers a comprehensive suite of tools to manage and modify your PDF documents:

### 📄 core PDF Operations
- **Merge PDFs**: Combine multiple PDF files into a single document.
- **Split PDF**: Split a PDF into multiple files or specific ranges.
- **Compress PDF**: Reduce file size while maintaining quality.
- **Convert**:
  - **Images to PDF**: Create PDFs from your gallery images.
  - **PDF to Images**: Convert PDF pages into high-quality images.
  - **HTML to PDF**: direct conversion of webpages to PDF.
  - **Scan to PDF**: Use your camera to scan physical documents.
  - **Extract Text**: Extract text content from PDF pages.

### 🛠️ Organization Tools
- **Reorder Pages**: visual page reordering with drag-and-drop-like interface.
- **Rotate Pages**: Rotate specific pages or the entire document.
- **Extract Pages**: Extract specific pages to create a new PDF.
- **Delete Pages**: Remove unwanted pages from your document.

### 🔒 Security & Privacy
- **Lock PDF**: Add password protection to your files.
- **Unlock PDF**: Remove passwords from protected PDFs (with password).
- **Watermark**: Add text or image watermarks for copyright/branding.
- **Local Processing**: All operations happen on-device for maximum privacy.

### 🖌️ Editing & Annotation
- **Annotate**: Highlight, draw, and mark up your PDFs.
- **Sign PDF**: Add your digital signature to documents.
- **Fill Forms**: Complete PDF forms on the go.
- **Flatten PDF**: Make form fields and annotations permanent.

### 🖼️ Image Tools
- **Compress Images**: Optimize image file sizes.
- **Resize Images**: Change image dimensions.
- **Convert Format**: Switch between standard image formats (JPG, PNG, WebP).
- **Remove Metadata**: Strip Exif data for privacy.

## 🛠️ Tech Stack

- **Language**: Kotlin 100%
- **UI Framework**: Jetpack Compose (Material Design 3)
- **Architecture**: MVVM (Model-View-ViewModel) + Clean Architecture
- **PDF Processing**: 
  - [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android) for PDF manipulation
  - Android native `PdfRenderer` for rendering
- **IO**: Coroutines & Flow for asynchronous operations

## 📥 Build & Installation

To build the project locally:

1. **Clone the repository**:
   ```bash
   git clone https://github.com/Karna14314/Pdf_Tools.git
   cd Pdf_Tools
   ```

2. **Open in Android Studio**:
   - Open Android Studio and select "Open an existing project".
   - Navigate to the cloned folder.

3. **Build and Run**:
   - Wait for Gradle sync to complete.
   - Run on an emulator or physical device (Android 8.0+ recommended).

## 📄 License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

```
Copyright 2024-2025 PDF Toolkit Contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

## 🤝 Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

---

**Repository Maintainer**: [Karna14314](https://github.com/Karna14314)
