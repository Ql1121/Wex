# ============================================================
# WEX Xposed 模块 ProGuard 规则
# 策略：只保留"外部必须能访问的点"，其余全部重混淆
# ============================================================

# ----- Xposed 入口类（xposed_init 写死的全类名，绝不能混淆）-----
-keep class wex.ui.core.xposed.HookEntry { *; }

# ----- IXposedHook 接口实现（框架回调）-----
-keep class * implements de.robv.android.xposed.IXposedHookLoadPackage { *; }
-keep class * implements de.robv.android.xposed.IXposedHookZygoteInit { *; }

# ----- AndroidManifest 声明的组件 -----
-keep class wex.ui.core.MainActivity { *; }
-keep class wex.ui.core.FeatureSettingsActivity { *; }

# ----- 系统回调（BroadcastReceiver 动态注册）-----
-keep class wex.ui.core.feature.MediaSessionFeature$* { *; }

# ----- WexStore（跨进程读写配置，方法名不能变）-----
-keep class wex.ui.core.WexStore { *; }

# ----- BuildConfig（SettingsPage 读版本号用）-----
-keep class wex.ui.core.BuildConfig { *; }

# ----- 数据类（JSON 序列化/反序列化用）-----
-keep class wex.ui.core.feature.MusicCardFeature$MusicItem { *; }

# ============================================================
# 重混淆增强（让反编译更难读）
# ============================================================
-repackageclasses 'wex'
-allowaccessmodification
-overloadaggressively
-renamesourcefileattribute SourceFile
-keepattributes Exceptions,InnerClasses,Signature,Deprecated,EnclosingMethod

# ----- 通用优化 -----
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*
-optimizationpasses 5
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-verbose