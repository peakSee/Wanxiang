package top.wanxiang.app.ui.chat

import top.wanxiang.app.core.datastore.GitCredential
import java.net.URLEncoder

/**
 * Git HTTPS 私有仓库凭证注入辅助。
 *
 * 设计原则：
 * - **不落盘**：不在 `~/.git-credentials` 或 `.git/config` 里留任何凭据；
 * - **不污染 remote**：不改 `origin` 的 URL（用户看到的就是干净的 https://host/repo.git）；
 * - **一次性**：每次 op 用 `mktemp` 生成临时凭据文件 → 通过 `GIT_CONFIG_KEY_0=credential.helper`
 *   注入 `store --file=$__cred` → op 结束立即 `rm -f`。
 *
 * 兼容 GitHub Classic/Fine-grained PAT、Gitee 私人令牌、GitLab PAT——都是 HTTPS Basic Auth
 * 里 username + password/token 的场景。username 部分 GitLab 建议填 "oauth2"、GitHub 建议
 * 填 PAT 名称或用户名、Gitee 建议填账号用户名，本工具不做假设，用户填什么就用什么。
 */
internal object GitAuth {

    /** 匹配 https://[user@]host/... / git@host:... / ssh://git@host/... 中的 host 部分。 */
    private val hostRegex = Regex(
        "^(?:https?://(?:[^/@]+@)?|ssh://(?:[^/@]+@)?|git@)([^/:@]+)",
        RegexOption.IGNORE_CASE,
    )

    /** 从 git 仓库 URL 提取 host（github.com、gitee.com、gitlab.com、自托管域名等）。 */
    fun hostOf(url: String): String? =
        hostRegex.find(url.trim())?.groupValues?.getOrNull(1)?.lowercase()

    /**
     * 按 host 找凭证：
     * 1. 精确匹配（`github.com` == `github.com`）；
     * 2. 后缀匹配（`raw.githubusercontent.com` 命中 `github.com` 的凭证）；
     * 3. 都找不到则返回 null。
     */
    fun findCredential(list: List<GitCredential>, host: String?): GitCredential? {
        if (host.isNullOrBlank()) return null
        val target = host.lowercase()
        list.firstOrNull { it.host.lowercase() == target }?.let { return it }
        return list.firstOrNull { target.endsWith("." + it.host.lowercase()) }
    }

    /**
     * 用一次性 credential.helper 包装 git 命令：
     * 1. `mktemp` 临时文件；
     * 2. `printf` 写 `https://USER:TOKEN@HOST`（USER/TOKEN 用 URL 编码规避特殊字符）；
     * 3. 通过 `GIT_CONFIG_COUNT` 环境变量把 helper 传给 git，不 `-c` 修改 git 命令本体；
     * 4. 跑完立即删临时文件；透传原退出码。
     */
    fun wrap(cmd: String, cred: GitCredential): String {
        val userEnc = urlEncode(cred.username)
        val tokenEnc = urlEncode(cred.token)
        val line = "https://$userEnc:$tokenEnc@${cred.host.lowercase()}"
        return buildString {
            append("__cred=")
            append("\$(mktemp -p /tmp 2>/dev/null || mktemp); ")
            append("printf '%s\\n' '")
            append(line)
            append("' > \"\$__cred\"; ")
            append("GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=credential.helper GIT_CONFIG_VALUE_0=\"store --file=\$__cred\" ")
            append(cmd)
            append("; __rc=\$?; rm -f \"\$__cred\"; exit \$__rc")
        }
    }

    private fun urlEncode(s: String): String =
        URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
