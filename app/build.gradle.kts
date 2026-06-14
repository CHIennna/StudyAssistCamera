import java.util.Properties

plugins {
    id("com.android.application")
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use(::load)
    }
}
val deepSeekApiKey = localProperties.getProperty("DEEPSEEK_API_KEY")
    ?: System.getenv("DEEPSEEK_API_KEY")
    ?: ""
val escapedDeepSeekApiKey = deepSeekApiKey
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")

android {
    namespace = "com.example.studyassist"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.studyassist"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "DEEPSEEK_API_KEY", "\"$escapedDeepSeekApiKey\"")
    }

    buildFeatures {
        buildConfig = true
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
