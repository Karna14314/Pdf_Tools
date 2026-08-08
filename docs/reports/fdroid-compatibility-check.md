# F-Droid Compatibility Check Report

**Document Path:** `docs/reports/fdroid-compatibility-check.md`  
**Date:** 2026-08-08  
**Author:** Antigravity AI Agent  

---

## 1. Executive Summary

F-Droid compatibility was audited to confirm that future releases tagged in Git will be automatically discovered and built by F-Droid's `checkupdates` bot without falling behind GitHub releases.

---

## 2. Before vs. After State

| Parameter | Before Migration | After Migration |
|---|---|---|
| **Version Source** | Ephemeral CI calculation (`github.run_number + 52`) passed via `-P` CLI flags | Canonical `gradle.properties` (`APP_VERSION_CODE` and `APP_VERSION_NAME`) |
| **Git Tagging** | Tags omitted or deleted daily by `manage-releases.yml` | Tags permanently preserved on remote repository |
| **`checkupdates` Regex** | `gradle.properties|APP_VERSION_CODE=(\d+)|.|APP_VERSION_NAME=(.+)` | Unchanged (Now matches committed `gradle.properties`) |
| **F-Droid Build Recipe** | Hardcoded at `1.3.175` | Added recipe block for `1.3.210` (`APP_VERSION_CODE=210`) |

---

## 3. Expected Update Detection Behavior

With this architecture active:
1. Developer updates `gradle.properties` (`APP_VERSION_CODE=211`, `APP_VERSION_NAME=1.3.211`).
2. Developer tags the commit `v1.3.211` and pushes to GitHub.
3. `fdroid checkupdates` scans git tags, matches `v1.3.211`, parses `APP_VERSION_CODE=211` and `APP_VERSION_NAME=1.3.211` directly from `gradle.properties` in that tag.
4. F-Droid auto-generates the build recipe and compiles the release binary natively.
