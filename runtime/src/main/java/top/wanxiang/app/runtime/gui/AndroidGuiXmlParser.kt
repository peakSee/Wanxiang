package top.wanxiang.app.runtime.gui

import android.graphics.Rect
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

data class GuiNode(
    val id: Int,
    val text: String,
    val contentDesc: String,
    val resourceId: String,
    val className: String,
    val packageName: String,
    val clickable: Boolean,
    val scrollable: Boolean,
    val editable: Boolean,
    val bounds: Rect,
) {
    val centerX: Int get() = bounds.centerX()
    val centerY: Int get() = bounds.centerY()

    fun toCompactString(): String = buildString {
        append("[$id] ")
        if (text.isNotBlank()) append("text=\"$text\" ")
        if (contentDesc.isNotBlank()) append("desc=\"$contentDesc\" ")
        if (resourceId.isNotBlank()) append("id=\"$resourceId\" ")
        if (clickable) append("clickable ")
        if (editable) append("editable ")
        if (scrollable) append("scrollable ")
        append("bounds=[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}] (center: $centerX, $centerY)")
    }
}

object AndroidGuiXmlParser {
    private val BOUNDS_PATTERN = Regex("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]")

    /**
     * 解析 UI XML 并过滤出对 Agent 有意义的可视/交互节点
     */
    fun parse(xmlContent: String, onlyInteractive: Boolean = true): List<GuiNode> {
        if (xmlContent.isBlank()) return emptyList()
        val nodes = mutableListOf<GuiNode>()
        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xmlContent))

            var eventType = parser.eventType
            var nextId = 0

            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name == "node") {
                    val text = parser.getAttributeValue(null, "text").orEmpty().trim()
                    val contentDesc = parser.getAttributeValue(null, "content-desc").orEmpty().trim()
                    val resourceId = parser.getAttributeValue(null, "resource-id").orEmpty().trim()
                    val className = parser.getAttributeValue(null, "class").orEmpty().trim()
                    val packageName = parser.getAttributeValue(null, "package").orEmpty().trim()
                    val clickable = parser.getAttributeValue(null, "clickable")?.toBooleanStrictOrNull() ?: false
                    val scrollable = parser.getAttributeValue(null, "scrollable")?.toBooleanStrictOrNull() ?: false
                    val editable = parser.getAttributeValue(null, "editable")?.toBooleanStrictOrNull() ?: false
                    val boundsStr = parser.getAttributeValue(null, "bounds").orEmpty().trim()

                    val bounds = parseBounds(boundsStr)

                    val hasContent = text.isNotBlank() || contentDesc.isNotBlank() || editable
                    val isActionable = clickable || scrollable || editable || hasContent
                    val isVisible = bounds.width() > 0 && bounds.height() > 0

                    if (isVisible && (!onlyInteractive || isActionable)) {
                        nodes.add(
                            GuiNode(
                                id = nextId++,
                                text = text,
                                contentDesc = contentDesc,
                                resourceId = resourceId,
                                className = className,
                                packageName = packageName,
                                clickable = clickable,
                                scrollable = scrollable,
                                editable = editable,
                                bounds = bounds,
                            )
                        )
                    }
                }
                eventType = parser.next()
            }
        } catch (_: Exception) {
            // 解析失败返回已解析部分或空列表
        }
        return nodes
    }

    private fun parseBounds(boundsStr: String): Rect {
        val match = BOUNDS_PATTERN.find(boundsStr) ?: return Rect(0, 0, 0, 0)
        val (left, top, right, bottom) = match.destructured
        return Rect(
            left.toIntOrNull() ?: 0,
            top.toIntOrNull() ?: 0,
            right.toIntOrNull() ?: 0,
            bottom.toIntOrNull() ?: 0,
        )
    }
}
