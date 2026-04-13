# Protected release rules for leodanmu dex.jar
# Goal: keep plugin entry classes stable, aggressively obfuscate internal implementation.

# Keep package paths stable for current dex.jar collection pipeline.
# We still obfuscate class/member names aggressively, but do not repackage/flatten yet.
-overloadaggressively
-allowaccessmodification
-useuniqueclassmembernames
-adaptclassstrings

# Keep useful metadata only where reflection/proxies may need it
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*,Exceptions

# Warnings
-dontwarn org.slf4j.**
-dontwarn org.xmlpull.v1.**
-dontwarn android.content.res.**
-dontwarn okhttp3.**
-dontwarn org.ietf.jgss.**
-dontwarn javax.**

# Runtime libraries that are safer to keep
-keep class org.slf4j.** { *; }
-keep class androidx.core.** { *; }
-keep class okio.** { *; }
-keep class okhttp3.** { *; }
-keep class com.whl.quickjs.** { *; }
-keep class com.thegrizzlylabs.sardineandroid.** { *; }
-keep class com.hierynomus.** { *; }
-keep class net.engio.mbassy.** { *; }
-keep class com.orhanobut.logger.** { *; }

# Core plugin contracts
-keep class com.github.catvod.crawler.** { *; }
-keep class * extends com.github.catvod.crawler.Spider { public <init>(...); public <methods>; }
-keep class com.github.catvod.js.Function { *; }

# Gson-reflected model layer: keep these stable first, otherwise config/media/entity parsing breaks.
-keep class com.github.catvod.spider.DanmakuConfig { *; }
-keep class com.github.catvod.spider.DanmakuConfigManager { *; }
-keep class com.github.catvod.spider.EpisodeInfo { *; }
-keep class com.github.catvod.spider.entity.** { *; }
-keep class com.github.catvod.bean.** { *; }

# Known public plugin entry points / compatibility anchors
-keep class com.github.catvod.spider.Leodanmu { public <init>(...); public <methods>; }
-keep class com.github.catvod.spider.Init { public <init>(...); public <methods>; }
-keep class com.github.catvod.spider.GoProxySpider { public <init>(...); public <methods>; }
-keep class com.github.catvod.spider.protect.ProtectedLoader { *; }
-keep class com.github.catvod.spider.protect.PayloadBridge { *; }
-keep class com.github.catvod.spider.protect.RealLeodanmu { *; }
-keep class com.github.catvod.spider.protect.impl.PayloadEntry { *; }

# Keep web server / nanohttpd methods stable enough for runtime dispatch
-keep class fi.iki.elonen.** { *; }
-keepclassmembers class com.github.catvod.spider.WebServer { *; }

# Preserve enums / serialization structures that may be parsed dynamically
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep native method names if future protected builds add JNI
-keepclasseswithmembernames class * {
    native <methods>;
}

# Obfuscate danmaku implementation internals as much as possible
# (No blanket keep for com.github.catvod.spider.* here on purpose.)
