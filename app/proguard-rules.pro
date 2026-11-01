# Retrofit / OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# Gson-Modelle: Feldnamen muessen erhalten bleiben
-keep class com.tvclient.app.data.model.** { *; }
-keepclassmembers class com.tvclient.app.data.model.** { <fields>; }

# Media3
-dontwarn androidx.media3.**
