# GitHub Release Management Workflow

## Overview

The `manage-releases.yml` workflow automatically manages GitHub releases and keeps only the 5 most recent releases to prevent repository bloat.

## Features

1. **Automatic Release Creation**: Creates a GitHub release for every push to master
2. **Artifact Management**: Copies build artifacts to `releases/` folder and commits them
3. **Version Tagging**: Generates unique tags based on VERSION file or timestamp
4. **Automatic Pruning**: Keeps only the 5 most recent releases, deleting older ones
5. **Tag Cleanup**: Removes git tags for deleted releases

## Workflow Triggers

The workflow runs on every push to the `master` branch.

## Required Permissions

The workflow requires the following permissions (already configured in the YAML):

```yaml
permissions:
  contents: write  # Create/delete releases, tags, and commit files
  actions: write   # Workflow operations
```

### Ensuring Permissions are Set

#### Option 1: Repository Settings (Recommended)

1. Go to your repository on GitHub
2. Navigate to **Settings** → **Actions** → **General**
3. Scroll to **Workflow permissions**
4. Select **Read and write permissions**
5. Check **Allow GitHub Actions to create and approve pull requests**
6. Click **Save**

#### Option 2: Using the Workflow File

The permissions are already set in the workflow file:

```yaml
permissions:
  contents: write
  actions: write
```

This ensures the workflow has the necessary permissions regardless of repository settings.

## Workflow Steps

### 1. Checkout Code
- Fetches full git history (`fetch-depth: 0`)
- Required for accessing all tags

### 2. Create Releases Directory
- Creates `releases/` folder if it doesn't exist
- This folder will store build artifacts

### 3. Copy Build Artifacts
- Checks for AAB files in `app/build/outputs/bundle/playstoreRelease/`
- Copies AAB and mapping files to `releases/`
- Skips if no artifacts are found (won't fail)

### 4. Commit and Push
- Commits the `releases/` folder with artifacts
- Uses `[skip ci]` to prevent infinite loops
- Pushes to master branch

### 5. Generate Unique Tag
- Reads version from `VERSION` file (e.g., `v1.3.12`)
- Falls back to timestamp if VERSION file doesn't exist
- Appends short SHA if tag already exists

### 6. Create GitHub Release
- Creates a new release with the generated tag
- Includes release notes with version info and commit message
- Uploads artifacts from `releases/` folder

### 7. Prune Old Releases
- Lists all releases sorted by creation date
- Keeps the 5 most recent releases
- Deletes older releases and their tags
- Safe: Won't fail if fewer than 5 releases exist

## Usage Examples

### Scenario 1: Regular Deployment

When you push to master after a successful build:

```bash
git add .
git commit -m "feat: add new feature"
git push origin master
```

**Result**:
- Workflow creates release `v1.3.12`
- Uploads AAB and mapping files
- Keeps 5 most recent releases
- Deletes older releases automatically

### Scenario 2: No Build Artifacts

When you push documentation or config changes:

```bash
git add README.md
git commit -m "docs: update readme"
git push origin master
```

**Result**:
- Workflow runs but skips artifact copying
- Still creates a release (for tracking)
- Prunes old releases as usual

### Scenario 3: First 5 Releases

When you have fewer than 5 releases:

**Result**:
- Workflow creates new releases normally
- Pruning step does nothing (keeps all)
- No releases are deleted

## Customization

### Change Number of Releases to Keep

Edit the pruning step in `.github/workflows/manage-releases.yml`:

```yaml
# Change this line:
if [ $TOTAL_RELEASES -le 5 ]; then

# To keep 10 releases:
if [ $TOTAL_RELEASES -le 10 ]; then

# And change this line:
TO_DELETE=$((TOTAL_RELEASES - 5))

# To:
TO_DELETE=$((TOTAL_RELEASES - 10))
```

### Change Tag Format

Edit the tag generation step:

```yaml
# Current format: v1.3.12
TAG="v${VERSION}"

# Alternative formats:
TAG="release-${VERSION}"           # release-1.3.12
TAG="${VERSION}-$(date +%Y%m%d)"   # 1.3.12-20260219
TAG="build-${VERSION_CODE}"        # build-39
```

### Add More Artifacts

Edit the artifact upload step:

```yaml
# Current: uploads everything in releases/
gh release upload "$TAG" releases/* --clobber

# Upload specific files:
gh release upload "$TAG" \
  releases/*.aab \
  releases/*.txt \
  app/build/outputs/apk/fdroid/release/*.apk \
  --clobber
```

## Monitoring

### View Workflow Runs

1. Go to your repository on GitHub
2. Click **Actions** tab
3. Select **Manage Releases and Prune Old Versions**
4. View individual workflow runs

### Check Release Status

```bash
# List all releases
gh release list

# View specific release
gh release view v1.3.12

# Download release artifacts
gh release download v1.3.12
```

## Troubleshooting

### Issue: Permission Denied

**Error**: `Resource not accessible by integration`

**Solution**:
1. Check repository settings (Settings → Actions → General)
2. Ensure "Read and write permissions" is selected
3. Verify workflow has `permissions: contents: write`

### Issue: Tag Already Exists

**Error**: `tag already exists`

**Solution**: The workflow automatically handles this by appending the commit SHA

### Issue: No Artifacts Found

**Warning**: `No artifacts to upload`

**Solution**: This is normal if the build didn't run. The workflow continues without failing.

### Issue: Workflow Runs Twice

**Problem**: Workflow triggers itself when committing releases/

**Solution**: The workflow uses `[skip ci]` in commit messages to prevent this

### Issue: Old Releases Not Deleted

**Check**:
1. Verify you have more than 5 releases
2. Check workflow logs for errors in the pruning step
3. Ensure GITHUB_TOKEN has delete permissions

## Best Practices

1. **Version Files**: Keep VERSION and VERSION_CODE files updated
2. **Git Tags**: Create git tags for releases: `git tag v1.3.12`
3. **Build Before Push**: Ensure builds complete before pushing to master
4. **Monitor Workflow**: Check Actions tab after each push
5. **Release Notes**: Customize release notes in the workflow for better documentation

## Integration with Deployment Workflow

This workflow works alongside your existing `deploy.yml`:

1. `deploy.yml` builds and deploys to stores
2. `manage-releases.yml` creates GitHub releases and manages versions
3. Both can run independently without conflicts

## Safety Features

- **No Data Loss**: Only deletes releases, not source code
- **Keeps Recent**: Always keeps 5 most recent releases
- **Graceful Failure**: Continues even if some steps fail
- **Skip CI**: Prevents infinite loops with `[skip ci]`
- **Full History**: Uses `fetch-depth: 0` for complete tag access

## Example Release Output

```
Release v1.3.12

## PDF Toolkit 1.3.12 (Build 39)

### Changes
feat: implement build flavors for dual OCR engine support

### Build Info
- Version: 1.3.12
- Build Code: 39
- Commit: b89f480
- Date: 2026-02-19 11:30:00 UTC

### Downloads
- Play Store: Available in internal track
- F-Droid: Submission in progress

Assets:
- PDFToolkit-v1.3.12-playstoreRelease.aab (33.5 MB)
- mapping-20260219-113000.txt (1.2 MB)
```

## Summary

The release management workflow provides:
- ✅ Automatic release creation
- ✅ Artifact storage and versioning
- ✅ Automatic cleanup of old releases
- ✅ Tag management
- ✅ Zero manual intervention required
- ✅ Safe and reliable operation

Your releases are now managed automatically with every push to master!
