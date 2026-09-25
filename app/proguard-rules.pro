# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep data classes
-keep class com.amperfy.data.model.** { *; }
-keep class com.amperfy.data.remote.dto.** { *; }

# Room
# 不保留 `-keep class * extends RoomDatabase` / `-keep @Entity class *` 两条宽泛规则：
# Room 2.8 生成代码自带 consumer proguard 规则，运行时不依赖反射
# 查找实体/DAO，宽泛 keep 只会白白撑大 release 包并掩盖真实的 keep 需求。
-dontwarn androidx.room.paging.**

# Retrofit
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# R8 full mode（AGP 8 默认）会剥离匿名 TypeToken 子类的泛型签名，
# 导致 release 下 object : TypeToken<List<T>>() {} 构造时抛 "Missing type parameter"
# 被各处 catch 静默吞掉（播放队列恢复为空/Library Edit 设置重置/离线 scrobble 丢失）。
# Gson 2.10.1（经 Retrofit 2.11 引入）尚未内置此规则，需手动声明（Gson 官方推荐规则）
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken

# Gson 直接序列化的持久化 data class（字段名写入 JSON，须跨版本稳定；
# data.model 包已整体 keep，此类在 player 包内需单独声明）
-keepclassmembers class com.amperfy.player.ScrobbleSyncer$PendingScrobble { <fields>; }

# 歌词落盘缓存的磁盘 DTO（DiskLyricsList/DiskStructuredLyrics/DiskLyricsLine，
# Gson 序列化到 accounts/<sh>/<uh>/lyrics/songs/<id>.json）：字段名即 JSON 键，
# 跨版本混淆名若变化会让老缓存被判「结构损坏」而删档重取（多一次网络请求），故固定字段名
-keepclassmembers class com.amperfy.data.repository.LibraryRepositoryImpl$Disk* { <fields>; }

# Media3
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

