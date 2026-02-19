# Dual-Store Deployment Configuration

## Overview
Your GitHub Actions workflow now supports automated deployment to both Google Play Store and Indus App Store with fault tolerance and unified versioning.

## Changes Made

### 1. Build Configuration (`app/build.gradle.kts`)
- Modified to accept `VERSION_CODE` environment variable (primary)
- Falls back to `APP_VERSION_CODE` for backward compatibility
- Defaults to `1` if neither is set (for local builds)

```kotlin
versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() 
    ?: System.getenv("APP_VERSION_CODE")?.toIntOrNull() 
    ?: 1
```

### 2. GitHub Actions Workflow (`.github/workflows/deploy.yml`)

#### Unified Versioning
- Uses `VERSION_CODE` file as source of truth
- Sets both `VERSION_CODE` and `APP_VERSION_CODE` environment variables
- Ensures unique, incrementing version codes for every build

#### Dual Deployment with Fault Tolerance
- **Play Store Deployment**: Uses existing `r0adkll/upload-google-play@v1` action
- **Indus App Store Deployment**: Uses `yogeshpaliyal/upload-indus-appstore@v1` action
- Both steps use `continue-on-error: true` for fault tolerance
- Job succeeds if at least ONE store deployment succeeds
- Version increment only happens if at least one deployment succeeds

#### Release Notes
- Uses `distribution/whatsnew/whatsnew-en-US` for Play Store
- Indus App Store action handles release notes automatically from the same source

## Required GitHub Secrets

Ensure these secrets are configured in your repository:

### Existing Secrets (Play Store)
- `PLAY_STORE_JSON_KEY` - Service account JSON for Play Store API
- `ANDROID_KEYSTORE_BASE64` - Base64 encoded keystore file
- `KEYSTORE_PASSWORD` - Keystore password
- `KEY_ALIAS` - Key alias
- `KEY_PASSWORD` - Key password

### New Secret (Indus App Store)
- `INDUS_APP_STORE_KEY` - ✅ Already configured (Bearer token)

## How It Works

1. **Build Phase**: Single AAB is built with unified version code
2. **Deploy Phase**: 
   - Uploads to Play Store (internal track)
   - Uploads to Indus App Store
   - Both run independently with fault tolerance
3. **Version Increment**: Only if at least one deployment succeeds
4. **Failure Handling**:
   - If both fail → Job fails, no version increment
   - If one fails → Job succeeds with warning, version increments
   - If both succeed → Job succeeds, version increments

## Deployment Summary

After each deployment, you'll see:
```
=== Deployment Summary ===
Play Store: success
Indus App Store: success
```

Or with partial failure:
```
=== Deployment Summary ===
Play Store: success
Indus App Store: failure
WARNING: Indus App Store deployment failed, but Play Store succeeded
At least one deployment succeeded - proceeding with version increment
```

## Testing

To test the workflow:
1. Push to `master` branch
2. Monitor GitHub Actions tab
3. Check deployment results for both stores
4. Verify version increment in VERSION and VERSION_CODE files

## Notes

- The existing Play Store deployment logic is preserved
- Release notes are automatically generated from commit messages
- Both stores receive the same version code and version name
- The workflow uses the same keystore for both stores
