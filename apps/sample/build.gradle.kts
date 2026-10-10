import java.util.Properties

plugins { alias(libs.plugins.android.application) }

// Private relay configuration: local.properties (git-ignored) or environment, never committed.
//   devicelink.relayUrl=https://relay.example.org
//   devicelink.enrollmentToken=...
// Release signing (optional; see docs/release.md). Without a store file the release build is unsigned.
//   devicelink.signing.storeFile=/absolute/path/outside/the/repo/upload.jks  (DEVICELINK_SIGNING_STORE_FILE)
//   devicelink.signing.storePassword=...                                    (DEVICELINK_SIGNING_STORE_PASSWORD)
//   devicelink.signing.keyAlias=...                                         (DEVICELINK_SIGNING_KEY_ALIAS)
//   devicelink.signing.keyPassword=...                                      (DEVICELINK_SIGNING_KEY_PASSWORD)
val local = Properties().apply { rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use(::load) }
fun private(key: String, env: String) = local.getProperty(key) ?: System.getenv(env) ?: ""
val signingStore = private("devicelink.signing.storeFile", "DEVICELINK_SIGNING_STORE_FILE")

android {
 namespace = "dev.devicelink.sample"
 compileSdk = 37
 defaultConfig {
  applicationId = "dev.devicelink.sample"
  minSdk = 26
  targetSdk = 36
  versionCode = 1
  versionName = "2.0.0"
  manifestPlaceholders["relayUrl"] = private("devicelink.relayUrl", "DEVICELINK_RELAY_URL")
  manifestPlaceholders["enrollmentToken"] = private("devicelink.enrollmentToken", "DEVICELINK_ENROLLMENT_TOKEN")
 }
 signingConfigs {
  if (signingStore.isNotEmpty()) create("release") {
   storeFile = file(signingStore)
   storePassword = private("devicelink.signing.storePassword", "DEVICELINK_SIGNING_STORE_PASSWORD")
   keyAlias = private("devicelink.signing.keyAlias", "DEVICELINK_SIGNING_KEY_ALIAS")
   keyPassword = private("devicelink.signing.keyPassword", "DEVICELINK_SIGNING_KEY_PASSWORD")
  }
 }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 buildTypes {
  release {
   isMinifyEnabled = true
   proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
   if (signingStore.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
  }
 }
}
dependencies {
 implementation(project(":sdk:android"))
 implementation(libs.coroutines.android)
}
