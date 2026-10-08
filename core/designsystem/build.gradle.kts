plugins { alias(libs.plugins.android.library); alias(libs.plugins.kotlin.compose) }
android {
 namespace = "dev.devicelink.designsystem"
 compileSdk = 37
 defaultConfig {  minSdk = 26 }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 buildFeatures { compose = true }
}
dependencies {
 implementation(libs.androidx.core)
 implementation(libs.coroutines.android)
 implementation(platform(libs.compose.bom))
 implementation(libs.compose.ui)
 implementation(libs.compose.material3)
 implementation(libs.compose.icons)
 implementation(libs.compose.preview)
 debugImplementation(libs.compose.tooling)
}
