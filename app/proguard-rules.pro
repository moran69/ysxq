# Add project specific ProGuard rules here.
-keepattributes *Annotation*
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class **$serializer { *; }
-keepclassmembers class ** {
    *** Companion;
}

# Preserve upnpcast library — SOAP/XML, reflection, and internal state management
# v1.1.2 uses Kotlin coroutines internally; ProGuard must not strip or rename any classes
-keep class com.yinnho.upnpcast.** { *; }
-keep interface com.yinnho.upnpcast.** { *; }
