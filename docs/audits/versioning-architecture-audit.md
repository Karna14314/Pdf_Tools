# PDF Toolkit Release Versioning Architecture Audit

**Document Path:** `docs/reports/versioning-architecture-audit.md`  
**Date:** 2026-08-08  
**Scope:** Repository-wide audit of version sources, build pipelines, GitHub Actions workflows, F-Droid metadata, and Play Store release synchronization.  
**Mode:** Investigation & Architecture Audit (No code modifications or git commits).  

---

## 1. Executive Summary

An audit was conducted across the PDF Toolkit codebase to investigate why application versions, Git tags, GitHub releases, and F-Droid metadata frequently desynchronize.

### Core Audit Findings
1. **Four Competing Version Sources:** Version information is fragmented across four distinct locations:
   - `gradle.properties` (hardcoded `APP_VERSION_CODE=210`, `APP_VERSION_NAME=1.3.210`)
   - `.github/workflows/deploy.yml` & `build-release.yml` (ephemerally calculates `VERSION_CODE = github.run_number + 52`)
   - `metadata/com.yourname.pdftoolkit.yml` (hardcoded build recipe `versionCode: 175`, `versionName: 1.3.175`)
   - `.github/workflows/manage-releases.yml` (hardcoded pinned version `v1.3.128`)
2. **Root Cause of F-Droid Desynchronization:** 
   GitHub Actions (`deploy.yml`) generates dynamic version numbers during CI execution and passes them to Gradle via command line flags (`-PAPP_VERSION_CODE=...`), **without updating `gradle.properties` in git or creating matching Git tags**. 
   Because F-Droid's `checkupdates` bot checks Git Tags and parses `gradle.properties`, F-Droid never sees the dynamic versions created by GitHub Actions.
3. **Destructive Tag Pruning:** The `manage-releases.yml` workflow runs daily to **delete old Git tags and GitHub releases** (keeping only the latest 10). Deleting remote Git tags destroys F-Droid build reproducibility and breaks `UpdateCheckMode: Tags`.

---

## 2. Complete Version Inventory

| File / Location | Variable / Key | Current Value | Purpose / Consumption Site | Source of Truth? |
|---|---|---|---|---|
| [`gradle.properties:15`](file:///c:/Users/chait/Projects/pdf_tools/gradle.properties#L15) | `VERSION_CODE` | `210` | Default local Gradle version code | No (Duplicate) |
| [`gradle.properties:16`](file:///c:/Users/chait/Projects/pdf_tools/gradle.properties#L16) | `VERSION_NAME` | `1.3.210` | Default local Gradle version name | No (Duplicate) |
| [`gradle.properties:19`](file:///c:/Users/chait/Projects/pdf_tools/gradle.properties#L19) | `APP_VERSION_CODE` | `210` | Source of truth for `app/build.gradle.kts` & F-Droid | **Primary Candidate** |
| [`gradle.properties:20`](file:///c:/Users/chait/Projects/pdf_tools/gradle.properties#L20) | `APP_VERSION_NAME` | `1.3.210` | Source of truth for `app/build.gradle.kts` & F-Droid | **Primary Candidate** |
| [`app/build.gradle.kts:20`](file:///c:/Users/chait/Projects/pdf_tools/app/build.gradle.kts#L20) | `defaultConfig.versionCode` | `project.property("APP_VERSION_CODE")` | Injected into Android `BuildConfig.VERSION_CODE` | Consumptive |
| [`app/build.gradle.kts:21`](file:///c:/Users/chait/Projects/pdf_tools/app/build.gradle.kts#L21) | `defaultConfig.versionName` | `project.property("APP_VERSION_NAME")` | Injected into Android `BuildConfig.VERSION_NAME` | Consumptive |
| [`.github/workflows/deploy.yml:26`](file:///c:/Users/chait/Projects/pdf_tools/.github/workflows/deploy.yml#L26) | `VERSION_CODE_OFFSET` | `52` | CI base offset for version code calculations | No (Ephemeral CI) |
| [`.github/workflows/deploy.yml:28`](file:///c:/Users/chait/Projects/pdf_tools/.github/workflows/deploy.yml#L28) | `VERSION_PREFIX` | `'1.3'` | CI string prefix for version name calculations | No (Ephemeral CI) |
| [`.github/workflows/deploy.yml:66`](file:///c:/Users/chait/Projects/pdf_tools/.github/workflows/deploy.yml#L66) | `BUILD_NUMBER` | `github.run_number + 52` | Overrides `APP_VERSION_CODE` via `-P` CLI flag | Ephemeral Override |
| [`.github/workflows/build-release.yml:20`](file:///c:/Users/chait/Projects/pdf_tools/.github/workflows/build-release.yml#L20) | `VERSION_CODE_OFFSET` | `52` | Manual build workflow version code offset | No (Duplicate) |
| [`.github/workflows/ensure-release-files.yml:18`](file:///c:/Users/chait/Projects/pdf_tools/.github/workflows/ensure-release-files.yml#L18) | `VERSION_CODE_OFFSET` | `52` | Weekly maintenance workflow version offset | No (Duplicate) |
| [`.github/workflows/manage-releases.yml:56`](file:///c:/Users/chait/Projects/pdf_tools/.github/workflows/manage-releases.yml#L56) | `PINNED_TAGS` | `("v1.3.128")` | Hardcoded pinned release tag | No (Hardcoded) |
| [`metadata/com.yourname.pdftoolkit.yml:14`](file:///c:/Users/chait/Projects/pdf_tools/metadata/com.yourname.pdftoolkit.yml#L14) | `Builds[0].versionName` | `1.3.175` | F-Droid client app update display | Hardcoded F-Droid |
| [`metadata/com.yourname.pdftoolkit.yml:15`](file:///c:/Users/chait/Projects/pdf_tools/metadata/com.yourname.pdftoolkit.yml#L15) | `Builds[0].versionCode` | `175` | F-Droid package manager version code | Hardcoded F-Droid |
| [`metadata/com.yourname.pdftoolkit.yml:49`](file:///c:/Users/chait/Projects/pdf_tools/metadata/com.yourname.pdftoolkit.yml#L49) | `UpdateCheckData` | `gradle.properties\|APP_VERSION_CODE...` | RegEx parser for F-Droid auto-updates | Configuration |
| [`AGENTS.md:44`](file:///c:/Users/chait/Projects/pdf_tools/AGENTS.md#L44) | Guidelines | `APP_VERSION_CODE=<int>` | Developer/Agent documentation constraint | Documentation |

---

## 3. Release Pipeline Lifecycle & Diagram

The current release lifecycle operates as a disconnected dual-pipeline:

```mermaid
flowchart TD
    subgraph Local Development
        GP[gradle.properties<br>APP_VERSION_CODE=210<br>APP_VERSION_NAME=1.3.210]
        BG[app/build.gradle.kts]
        GP -->|Reads properties| BG
    end

    subgraph GitHub Actions CI/CD (deploy.yml)
        GRN[github.run_number]
        CALC[Compute: BUILD_NUMBER = run_number + 52<br>VERSION_NAME = 1.3.BUILD_NUMBER]
        CLI[./gradlew assembleRelease<br>-PAPP_VERSION_CODE=BUILD_NUMBER<br>-PAPP_VERSION_NAME=VERSION_NAME]
        GHR[GitHub Release & APK/AAB Upload]
        
        GRN --> CALC
        CALC --> CLI
        CLI --> GHR
    end

    subgraph F-Droid Update Engine (fdroiddata)
        TAGS[Git Remote Tags: v1.3.X]
        CHK[fdroid checkupdates]
        PARSER[Read gradle.properties in Git Tag]
        FDR[F-Droid Build Server]

        TAGS -->|Scans tags| CHK
        CHK -->|Parses gradle.properties| PARSER
        PARSER -->|Mismatch / Tag missing| FDR
    end

    subgraph Release Pruner (manage-releases.yml)
        CRON[Daily Cron Job]
        PRUNE[Delete Git Tags & Releases > 10 old]
        CRON --> PRUNE
        PRUNE -.->|Destroys Git Tags| TAGS
    end

    GP -.- X[NO AUTO COMMIT TO GIT OR TAG CREATION] -.- CALC
```

---

## 4. Root Cause Analysis

### Failure Mode 1: Ephemeral CI Version Bumping Without Git Commitment
In `deploy.yml`, when a release build is triggered by a commit to `master`:
1. GitHub Actions calculates `BUILD_NUMBER = github.run_number + 52`.
2. It passes `-PAPP_VERSION_CODE=${BUILD_NUMBER}` and `-PAPP_VERSION_NAME=1.3.${BUILD_NUMBER}` to Gradle.
3. It creates a GitHub Release asset tagged `v1.3.${BUILD_NUMBER}`.
4. **CRITICAL MISSING STEP:** It **never updates `gradle.properties` in git**, nor does it commit/push the new version back to the repository. As a result, `gradle.properties` in `master` remains stuck at `210`.

### Failure Mode 2: F-Droid Update Checker Disconnect
F-Droid's update bot (`fdroid checkupdates`) uses tag-based auto-updating configured in `metadata/com.yourname.pdftoolkit.yml`:
```yaml
AutoUpdateMode: Version
UpdateCheckMode: Tags
UpdateCheckData: gradle.properties|APP_VERSION_CODE=(\\d+)|.|APP_VERSION_NAME=(.+)
```
- When F-Droid checks the git repository for new tags, it inspects `gradle.properties` inside each tag.
- Because CI builds generate version numbers dynamically without committing updated `gradle.properties` to Git, any git tag created by CI or developers contains stale `gradle.properties` values.
- `fdroid checkupdates` encounters a contradiction between the tag name (e.g. `v1.3.250`) and `gradle.properties` (`APP_VERSION_CODE=210`), resulting in the error: `"Couldn't find any version information"`.

### Failure Mode 3: Destructive Tag Pruning (`manage-releases.yml`)
The workflow `manage-releases.yml` executes a daily cron job that deletes Git tags and GitHub releases older than the 10 most recent entries:
```bash
git push origin --delete "$TAG"
```
Deleting remote Git tags breaks F-Droid's build reproducibility and prevents F-Droid maintainers from auditing or building historical releases against tagged commits.

---

## 5. Single Source of Truth & Duplication Analysis

Version information is currently defined or overridden in **6 separate places**:

```
1. gradle.properties (VERSION_CODE / APP_VERSION_CODE)
2. .github/workflows/deploy.yml (VERSION_CODE_OFFSET + run_number)
3. .github/workflows/build-release.yml (VERSION_CODE_OFFSET + run_number)
4. .github/workflows/ensure-release-files.yml (VERSION_CODE_OFFSET + fallback parser)
5. metadata/com.yourname.pdftoolkit.yml (Hardcoded build recipes)
6. .github/workflows/manage-releases.yml (Hardcoded pinned version tag v1.3.128)
```

| Location | Is Source of Truth? | Is Duplicate? | Risk Level |
|---|---|---|---|
| `gradle.properties` | **YES (Designated SOT)** | No | Low |
| `app/build.gradle.kts` | No (Consumes SOT) | No | Low |
| `.github/workflows/deploy.yml` | No (Overrides SOT) | **YES** | **CRITICAL** (Causes F-Droid desync) |
| `.github/workflows/build-release.yml` | No (Overrides SOT) | **YES** | **HIGH** (Inconsistent CLI overrides) |
| `.github/workflows/ensure-release-files.yml` | No (Overrides SOT) | **YES** | **HIGH** (Scrapes tags & re-computes) |
| `metadata/com.yourname.pdftoolkit.yml` | No (Static Mirror) | **YES** | **CRITICAL** (Falls behind releases) |
| `.github/workflows/manage-releases.yml` | No (Tag Deletion) | **YES** | **CRITICAL** (Deletes Git tags) |

---

## 6. Automation Opportunities & Candidates

### Candidate Option A: Static `gradle.properties` as Single Source of Truth
Developer updates `gradle.properties` (`APP_VERSION_CODE` / `APP_VERSION_NAME`) and pushes a Git tag `vX.Y.Z`. CI reads `gradle.properties` directly.

- **Complexity:** Extremely Low.
- **Reliability:** Very High.
- **F-Droid Compatibility:** **100% (Native F-Droid design)**.
- **Play Store Compatibility:** **100%**.
- **Maintenance Burden:** Requires manual version bump in `gradle.properties` before release.

---

### Candidate Option B: Git Tag as Single Source of Truth (`git describe`)
Version derived dynamically at build time from `git describe --tags`.

- **Complexity:** Medium.
- **Reliability:** Medium.
- **F-Droid Compatibility:** **POOR / BROKEN** (F-Droid `checkupdates` cannot execute custom Gradle plugins or dynamic shell commands during metadata inspection).
- **Play Store Compatibility:** High.
- **Maintenance Burden:** Low.

---

### Candidate Option C: Automated CI Bumping & Tagging (RECOMMENDED)
CI workflow updates `gradle.properties`, creates a Git tag `vX.Y.Z`, commits back to `master`, and triggers the build.

- **Complexity:** Low-Medium.
- **Reliability:** High.
- **F-Droid Compatibility:** **100%**.
- **Play Store Compatibility:** **100%**.
- **Maintenance Burden:** Zero manual version updates.

---

### Candidate Option D: Semantic Release / Conventional Commits
Versions computed from commit messages (`feat:`, `fix:`, `BREAKING CHANGE:`).

- **Complexity:** High.
- **Reliability:** High.
- **F-Droid Compatibility:** High (if it commits `gradle.properties` and pushes tags).
- **Play Store Compatibility:** High.
- **Maintenance Burden:** Low (strict commit syntax enforcement required).

---

## 7. Recommended Future Architecture

### Architecture Principles
1. **Single Source of Truth:** `gradle.properties` (`APP_VERSION_CODE` and `APP_VERSION_NAME`) is the **ONLY canonical version source**.
2. **Synchronized Tagging:** Every release MUST have a Git tag `vX.Y.Z` where `gradle.properties` in that tag matches `X.Y.Z` and `APP_VERSION_CODE`.
3. **No CI Property Overrides:** CI workflows MUST stop using `-PAPP_VERSION_CODE` and `-PAPP_VERSION_NAME` CLI flags; they must build directly from `gradle.properties`.
4. **Preserve Git Tags:** Disable Git tag deletion in `manage-releases.yml`.

---

## 8. Step-by-Step Migration Plan

```
Phase 1: Single Source of Truth Standardization
  ├── Stop dynamic version calculation in deploy.yml
  └── Align gradle.properties (APP_VERSION_CODE & APP_VERSION_NAME) as SOT

Phase 2: CI/CD Release Workflow Alignment
  ├── Modify deploy.yml to read gradle.properties directly
  ├── Auto-create & push Git tag v${APP_VERSION_NAME} upon release
  └── Remove -PAPP_VERSION_CODE and -PAPP_VERSION_NAME CLI flags

Phase 3: F-Droid Metadata & Workflow Cleanup
  ├── Update metadata/com.yourname.pdftoolkit.yml UpdateCheckData
  └── Disable git tag deletion in manage-releases.yml
```
