import java.util.Properties

plugins { alias(libs.plugins.android.application) }

// Private relay configuration: local.properties (git-ignored) or environment, never committed.
//   devicelink.relayUrl=https://relay.example.org
//   devicelink.enrollmentToken=...
val local = Properties().apply { rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use(::load) }
fun private(key: String, env: String) = local.getProperty(key) ?: System.getenv(env) ?: ""

android {
 namespace = "dev.devicelink.receiver"
 compileSdk = 37
 defaultConfig {
  applicationId = "dev.devicelink.receiver"
  minSdk = 26
  targetSdk = 36
  versionCode = 1
  versionName = "2.0.0"
  manifestPlaceholders["relayUrl"] = private("devicelink.relayUrl", "DEVICELINK_RELAY_URL")
  manifestPlaceholders["enrollmentToken"] = private("devicelink.enrollmentToken", "DEVICELINK_ENROLLMENT_TOKEN")
 }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 buildTypes { release { isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt")) } }
}
dependencies {
 implementation(project(":sdk:android"))
 implementation(libs.coroutines.android)
 testImplementation(libs.junit)
}
