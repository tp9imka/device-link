# kotlinx.serialization models in sdk:core and Tink HPKE are reached reflectively.
-keep class dev.devicelink.sdk.core.** { *; }
-keepclassmembers class dev.devicelink.sdk.core.** { *** Companion; }
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
