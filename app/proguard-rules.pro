# Keep Kotlinx Serialization models
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.SerializationKt
-keep,includedescriptorclasses class com.sepehr.ikev2.data.**$$serializer { *; }
-keepclassmembers class com.sepehr.ikev2.data.** {
    *** Companion;
}
-keepclasseswithmembers class com.sepehr.ikev2.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}
