# Play Store Deployment Guide for PDF Toolkit

This guide covers building the signed release bundle and uploading it to the Google Play Console.

## 1. Prerequisites
Ensure you have the following installed:
- **Java Development Kit (JDK) 17**
- **Android SDK**
- **Gradle** (if `gradlew` is missing)

## 2. Build the Signed Bundle (AAB)
Google Play requires an **Android App Bundle (.aab)** instead of an APK.

### Step 2.1: Ensure Gradle Wrapper Exists
If you don't see `gradlew` in the root folder, run:
```bash
gradle wrapper
```

### Step 2.2: Build the Release Bundle
Run the following command in the project root:
```bash
./gradlew bundleRelease
```
*Note: This uses the credentials from `keystore.properties`.*

### Step 2.3: Locate the Output
Upon success, the bundle will be located at:
`app/build/outputs/bundle/release/app-release.aab`

## 3. Upload to Google Play Console

### Step 3.1: Create Application
1.  Go to [Google Play Console](https://play.google.com/console).
2.  Click **Create App**.
3.  Enter App Name: "PDF Toolkit" (or your specific name).
4.  Select **App** and **Free**.
5.  Accept declarations and create.

### Step 3.2: Set up App Content
Complete the required sections in the Dashboard:
- **Privacy Policy**: Link to your privacy policy URL.
- **App Access**: All functionality is available without special access.
- **Ads**: No ads.
- **Content Rating**: Fill out the questionnaire (Utility app, usually Rated 3+).
- **Target Audience**: 13+ or 18+.
- **News Apps**: No.
- **Data Safety**: Declare that no data is collected/shared (Offline app).

### Step 3.3: Store Listing
- **Short Description**: "Offline PDF tools: Merge, Split, Compress, Secure, and more."
- **Full Description**: detailed list of features (Edit, Convert, Optimize).
- **Graphics**: Upload App Icon (512x512) and Feature Graphic (1024x500). Upload Phone/Tablet screenshots.

### Step 3.4: Create Release
1.  Go to **Testing > Internal testing** (recommended first).
2.  Click **Create new release**.
3.  **Signing Key**: Let Google Play Manage your signing key (Recommended).
4.  **Upload**: Upload the `app-release.aab` file generated in Step 2.
5.  **Release Name**: 1.0.0.
6.  **Release Notes**: "Initial Release - Core PDF Tools".
7.  **Review Release** and **Start Rollout**.

## 4. Troubleshooting
- **Build Fails**: Check `keystore.properties` paths and passwords.
- **Signing Error**: Ensure `keyAlias` matches what was generated.
- **Lint Errors**: Run `./gradlew lintRelease` to check for issues.
