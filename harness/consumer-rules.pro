# harness module consumer rules for R8 / proguard.
#
# Ktor server reflects / references classes that are absent from Android runtime.
# Tell R8 not to fail when referenced symbols are missing; Ktor's own ProGuard rules
# already cover the rest.
-dontwarn java.lang.management.**
-dontwarn org.slf4j.**
-dontwarn ch.qos.logback.**
-dontwarn org.fusesource.jansi.**
-dontwarn com.sun.nio.file.SensitivityWatchEventModifier
-dontwarn sun.nio.fs.**
-dontwarn org.slf4j.impl.StaticLoggerBinder
-dontwarn org.apache.log4j.**
-dontwarn javax.script.**
-dontwarn org.codehaus.groovy.**
-dontwarn io.netty.**

# Keep Ktor runtime classes that R8 might aggressively strip when referenced via reflection
# (routing DSL, content negotiation, websocket pipeline).
-keep class io.ktor.** { *; }
-keep class kotlinx.coroutines.** { *; }
-dontwarn io.ktor.**
