plugins { id("com.android.application") }
android {
    namespace = "com.example.sensorae"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.example.sensorae"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1-ae"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation("junit:junit:4.13.2")
}
