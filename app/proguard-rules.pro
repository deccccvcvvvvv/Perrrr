# JNI callbacks resolved by name from native code
-keep class pro.cslauncher.app.game.NativeBridge { *; }
-keepclassmembers class * { @kotlinx.serialization.Serializable *; }
