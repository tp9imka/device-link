plugins { alias(libs.plugins.android.library) }
android {
 namespace = "dev.devicelink.sdk"
 compileSdk = 37
 defaultConfig { minSdk = 26; consumerProguardFiles("consumer-rules.pro") }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
 api(project(":sdk:core"))
 implementation(libs.coroutines.android)
 implementation(libs.zxing)
 implementation(libs.qr.scanner)
 testImplementation(libs.junit)
}
