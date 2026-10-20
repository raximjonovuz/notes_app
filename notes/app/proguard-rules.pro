# =============================================================================
# Notes app — R8 / ProGuard rules for release builds.
#
# Compose, Kotlin stdlib, kotlinx.coroutines, and AndroidX ship their own
# consumer ProGuard files through the AAR; the rules below cover only the
# app-specific surfaces that R8 otherwise strips or renames incorrectly.
# =============================================================================

# ---------------------------------------------------------------------------
# V5.0.2 — log calls gate on BuildConfig.DEBUG. R8 strips the constant from
# BuildConfig in release builds; the field reference must remain so the
# comparison compiles and resolves to the final constant value.
# ---------------------------------------------------------------------------
-keep class com.example.notes.BuildConfig { *; }

# ---------------------------------------------------------------------------
# kotlinx.serialization (V3.1 — auth/sync DTOs).
# Generated $$serializer companions are looked up reflectively when the
# serializer is requested; without these rules R8 inlines them away and
# runtime serialization fails with SerializationException.
# ---------------------------------------------------------------------------
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class **$$serializer { *; }
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---------------------------------------------------------------------------
# Room (2.7.0). Entities, the RoomDatabase subclass and the generated DAO
# impls are looked up by name; keep them and their annotated members.
# ---------------------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase {
    public <init>(...);
}
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keepclassmembers @androidx.room.Entity class * {
    <fields>;
    <init>(...);
}
-dontwarn androidx.room.paging.**

# ---------------------------------------------------------------------------
# OkHttp 4.x — silence R8 warnings about the optional Conscrypt/
# BouncyCastle integrations we don't bundle. Conservative: the library
# itself emits its own consumer rules.
# ---------------------------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---------------------------------------------------------------------------
# Play Services Auth (V4.2 — Google Sign-In). The SDK uses reflection on
# its own internal classes from native code paths; the consumer rules
# cover most of it, but the auth.api surface needs an explicit keep.
# ---------------------------------------------------------------------------
-keep class com.google.android.gms.auth.** { *; }
-keep interface com.google.android.gms.auth.api.** { *; }
-dontwarn com.google.android.gms.auth.**

# ---------------------------------------------------------------------------
# Notes domain types — keep the @Serializable DTOs (the rules above
# already cover $$serializer, but the data class fields must stay
# reflectively accessible too).
# ---------------------------------------------------------------------------
-keep class com.example.notes.data.remote.AuthResponse { *; }
-keep class com.example.notes.data.remote.UserResponse { *; }
-keep class com.example.notes.data.remote.NoteSyncDto { *; }
-keep class com.example.notes.data.remote.NotesListResponse { *; }
-keep class com.example.notes.data.remote.UpsertNoteResponse { *; }
-keep class com.example.notes.data.remote.UploadResponse { *; }
-keep class com.example.notes.data.local.AuthStore$Session { *; }
