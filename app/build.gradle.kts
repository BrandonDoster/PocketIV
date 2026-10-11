plugins {
    id("com.android.application") version "9.4.1"
}

android {
    namespace = "cc.doster.pocketiv"
    compileSdk = 37

    defaultConfig {
        applicationId = "cc.doster.pocketiv"
        minSdk = 35
        targetSdk = 37
        // CI passes these from the tag (v1.2.3) and run number
        versionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = findProperty("versionName") as String? ?: "0.0.0-dev"
    }
}

dependencies {
    // Unbundled ML Kit OCR: model ships with Play services, not in the APK
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")
    testImplementation("junit:junit:4.13.2")
}
