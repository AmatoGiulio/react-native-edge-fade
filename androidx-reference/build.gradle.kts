plugins {
  id("com.android.application") version "9.1.1"
}

android {
  namespace = "com.edgefade.androidxref"
  compileSdk = 37
  compileSdkMinor = 1

  defaultConfig {
    applicationId = "com.edgefade.androidxref"
    minSdk = 33
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"
  }
}

dependencies {
  implementation("androidx.compose.ui:ui-graphics:1.13.0-alpha03")
}
