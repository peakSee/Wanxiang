#!/bin/sh
set -eu

TOOL_DIR="${WANXIANG_TOOL_DIR:?missing WANXIANG_TOOL_DIR}"
rm -f /opt/wanxiang/bin/java /opt/wanxiang/bin/javac /opt/wanxiang/bin/gradle /opt/wanxiang/bin/cmake /opt/wanxiang/bin/ninja /opt/wanxiang/bin/flutter /opt/wanxiang/bin/dart
rm -rf "$TOOL_DIR"
rm -rf /opt/android-sdk /opt/gradle-8.14.2 /opt/wanxiang/toolchains/android /opt/flutter
rm -f /root/.gradle/init.d/wanxiang-android-ndk.gradle
