#!/bin/sh
# ==============================================================================
# WanXiang (LinuxAIRuntime) - Rust & Android JNI Cross Compilation Suite Setup
# 支持 Rust ARM64 独立开发包拉取、aarch64-linux-android 交叉编译目标库与 NDK Linker 预绑定
# ==============================================================================
set -e

echo "==> [WanXiang] 正在初始化 Rust & Android JNI 交叉编译开发环境..."

RUST_HOME="/opt/wanxiang/toolchains/rust"
mkdir -p "$RUST_HOME" /usr/local/bin /usr/bin /tmp /root/.cargo 2>/dev/null || true

RUST_VERSION="1.85.0"
RUST_DIST_URLS="
https://mirrors.tuna.tsinghua.edu.cn/rustup/dist/rust-${RUST_VERSION}-aarch64-unknown-linux-gnu.tar.gz
https://static.rust-lang.org/dist/rust-${RUST_VERSION}-aarch64-unknown-linux-gnu.tar.gz
"

RUST_STD_ANDROID_URLS="
https://mirrors.tuna.tsinghua.edu.cn/rustup/dist/rust-std-${RUST_VERSION}-aarch64-linux-android.tar.gz
https://static.rust-lang.org/dist/rust-std-${RUST_VERSION}-aarch64-linux-android.tar.gz
"

download_rust_core() {
    rm -f /tmp/rust-core.tar.gz
    for url in $RUST_DIST_URLS; do
        echo "==> [WanXiang] 正在拉取 Rust ${RUST_VERSION} ARM64 工具链 ($url)..."
        if curl -fsSL -m 300 "$url" -o /tmp/rust-core.tar.gz 2>/dev/null && [ -s /tmp/rust-core.tar.gz ]; then
            echo "==> [WanXiang] 正在安装 Rust 独立开发包到 $RUST_HOME..."
            rm -rf /tmp/wanxiang-rust-core
            mkdir -p /tmp/wanxiang-rust-core
            tar -xzf /tmp/rust-core.tar.gz -C /tmp/wanxiang-rust-core --strip-components=1
            if [ -x /tmp/wanxiang-rust-core/install.sh ]; then
                sh /tmp/wanxiang-rust-core/install.sh --prefix="$RUST_HOME" --components=rustc,cargo,rust-std-aarch64-unknown-linux-gnu --disable-ldconfig >/dev/null 2>&1
            fi
            rm -rf /tmp/wanxiang-rust-core /tmp/rust-core.tar.gz
            if [ -x "$RUST_HOME/bin/rustc" ]; then
                return 0
            fi
        fi
    done
    return 1
}

download_rust_android_target() {
    rm -f /tmp/rust-android.tar.gz
    for url in $RUST_STD_ANDROID_URLS; do
        echo "==> [WanXiang] 正在拉取 Rust aarch64-linux-android 交叉编译目标库 ($url)..."
        if curl -fsSL -m 180 "$url" -o /tmp/rust-android.tar.gz 2>/dev/null && [ -s /tmp/rust-android.tar.gz ]; then
            rm -rf /tmp/wanxiang-rust-android
            mkdir -p /tmp/wanxiang-rust-android
            tar -xzf /tmp/rust-android.tar.gz -C /tmp/wanxiang-rust-android --strip-components=1
            if [ -x /tmp/wanxiang-rust-android/install.sh ]; then
                sh /tmp/wanxiang-rust-android/install.sh --prefix="$RUST_HOME" --disable-ldconfig >/dev/null 2>&1
            fi
            rm -rf /tmp/wanxiang-rust-android /tmp/rust-android.tar.gz
            return 0
        fi
    done
    return 0
}

if [ ! -x "$RUST_HOME/bin/rustc" ] || [ ! -x "$RUST_HOME/bin/cargo" ]; then
    if ! download_rust_core; then
        echo "==> [WanXiang] ⚠️ 独立 Rust 归档拉取受限，尝试通过 rustup 快速装配..."
        export RUSTUP_DIST_SERVER=https://mirrors.tuna.tsinghua.edu.cn/rustup
        export RUSTUP_UPDATE_ROOT=https://mirrors.tuna.tsinghua.edu.cn/rustup/rustup
        curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --profile minimal --default-toolchain stable
    fi
fi

if [ -x "$RUST_HOME/bin/rustc" ]; then
    download_rust_android_target || true
fi

# 配置 Cargo 镜像源与 Android NDK Linker
cat << 'EOF' > /root/.cargo/config.toml
[target.aarch64-linux-android]
linker = "/opt/wanxiang/toolchains/android/ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang"
ar = "/opt/wanxiang/toolchains/android/ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ar"

[source.crates-io]
replace-with = 'tuna'

[source.tuna]
registry = "sparse+https://mirrors.tuna.tsinghua.edu.cn/crates.io-index/"
EOF

# 软链接至全局路径
mkdir -p /opt/wanxiang/bin
for cmd in rustc cargo rustdoc; do
    if [ -x "$RUST_HOME/bin/$cmd" ]; then
        ln -sf "$RUST_HOME/bin/$cmd" "/opt/wanxiang/bin/$cmd" 2>/dev/null || true
        ln -sf "$RUST_HOME/bin/$cmd" "/usr/local/bin/$cmd" 2>/dev/null || true
        ln -sf "$RUST_HOME/bin/$cmd" "/usr/bin/$cmd" 2>/dev/null || true
    fi
done

# 写入持久化环境变量
mkdir -p /etc/profile.d
cat << 'EOF' > /etc/profile.d/wanxiang-rust.sh
# WanXiang Rust development environment
export RUSTUP_HOME="/opt/wanxiang/toolchains/rust"
export CARGO_HOME="/root/.cargo"
export PATH="/opt/wanxiang/toolchains/rust/bin:/opt/wanxiang/bin:$PATH"
EOF
chmod 644 /etc/profile.d/wanxiang-rust.sh 2>/dev/null || true

echo "==> [WanXiang] ✅ Rust & Android JNI 交叉编译开发环境配置完成！"
