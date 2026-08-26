-repackageclasses ''
-allowaccessmodification
-adaptclassstrings
-renamesourcefileattribute SourceFile
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod

# These models are reflected/serialized and must keep their external names and members. R8 may
# still optimize their method bodies; the rest of the application remains shrinkable/obfuscatable.
-keep,allowoptimization class io.nekohasekai.sagernet.fmt.** { *; }
-keep,allowoptimization class com.github.exclavenetwork.exclave.core.app.observatory.** { *; }
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
