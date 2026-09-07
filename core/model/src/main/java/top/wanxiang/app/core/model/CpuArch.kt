package top.wanxiang.app.core.model

enum class CpuArch {
    ARM64,
    ARM32,
    X86_64,
    X86,
    UNKNOWN;

    companion object {
        fun fromBuildAbi(abi: String): CpuArch = when (abi.lowercase()) {
            "arm64-v8a" -> ARM64
            "armeabi-v7a" -> ARM32
            "x86_64" -> X86_64
            "x86" -> X86
            else -> UNKNOWN
        }
    }
}