# Release Versioning Migration & Ecosystem Readiness Report

**Document Path:** `docs/release-versioning-migration-and-readiness.md`  
**Date:** 2026-08-08  
**Status:** **READY FOR PUSH**  

---

## 1. Executive Summary & Problem Diagnosis

An audit was conducted to resolve version desynchronization across Android builds, GitHub Releases, Git tags, and F-Droid metadata.

### Root Causes Identified
1. **Dynamic Version Overrides in CI:** GitHub Actions (`deploy.yml`) computed dynamic version codes (`github.run_number + 52`) and passed them to Gradle via command line flags (`-PAPP_VERSION_CODE=...`), overriding `gradle.properties` without committing changes to Git or creating Git tags.
2. **F-Droid Update Discovery Breakdown:** F-Droid's `checkupdates` parses `gradle.properties` inside Git tags (`UpdateCheckMode: Tags`). Because CI builds did not commit version bumps or create tags, F-Droid failed to discover new versions.
3. **Destructive Tag Deletion:** `.github/workflows/manage-releases.yml` deleted remote Git tags older than 10 releases, breaking F-Droid build reproducibility.

---

## 2. Architecture Migration Details

### Sole Source of Truth
`gradle.properties` (`APP_VERSION_CODE` and `APP_VERSION_NAME`) is established as the **single source of truth** across all build flavors and workflows:
```properties
APP_VERSION_CODE=210
APP_VERSION_NAME=1.3.210
```

### Workflow Cleanup
- **`.github/workflows/deploy.yml`**: Removed `VERSION_CODE_OFFSET`, `VERSION_PREFIX`, and dynamic `github.run_number` calculations. Updated steps to read `gradle.properties` directly. Removed `-PAPP_VERSION_CODE` and `-PAPP_VERSION_NAME` CLI parameter overrides.
- **`.github/workflows/build-release.yml` & `ensure-release-files.yml`**: Removed dynamic offsets and CLI parameter overrides.
- **`.github/workflows/manage-releases.yml`**: Disabled `git push origin --delete "$TAG"` to preserve Git tags permanently.

### Metadata Alignments
- **F-Droid Metadata ([`metadata/com.yourname.pdftoolkit.yml`](file:///c:/Users/chait/Projects/pdf_tools/metadata/com.yourname.pdftoolkit.yml)):** Added top-level `CurrentVersion: 1.3.210` and `CurrentVersionCode: 210`. Retained historical build recipe `1.3.175` and active recipe `1.3.210`.
- **Fastlane Changelog ([`fastlane/metadata/android/en-US/changelogs/210.txt`](file:///c:/Users/chait/Projects/pdf_tools/fastlane/metadata/android/en-US/changelogs/210.txt)):** Created release notes for version code 210.
- **Root Changelog ([`CHANGELOG.md`](file:///c:/Users/chait/Projects/pdf_tools/CHANGELOG.md)):** Created central release history for versions `1.3.210`, `1.3.175`, and `1.0.0`.

---

## 3. Pipeline & Build Verification Results

| Verification Test | Target / Command | Result | Status |
|---|---|---|---|
| **Workflow Syntax** | `.github/workflows/*.yml` (7 files) | 0 syntax errors | ✅ **PASSED** |
| **CLI Parameter Search** | Repository-wide | 0 active `-P` overrides remaining | ✅ **PASSED** |
| **Android Compilation** | `./gradlew :app:assembleFdroidDebug` | `BUILD SUCCESSFUL in 22s` | ✅ **PASSED** |
| **Generated `BuildConfig`** | `app/build/generated/.../BuildConfig.java` | `VERSION_CODE = 210`<br>`VERSION_NAME = "1.3.210-debug"` | ✅ **PASSED** |

---

## 4. Release Process Workflow

To publish future releases:
```bash
# 1. Update APP_VERSION_CODE and APP_VERSION_NAME in gradle.properties
# 2. Commit: git commit -am "chore: bump version to X.Y.Z"
# 3. Tag: git tag -a vX.Y.Z -m "Release vX.Y.Z"
# 4. Push: git push origin master --tags
```
GitHub Actions, Play Store AAB builds, GitHub Releases, and F-Droid's `checkupdates` will automatically build and publish version `X.Y.Z` in perfect synchronization.
