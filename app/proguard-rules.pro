-repackageclasses ''
-obfuscationdictionary obfuscation-dictionary.txt
-classobfuscationdictionary obfuscation-dictionary.txt
-packageobfuscationdictionary obfuscation-dictionary.txt
-allowaccessmodification
-adaptclassstrings
-renamesourcefileattribute SourceFile
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod

# These models are reflected/serialized and must keep their members. Their class/package names may
# still be renamed and repackaged, and R8 may optimize their method bodies.
-keep,allowoptimization,allowobfuscation,allowrepackage class io.nekohasekai.sagernet.fmt.** { *; }
-keep,allowoptimization,allowobfuscation,allowrepackage class com.github.exclavenetwork.exclave.core.app.observatory.** { *; }

# V2RayConfig is converted to/from JSON by Gson and most fields intentionally do not carry
# @SerializedName. Keep only those external JSON field names stable; the declaring class names and
# packages remain fully obfuscated/repackaged by the rule above. Allowing these fields to be renamed
# produces syntactically valid JSON with meaningless short keys and makes every release speed test
# and connection fail even though debug builds continue to work.
-keepclassmembers,allowshrinking,allowoptimization class io.nekohasekai.sagernet.fmt.v2ray.V2RayConfig {
    <fields>;
}
-keepclassmembers,allowshrinking,allowoptimization class io.nekohasekai.sagernet.fmt.v2ray.V2RayConfig$* {
    <fields>;
}
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# SnakeYaml
-keep class org.yaml.snakeyaml.** { *; }

-dontwarn java.beans.BeanInfo
-dontwarn java.beans.FeatureDescriptor
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.Introspector
-dontwarn java.beans.PropertyDescriptor
-dontwarn java.beans.Transient
-dontwarn java.beans.VetoableChangeListener
-dontwarn java.beans.VetoableChangeSupport

# Remove verbose/debug platform logging calls that survive constant folding in release builds.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
