plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android {
    namespace = "icu.kairo.link"
    compileSdk = 36
    defaultConfig {
        applicationId = "icu.kairo.link"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildTypes { release { isMinifyEnabled = false } }
    lint {
        abortOnError = true
        // The relay must survive Doze in a pocket; asking for the exemption is deliberate.
        disable += "BatteryLife"
    }
}

dependencies { implementation(project(":core")) }
