# Build Version Verification Report

**Document Path:** `docs/reports/build-version-verification.md`  
**Date:** 2026-08-08  
**Author:** Antigravity AI Agent  
**Build Target:** `./gradlew :app:assembleFdroidDebug`  
**Status:** **100% VERIFIED**  

---

## 1. Executive Summary

Build artifact output and generated `BuildConfig.java` sources were inspected to verify that `versionCode` and `versionName` derive exclusively from `gradle.properties`.

---

## 2. Generated `BuildConfig.java` Inspection

Inspected file: `app/build/generated/source/buildConfig/fdroid/debug/com/yourname/pdftoolkit/BuildConfig.java`

```java
public final class BuildConfig {
  public static final boolean DEBUG = Boolean.parseBoolean("true");
  public static final String APPLICATION_ID = "com.yourname.pdftoolkit.debug";
  public static final String BUILD_TYPE = "debug";
  public static final String FLAVOR = "fdroid";
  public static final int VERSION_CODE = 210;
  public static final String VERSION_NAME = "1.3.210-debug";
}
```

---

## 3. Verification Matrix

| Property | `gradle.properties` Source | Generated `BuildConfig.java` | Status |
|---|---|---|---|
| **Version Code** | `APP_VERSION_CODE=210` | `VERSION_CODE = 210` | **MATCHED** |
| **Version Name** | `APP_VERSION_NAME=1.3.210` | `VERSION_NAME = "1.3.210-debug"` | **MATCHED** (Debug suffix applied) |
| **Gradle Task** | `:app:assembleFdroidDebug` | `BUILD SUCCESSFUL in 29s` | **PASSED** |
