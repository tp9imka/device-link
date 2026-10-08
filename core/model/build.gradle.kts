plugins { `java-library`; alias(libs.plugins.kotlin.jvm); alias(libs.plugins.kotlin.serialization) }
kotlin { jvmToolchain(17) }
dependencies {
 api(libs.tink.android)
 implementation(libs.coroutines.core)
 implementation(libs.serialization.json)
 testImplementation(libs.junit)
 testImplementation(libs.coroutines.test)
}
