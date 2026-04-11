# Add project specific ProGuard rules here.
-keepattributes *Annotation*
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class **$serializer { *; }
-keepclassmembers class ** {
    *** Companion;
}
