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
