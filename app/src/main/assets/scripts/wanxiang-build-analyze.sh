#!/bin/sh
# Read-only compatibility report for third-party Android/Flutter projects.
set -eu
project="${1:-}"
offline=0
[ "${2:-}" = "--offline" ] && offline=1
test -d "$project" || { echo "WANXIANG_ANALYZE_FAIL: project_missing" >&2; exit 2; }

kind=unknown
if test -f "$project/pubspec.yaml"; then kind=flutter
elif test -f "$project/settings.gradle" || test -f "$project/settings.gradle.kts" || test -f "$project/build.gradle" || test -f "$project/build.gradle.kts"; then kind=android
fi

echo "WanXiang project compatibility report"
echo "project=$project"
echo "type=$kind"
echo "policy=arm64-v8a-only"
echo "offline=$offline"
errors=0

compile_sdk=$(grep -RhsE 'compileSdk(Version)?[[:space:]]*[=( ]+[0-9]+' "$project"/*.gradle "$project"/*.gradle.kts "$project"/app/*.gradle "$project"/app/*.gradle.kts "$project"/android/app/*.gradle "$project"/android/app/*.gradle.kts 2>/dev/null | grep -Eo '[0-9]+' | head -n 1 || true)
if test -n "$compile_sdk"; then
    if test "$compile_sdk" -gt 34; then echo "ERROR compileSdk=$compile_sdk expected<=34 action=align_or_install_platform"; errors=1; else echo "OK compileSdk=$compile_sdk"; fi
else echo "INFO compileSdk=unknown"; fi

wrapper="$project/gradle/wrapper/gradle-wrapper.properties"
test "$kind" = flutter && wrapper="$project/android/gradle/wrapper/gradle-wrapper.properties"
if test -f "$wrapper"; then
    version=$(sed -n 's#.*gradle-\([0-9][0-9.]*\)-bin\\.zip.*#\1#p' "$wrapper" | head -n 1 || true)
    if test -n "$version" && test "$version" != "8.14.2"; then echo "WARN gradleWrapper=$version expected=8.14.2 action=use_local_gradle_or_align"; else echo "OK gradleWrapper=${version:-unknown}"; fi
else echo "WARN gradleWrapper=missing action=provide_wrapper_or_use_local_gradle"; fi

if grep -RhsEiq 'abiFilters[^\n]*(x86_64|x86|armeabi-v7a)' "$project"/*.gradle "$project"/*.gradle.kts "$project"/app/*.gradle "$project"/app/*.gradle.kts "$project"/android/app/*.gradle "$project"/android/app/*.gradle.kts 2>/dev/null; then
    echo "ERROR non_arm64_abi=declared expected=arm64-v8a-only action=align_abi_filters"; errors=1
else echo "OK x86_abi=absent"; fi

ndk_home="${WANXIANG_NDK_PATH:-${ANDROID_NDK_HOME:-/opt/wanxiang/toolchains/android/ndk}}"
expected_ndk=$(sed -n 's/^[[:space:]]*Pkg\.Revision[[:space:]]*=[[:space:]]*//p' "$ndk_home/source.properties" 2>/dev/null | head -n 1 || true)
test -n "$expected_ndk" || expected_ndk=unknown
declared_ndk=$(grep -RhsE 'ndkVersion[^0-9]*[0-9]+(\.[0-9]+)+' "$project"/*.gradle "$project"/*.gradle.kts "$project"/app/*.gradle "$project"/app/*.gradle.kts "$project"/android/app/*.gradle "$project"/android/app/*.gradle.kts 2>/dev/null | sed -n 's/.*ndkVersion[^0-9]*\([0-9][0-9.]*\).*/\1/p' | head -n 1 || true)
if test -n "$declared_ndk" && test "$expected_ndk" != unknown && test "$declared_ndk" != "$expected_ndk"; then
    echo "ERROR ndkVersion=$declared_ndk expected=$expected_ndk action=edit_project_manually"; errors=1
else echo "OK ndkVersion=${declared_ndk:-unspecified} expected=$expected_ndk"; fi

if test "$offline" = 1; then
    gradle_cache="${GRADLE_USER_HOME:-/root/.gradle}/caches/modules-2/files-2.1"
    pub_cache="${PUB_CACHE:-/opt/wanxiang/cache/flutter-pub}/hosted"
    if test "$kind" = flutter; then test -d "$pub_cache" && echo "OK flutter_pub_cache=$pub_cache" || { echo "ERROR flutter_pub_cache=missing action=populate_cache_before_offline_build"; errors=1; };
    else test -d "$gradle_cache" && echo "OK gradle_cache=$gradle_cache" || { echo "ERROR gradle_cache=missing action=populate_cache_before_offline_build"; errors=1; }; fi
fi

echo "NEXT mobile_project_align: review this report, then request confirmation before editing project files"
test "$errors" -eq 0
