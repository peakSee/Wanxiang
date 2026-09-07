package top.wanxiang.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelCandidateFilterTest {

    private val models = listOf(
        "gpt-5",
        "gpt-4.1-mini",
        "claude-sonnet-4",
        "deepseek-reasoner",
    )

    @Test
    fun blankQueryKeepsAllCandidates() {
        assertEquals(models, filterCandidateModels(models, "  "))
    }

    @Test
    fun queryMatchesModelIdIgnoringCaseAndWhitespace() {
        assertEquals(
            listOf("gpt-5", "gpt-4.1-mini"),
            filterCandidateModels(models, "  GPT  "),
        )
    }

    @Test
    fun unmatchedQueryReturnsEmptyList() {
        assertEquals(emptyList<String>(), filterCandidateModels(models, "gemini"))
    }
}
