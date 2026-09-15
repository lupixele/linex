# Proguard rules for LinuxDroid
# Standard Android + Jetpack Compose + JNI Native + Kotlinx Serialization / Data classes

# JNI / Native method preservation
-keepclasseswithmembernames class * {
    native <methods>;
}

# Preserve native JNI callbacks and bindings in LinuxDroid
-keep class com.linuxdroid.app.core.** { *; }

# Jetpack Compose rules
-keepclassmembers class * {
    @androidx.compose.runtime.Composable *;
    void <init>(androidx.compose.runtime.Composer, int);
}

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.SerializationCoreKt
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,allowobfuscation,allowshrinking class * {
    <init>(...);
}
-keepclassmembers class * {
    @kotlinx.serialization.Serializable *;
}

# Preserve Data Classes (and their copy/component methods / getters)
-keepclassmembers class * {
    public java.lang.String toString();
    public int hashCode();
    public boolean equals(java.lang.Object);
    public *** component*();
    public *** copy*(...);
}

# OkHttp rules
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
