# Release Flow Validation Report

**Document Path:** `docs/reports/release-flow-validation.md`  
**Date:** 2026-08-08  
**Author:** Antigravity AI Agent  

---

## 1. End-to-End Release Workflow

The newly aligned release workflow operates seamlessly across local development, GitHub CI/CD, Play Store, and F-Droid:

```
Developer
  │
  ├─► 1. Bumps APP_VERSION_CODE and APP_VERSION_NAME in gradle.properties
  ├─► 2. Commits: "chore: bump version to 1.3.211"
  ├─► 3. Tags: git tag -a v1.3.211 -m "Release v1.3.211"
  └─► 4. Pushes: git push origin master --tags
        │
        ▼
   GitHub Actions (deploy.yml)
        │
        ├─► Reads APP_VERSION_CODE & APP_VERSION_NAME directly from gradle.properties
        ├─► Builds AAB (Play Store) & APKs (PlayStore, OpenSource, F-Droid)
        ├─► Deploys AAB to Google Play Store / Indus App Store
        └─► Creates GitHub Release tagged `v1.3.211` with release assets
        │
        ▼
   F-Droid Build Server (fdroiddata)
        │
        ├─► Scans Git remote tags (discovers `v1.3.211`)
        ├─► Reads `gradle.properties` inside tag `v1.3.211`
        └─► Automatically builds FOSS APK natively from source
```

---

## 2. Advantages of Aligned Workflow

- **Zero Desynchronization:** GitHub Releases, Play Store AABs, Git Tags, and F-Droid metadata share the exact same version code and name.
- **Reproducible Builds:** F-Droid and third-party auditors can check out any tag `vX.Y.Z` and build the exact binary without missing environment variables or CLI property flags.
- **Simple Developer Maintenance:** Releasing a new version requires updating `gradle.properties` and pushing a Git tag.
