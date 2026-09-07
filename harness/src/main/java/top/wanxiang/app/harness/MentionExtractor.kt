package top.wanxiang.app.harness

/** 解析用户消息中的 @提及 名称（技能 / MCP 服务 / 子智能体触发）。 */
object MentionExtractor {
    fun parse(text: String): Set<String> {
        if (!text.contains("@")) return emptySet()
        val regex = Regex("""@([^\s@,，:：\n]+)""")
        return regex.findAll(text).map { it.groupValues[1].trim().lowercase() }.toSet()
    }
}
