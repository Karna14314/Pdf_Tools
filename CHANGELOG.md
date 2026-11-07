# Changelog

All notable changes to the PDF Toolkit project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

### Added
- **Crop Pages** tool (Organize section): draw crop areas directly on the rendered page.
  - *All pages*: one area applied to every page.
  - *Per page*: a different area for each page (with copy-to-next-pages), or one area for odd and one for even pages.
  - *Split*: turn each page into two pages using a draggable split line (vertical/horizontal) or two free areas, for all pages or with a separate setup for odd and even pages; optional reverse order for right-to-left documents.
  - Lossless: sets the page CropBox with PdfBox (no rasterization), text stays selectable.
  - Handles rotated pages and existing crop boxes; translated in all 15 languages.

---

## [1.3.210] - 2026-08-08

### Added
- Created `SafeLauncher.kt` extension abstraction (`safeLaunch`) to safely launch all `ActivityResultLauncher` intents.
- Centralized `ActivityNotFoundException` error handling with fallback user notifications on devices missing document providers or file managers.
- Added static release versioning architecture using `gradle.properties` as the single source of truth for `APP_VERSION_CODE` and `APP_VERSION_NAME`.

### Fixed
- Fixed potential application crashes caused by unprotected Storage Access Framework (SAF) calls when no `DocumentsUI` or compatible document provider activity is available.
- Hardened image cropping flow (`CropHelper.kt`) against missing target activity exceptions.
- Synchronized F-Droid metadata, GitHub Actions workflows, and Play Store build properties.

### Changed
- Preserved Git release tags permanently in GitHub Action workflows to support F-Droid build reproducibility.

---

## [1.3.175] - 2026-05-07

### Added
- Introduced dedicated `fdroid` product flavor maintaining 100% open-source software (FOSS) compliance.
- Integrated Tesseract OCR (`tesseract4android`) for offline text extraction in F-Droid and OpenSource flavors.
- Added feature flavor switches in `BuildConfig` for ML Kit vs. Tesseract OCR.

---

## [1.0.0] - 2026-01-15

### Added
- Initial public release of PDF Toolkit.
- Core PDF tools: Merge, Split, Compress, Convert to/from Images, Page Rotation, Watermarking, and Password Protection.
