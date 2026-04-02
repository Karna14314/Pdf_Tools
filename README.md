<p align="center">
  <img src="store_assets/app_icon_512.png" width="120" height="120" alt="PDF Toolkit">
</p>

<h1 align="center">PDF Toolkit</h1>

<p align="center">
  <strong>A privacy-first, offline PDF utility for Android</strong>
</p>

<p align="center">
  <a href="https://play.google.com/store/apps/details?id=com.yourname.pdftoolkit">
    <img src="https://img.shields.io/badge/Play%20Store-Download-green?logo=googleplay" alt="Play Store">
  </a>
  <a href="LICENSE">
    <img src="https://img.shields.io/badge/License-Apache%202.0-blue.svg" alt="License">
  </a>
  <a href="https://github.com/Karna14314/Pdf_Tools/stargazers">
    <img src="https://img.shields.io/github/stars/Karna14314/Pdf_Tools?style=flat" alt="GitHub Stars">
  </a>
  <a href="https://github.com/Karna14314/Pdf_Tools/releases">
    <img src="https://img.shields.io/github/v/release/Karna14314/Pdf_Tools?include_prereleases" alt="Latest Release">
  </a>
  <img src="https://img.shields.io/badge/Platform-Android-3DDC84?logo=android" alt="Platform">
</p>

---

## � Get it on Android

<p align="center">
  <a href="https://play.google.com/store/apps/details?id=com.yourname.pdftoolkit">
    <img src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png" height="80" alt="Get it on Google Play">
  </a>
</p>

> **500+ installs** · Offline · Privacy-first · No account required

---

## � Screenshots
<!-- Add screenshots here -->

---

## ✨ Features

### 📄 PDF Management
- **Merge PDFs** — Combine multiple PDF files into a single document
- **Split PDF** — Split into multiple files or specific page ranges
- **Compress PDF** — Reduce file size while maintaining quality
- **Reorder Pages** — Visual drag-and-drop page reordering
- **Rotate Pages** — Rotate specific pages or entire documents
- **Extract Pages** — Extract specific pages to create new PDFs
- **Delete Pages** — Remove unwanted pages

### 🔄 Conversion Tools
- **Images to PDF** — Create PDFs from gallery images
- **PDF to Images** — Convert PDF pages to high-quality images (JPG/PNG/WebP)
- **HTML to PDF** — Direct webpage to PDF conversion
- **Scan to PDF** — Camera-based document scanning with automatic edge detection

### �️ Editing & Annotation
- **Annotate** — Highlight, draw, and markup PDFs with AndroidX Ink
- **Sign PDF** — Add digital signatures to documents
- **Fill Forms** — Complete PDF forms on the go
- **Flatten PDF** — Make forms and annotations permanent

### 🔒 Privacy & Security
- **Lock PDF** — Password-protect your files
- **Unlock PDF** — Remove passwords (with valid password)
- **Watermark** — Add text or image watermarks
- **All processing on-device** — No cloud, no servers
- **No internet permission** — Completely offline capable
- **No data collection or tracking**

### � OCR & Text
- **Extract Text** — Pull text content from PDF pages
- **Tesseract OCR** — F-Droid and opensource flavors (100% open source)
- **ML Kit OCR** — Play Store flavor (smaller APK, 40MB runtime download)

### 🖼️ Image Tools
- **Compress Images** — Optimize file sizes
- **Resize Images** — Change dimensions
- **Format Conversion** — JPG, PNG, WebP
- **Remove Metadata** — Strip EXIF data for privacy

---

## 🏗️ Build Flavors

| Flavor | OCR Engine | Ads | Firebase | Distribution |
|--------|-----------|-----|----------|--------------|
| `playstore` | ML Kit | No | No | Google Play |
| `fdroid` | Tesseract | No | No | F-Droid (pending) |
| `opensource` | Tesseract | No | No | GitHub Releases |

All flavors are **privacy-first** with no ads, no analytics, and no proprietary dependencies except ML Kit in the Play Store flavor.

---

## 🛠️ Tech Stack

| Category | Technology |
|----------|------------|
| **Language** | Kotlin 100% |
| **UI Framework** | Jetpack Compose (Material Design 3) |
| **Architecture** | MVVM + Clean Architecture |
| **PDF Processing** | PdfBox-Android, Android PdfRenderer |
| **Annotations** | AndroidX Ink Library |
| **OCR (Play Store)** | Google ML Kit |
| **OCR (F-Droid)** | Tesseract (tess-two) |
| **Camera** | CameraX |
| **Images** | Coil, Glide, uCrop |
| **Database** | Room |
| **Preferences** | DataStore |
| **Async** | Coroutines & Flow |
| **Build** | Gradle + KSP |

---

## 🚀 Getting Started

### Prerequisites
- Android Studio Hedgehog or newer
- JDK 17+
- Android SDK 26+

### Build

```bash
# Clone the repository
git clone https://github.com/Karna14314/Pdf_Tools.git
cd Pdf_Tools

# Play Store flavor (ML Kit OCR)
./gradlew assemblePlaystoreRelease

# F-Droid flavor (Tesseract OCR, no proprietary deps)
./gradlew assembleFdroidRelease

# Opensource flavor (fully FOSS)
./gradlew assembleOpensourceRelease
```

---

## 📦 Download

| Platform | Link | Notes |
|----------|------|-------|
| Google Play | [Install](https://play.google.com/store/apps/details?id=com.yourname.pdftoolkit) | Stable, auto-updates |
| GitHub Releases | [Download APK](https://github.com/Karna14314/Pdf_Tools/releases) | Opensource flavor, manual install |
| F-Droid | Coming soon | Under review |

---

## 🤝 Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'feat: add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request against `master`

**Important:** The F-Droid and opensource flavors must remain free of proprietary dependencies. Any new dependencies must be compatible with the F-Droid inclusion policy.

See [open issues](https://github.com/Karna14314/Pdf_Tools/issues) for feature requests and bug reports.

---

## 👤 Maintainer

**Narisetti Chaitanya Naidu**  
GitHub: [@Karna14314](https://github.com/Karna14314)

---

## 📄 License

Copyright © 2026 Narisetti Chaitanya Naidu

Licensed under the Apache License, Version 2.0  
See [LICENSE](LICENSE) for full text.
