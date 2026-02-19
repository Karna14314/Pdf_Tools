# F-Droid Submission Guide

## Prerequisites Checklist

✅ Open source (Apache 2.0 license)
✅ No proprietary dependencies in fdroid flavor
✅ Builds cleanly from source
✅ F-Droid metadata file created

## App Information

- **Package Name**: `com.yourname.pdftoolkit`
- **App Name**: PDF Toolkit
- **License**: Apache-2.0
- **Source Repository**: https://github.com/Karna14314/Pdf_Tools
- **Current Version**: 1.3.11 (Build 38)
- **Min SDK**: 26 (Android 8.0)
- **Target SDK**: 35 (Android 15)

## Important: Tesseract Training Data Required

Before F-Droid can build your app, you need to add Tesseract training data:

1. Create directory: `app/src/fdroid/assets/tessdata/`
2. Download English training data:
   ```bash
   wget https://github.com/tesseract-ocr/tessdata/raw/main/eng.traineddata
   ```
3. Place `eng.traineddata` in the tessdata directory
4. Commit and push to your repository

## Step-by-Step Submission Process

### STEP 1: Verify Build

Test that the F-Droid flavor builds successfully:

```bash
# Clean build
./gradlew clean

# Build F-Droid flavor
./gradlew assembleFdroidRelease

# Verify the APK was created
ls -lh app/build/outputs/apk/fdroid/release/
```

**Expected output**: APK file around 62.5 MB

### STEP 2: F-Droid Metadata File

The metadata file has been created at: `metadata/com.yourname.pdftoolkit.yml`

Key sections:
- **Categories**: Reading, Writing
- **License**: Apache-2.0
- **Build flavor**: fdroid (uses Tesseract OCR instead of ML Kit)
- **AutoUpdate**: Enabled (tracks git tags)

### STEP 3: Fork and Clone fdroiddata

You'll need to use GitLab since F-Droid uses GitLab:

```bash
# Install glab CLI if not present
# On Ubuntu/Debian: sudo apt install glab
# On macOS: brew install glab
# Or download from: https://gitlab.com/gitlab-org/cli/-/releases

# Authenticate with GitLab
glab auth login

# Fork the fdroiddata repository
glab repo fork fdroid/fdroiddata --clone

# Navigate to the cloned directory
cd fdroiddata
```

### STEP 4: Create Branch and Add Metadata

```bash
# Create a new branch
git checkout -b add-pdf-toolkit

# Copy the metadata file
cp /path/to/your/repo/metadata/com.yourname.pdftoolkit.yml metadata/

# Verify the file is in place
ls -l metadata/com.yourname.pdftoolkit.yml
```

### STEP 5: Install fdroidserver and Run Lint

```bash
# Install fdroidserver
pip install fdroidserver

# Run lint to check for errors
fdroid lint com.yourname.pdftoolkit

# If there are errors, fix them in the metadata file
# Common issues:
# - Invalid category names
# - Missing required fields
# - Incorrect SPDX license identifier
# - Invalid git commit/tag references

# Re-run lint after fixes
fdroid lint com.yourname.pdftoolkit
```

**Expected output**: No errors or warnings

### STEP 6: Commit and Push

```bash
# Stage the metadata file
git add metadata/com.yourname.pdftoolkit.yml

# Commit with descriptive message
git commit -m "Add PDF Toolkit

New app submission for PDF Toolkit, a comprehensive PDF and image
manipulation tool for Android.

Package: com.yourname.pdftoolkit
License: Apache-2.0
Source: https://github.com/Karna14314/Pdf_Tools

The app provides PDF merging, splitting, compression, conversion,
OCR, watermarking, and many other PDF manipulation features.

The F-Droid version uses Tesseract OCR (open source) instead of
Google ML Kit to maintain compatibility with F-Droid's free
software requirements."

# Push to your fork
git push origin add-pdf-toolkit
```

### STEP 7: Create Merge Request

```bash
# Create the merge request using glab
glab mr create \
  --source-branch add-pdf-toolkit \
  --target-branch master \
  --target-repo fdroid/fdroiddata \
  --title "Add PDF Toolkit" \
  --description "New app submission: PDF Toolkit

Package: com.yourname.pdftoolkit
License: Apache-2.0
Source: https://github.com/Karna14314/Pdf_Tools

PDF Toolkit is a comprehensive PDF and image manipulation tool that provides:
- PDF merging, splitting, and compression
- PDF to image and image to PDF conversion
- OCR using Tesseract (open source)
- Watermarking and page numbering
- Password protection and digital signatures
- Document scanning with camera
- And many more PDF manipulation features

The app has no proprietary dependencies in the fdroid flavor and builds
cleanly from source. It uses Tesseract OCR instead of Google ML Kit to
maintain F-Droid compatibility.

Build tested successfully with:
\`\`\`
./gradlew assembleFdroidRelease
\`\`\`

All lint checks pass without errors."
```

The command will output the MR URL. Save this URL to monitor the review process.

## Post-Submission

### What Happens Next?

1. **Automated Checks**: F-Droid's CI will automatically build your app
2. **Review Process**: F-Droid maintainers will review your submission
3. **Feedback**: You may receive comments or requests for changes
4. **Approval**: Once approved, your app will be added to F-Droid

### Expected Timeline

- Initial automated checks: 1-2 hours
- Human review: 1-2 weeks (can vary)
- Total time to publication: 2-4 weeks

### Monitoring Your Submission

- Check the MR URL regularly for comments
- Respond promptly to any feedback
- F-Droid maintainers may request changes to:
  - Metadata file
  - Build configuration
  - App description
  - Screenshots

## Common Issues and Solutions

### Issue: Build Fails - Missing Tesseract Data

**Solution**: Add `eng.traineddata` to `app/src/fdroid/assets/tessdata/`

### Issue: Lint Error - Invalid Category

**Solution**: Use only F-Droid approved categories:
- Connectivity, Development, Games, Graphics, Internet, Money
- Multimedia, Navigation, Phone & SMS, Reading, Science & Education
- Security, Sports & Health, System, Time, Writing

### Issue: Build Fails - Proprietary Dependencies

**Solution**: Ensure you're building the `fdroid` flavor, not `playstore`

### Issue: Version Mismatch

**Solution**: Update the metadata file with the correct version from your latest git tag

## Updating Your App on F-Droid

After your app is accepted, F-Droid will automatically check for updates:

1. Create a new git tag: `git tag v1.3.12 && git push origin v1.3.12`
2. F-Droid's bot will detect the new tag
3. It will automatically build and publish the update
4. No manual intervention needed!

## Support

- F-Droid Forum: https://forum.f-droid.org/
- F-Droid Issue Tracker: https://gitlab.com/fdroid/fdroiddata/-/issues
- F-Droid Documentation: https://f-droid.org/docs/

## Checklist Before Submission

- [ ] Tesseract training data added to repository
- [ ] F-Droid flavor builds successfully
- [ ] Metadata file passes lint checks
- [ ] Git tag exists for the version being submitted
- [ ] All proprietary dependencies removed from fdroid flavor
- [ ] App description is clear and accurate
- [ ] License file is present in repository
- [ ] Source code is publicly accessible

## Notes

- The F-Droid version uses Tesseract OCR (62.5 MB APK, no downloads)
- The Play Store version uses ML Kit (33.5 MB APK + 40 MB download)
- Both versions provide the same user experience
- F-Droid users get a fully open-source app with no proprietary components
