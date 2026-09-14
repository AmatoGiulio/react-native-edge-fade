plugins {
  id("com.android.library") version "9.1.1"
}

android {
  namespace = "com.edgefade.androidxref"
  compileSdk = 37
  compileSdkMinor = 1

  defaultConfig {
    minSdk = 24
  }
}

dependencies {
  implementation("androidx.compose.ui:ui-graphics:1.13.0-alpha03")
}
