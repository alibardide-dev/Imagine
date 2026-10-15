plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.alibardide.imagine"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.alibardide.imagine"
        minSdk = 23
        targetSdk = 37
        versionCode = 5
        versionName = "1.2.26"
        vectorDrawables.useSupportLibrary = true
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures.viewBinding = true

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.appcompat)
    implementation(libs.android.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.lifecycle.runtime)
}