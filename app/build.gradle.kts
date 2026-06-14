plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.studyassist"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.studyassist"
        minSdk = 23
        targetSdk = 36
        versionCode = 4
        versionName = "0.2.1"
    }

    buildFeatures {
        buildConfig = false
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

dependencies {
    val cameraxVersion = "1.6.1"

    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
}
