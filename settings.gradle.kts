pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
 repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
 repositories { google(); mavenCentral() }
}
rootProject.name = "DeviceLink"
include(":app", ":core:model", ":core:transfer", ":core:designsystem", ":feature:link")
// Link v2: cross-platform SDK and apps (QR pairing over the relay).
include(":sdk:core", ":sdk:android", ":apps:receiver", ":apps:sample")
