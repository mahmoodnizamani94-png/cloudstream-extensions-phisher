# ProGuard rules for Cloudstream Extensions
# Enable optimizations and shrinking, but keep dynamic loading entry points

-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,MethodParameters

# Keep the plugin entry point annotated with @CloudstreamPlugin or extending BasePlugin
-keep @com.lagradost.cloudstream3.plugins.CloudstreamPlugin class * { *; }
-keep class * extends com.lagradost.cloudstream3.plugins.BasePlugin { *; }

# Keep MainAPI and ExtractorApi classes and their members for dynamic reflection lookup
-keep class * extends com.lagradost.cloudstream3.MainAPI { *; }
-keep class * extends com.lagradost.cloudstream3.utils.ExtractorApi { *; }

# Keep all field names in all classes to prevent Jackson/Gson deserialization failures.
# This allows method renaming/optimization and unused method stripping while keeping serialization safe.
-keepclassmembers class * {
    <fields>;
}

# Keep all constructors to ensure Jackson reflection retains parameter names and constructors under R8 optimization
-keepclassmembers class * {
    <init>(...);
}

# Suppress warnings for optional Cloudstream host app classes not bundled in extension JARs
-dontwarn coil3.**
-dontwarn com.google.android.gms.cast.**
-dontwarn com.jaredrummler.android.colorpicker.**
-dontwarn com.uwetrottmann.tmdb2.**
-dontwarn kotlinx.datetime.**
-dontwarn kotlinx.serialization.**

