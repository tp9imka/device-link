# kotlinx.serialization models in sdk:core and Tink HPKE are reached reflectively.
# (kotlinx-serialization and Tink also ship their own R8 rules; this broad keep is the safe superset.)
-keep class dev.devicelink.sdk.core.** { *; }
-keepclassmembers class dev.devicelink.sdk.core.** { *** Companion; }
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

# dev.devicelink.sdk (this module) needs no rules: it uses no reflection or serialization (history is
# org.json, enums go through direct valueOf calls), and its manifest components (ReceiverService,
# DeviceLinkFileProvider, SendClipboardActivity, ShareToDeviceActivity, NotificationActionReceiver,
# BootReceiver) are kept by AAPT's generated rules. Checked with :apps:receiver:minifyReleaseWithR8:
# those six keep their names in mapping.txt, everything else is renamed safely.
