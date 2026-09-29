# FakeLoc — R8 / ProGuard rules
#
# `isMinifyEnabled` is **true** for release as of v1.5.0. See the note in
# app/build.gradle.kts for why it was off before and what changed; the short
# version is that the rules below were already correct, and the two SDKs whose
# keep-whole requirements made shrinking pointless are no longer bundled.
#
# Two categories of class must survive verbatim, for the same underlying reason:
# something outside the compiler resolves them by name.
#
#   1. This app's own entry points and hook targets — named by LSPosed in
#      META-INF/xposed/java_init.list, or looked up reflectively at runtime.
#   2. Every class the bundled map SDK resolves for itself — called from
#      `libtxmapengine.so`, from a server-supplied string, or from xml.
#
# Everything else (androidx, Compose, Kotlin, our own UI) is shrunk normally.
# `isShrinkResources` stays false: our `res/` is 31 KB, and a vendor SDK is a
# third party that may look resources up by name — see the build script.

# --- Xposed module entry points ---------------------------------------------
# The class named in META-INF/xposed/java_init.list is instantiated
# reflectively by the framework. It must survive obfuscation and stripping.
-keep class io.github.coby.fakeloc.xposed.ModuleEntry { *; }
-keep class io.github.coby.fakeloc.xposed.** { *; }

# --- libxposed --------------------------------------------------------------
-keep class io.github.libxposed.** { *; }
-dontwarn io.github.libxposed.**

# Hooks are installed on framework classes; the lambdas we register are
# XposedInterface.Hooker implementations and are referenced only from native
# side after registration.
-keepclassmembers class * implements io.github.libxposed.api.XposedInterface$Hooker {
    public *;
}

# --- Reflection targets -----------------------------------------------------
# We look these up by name at runtime through the module's ClassLoader.
-keepclassmembers class android.location.Location {
    void setIsFromMockProvider(boolean);
    void setMock(boolean);
}
-keepclassmembers class android.app.AppOpsManager {
    public int checkOp(int, int, java.lang.String);
    public int checkOpNoThrow(int, int, java.lang.String);
    public int unsafeCheckOp(java.lang.String, int, java.lang.String);
    public int unsafeCheckOpNoThrow(java.lang.String, int, java.lang.String);
}

# --- Model classes serialised by hand to JSON -------------------------------
-keep class io.github.coby.fakeloc.core.SpoofConfig { *; }

# --- Tencent Map SDK --------------------------------------------------------
# The SDK resolves its own classes by name in several places: the engine
# callbacks registered from `libtxmapengine.so`, the beacon/QMSP analytics
# clients, the LBS search client, and the map view it inflates. Stripping or
# renaming any of them produces a blank map or an "AK invalid" error that looks
# like a credential problem, so keep the lot.
#
# The package roots were not guessed — they were read off the shipped dex
# (2026-09-29, 2 168 classes): com.tencent.mapsdk, com.tencent.tencentmap,
# com.tencent.map, com.tencent.tmsbeacon, com.tencent.lbssearch,
# com.tencent.tmsqmsp, plus one class in com.qq.taf. `com.tencent.**` covers all
# of the first six; com.qq is listed separately because it is a different root.
#
# Baidu's `-keep class com.baidu.**` / `vi.com.**` block was removed here along
# with the dependency. If a second renderer ever comes back, it needs its own
# block, derived the same way and not copied from a blog post: the failure mode
# of a missing rule is a map that renders nothing, which is indistinguishable
# from a bad key and only reproducible on the user's device.
-keep class com.tencent.** { *; }
-keep class com.qq.** { *; }
-dontwarn com.tencent.**
-dontwarn com.qq.**

# JNI entry points live in the .so and are matched to Java methods by exact
# name and signature. Nothing in the dex references them, so without this R8 is
# free to rename or delete them.
-keepclasseswithmembernames class * {
    native <methods>;
}

# --- Kotlin / Compose noise -------------------------------------------------
-dontwarn kotlin.**
-dontwarn kotlinx.**
-keep class kotlin.Metadata { *; }
