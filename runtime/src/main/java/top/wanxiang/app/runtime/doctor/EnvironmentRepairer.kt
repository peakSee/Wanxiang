package top.wanxiang.app.runtime.doctor

import top.wanxiang.app.core.model.RepairProgress
import top.wanxiang.app.core.model.RuntimeState
import top.wanxiang.app.runtime.LinuxRuntime
import top.wanxiang.app.runtime.shell.CommandResult
import top.wanxiang.app.runtime.shell.ShellCommand
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@Singleton
class EnvironmentRepairer @Inject constructor(
    private val linuxRuntime: LinuxRuntime,
    private val environmentDoctor: EnvironmentDoctor,
    private val logger: top.wanxiang.app.core.common.logging.AppLogger,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _progress = MutableStateFlow<RepairProgress?>(null)
    val progress: StateFlow<RepairProgress?> = _progress.asStateFlow()
    private val _isRepairing = MutableStateFlow(false)
    val isRepairing: StateFlow<Boolean> = _isRepairing.asStateFlow()
    private var currentRepairJob: Job? = null

    fun startRepair(): Job {
        val existing = currentRepairJob
        if (existing?.isActive == true) return existing
        val job = scope.launch {
            _isRepairing.value = true
            try {
                repair().collect { p ->
                    _progress.value = p
                }
            } finally {
                _isRepairing.value = false
            }
        }
        currentRepairJob = job
        return job
    }

    fun cancelRepair() {
        currentRepairJob?.cancel()
        _isRepairing.value = false
        _progress.value = null
    }

    fun repair(): Flow<RepairProgress> = flow {
        val totalSteps = 5
        val logs = mutableListOf<String>()

        fun addLog(message: String) {
            // 双写：UI 日志列表 + AppLogger（logcat WanXiang tag + 公共 Download/WanXiang/runtime.log）
            // 让「开发者控制台 → 应用日志抓取」和用户直接拷 runtime.log 都能看到自愈全过程。
            logger.i("[自愈] $message")
            logs.add(message)
            if (logs.size > 200) {
                logs.removeAt(0)
            }
        }

        if (linuxRuntime.state.value !is RuntimeState.Ready) {
            emit(
                RepairProgress(
                    stepTitle = "Linux 沙箱未就绪",
                    stepIndex = 0,
                    totalSteps = totalSteps,
                    progress = 0.0f,
                    logs = listOf("错误: Linux 沙箱未初始化或处于异常状态，无法执行自愈修复"),
                    isCompleted = false,
                    isFailed = true,
                    errorMessage = "Linux 沙箱未就绪，请先初始化沙箱",
                ),
            )
            return@flow
        }

        try {
            // ==========================================
            // Step 1: 修复 DNS 与证书
            // ==========================================
            emit(
                RepairProgress(
                    stepTitle = "正在配置高可用 DNS 与 SSL 根证书...",
                    stepIndex = 1,
                    totalSteps = totalSteps,
                    progress = 0.15f,
                    logs = logs.toList(),
                ),
            )
            addLog("[Step 1/5] 写入公共 DNS (114.114.114.114, 223.5.5.5, 8.8.8.8)")
            val dnsCmd = "mkdir -p /etc && printf 'nameserver 114.114.114.114\\nnameserver 223.5.5.5\\nnameserver 8.8.8.8\\n' > /etc/resolv.conf"
            val dnsRes = executeCommand(dnsCmd, logs)
            if (!dnsRes.isSuccess) {
                addLog("警告: 写入 /etc/resolv.conf 失败: ${dnsRes.stderr}")
            }

            // ==========================================
            // Step 2: 清理残留锁并切换国内镜像加速源
            // ==========================================
            emit(
                RepairProgress(
                    stepTitle = "正在配置清华大学国内镜像加速源...",
                    stepIndex = 2,
                    totalSteps = totalSteps,
                    progress = 0.35f,
                    logs = logs.toList(),
                ),
            )
            addLog("[Step 2/5] 清理 dpkg/apt 事务锁并替换国内源")
            val mirrorScript = """
                rm -rf /var/lib/dpkg/updates/* /var/lib/apt/lists/lock /var/cache/apt/archives/lock /var/lib/dpkg/lock* 2>/dev/null || true
                DEBIAN_FRONTEND=noninteractive dpkg --configure -a 2>/dev/null || true
                mkdir -p /etc/apt/sources.list.d
                # 停用所有自带官方源与旧源，避免与新源冲突或引发 404
                for f in /etc/apt/sources.list /etc/apt/sources.list.d/*.list /etc/apt/sources.list.d/*.sources; do
                    if [ -f "${'$'}f" ] && [ "${'$'}(basename "${'$'}f")" != "wanxiang-mirrors.list" ]; then
                        mv "${'$'}f" "${'$'}f.wanxiang-disabled" 2>/dev/null || true
                    fi
                done
                if [ -f /etc/os-release ]; then
                    . /etc/os-release
                    PICK=""
                    probe() { curl -fsS -m 8 -o /dev/null "${'$'}1/dists/${'$'}2/InRelease" 2>/dev/null; }
                    if [ "${'$'}ID" = "ubuntu" ]; then
                        CN="${'{'}VERSION_CODENAME:-noble}"
                        for m in https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports https://mirrors.aliyun.com/ubuntu-ports https://mirrors.ustc.edu.cn/ubuntu-ports https://mirrors.sjtug.sjtu.edu.cn/ubuntu-ports https://ports.ubuntu.com/ubuntu-ports; do
                            if probe "${'$'}m" "${'$'}CN"; then PICK="${'$'}m"; break; fi
                        done
                        [ -z "${'$'}PICK" ] && PICK="https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports"
                        printf "deb %s %s main restricted universe multiverse\ndeb %s %s-updates main restricted universe multiverse\ndeb %s %s-security main restricted universe multiverse\ndeb %s %s-backports main restricted universe multiverse\n" "${'$'}PICK" "${'$'}CN" "${'$'}PICK" "${'$'}CN" "${'$'}PICK" "${'$'}CN" "${'$'}PICK" "${'$'}CN" > /etc/apt/sources.list.d/wanxiang-mirrors.list
                        echo "apt 镜像选定: ${'$'}PICK"
                    elif [ "${'$'}ID" = "debian" ] || [ "${'{'}ID_LIKE:-}" = "debian" ]; then
                        CN="${'{'}VERSION_CODENAME:-bookworm}"
                        for pair in "https://mirrors.tuna.tsinghua.edu.cn/debian https://mirrors.tuna.tsinghua.edu.cn/debian-security" "https://mirrors.aliyun.com/debian https://mirrors.aliyun.com/debian-security" "https://mirrors.ustc.edu.cn/debian https://mirrors.ustc.edu.cn/debian-security" "https://deb.debian.org/debian https://security.debian.org/debian-security"; do
                            M=${'{'}pair%% *}; S=${'{'}pair##* }
                            if probe "${'$'}M" "${'$'}CN"; then PICKM="${'$'}M"; PICKS="${'$'}S"; break; fi
                        done
                        [ -z "${'{'}PICKM:-}" ] && PICKM="https://mirrors.tuna.tsinghua.edu.cn/debian" && PICKS="https://mirrors.tuna.tsinghua.edu.cn/debian-security"
                        printf "deb %s %s main contrib non-free non-free-firmware\ndeb %s %s-updates main contrib non-free non-free-firmware\ndeb %s %s-security main contrib non-free non-free-firmware\n" "${'$'}PICKM" "${'$'}CN" "${'$'}PICKM" "${'$'}CN" "${'$'}PICKS" "${'$'}CN" > /etc/apt/sources.list.d/wanxiang-mirrors.list
                        echo "apt 镜像选定: ${'$'}PICKM"
                    elif [ "${'$'}ID" = "kali" ]; then
                        for m in https://mirrors.tuna.tsinghua.edu.cn/kali https://mirrors.aliyun.com/kali https://mirrors.ustc.edu.cn/kali https://mirrors.sjtug.sjtu.edu.cn/kali; do
                            if probe "${'$'}m" "kali-rolling"; then PICK="${'$'}m"; break; fi
                        done
                        [ -z "${'$'}PICK" ] && PICK="https://mirrors.tuna.tsinghua.edu.cn/kali"
                        printf "deb %s kali-rolling main contrib non-free\n" "${'$'}PICK" > /etc/apt/sources.list.d/wanxiang-mirrors.list
                        echo "apt 镜像选定: ${'$'}PICK"
                    fi
                fi
                # 清理旧的 apt lists 缓存，确保重新从镜像拉取完整的 index
                rm -rf /var/lib/apt/lists/* 2>/dev/null || true
            """.trimIndent()
            executeCommand(mirrorScript, logs)

            // ==========================================
            // Step 3: 更新 APT 软件包索引
            // ==========================================
            emit(
                RepairProgress(
                    stepTitle = "正在更新 APT 软件包索引...",
                    stepIndex = 3,
                    totalSteps = totalSteps,
                    progress = 0.55f,
                    logs = logs.toList(),
                ),
            )
            addLog("[Step 3/5] 执行 apt-get update 刷新索引")
            val updateRes = executeCommand("rm -rf /var/lib/dpkg/updates/* /var/lib/dpkg/lock* 2>/dev/null || true; DEBIAN_FRONTEND=noninteractive dpkg --configure -a 2>/dev/null || true; DEBIAN_FRONTEND=noninteractive apt-get update -y", logs, timeoutMs = 120_000L)
            if (!updateRes.isSuccess) {
                addLog("提示: apt-get update 产生部分非致命提示")
            }

            // ==========================================
            // Step 4: 安装核心工具链 (curl, git, tar, xz-utils)
            // ==========================================
            emit(
                RepairProgress(
                    stepTitle = "正在安装核心工具链 (Git / Curl / Tar / XZ)...",
                    stepIndex = 4,
                    totalSteps = totalSteps,
                    progress = 0.75f,
                    logs = logs.toList(),
                ),
            )
            addLog("[Step 4/5] 安装 ca-certificates, curl, git, tar, xz-utils, procps")
            val installToolsCmd = "DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends ca-certificates curl git tar xz-utils procps"
            val installToolsRes = executeCommand(installToolsCmd, logs, timeoutMs = 180_000L)
            if (!installToolsRes.isSuccess) {
                addLog("警告: 基础工具链安装异常: ${installToolsRes.stderr.ifBlank { installToolsRes.stdout }}")
            }

            // ==========================================
            // Step 5: 配置并准备/升级 Node.js 与国内包镜像
            // ==========================================
            emit(
                RepairProgress(
                    stepTitle = "正在就绪/升级 Node.js 运行时与 NPM/PIP 国内镜像...",
                    stepIndex = 5,
                    totalSteps = totalSteps,
                    progress = 0.90f,
                    logs = logs.toList(),
                ),
            )
            addLog("[Step 5/5] 检查并自动升级 Node.js (>= v20 LTS) 及配置镜像加速")
            val setupNodeAndMirrors = """
                NODE_VER=${'$'}(node -v 2>/dev/null | tr -d 'v' | cut -d. -f1)
                if [ -z "${'$'}NODE_VER" ] || [ "${'$'}NODE_VER" -lt 20 ]; then
                    echo "检测到 Node.js 缺失或版本较低 (当前: ${'$'}{NODE_VER:-未安装})，正在下载安装 Node.js v22 (LTS ARM64)..."
                    mkdir -p /tmp/node_setup
                    curl -fsSL --connect-timeout 10 --max-time 180 https://npmmirror.com/mirrors/node/v22.14.0/node-v22.14.0-linux-arm64.tar.xz -o /tmp/node_setup/node.tar.xz || \
                    curl -fsSL --connect-timeout 10 --max-time 180 https://nodejs.org/dist/v22.14.0/node-v22.14.0-linux-arm64.tar.xz -o /tmp/node_setup/node.tar.xz || true
                    if [ -f /tmp/node_setup/node.tar.xz ]; then
                        tar -xJf /tmp/node_setup/node.tar.xz -C /usr/local --strip-components=1
                        rm -rf /tmp/node_setup
                        echo "Node.js v22 升级完成: ${'$'}(node -v 2>/dev/null)"
                    else
                        echo "预编译包拉取受限，尝试通过系统包管理器就绪基础 Node..."
                        DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends nodejs npm || true
                    fi
                fi
                which npm >/dev/null 2>&1 && npm config set registry https://registry.npmmirror.com || true
                mkdir -p ${'$'}HOME/.pip
                printf '[global]\nindex-url = https://pypi.tuna.tsinghua.edu.cn/simple\n' > ${'$'}HOME/.pip/pip.conf 2>/dev/null || true
                echo "运行时就绪状态: Node ${'$'}(node -v 2>/dev/null || echo '未就绪'), NPM ${'$'}(npm -v 2>/dev/null || echo '未就绪')"
                if [ -d /opt/android-sdk ] || [ -d /opt/wanxiang/toolchains/android ]; then
                    if [ ! -f /etc/profile.d/wanxiang-android.sh ]; then
                        mkdir -p /etc/profile.d
                        cat << 'EOF' > /etc/profile.d/wanxiang-android.sh
# WanXiang Android development environment (self-healed by EnvironmentRepairer)
export JAVA_HOME="${'$'}{JAVA_HOME:-/opt/wanxiang/toolchains/android/jdk}"
export ANDROID_HOME="/opt/android-sdk"
export ANDROID_SDK_ROOT="/opt/android-sdk"
export GRADLE_HOME="/opt/gradle-8.14.2"
export WANXIANG_AAPT2_PATH="/opt/android-sdk/build-tools/35.0.0/aapt2"
export WANXIANG_NDK_PATH="/opt/wanxiang/toolchains/android/ndk"
export WANXIANG_NDK_VERSION="r29"
export ANDROID_NDK_HOME="/opt/wanxiang/toolchains/android/ndk"
export ANDROID_NDK_ROOT="/opt/wanxiang/toolchains/android/ndk"
export WANXIANG_CMAKE_HOME="/opt/wanxiang/tools/android-suite-offline/cmake"
export WANXIANG_NINJA_HOME="/opt/wanxiang/tools/android-suite-offline/bin"
export PATH="/opt/wanxiang/bin:/opt/wanxiang/tools/android-suite-offline/bin:/opt/wanxiang/tools/android-suite-offline/cmake/bin:${'$'}JAVA_HOME/bin:${'$'}GRADLE_HOME/bin:/opt/flutter/bin:${'$'}PATH"
export _JAVA_OPTIONS="-Djava.security.egd=file:/dev/urandom"
EOF
                        chmod 644 /etc/profile.d/wanxiang-android.sh 2>/dev/null || true
                    fi
                    if [ -f /root/.bashrc ] && ! grep -q "wanxiang-android" /root/.bashrc 2>/dev/null; then
                        echo '. /etc/profile.d/wanxiang-android.sh 2>/dev/null || true' >> /root/.bashrc
                    fi
                fi
            """.trimIndent()
            executeCommand(setupNodeAndMirrors, logs, timeoutMs = 240_000L)

            // 触发重新体检
            addLog("自愈修复已全部执行完成，正在刷新环境体检报告...")
            environmentDoctor.check()

            emit(
                RepairProgress(
                    stepTitle = "环境自愈与加速配置完成！",
                    stepIndex = 5,
                    totalSteps = totalSteps,
                    progress = 1.0f,
                    logs = logs.toList(),
                    isCompleted = true,
                    isFailed = false,
                ),
            )
        } catch (cancellation: CancellationException) {
            addLog("修复流程被用户取消")
            emit(
                RepairProgress(
                    stepTitle = "修复已取消",
                    stepIndex = 0,
                    totalSteps = totalSteps,
                    progress = 0.0f,
                    logs = logs.toList(),
                    isCompleted = false,
                    isFailed = true,
                    errorMessage = "修复任务已被手动取消",
                ),
            )
            throw cancellation
        } catch (throwable: Throwable) {
            addLog("修复异常: ${throwable.message}")
            emit(
                RepairProgress(
                    stepTitle = "自愈修复失败",
                    stepIndex = 0,
                    totalSteps = totalSteps,
                    progress = 0.0f,
                    logs = logs.toList(),
                    isCompleted = false,
                    isFailed = true,
                    errorMessage = throwable.message ?: "环境自愈修复发生未知异常",
                ),
            )
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun executeCommand(
        command: String,
        logs: MutableList<String>,
        timeoutMs: Long = 60_000L,
    ): CommandResult {
        val result = runCatching {
            linuxRuntime.execute(ShellCommand(commandLine = command, timeoutMs = timeoutMs))
        }.getOrElse {
            CommandResult(
                exitCode = 1,
                stdout = "",
                stderr = it.message ?: "执行命令出错",
                durationMs = 0L,
            )
        }

        result.stdout.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(50)
            .forEach { logs.add(it) }

        if (!result.isSuccess) {
            result.stderr.lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .take(30)
                .forEach { logs.add("ERR: $it") }
        }

        return result
    }
}
