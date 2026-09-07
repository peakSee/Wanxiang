#!/bin/sh
# ==============================================================================
# WanXiang (LinuxAIRuntime) - Self-Adaptive Gradle Wrapper
# ==============================================================================
DIR="$(cd "$(dirname "$0")" && pwd)"

# 优先加载插件装配期固化的环境变量
if [ -f /etc/profile.d/wanxiang-android.sh ]; then
    . /etc/profile.d/wanxiang-android.sh
fi

JAVA_BIN=$(which java 2>/dev/null || ls /usr/lib/jvm/*/bin/java 2>/dev/null | head -n 1 || true)
if [ -n "$JAVA_BIN" ] && [ -x "$JAVA_BIN" ]; then
    export JAVA_HOME=${JAVA_HOME:-$(dirname $(dirname $(readlink -f "$JAVA_BIN" 2>/dev/null || echo "$JAVA_BIN")))}
    export PATH="$JAVA_HOME/bin:$PATH"
    JAVA_EXEC="$JAVA_BIN"
elif [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    export PATH="$JAVA_HOME/bin:$PATH"
    JAVA_EXEC="$JAVA_HOME/bin/java"
else
    JAVA_EXEC=$(which java 2>/dev/null || echo "java")
fi

if [ -f "$DIR/gradle/wrapper/gradle-wrapper.jar" ]; then
    exec "$JAVA_EXEC" -jar "$DIR/gradle/wrapper/gradle-wrapper.jar" "$@"
elif [ -d /opt/gradle-8.14.2/lib ]; then
    exec "$JAVA_EXEC" -Xmx1024m -XX:MaxMetaspaceSize=384m -XX:+UseSerialGC \
        -Dorg.gradle.appname=gradle \
        -Dorg.gradle.installation.dir=/opt/gradle-8.14.2 \
        -Dorg.gradle.native=false \
        -classpath "/opt/gradle-8.14.2/lib/*" \
        org.gradle.launcher.GradleMain "$@"
elif [ -d /opt/gradle-8.7/lib ]; then
    exec "$JAVA_EXEC" -Xmx1024m -XX:MaxMetaspaceSize=384m -XX:+UseSerialGC \
        -Dorg.gradle.appname=gradle \
        -Dorg.gradle.installation.dir=/opt/gradle-8.7 \
        -Dorg.gradle.native=false \
        -classpath "/opt/gradle-8.7/lib/*" \
        org.gradle.launcher.GradleMain "$@"
elif [ -x /opt/gradle-8.14.2/bin/gradle ]; then
    exec /opt/gradle-8.14.2/bin/gradle "$@"
elif [ -x /usr/local/bin/gradle ]; then
    exec /usr/local/bin/gradle "$@"
elif command -v gradle >/dev/null 2>&1; then
    exec gradle "$@"
else
    exec /usr/bin/gradle "$@"
fi
