# ARCore loads native classes reflectively; stripping them breaks the session
# at runtime with no compile-time warning.
-keep class com.google.ar.core.** { *; }
-dontwarn com.google.ar.core.**

# Our GL renderers are instantiated normally, but the core geometry types are
# small and shrinking them yields nothing meaningful.
-keep class com.sarab.vision.core.** { *; }
