# Versioning Strategy

This project uses **dynamic CI/CD versioning** to eliminate version conflicts and streamline deployments.

## How It Works

### Version Code
- Computed automatically: `github.run_number + VERSION_CODE_OFFSET`
- Never stored in git
- Always increments (GitHub's run_number never decreases)
- Example: Run #5 with offset 52 → versionCode = 57

### Version Name
- Format: `VERSION_PREFIX.versionCode`
- Example: `1.3.57` (prefix: 1.3, code: 57)
- Automatically matches the version code

### Configuration
Edit `.github/workflows/deploy.yml`:
```yaml
env:
  VERSION_CODE_OFFSET: 52  # Current Play Store version
  VERSION_PREFIX: '1.3'     # Major.Minor prefix
```

## Benefits

✅ **No Git Conflicts**: Versions never committed, no merge conflicts  
✅ **Auto-Incrementing**: Guaranteed unique version codes  
✅ **Parallel Branches**: Multiple branches can build without collisions  
✅ **Simpler Workflow**: No manual version bumps needed

## Skip CI

To skip CI/CD on a commit, include one of these in your commit message:
- `[skip ci]`
- `[ci skip]`
- `[no ci]`
- `[skip actions]`
- `[actions skip]`

Example:
```bash
git commit -m "docs: update README [skip ci]"
```

## Local Development

Local builds use default versions:
- versionCode: 1
- versionName: "1.0.0-local"

These won't conflict with Play Store releases.

## Manual Builds

Use the "Build Release AAB" workflow with custom inputs:
1. Go to Actions → Build Release AAB → Run workflow
2. Enter custom version name and code (optional)
3. Download the AAB artifact

## Migration Notes

- Removed `VERSION` and `VERSION_CODE` files (no longer needed)
- Removed hardcoded versions from `build.gradle.kts`
- CI/CD now computes versions dynamically
- No more version increment commits after deployments
