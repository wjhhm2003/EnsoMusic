# Room
-keep class androidx.room.** { *; }
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Keep our Room Entities & DAOs
-keep class com.enso.music.data.** { *; }

# MediaSession and Services
-keep class androidx.media.** { *; }
-keepclassmembers class * extends androidx.media.MediaBrowserServiceCompat { *; }

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
