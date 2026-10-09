plugins { alias(libs.plugins.android.application); alias(libs.plugins.kotlin.compose) }
android {
 namespace = "dev.devicelink"
 compileSdk = 37
 defaultConfig { applicationId = "dev.devicelink"; targetSdk = 36; versionCode = 1; versionName = "0.1.0"; minSdk = 26
  testInstrumentationRunner = "dev.devicelink.RelayDeviceProbe"
 }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 buildFeatures { compose = true }
 buildTypes { release { isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") } }
}
dependencies {
 implementation(project(":core:model"))
 implementation(project(":core:transfer"))
 implementation(project(":core:designsystem"))
 implementation(project(":feature:link"))
 implementation(libs.androidx.core)
 implementation(libs.androidx.activity.compose)
 implementation(libs.androidx.lifecycle.runtime)
 implementation(libs.coroutines.android)
 implementation(libs.qr.scanner)
 testImplementation(libs.junit)
 implementation(platform(libs.compose.bom))
 implementation(libs.compose.ui)
 implementation(libs.compose.material3)
 implementation(libs.compose.icons)
 implementation(libs.compose.preview)
 debugImplementation(libs.compose.tooling)
}
