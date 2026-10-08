plugins { alias(libs.plugins.android.library) }
android {
 namespace = "dev.devicelink.transfer"
 compileSdk = 37
 defaultConfig {  minSdk = 26 }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
 implementation(project(":core:model"))
 implementation(libs.androidx.core)
 implementation(libs.coroutines.android)
 implementation(libs.serialization.json)
 testImplementation(libs.junit)
 testImplementation(libs.coroutines.test)
}
