-repackageclasses ''
-allowaccessmodification
-adaptclassstrings
-renamesourcefileattribute SourceFile
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod

-keep class io.nekohasekai.sagernet.fmt.** { *; }
-keep class com.github.exclavenetwork.exclave.core.app.observatory.** { *; }
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
