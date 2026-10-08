plugins { alias(libs.plugins.android.library); alias(libs.plugins.kotlin.compose) }
android {
 namespace = "dev.devicelink.feature.link"
 compileSdk = 37
 defaultConfig {  minSdk = 26 }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 buildFeatures { compose = true }
}
dependencies {
 implementation(libs.androidx.activity.compose)
 implementation(project(":core:model"))
 implementation(project(":core:designsystem"))
 implementation(libs.androidx.lifecycle.viewmodel)
 implementation(libs.androidx.lifecycle.runtime)
 implementation(libs.zxing)
 implementation(platform(libs.compose.bom))
 implementation(libs.compose.ui)
 implementation(libs.compose.material3)
 implementation(libs.compose.icons)
 implementation(libs.compose.preview)
 debugImplementation(libs.compose.tooling)
}
