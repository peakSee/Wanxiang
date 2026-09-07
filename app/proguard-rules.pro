# app module R8/ProGuard rules. Library-specific rules are supplied by each dependency.

# Keep generic type and nesting metadata used by serialization and reflective APIs.
-keepattributes Signature,InnerClasses,EnclosingMethod

# Persisted polymorphic payloads may use their declared serializable class name. Keep only
# those class names while still allowing unused classes/members to shrink and optimize.
-keep,allowshrinking,allowoptimization @kotlinx.serialization.Serializable class top.wanxiang.app.**

# JNI symbol lookup includes the declaring class and method names. This precise rule also
# covers zstd-jni native entry points without retaining the whole dependency.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep class top.wanxiang.app.runtime.pty.NativePty { *; }

# PrivilegeManager reflectively invokes Shizuku's private compatibility API.
-keepclassmembers,allowoptimization class rikka.shizuku.Shizuku {
    private static *** newProcess(java.lang.String[], java.lang.String[], java.lang.String);
}

# Harness resolves this app-layer service by a constant class name to avoid a module cycle.
-keepnames class top.wanxiang.app.service.AgentForegroundService

# Keep line information for deobfuscating production crashes while hiding source filenames.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# === Shizuku UserService (release 构建必保) ===
# Shizuku 在独立 fork 的进程中按全限定类名反射加载 ShizukuHostUserService，
# 并通过 AIDL 描述符 (DESCRIPTOR = 接口全限定名) 匹配 Binder 接口。
# R8 一旦重命名/裁剪这些类或其构造函数，UserService 进程会静默崩溃，
# 表现为 bindUserService 一直超时、服务端日志只有 addUserService 无进程启动。
-keep class top.wanxiang.app.runtime.privilege.ShizukuHostUserService { *; }
-keep class top.wanxiang.app.runtime.privilege.IShizukuHostService { *; }
-keep class top.wanxiang.app.runtime.privilege.IShizukuHostService$* { *; }
# HostProcessRunner 由 ShizukuHostUserService 直接引用，R8 可达性分析应自动保留，
# 但因其运行在 Shizuku 独立进程中，显式保活避免边缘裁剪。
-keep class top.wanxiang.app.runtime.privilege.HostProcessRunner { *; }
