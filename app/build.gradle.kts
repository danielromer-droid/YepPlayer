plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.yepplayer.app"
    compileSdk = 35
    defaultConfig { applicationId = "com.yepplayer.app"; minSdk = 26; targetSdk = 35; versionCode = 4; versionName = "0.3.0" }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-datasource:1.5.1")
    implementation("androidx.media3:media3-common:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
}
