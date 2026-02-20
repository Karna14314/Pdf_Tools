import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.yourname.pdftoolkit"
    compileSdk = 35
    ndkVersion = "28.0.12433510"

    defaultConfig {
        applicationId = "com.yourname.pdftoolkit"
        minSdk = 26
        targetSdk = 35
        // Version is set by F-Droid via sed in prebuild step
        // For local/CI builds, use gradle.properties or environment variables
        versionCode = project.findProperty("VERSION_CODE")?.toString()?.toIntOrNull() ?: System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = project.findProperty("VERSION_NAME")?.toString() ?: System.getenv("VERSION_NAME") ?: "dev"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        
        // Play Store requirements
        multiDexEnabled = true
    }

    signingConfigs {
        create("release") {
            val isCi = System.getenv("CI") == "true"
            if (isCi) {
                println("Configuring signing for CI...")
                val keystorePath = System.getenv("ANDROID_KEYSTORE_FILE") ?: "keystore.jks"
                val keystoreFile = file(keystorePath)
                
                if (keystoreFile.exists()) {
                    println("Keystore file found at: ${keystoreFile.absolutePath}")
                    storeFile = keystoreFile
                } else {
                     println("ERROR: Keystore file NOT found at: ${keystoreFile.absolutePath}")
                     // Don't throw here, let gradle fail naturally or subsequent checks fail
                }

                val kPassword = System.getenv("KEYSTORE_PASSWORD")
                val kAlias = System.getenv("KEY_ALIAS")
                val kKeyPassword = System.getenv("KEY_PASSWORD")

                println("KEYSTORE_PASSWORD present: ${!kPassword.isNullOrEmpty()}")
                println("KEY_ALIAS present: ${!kAlias.isNullOrEmpty()}")
                println("KEY_PASSWORD present: ${!kKeyPassword.isNullOrEmpty()}")
                // Mask alias for safety in logs though usually public
                println("KEY_ALIAS value: '${if (kAlias.isNullOrEmpty()) "null/empty" else kAlias}'") 
                
                if (!kPassword.isNullOrEmpty() && !kAlias.isNullOrEmpty() && !kKeyPassword.isNullOrEmpty()) {
                    storePassword = kPassword
                    keyAlias = kAlias
                    keyPassword = kKeyPassword
                } else {
                    println("ERROR: One or more signing secrets are missing in CI environment.")
                    // Initialize with empty strings to verify if NPE comes from null values
                    storePassword = kPassword ?: ""
                    keyAlias = kAlias ?: ""
                    keyPassword = kKeyPassword ?: ""
                }
            } else {
                val keystorePropertiesFile = rootProject.file("keystore.properties")
                if (keystorePropertiesFile.exists()) {
                    val properties = Properties()
                    properties.load(FileInputStream(keystorePropertiesFile))
                    storeFile = rootProject.file(properties["storeFile"] as String)
                    storePassword = properties["storePassword"] as String
                    keyAlias = properties["keyAlias"] as String
                    keyPassword = properties["keyPassword"] as String
                }
            }
        }
    }

    flavorDimensions += "store"
    productFlavors {
        create("playstore") {
            dimension = "store"
            // Play Store and Indus App Store flavor
            // Uses ML Kit for OCR (proprietary but smaller)
            buildConfigField("boolean", "HAS_OCR", "true")
            buildConfigField("boolean", "USE_MLKIT_OCR", "true")
        }
        
        create("fdroid") {
            dimension = "store"
            // F-Droid flavor with only open source dependencies
            // Uses Tesseract for OCR (open source but larger APK)
            buildConfigField("boolean", "HAS_OCR", "true")
            buildConfigField("boolean", "USE_MLKIT_OCR", "false")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            
            // Play Store optimization
            isDebuggable = false
            isJniDebuggable = false
            
            // Bundle debug symbols in the AAB for Play Console crash reports
            ndk {
                debugSymbolLevel = "FULL"
            }
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.3"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE"
            excludes += "META-INF/NOTICE"
            excludes += "META-INF/LICENSE.md"
            excludes += "META-INF/NOTICE.md"
            excludes += "META-INF/versions/9/module-info.class"
        }
        // 16 KB page alignment: store native libs uncompressed & page-aligned
        jniLibs {
            useLegacyPackaging = false
        }
    }
    
    bundle {
        language {
            enableSplit = true
        }
        density {
            enableSplit = true
        }
        abi {
            enableSplit = true
        }
    }
    
    // Custom AAB naming with app name and version
    applicationVariants.all {
        val variant = this
        variant.outputs.all {
            val outputImpl = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            if (variant.buildType.name == "release") {
                outputImpl.outputFileName = "PDFToolkit-v${variant.versionName}-${variant.buildType.name}.apk"
            }
        }
    }
}

dependencies {
    // AndroidX Core & Lifecycle
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    
    // AppCompat for uCrop compatibility
    implementation("androidx.appcompat:appcompat:1.6.1")
    
    // RecyclerView for PDF viewer page management
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    
    // ExifInterface for EXIF metadata reading (Apache 2.0)
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // Compose BOM 2023.10.01 - Stable version
    implementation(platform("androidx.compose:compose-bom:2023.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // Using extended icons - consider switching to subset for smaller APK
    implementation("androidx.compose.material:material-icons-extended")
    
    // Compose Navigation & ViewModel
    implementation("androidx.navigation:navigation-compose:2.7.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    // PDF Tools - PdfBox-Android for PDF manipulation
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    
    // CameraX for Scan to PDF (Apache 2.0)
    // v1.4.1+ includes 16KB page-aligned native libraries
    val cameraxVersion = "1.4.1"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")
    
    // OCR Libraries - flavor-specific
    // Play Store: ML Kit (proprietary, smaller APK + 40MB runtime download)
    "playstoreImplementation"("com.google.mlkit:text-recognition:16.0.1")
    // F-Droid: Tesseract (open source, larger APK but no runtime downloads)
    "fdroidImplementation"("com.rmtheis:tess-two:9.1.0")
    
    // Coil for image loading (Apache 2.0) - lightweight (~2MB)
    implementation("io.coil-kt:coil-compose:2.5.0")
    
    // Glide for advanced image loading with EXIF rotation support (BSD-like license)
    // v4.16.0+ AVIF decoder is 16KB page-aligned
    implementation("com.github.bumptech.glide:glide:4.16.0")
    ksp("com.github.bumptech.glide:ksp:4.16.0")
    
    // uCrop for lightweight image cropping (Apache 2.0)
    // v2.2.9+ includes 16KB page-aligned native libraries
    implementation("com.github.yalantis:ucrop:2.2.9")

    // Room Database
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")
    
    // DataStore for preferences
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.11.1")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2023.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // PDF Viewer (Jetpack PDF)
    implementation("androidx.pdf:pdf-viewer-fragment:1.0.0-alpha04")
    implementation("androidx.fragment:fragment-ktx:1.8.5")

    // Ink Library (for annotations)
    implementation("androidx.ink:ink-brush:1.0.0-alpha01")
    implementation("androidx.ink:ink-geometry:1.0.0-alpha01")
    implementation("androidx.ink:ink-rendering:1.0.0-alpha01")
    implementation("androidx.ink:ink-strokes:1.0.0-alpha01")
}
