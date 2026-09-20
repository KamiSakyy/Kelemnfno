-allowaccessmodification
-renamesourcefileattribute ''
-repackageclasses ''
-flattenpackagehierarchy ''
-overloadaggressively
-dontusemixedcaseclassnames
-obfuscationdictionary proguard-dictionary.txt
-classobfuscationdictionary proguard-dictionary.txt
-packageobfuscationdictionary proguard-dictionary.txt
-optimizationpasses 5
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*,!method/removal/parameter
-keepattributes *Annotation*,InnerClasses,EnclosingMethod
-keepattributes !SourceFile,!LineNumberTable,!LocalVariableTable,!LocalVariableTypeTable,!MethodParameters
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
    public static java.lang.String getStackTraceString(java.lang.Throwable);
}
-assumenosideeffects class java.lang.Throwable {
    public void printStackTrace();
}
# Keep Sec fully - it is the string decryptor and native bridge
-keep class com.tsuyu.line.Sec {
    *;
}
# Keep native methods
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
# Keep JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
# Keep throwable
-keepclassmembers class * extends java.lang.Throwable { *; }
# Allow obfuscation of all Android entry points - R8 will update manifest
-keep,allowobfuscation class * extends android.app.Activity
-keep,allowobfuscation class * extends android.app.Service
-keep,allowobfuscation class * extends android.content.BroadcastReceiver
-keep,allowobfuscation class * extends android.app.job.JobService
-keep,allowobfuscation class * extends androidx.work.ListenableWorker
-keep,allowobfuscation class com.tsuyu.line.YoruApp
-keep,allowobfuscation class com.tsuyu.line.YoruWidgetProvider
# Keep constructors for entry points (allow obfuscation of class name but keep init)
-keepclassmembers,allowobfuscation class * extends android.app.Activity { <init>(...); }
-keepclassmembers,allowobfuscation class * extends android.app.Service { <init>(...); }
-keepclassmembers,allowobfuscation class * extends android.content.BroadcastReceiver { <init>(...); }
-keepclassmembers,allowobfuscation class * extends android.app.job.JobService { <init>(...); }
-keepclassmembers,allowobfuscation class com.tsuyu.line.YoruApp { <init>(...); }
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn org.json.**
# Print mapping for verification
-printmapping build/outputs/mapping/tsuyu/release/mapping.txt
