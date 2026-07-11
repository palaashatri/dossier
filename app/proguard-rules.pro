# Release rules for reflection-heavy SDKs and optional transport stacks.

-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-keep class com.google.mediapipe.** { *; }
-keep class com.airbnb.lottie.** { *; }

-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault

-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn org.jsoup.**
-dontwarn com.google.mediapipe.proto.**
-dontwarn javax.lang.model.**
-keep class ai.onnxruntime.** { *; }
-keep class org.tensorflow.lite.** { *; }
-dontwarn ai.onnxruntime.**
-dontwarn org.tensorflow.lite.**
