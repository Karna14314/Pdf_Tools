# Versioning Migration Pre-Check Report

**Document Path:** `docs/reports/versioning-migration-precheck.md`  
**Date:** 2026-08-08  
**Author:** Antigravity AI Agent  

---

## 1. Current Deploy Workflow Behavior (`deploy.yml`)

Prior to migration, `.github/workflows/deploy.yml` calculated dynamic version codes and names on every run:
```yaml
VERSION_CODE_OFFSET: 52
VERSION_PREFIX: '1.3'
BUILD_NUMBER=$(( RUN_NUM + OFFSET ))
VERSION_NAME="${PREFIX}.${BUILD_NUMBER}"
```
Gradle builds were executed with CLI property overrides:
```bash
./gradlew assemblePlaystoreRelease \
  -PAPP_VERSION_CODE=${BUILD_NUMBER} \
  -PAPP_VERSION_NAME=${VERSION_NAME}
```
**Issues Identified:**
- Overrode `gradle.properties` in CI without updating `gradle.properties` in git.
- Generated dynamic version codes (e.g. `250`) that were never committed back to the repository.

---

## 2. Current Release Workflow Behavior (`build-release.yml`, `ensure-release-files.yml`)

- `build-release.yml` contained duplicate `VERSION_CODE_OFFSET: 52` and `VERSION_PREFIX: '1.3'` logic.
- `ensure-release-files.yml` attempted to extract version names from tag strings (`v1.3.129 -> 1.3.129`), read a non-existent `VERSION_CODE` file, and fell back to computing `VERSION_CODE` from regex matching on tag numbers.

---

## 3. Current Tag Behavior (`manage-releases.yml`)

- `manage-releases.yml` ran a daily cron job to delete old releases and tags:
```bash
gh release delete "$TAG" --yes
git push origin --delete "$TAG"
```
**Issues Identified:**
- Deleting remote Git tags breaks F-Droid build reproducibility and historical version tracking.

---

## 4. Current F-Droid Metadata State (`metadata/com.yourname.pdftoolkit.yml`)

- Contains hardcoded build recipe:
```yaml
Builds:
  - versionName: 1.3.175
    versionCode: 175
    commit: 455bdba0166df19db27eeda40528f03fadccd0f9
    subdir: app
    gradle:
      - fdroid
    gradleprops:
      - VERSION_CODE=175
      - VERSION_NAME=1.3.175
```
- Configured with `UpdateCheckMode: Tags` and `UpdateCheckData: gradle.properties|APP_VERSION_CODE=(\\d+)|.|APP_VERSION_NAME=(.+)`.
- Because CI generated dynamic versions without updating `gradle.properties` or pushing git tags, `checkupdates` failed to discover new versions.
