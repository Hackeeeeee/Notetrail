# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Prevent obfuscation of project classes to ensure Firestore and Gson serialization works correctly
-keep class com.mechanicalaxiom.notetrail.PaperVariant { *; }
-keep class com.mechanicalaxiom.notetrail.SessionGroup { *; }
-keep class com.mechanicalaxiom.notetrail.YearGroup { *; }
-keep class com.mechanicalaxiom.notetrail.Subject { *; }
-keep class com.mechanicalaxiom.notetrail.Group { *; }
-keep class com.mechanicalaxiom.notetrail.LeaderboardEntry { *; }
-keep class com.mechanicalaxiom.notetrail.PageAnnotations { *; }
-keep class com.mechanicalaxiom.notetrail.StrokeData { *; }
-keep class com.mechanicalaxiom.notetrail.TextAnnotationData { *; }
-keep class com.mechanicalaxiom.notetrail.ShapeAnnotationData { *; }
-keep class com.mechanicalaxiom.notetrail.NormalizedPoint { *; }
-keep class com.mechanicalaxiom.notetrail.AnnotationPageData { *; }

# Firebase Specifics
-keep class com.google.firebase.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
