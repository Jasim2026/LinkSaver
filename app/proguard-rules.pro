# Jetpack Compose
-keepclasseswithmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}

# Chaquopy (Python Bridge) Requirements
-keep class com.chaquo.** { *; }
-keep class java.lang.reflect.** { *; }
-keep class java.lang.Class { *; }

# Keep data classes so database queries map properly
-keep class com.linksaver.AppLog { *; }
-keep class com.linksaver.LinkItem { *; }
-keep class com.linksaver.ScheduleConfig { *; }

# SQLite FTS might rely on certain cursor behaviors
-keepclassmembers class * extends android.database.sqlite.SQLiteOpenHelper {
    <init>(...);
}

# JSch Tunnel Requirements
-keep class com.jcraft.jsch.** { *; }