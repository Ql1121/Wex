# ==================== Xposed 入口（绝对不能动） ====================
-keep public class wex.ui.core.xposed.HookEntry {
    public *;
    protected *;
}
-keep class de.robv.android.xposed.** { *; }
-keepclassmembers class * {
    @de.robv.android.xposed.XC_MethodHook *;
}

# ==================== Activity ====================
-keep class wex.ui.core.MainActivity { *; }
-keep class wex.ui.core.FeatureSettingsActivity { *; }

# ==================== Compose UI ====================
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}
-keep class wex.ui.core.ui.** { *; }

# ==================== 数据模型（JSON 序列化用） ====================
-keep class wex.ui.core.feature.MusicCardFeature$MusicItem { *; }
-keep class wex.ui.core.WexStore { *; }

# ==================== Assets/资源反射 ====================
-keepclassmembers class * {
    *** addAssetPath(...);
}
-keep class android.content.res.AssetManager { *; }

# ==================== 接口/回调 ====================
-keep class wex.ui.core.feature.MusicPanels { *; }
-keep class wex.ui.core.feature.ImagePicker { *; }
-keep class wex.ui.core.feature.HomeCardManager { *; }
-keep class wex.ui.core.feature.TopBarFeature { *; }
-keep class wex.ui.core.feature.CalendarCardFeature { *; }
-keep class wex.ui.core.feature.ImageCardFeature { *; }
-keep class wex.ui.core.feature.FloatLyricFeature { *; }
-keep class wex.ui.core.feature.MediaSessionFeature { *; }
-keep class wex.ui.core.feature.LocationHelper { *; }
-keep class wex.ui.core.core.ViewFinder { *; }

# ==================== Kotlin ====================
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlin.**

# ==================== Compose / Material3 ====================
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**
-keep class com.kyant.backdrop.** { *; }

# ==================== JSON ====================
-keep class org.json.** { *; }

# ==================== 网络 ====================
-keep class java.net.** { *; }
-keep class javax.net.** { *; }
-keep class javax.crypto.** { *; }

# ==================== Base64 ====================
-keep class android.util.Base64 { *; }

# ==================== 通用保留 ====================
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses

# ==================== 行号保留（方便查崩溃日志） ====================
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile