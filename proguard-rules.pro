# ProGuard rules for Cloudstream Extensions
# Enable optimizations and shrinking, but keep dynamic loading entry points

-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

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
