# NanoHTTPD loads its MIME table from a classpath resource and is otherwise plain Java; keep it
# whole so R8 cannot strip handlers it cannot see being called.
-keep class fi.iki.elonen.** { *; }
-dontwarn fi.iki.elonen.**
# ZXing QR encoder
-keep class com.google.zxing.** { *; }
