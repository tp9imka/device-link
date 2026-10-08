plugins {
 alias(libs.plugins.android.application) apply false
 alias(libs.plugins.android.library) apply false
 alias(libs.plugins.kotlin.jvm) apply false
 alias(libs.plugins.kotlin.compose) apply false
 alias(libs.plugins.kotlin.serialization) apply false
}
tasks.register<Exec>("checkArchitecture") {
 commandLine("python3", "scripts/check_architecture.py")
}
tasks.register("check") {
 group = "verification"
 dependsOn("checkArchitecture", ":core:model:test", ":app:lintDebug", ":app:testDebugUnitTest", ":core:transfer:testDebugUnitTest", ":app:assembleDebug")
}
