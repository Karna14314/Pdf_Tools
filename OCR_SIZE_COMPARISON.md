# OCR Library Size Comparison: ML Kit vs Tesseract

## Current Setup (ML Kit)

### ML Kit Text Recognition v2
```gradle
implementation("com.google.mlkit:text-recognition:16.0.1")
```

**Size Impact:**
- **Base library**: ~4 MB per architecture (arm64-v8a, armeabi-v7a, x86, x86_64)
- **Model download**: ~40 MB (downloaded on first use, not in APK)
- **Total APK increase**: ~4-16 MB depending on architectures included
- **Runtime download**: ~40 MB on first OCR use (requires internet)

**Pros:**
- ✅ Smaller initial APK size
- ✅ Better accuracy (Google's ML models)
- ✅ Faster recognition
- ✅ Automatic model updates via Play Services
- ✅ Supports 100+ languages

**Cons:**
- ❌ Proprietary (NOT allowed on F-Droid)
- ❌ Requires Google Play Services
- ❌ Requires internet for first-time model download
- ❌ Privacy concerns (Google services)

---

## Alternative: Tesseract OCR (tess-two)

### Tesseract via tess-two
```gradle
implementation("com.rmtheis:tess-two:9.1.0")
```

**Size Impact:**
- **Base library**: ~8-12 MB (native libraries for all architectures)
- **Language data (tessdata)**: 
  - English only: ~21 MB
  - Each additional language: ~3-30 MB
  - Total for English: ~21 MB
- **Total APK increase**: ~30-35 MB (library + English data)
- **No runtime download**: Everything bundled in APK

**Pros:**
- ✅ 100% Open Source (F-Droid compatible)
- ✅ Works offline (no internet required)
- ✅ No Google Play Services needed
- ✅ Privacy-friendly (all on-device)
- ✅ Supports 100+ languages
- ✅ Customizable and configurable

**Cons:**
- ❌ Larger APK size (~30 MB more)
- ❌ Slower recognition than ML Kit
- ❌ Lower accuracy than ML Kit
- ❌ Requires manual language data management
- ❌ More complex integration

---

## Size Comparison Summary

| Aspect | ML Kit | Tesseract (tess-two) | Difference |
|--------|--------|---------------------|------------|
| **Initial APK Size** | +4-16 MB | +30-35 MB | **+14-31 MB larger** |
| **First Download** | +40 MB (runtime) | 0 MB | Tesseract better |
| **Total Size (user)** | 44-56 MB | 30-35 MB | **Tesseract smaller overall** |
| **Offline Support** | ❌ No | ✅ Yes | Tesseract better |
| **F-Droid Compatible** | ❌ No | ✅ Yes | Tesseract only option |

---

## Current App Size Analysis

Your current APK: **~33.5 MB**

### With ML Kit (Current):
- Base app: ~29.5 MB
- ML Kit: ~4 MB
- **Total APK**: ~33.5 MB
- **First run download**: +40 MB
- **Total user storage**: ~73.5 MB

### With Tesseract (F-Droid):
- Base app: ~29.5 MB
- Tesseract library: ~12 MB
- English tessdata: ~21 MB
- **Total APK**: ~62.5 MB
- **First run download**: 0 MB
- **Total user storage**: ~62.5 MB

---

## Recommendations

### Option 1: Build Flavors (RECOMMENDED)
Create separate builds for different stores:

```gradle
android {
    flavorDimensions += "distribution"
    productFlavors {
        create("playstore") {
            dimension = "distribution"
            // Use ML Kit - smaller APK, better accuracy
        }
        create("fdroid") {
            dimension = "distribution"
            // Use Tesseract - F-Droid compatible
        }
    }
}

dependencies {
    "playstoreImplementation"("com.google.mlkit:text-recognition:16.0.1")
    "fdroidImplementation"("com.rmtheis:tess-two:9.1.0")
}
```

**Result:**
- Play Store APK: ~33.5 MB (current)
- F-Droid APK: ~62.5 MB (+29 MB)
- Users get best option for their platform

### Option 2: Tesseract Only
Replace ML Kit with Tesseract for all builds:

**Result:**
- All APKs: ~62.5 MB (+29 MB)
- F-Droid compatible
- Works offline everywhere
- Slightly lower accuracy

### Option 3: Disable OCR in F-Droid
Remove OCR feature from F-Droid builds:

**Result:**
- Play Store APK: ~33.5 MB (current)
- F-Droid APK: ~29.5 MB (-4 MB)
- F-Droid users lose OCR feature

---

## Size Optimization Tips

If you choose Tesseract, you can reduce size:

1. **Use App Bundles (AAB)**: Google Play automatically splits by architecture
   - Reduces per-device download by ~50%
   
2. **Download tessdata on demand**: Don't bundle in APK
   - APK size: ~41.5 MB (saves 21 MB)
   - Download on first OCR use
   - Similar to ML Kit approach

3. **Support fewer languages**: Only include needed languages
   - English only: 21 MB
   - Add languages as needed: 3-30 MB each

4. **Use compressed tessdata**: Use .traineddata.gz files
   - Saves ~30% size
   - Decompress at runtime

---

## Final Recommendation

**Use Build Flavors (Option 1)**:
- Play Store users get ML Kit (smaller APK, better accuracy)
- F-Droid users get Tesseract (open source, offline)
- APK size increase for F-Droid: +29 MB (acceptable trade-off)
- Best of both worlds

The 29 MB increase for F-Droid is acceptable because:
1. F-Droid users prioritize privacy/FLOSS over size
2. No runtime download needed (better for privacy)
3. Works completely offline
4. Total user storage is actually less than ML Kit (62.5 MB vs 73.5 MB)

---

## Implementation Effort

- **Build Flavors**: 2-4 hours
- **Tesseract Integration**: 4-6 hours
- **Testing**: 2-3 hours
- **Total**: 8-13 hours

Worth it to reach F-Droid's privacy-conscious user base!
