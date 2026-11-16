plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android { namespace = "org.echomap.app"; compileSdk = 35
 defaultConfig { applicationId = "org.echomap.app"; minSdk = 26; targetSdk = 28; versionCode = 1; versionName = "0.1" }
 compileOptions {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
 }
 kotlinOptions { jvmTarget = "17" }
}
