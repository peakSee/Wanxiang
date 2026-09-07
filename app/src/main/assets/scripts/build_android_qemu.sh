#!/bin/sh
# WanXiang x86_64 compatibility build. This script must run inside the dedicated
# QEMU guest selected by LinuxRuntime; it is never used by the normal ARM64 path.
set -e

PROJECT_PATH="${1:-.}"
TASK="${2:-assembleDebug}"
COMPAT_ROOT=/opt/wanxiang/compat/x86_64
JAVA_HOME="$COMPAT_ROOT/jdk-17"
GRADLE_HOME="$COMPAT_ROOT/gradle-8.14.2"
ANDROID_HOME="$COMPAT_ROOT/android-sdk"

echo "==> [WanXiang QEMU Build] x86_64 兼容环境预检"
[ "$(uname -m)" = "x86_64" ] || { echo "==> [WanXiang QEMU Build] ❌ 当前并非 x86_64 QEMU Guest"; exit 126; }
[ -x "$JAVA_HOME/bin/java" ] || { echo "==> [WanXiang QEMU Build] ❌ 缺少 $JAVA_HOME/bin/java"; exit 127; }
java_lib=$(find "$JAVA_HOME" \( -type f -o -type l \) -name libjvm.so -print -quit 2>/dev/null || true)
[ -n "$java_lib" ] || { echo "==> [WanXiang QEMU Build] ❌ 未找到 JVM 原生库 (libjvm.so)"; exit 126; }
machine=$(od -An -t x1 -j 18 -N 2 "$java_lib" 2>/dev/null | tr -d '[:space:]')
[ "$machine" = "3e00" ] || { echo "==> [WanXiang QEMU Build] ❌ JDK 不是 x86_64 ELF: $JAVA_HOME/bin/java"; exit 126; }
for binary in "$ANDROID_HOME/build-tools/35.0.0/aapt2"; do
    [ -x "$binary" ] || { echo "==> [WanXiang QEMU Build] ❌ 缺少 $binary"; exit 127; }
    machine=$(od -An -t x1 -j 18 -N 2 "$binary" 2>/dev/null | tr -d '[:space:]')
    [ "$machine" = "3e00" ] || { echo "==> [WanXiang QEMU Build] ❌ 主机工具不是 x86_64 ELF: $binary"; exit 126; }
done
[ -f "$ANDROID_HOME/platforms/android-34/android.jar" ] || { echo "==> [WanXiang QEMU Build] ❌ 缺少 Android Platform 34"; exit 127; }
[ -f "$ANDROID_HOME/build-tools/35.0.0/lib/d8.jar" ] || { echo "==> [WanXiang QEMU Build] ❌ 缺少 Build-Tools 35.0.0"; exit 127; }

export JAVA_HOME ANDROID_HOME
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME="$COMPAT_ROOT/cache/gradle"
export PATH="$JAVA_HOME/bin:$GRADLE_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/build-tools/35.0.0:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
mkdir -p "$GRADLE_USER_HOME"

LOCAL_PROPERTIES="$PROJECT_PATH/local.properties"
TMP_PROPERTIES="${LOCAL_PROPERTIES}.wanxiang-qemu.tmp"
if [ -f "$LOCAL_PROPERTIES" ]; then
    sed -e '/^[[:space:]]*sdk\.dir[[:space:]]*=/d' -e '/^[[:space:]]*ndk\.dir[[:space:]]*=/d' "$LOCAL_PROPERTIES" > "$TMP_PROPERTIES"
else
    : > "$TMP_PROPERTIES"
fi
printf 'sdk.dir=%s\n' "$ANDROID_HOME" >> "$TMP_PROPERTIES"
mv -f "$TMP_PROPERTIES" "$LOCAL_PROPERTIES"

cd "$PROJECT_PATH"
EXTRA_ARGS="--console=plain --info --stacktrace --no-daemon --max-workers=2 -Dorg.gradle.native=false -Dorg.gradle.internal.http.connectionTimeout=30000 -Dorg.gradle.internal.http.socketTimeout=60000 -Pandroid.builder.sdkDownload=false -Pandroid.aapt2FromMavenOverride=$ANDROID_HOME/build-tools/35.0.0/aapt2"
[ "${WANXIANG_OFFLINE:-0}" = "1" ] && EXTRA_ARGS="$EXTRA_ARGS --offline"
echo "==> [WanXiang QEMU Build] 使用 x86_64 JDK/SDK 执行 $TASK；APK ABI 仍由项目配置决定"
if [ -f "$GRADLE_HOME/lib/gradle-launcher-8.14.2.jar" ] || [ -d "$GRADLE_HOME/lib" ]; then
    exec "$JAVA_HOME/bin/java" -Xmx1024m -XX:MaxMetaspaceSize=384m -XX:+UseSerialGC -Dfile.encoding=UTF-8 -Dorg.gradle.appname=gradle -Dorg.gradle.installation.dir="$GRADLE_HOME" -classpath "$GRADLE_HOME/lib/*" org.gradle.launcher.GradleMain "$TASK" $EXTRA_ARGS
elif [ -f ./gradle/wrapper/gradle-wrapper.jar ]; then
    exec "$JAVA_HOME/bin/java" -Xmx1024m -XX:MaxMetaspaceSize=384m -XX:+UseSerialGC -Dfile.encoding=UTF-8 -classpath ./gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain "$TASK" $EXTRA_ARGS
else
    echo "==> [WanXiang QEMU Build] ❌ 未找到 Gradle 8.14.2 或项目 Wrapper"
    exit 127
fi
