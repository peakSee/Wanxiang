package top.wanxiang.app.runtime.sandbox

import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import top.wanxiang.app.runtime.LinuxRuntime
import top.wanxiang.app.runtime.shell.ShellCommand

/**
 * 沙箱文档文本抽取：附件路径 → 纯文本喂给 LLM。
 *
 * **PDF**：走 poppler-utils 的 `pdftotext -enc UTF-8 <file> -`（沙箱未装则首次 `apt-get install -y poppler-utils`，
 * 一次性代价约 5MB + 30s，之后即时）。poppler 对绝大多数论文 / 报告效果最好；扫描图版本会拿不到文字。
 *
 * **DOCX / PPTX / EPUB**：三者本质都是 zip 包，用 `unzip -p` 抽出内部 XML → `sed` 剥标签 → 得到近似正文。
 * 不用装 libreoffice/pandoc（>30MB 依赖），纯 shell 秒回。复杂排版会丢格式，但读文本足够。
 *
 * **MD / TXT / HTML**：直接 cat（HTML 也走 sed 剥标签）。
 *
 * 上限 40KB，够喂大多数长文档给 LLM；超出的部分尾部提示省略。
 */
@Singleton
class SandboxTextExtractor @Inject constructor(
    private val linuxRuntime: LinuxRuntime,
) {
    sealed interface Result {
        data class Ok(val text: String, val truncated: Boolean) : Result
        data class Skipped(val reason: String) : Result
        data class Failed(val error: String) : Result
    }

    /** 输入是沙箱内路径（形如 `/attachments/1723..._report.pdf`），guestPath 由 [top.wanxiang.app.ui.chat.ChatAttachment] 提供。 */
    suspend fun extract(guestPath: String, originalName: String): Result {
        val ext = originalName.substringAfterLast('.', "").lowercase(Locale.US)
        val cmd = when (ext) {
            "pdf" -> """
                command -v pdftotext >/dev/null 2>&1 || { apt-get update -qq >/dev/null 2>&1 && apt-get install -y -qq poppler-utils >/dev/null 2>&1; }
                if ! command -v pdftotext >/dev/null 2>&1; then
                  echo "__WANXIANG_NO_PDFTOTEXT__" >&2; exit 2
                fi
                pdftotext -enc UTF-8 ${sq(guestPath)} - 2>/dev/null | head -c $MAX_BYTES
            """.trimIndent()
            "docx", "pptx", "epub" -> {
                // zip 内文档 XML 抽正文：docx 用 word/document.xml；pptx 逐张 slide；epub 合并 xhtml
                val xmlGlob = when (ext) {
                    "docx" -> "word/document.xml"
                    "pptx" -> "ppt/slides/slide*.xml"
                    "epub" -> "*.xhtml *.xml"
                    else -> ""
                }
                """
                  if [ ! -f ${sq(guestPath)} ]; then echo "__WANXIANG_NOT_FOUND__" >&2; exit 3; fi
                  unzip -o -qq ${sq(guestPath)} -d /tmp/_wx_extract >/dev/null 2>&1 || { echo "__WANXIANG_UNZIP_FAIL__" >&2; exit 4; }
                  cat /tmp/_wx_extract/$xmlGlob 2>/dev/null \
                    | sed -e 's/<[^>]*>/ /g' -e 's/&amp;/\&/g; s/&lt;/</g; s/&gt;/>/g; s/&quot;/"/g' \
                    -e 's/[[:space:]]\+/ /g' | head -c $MAX_BYTES
                  rm -rf /tmp/_wx_extract
                """.trimIndent()
            }
            "txt", "md", "markdown", "html", "htm", "log", "json", "csv" ->
                "cat ${sq(guestPath)} 2>/dev/null | head -c $MAX_BYTES"
            else -> return Result.Skipped("未支持的文件类型 .$ext")
        }
        val result = runCatching {
            linuxRuntime.execute(
                ShellCommand(
                    commandLine = "$cmd 2>/tmp/_wx_err; ec=$?; if [ \$ec -ne 0 ]; then cat /tmp/_wx_err; fi; exit \$ec",
                    workingDirectory = "/root",
                    timeoutMs = 90_000L,
                ),
            )
        }
        val r = result.getOrNull() ?: return Result.Failed("沙箱未就绪或执行失败")
        val out = r.stdout
        val errCombined = out + "\n" + r.stderr
        return when {
            "__WANXIANG_NO_PDFTOTEXT__" in errCombined -> Result.Failed("poppler-utils 装不上（可能网络不通）。手动 apt install poppler-utils 后再试")
            "__WANXIANG_UNZIP_FAIL" in errCombined -> Result.Failed("解压失败，文件可能损坏或加密")
            "__WANXIANG_NOT_FOUND" in errCombined -> Result.Failed("沙箱找不到附件文件")
            out.isBlank() -> Result.Skipped("抽不到文本（可能是纯图片/扫描件）")
            out.length >= MAX_BYTES -> Result.Ok(out, truncated = true)
            else -> Result.Ok(out, truncated = false)
        }
    }

    private fun sq(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    private companion object { const val MAX_BYTES = 40_000 }
}
