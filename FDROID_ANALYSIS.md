# F-Droid Inclusion Analysis for PDF Toolkit

## F-Droid Requirements Summary
1. **100% FLOSS** - All code and dependencies must be Free/Libre Open Source
2. **Buildable with FLOSS tools only** - No proprietary build tools
3. **No proprietary binary downloads** - No runtime downloads of non-free code
4. **Source in public VCS** - Git/GitLab/GitHub with up-to-date source
5. **No API keys required** - F-Droid won't sign up for API keys

## Current Dependencies Analysis

### ✅ ALLOWED (Open Source)
- androidx.* libraries - Apache 2.0
- Jetpack Compose - Apache 2.0
- Kotlin coroutines - Apache 2.0
- PdfBox-Android (com.tom-roush:pdfbox-android) - Apache 2.0
- CameraX - Apache 2.0
- Coil - Apache 2.0
- uCrop - Apache 2.0
- Room Database - Apache 2.0

### ❌ BLOCKED (Proprietary/Non-Free)

#### 1. **ML Kit Text Recognition** - CRITICAL BLOCKER
```gradle
implementation("com.google.mlkit:text-recognition:16.0.1")
```
- **Status**: PROPRIETARY - Requires Google Play Services
- **Impact**: Used for OCR feature
- **F-Droid Action**: Will REJECT the app
- **Solution**: Replace with Tesseract OCR (open source)

#### 2. **Glide** - POTENTIAL ISSUE
```gradle
implementation("com.github.bumptech.glide:glide:4.16.0")
```
- **Status**: BSD-like license (needs verification)
- **Impact**: Image loading
- **F-Droid Action**: May flag or reject
- **Solution**: Use Coil (already included) or verify license

### ⚠️ NEEDS REVIEW

#### 1. **androidx.pdf:pdf-viewer-fragment** - ALPHA LIBRARY
```gradle
implementation("androidx.pdf:pdf-viewer-fragment:1.0.0-alpha04")
```
- **Status**: Alpha library from Google, license unclear
- **Impact**: Core PDF viewing functionality
- **F-Droid Action**: May reject if proprietary components
- **Solution**: Verify it's fully open source or use alternative

#### 2. **androidx.ink libraries** - ALPHA LIBRARIES
```gradle
implementation("androidx.ink:ink-brush:1.0.0-alpha01")
implementation("androidx.ink:ink-geometry:1.0.0-alpha01")
implementation("androidx.ink:ink-rendering:1.0.0-alpha01")
implementation("androidx.ink:ink-strokes:1.0.0-alpha01")
```
- **Status**: Alpha libraries, may have proprietary components
- **Impact**: Annotation/drawing features
- **F-Droid Action**: May reject
- **Solution**: Verify licenses or implement custom drawing

## Required Changes for F-Droid

### Option 1: Create F-Droid Build Flavor (RECOMMENDED)

Create a separate build flavor that excludes proprietary dependencies:

```gradle
android {
    flavorDimensions += "distribution"
    productFlavors {
        create("fdroid") {
            dimension = "distribution"
            // Exclude OCR and other proprietary features
        }
        create("playstore") {
            dimension = "distribution"
            // Include all features
        }
    }
}

dependencies {
    // Common dependencies
    implementation("androidx.core:core-ktx:1.12.0")
    // ... other FLOSS dependencies
    
    // Play Store only (with ML Kit OCR)
    "playstoreImplementation"("com.google.mlkit:text-recognition:16.0.1")
    
    // F-Droid only (with Tesseract OCR)
    "fdroidImplementation"("com.rmtheis:tess-two:9.1.0") // Tesseract
}
```

### Option 2: Remove Proprietary Dependencies Completely

Replace ML Kit with Tesseract OCR for all builds:
```gradle
// Remove this:
// implementation("com.google.mlkit:text-recognition:16.0.1")

// Add this:
implementation("com.rmtheis:tess-two:9.1.0") // Tesseract OCR
```

### Option 3: Make OCR Optional

Disable OCR feature entirely in F-Droid builds and show a message that it's unavailable.

## Recommended Approach

**Create an F-Droid flavor** with these changes:

1. **Remove ML Kit** - Replace with Tesseract or disable OCR
2. **Verify androidx.pdf license** - Check if it's fully open source
3. **Review Glide** - Consider using only Coil
4. **Test build** - Ensure F-Droid flavor builds successfully
5. **Update metadata** - Create F-Droid metadata file

## Next Steps

1. Choose which approach to take
2. Implement the changes
3. Test the F-Droid build flavor
4. Create F-Droid metadata file
5. Submit to F-Droid repository

## Estimated Effort

- **Option 1 (Build Flavor)**: 2-4 hours
- **Option 2 (Replace ML Kit)**: 4-8 hours (need to integrate Tesseract)
- **Option 3 (Disable OCR)**: 1-2 hours

## F-Droid Benefits

- Reach privacy-conscious users
- No Google Play Services required
- Automatic updates through F-Droid
- Increased trust and credibility
- Larger user base

## Conclusion

Your app **CANNOT be accepted to F-Droid** in its current state due to ML Kit dependency. You must either:
1. Create an F-Droid build flavor without ML Kit
2. Replace ML Kit with Tesseract OCR
3. Remove OCR feature entirely

The recommended approach is **Option 1** - create a separate F-Droid flavor that uses Tesseract or disables OCR, while keeping the Play Store version with ML Kit.
