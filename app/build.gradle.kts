plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "saaicom.tcb.docuscanner"
    compileSdk = 35

    defaultConfig {
        applicationId = "saaicom.tcb.docuscanner"
        minSdk = 31
        targetSdk = 35
        versionCode = 4
        versionName = "4.07"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        packaging {
            resources {
                excludes.add("META-INF/DEPENDENCIES")
            }
            jniLibs {
                // This tells Gradle to pick the first copy of the file it finds
                // and ignore all other duplicates.
                pickFirsts.add("**/libc++_shared.so")
                useLegacyPackaging = false
            }
        }
    }

    buildTypes {
        release {
            //isMinifyEnabled = true     // Code shrinking and obfuscation (R8)
            //isShrinkResources = true   // Resource shrinking (removes unused drawables, etc.)
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.4.8"  // Compose Compiler Version
    }

    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.4.8"  // Compatible with Kotlin 1.8.22
    }
}

dependencies {

    // CameraX dependencies
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.video)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.extensions)
    // Lifecycle ViewModel and runtime
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx.v292)
    // Add these lines for Jetpack DataStore
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.coil.kt.coil.compose)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.ui)
    implementation(libs.material3)
    implementation(libs.ui.tooling.preview)
    implementation(project(":opencv"))
    implementation(libs.androidx.navigation.runtime.ktx)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.play.services.ads.api)
    implementation(libs.androidx.compose.ui.unit)
    implementation(libs.androidx.window)
    implementation(libs.androidx.compose.material3.window.size.class1)
    debugImplementation(libs.ui.tooling)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    // For Google Sign-In
    implementation(libs.play.services.auth)
// For Google Drive API
    implementation(libs.google.api.client.android)
    implementation("com.google.apis:google-api-services-drive:v3-rev20220815-2.0.0") {
        exclude("org.apache.httpcomponents")
    }
    // For the Google API Client, which builds the Drive service
    implementation(libs.google.api.client) // <-- ADD THIS LINE
    implementation(libs.google.api.client.android)
    implementation(libs.google.api.client.gson)
    implementation(libs.google.http.client.android)
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.pdf:pdf-viewer-fragment:1.0.0-alpha04")
    implementation("androidx.appcompat:appcompat:1.7.0") // Required for Fragment support
    // ADD THIS LINE (Required for the PDF Viewer's internal UI elements)
    implementation(libs.material)
}