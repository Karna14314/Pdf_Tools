# Pipeline Validation Report

**Document Path:** `docs/reports/pipeline-validation.md`  
**Date:** 2026-08-08  
**Author:** Antigravity AI Agent  

---

## 1. Summary of Pipeline Checks

All workflows in `.github/workflows/` and metadata in `metadata/` were validated to ensure zero broken references, zero syntax errors, and zero remaining dynamic CLI version parameters.

---

## 2. Validation Status by Workflow File

| File Path | Purpose | Status | Stale Overrides Found |
|---|---|---|---|
| `.github/workflows/deploy.yml` | Primary release & deploy pipeline | **PASSED** | 0 |
| `.github/workflows/build-release.yml` | Manual AAB build workflow | **PASSED** | 0 |
| `.github/workflows/ensure-release-files.yml` | Weekly missing release file fixer | **PASSED** | 0 |
| `.github/workflows/manage-releases.yml` | Release asset pruner | **PASSED** | 0 |
| `.github/workflows/pin-release.yml` | Release pinning workflow | **PASSED** | 0 |
| `.github/workflows/release-dual-apks.yml` | Legacy pointer workflow | **PASSED** | 0 |
| `.github/workflows/test.yml` | CI test & compilation check | **PASSED** | 0 |
| `metadata/com.yourname.pdftoolkit.yml` | F-Droid metadata recipe | **PASSED** | 0 |

---

## 3. Results & Integrity Guarantee

All GitHub Actions workflows now read version information exclusively from `gradle.properties`. No CLI version overrides (`-PAPP_VERSION_CODE` / `-PAPP_VERSION_NAME`) or `VERSION_CODE_OFFSET` math logic exist in the repository.
