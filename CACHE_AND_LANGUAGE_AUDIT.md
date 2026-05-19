# Audit: Cache Accumulation & Language Selection Issues

## Issue 1: Cache Accumulation (50 MB for 50 MB PDF)

### Root Cause
**File:** [app/src/main/java/com/yourname/pdftoolkit/ui/navigation/AppNavigation.kt](app/src/main/java/com/yourname/pdftoolkit/ui/navigation/AppNavigation.kt#L111)

The `normalizeUriToCache()` function copies **entire PDF files** from external providers (Google Drive, Downloads, etc.) to the app's cache directory for reliable local access.

**Problem Flow:**
1. User opens 50 MB PDF from Downloads or Google Drive (content:// URI)
2. App calls `normalizeUriToCache()` which:
   - Creates `pdf_cache/pdf_<hash>.pdf` in cache
   - Copies entire file via `context.contentResolver.openInputStream()`
   - Result: **50 MB cache file created**
3. Cache cleanup only triggers when total exceeds 50 MB (limit in `cleanPdfCache()`)
4. Since a single 50 MB file fits, it stays cached indefinitely

**Storage Impact:**
- Original file: 50 MB (on device storage or cloud)
- Cached copy: 50 MB (in app cache directory)
- **Total: 100 MB storage used for viewing one PDF**

### Current Cache Architecture

**File:** [app/src/main/java/com/yourname/pdftoolkit/ui/navigation/AppNavigation.kt](app/src/main/java/com/yourname/pdftoolkit/ui/navigation/AppNavigation.kt#L49-L68)
```kotlin
private fun cleanPdfCache(context: android.content.Context) {
    val cacheDir = File(context.cacheDir, "pdf_cache")
    val maxCacheSizeMb = 50L  // <-- Only deletes when >50MB
    val maxCacheSizeBytes = maxCacheSizeMb * 1024 * 1024
    
    val files = cacheDir.listFiles()
        ?.filter { it.isFile && it.name.endsWith(".pdf") }
        ?.sortedBy { it.lastModified() } // oldest first
        ?: return
    
    var totalSize = files.sumOf { it.length() }
    
    for (file in files) {
        if (totalSize <= maxCacheSizeBytes) break  // Stop when under limit
        totalSize -= file.length()
        file.delete()
    }
}
```

**Why This Doesn't Work:**
- Cleanup is called in `normalizeUriToCache()` BEFORE adding the new file
- 50 MB file is added → exceeds limit → trigger cleanup
- But cleanup logic has edge cases where single large file fits exactly at limit

**Additional Cache Directories:**
From [CacheManager.kt](app/src/main/java/com/yourname/pdftoolkit/util/CacheManager.kt):
- `pdf_cache/` — PDFs from external providers (the 50 MB accumulation point)
- `scans/` — OCR/scan captures
- `ocr_cache/` — OCR processing temp files  
- `compress_cache/` — PDF compression temp files
- `viewer_cache/` — PDF viewer temp files
- `file_opener_cache/` — External file opener temp files
- `shared_files/` — Files shared via intents

---

## Issue 2: Language Selection Not Changing

### Root Cause
Multiple synchronization issues between **AppCompatDelegate** and **DataStore**:

#### Problem 1: Race Condition in `changeLanguage()`
**File:** [app/src/main/java/com/yourname/pdftoolkit/util/LanguageManager.kt](app/src/main/java/com/yourname/pdftoolkit/util/LanguageManager.kt#L110)

```kotlin
suspend fun changeLanguage(context: Context, langCode: String) {
    LanguageDataStore.saveSelectedLanguage(context, langCode)  // Async write
    setLanguage(context, langCode)                             // Immediate
}
```

**Issue:**
- `setLanguage()` applies locale immediately via `AppCompatDelegate`
- But UI observer reads from `getLanguageFlow()` which reads from DataStore
- If DataStore write is slow, UI might show stale language
- AppCompatDelegate change may not trigger Compose recomposition

#### Problem 2: Out-of-Sync Language Sources
**File:** [app/src/main/java/com/yourname/pdftoolkit/ui/screens/SettingsScreen.kt](app/src/main/java/com/yourname/pdftoolkit/ui/screens/SettingsScreen.kt#L115)

```kotlin
val currentLanguage by LanguageManager.getLanguageFlow(context)  // From DataStore
    .collectAsState(initial = LanguageManager.getCurrentLanguage())  // From AppCompatDelegate
```

**Issue:**
- Initial value reads from `AppCompatDelegate`
- Observable Flow reads from `DataStore`
- After language change, these can be out of sync:
  - `AppCompatDelegate.setApplicationLocales()` → Immediate change
  - `DataStore.saveSelectedLanguage()` → Async change
  - UI uses DataStore Flow, might miss AppCompatDelegate change

#### Problem 3: Compose Resources Don't Auto-Reload
**Observation from [SettingsScreen.kt](app/src/main/java/com/yourname/pdftoolkit/ui/screens/SettingsScreen.kt#L267):**

```kotlin
subtitle = LanguageManager.getLanguageDisplayName(currentLanguage)
```

**Issue:**
- Even when `currentLanguage` state updates, Compose string resources (`stringResource()`) don't automatically reload based on new locale
- Android's resource system caches Configuration, needs Activity recreation or resources re-read
- Simply changing AppCompatDelegate locale doesn't trigger resource reloading in Compose UI

#### Problem 4: Missing Configuration Change Handling
**File:** [app/src/main/java/com/yourname/pdftoolkit/ui/MainActivity.kt](app/src/main/java/com/yourname/pdftoolkit/ui/MainActivity.kt#L48)

**Issue:**
- No `android:configChanges` attribute to handle locale changes in manifest
- No `onConfigurationChanged()` callback in MainActivity
- When locale changes, Activity may need recreation for resources to reload

### Language Flow Diagram (Current - Broken)

```
User clicks language in SettingsScreen
         ↓
LanguageManager.changeLanguage(context, langCode)
         ↓
    ┌────────────────────────────────┐
    │ ASYNC: DataStore.saveSelected  │  
    │        Language(context, code) │
    └────────────────────────────────┘
                  ↓ (eventually)
         currentLanguage Flow updates
         ↓ (sometime later - possible delay)
         UI rebuilds with new language
    
    ┌──────────────────────────────┐
    │ IMMEDIATE: setLanguage()     │
    │ AppCompatDelegate locale     │
    └──────────────────────────────┘
         ↓
    But Compose strings still use old resources!
    No automatic resource reload happens
    UI might not reflect change unless recomposed
```

---

## Summary of Issues

| Issue | Location | Impact | Severity |
|-------|----------|--------|----------|
| Full PDF copy to cache | `AppNavigation.kt:normalizeUriToCache()` | 50MB cache bloat per PDF | **HIGH** - Storage waste |
| Cache cleanup triggered only at >50MB | `AppNavigation.kt:cleanPdfCache()` | Single large PDFs stay cached | **HIGH** - No enforcement |
| Race condition in `changeLanguage()` | `LanguageManager.kt:110` | UI might show stale language | **MEDIUM** - Race condition |
| AppCompatDelegate ↔ DataStore sync | `SettingsScreen.kt:115` | Language change delayed/not visible | **MEDIUM** - State sync bug |
| Compose strings don't auto-reload | `SettingsScreen.kt` | Language text doesn't update on-screen | **HIGH** - User sees no change |
| No configuration change handling | `MainActivity.kt` | Resources not reloaded after locale change | **HIGH** - Config not applied |

---

## Recommended Fixes

### For Cache Issue:
1. Lower cache limit to 20-30 MB (more aggressive cleanup)
2. Implement smarter cache key: avoid duplicating identical files
3. Add time-based cleanup in addition to size-based cleanup
4. Consider streaming PDF operations instead of full copy for large files

### For Language Issue:
1. Fix race condition by awaiting DataStore save before applying locale
2. Consolidate language source (use only AppCompatDelegate or only DataStore, not both)
3. Force Activity recreation when locale changes (allows resource reload)
4. Add `android:configChanges="locale|layoutDirection"` to manifest
5. Implement `onConfigurationChanged()` to handle resource updates
