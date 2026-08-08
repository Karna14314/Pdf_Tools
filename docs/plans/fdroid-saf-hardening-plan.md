# F-Droid SAF Hardening Technical Design Plan

**Document Path:** `docs/plans/fdroid-saf-hardening-plan.md`  
**Status:** Approved Technical Design  
**Author:** Antigravity AI Agent  

---

## 1. Current Architecture Overview

### Existing Launcher Usage
Throughout the application's 27 Jetpack Compose screen components, Storage Access Framework (SAF) contract launchers are declared using `rememberLauncherForActivityResult(...)`:

```kotlin
// Example in AnnotationScreen.kt, ConvertScreen.kt, etc.
val pdfPickerLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument()
) { uri ->
    if (uri != null) {
        // handle document
    }
}

val savePdfLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.CreateDocument("application/pdf")
) { uri ->
    if (uri != null) {
        // handle save
    }
}
```

### Existing SAF Launch Flow
When a user clicks a button such as "Select PDF" or "Save PDF", the composable directly invokes the launcher's `.launch(...)` method:

```kotlin
IconButton(onClick = { pdfPickerLauncher.launch(arrayOf("application/pdf")) }) { ... }
savePdfLauncher.launch(fileName)
```

### Existing Save / Open Workflows
- **Open Flow:** Opens the system document picker to grant permission for a `content://` URI.
- **Save Flow:** Opens the system document creator to allow the user to select an output destination. If custom save location is unchecked, files are written to internal app storage via `SaveHelper.saveToDefaultFolder()`.

---

## 2. Candidate Solutions Evaluation

### Solution A: Per-Launch `try/catch` Duplication
Wrap each `.launch(...)` call in `try/catch (e: ActivityNotFoundException)` locally inside button click handlers.

* **Evaluation:**
  - **Pros:** Does not require creating new utility files.
  - **Cons:** Extremely boilerplate-heavy (63+ try-catch blocks across 27 files). High maintenance burden and high risk of regressions in future features.

---

### Solution B: `safeLaunch` Extension Function on `ActivityResultLauncher<I>`
Create an extension function on `ActivityResultLauncher<I>`:

```kotlin
package com.yourname.pdftoolkit.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher

fun <I> ActivityResultLauncher<I>.safeLaunch(
    input: I,
    context: Context,
    onNotFound: (() -> Unit)? = null
) {
    try {
        launch(input)
    } catch (e: ActivityNotFoundException) {
        if (onNotFound != null) {
            onNotFound()
        } else {
            Toast.makeText(
                context,
                "No compatible document provider or file manager is available on this device.",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
```

* **Evaluation:**
  - **Pros:** Minimal footprint, 100% type-safe, seamless integration with existing `rememberLauncherForActivityResult` declarations in Compose, centralized exception handling.
  - **Cons:** Call sites need to pass `context` or custom callback.

---

### Solution C: `rememberSafeLauncherForActivityResult` Composable Wrapper
Define a custom composable wrapper that returns a `SafeActivityResultLauncher` object with automatic context and exception handling built-in.

* **Evaluation:**
  - **Pros:** Hides context handling completely inside the composable.
  - **Cons:** Slightly higher composition tree overhead.

---

## 3. Final Selected Solution

### Selected Architecture: **`SafeLauncher.kt` (Unified Option B & C)**

We will implement `SafeLauncher.kt` in package `com.yourname.pdftoolkit.util` providing `ActivityResultLauncher<I>.safeLaunch(...)`.

### Key Design Guarantee
- **Zero Crashes:** All `ActivityNotFoundException` errors are intercepted at launch time.
- **Consistent UX:** Displays a clear, standardized user notification: *"No compatible document provider or file manager is available on this device."*
- **F-Droid Compatibility:** Completely safe on custom/debloated/AOSP ROMs without Play Services or DocumentUI.
- **Zero Processing Side Effects:** All PDF conversion, OCR, watermarking, signing, and file saving business logic remains completely untouched.

---

## 4. Refactoring Strategy

Every call to `launcher.launch(input)` across all 27 screen files will be systematically replaced with `launcher.safeLaunch(input, context)`.
