# Add project specific ProGuard rules here.
# Keep BouncyCastle X25519 classes (reflection-sensitive) and Room entities.
-keep class org.bouncycastle.** { *; }
-keepclassmembers class * {
    @androidx.room.* <fields>;
}
