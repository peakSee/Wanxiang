package top.wanxiang.app.harness.prompt

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.wanxiang.app.harness.R

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PromptAssetLoaderTest {
    private lateinit var loader: PromptAssetLoader

    @Before
    fun setUp() {
        loader = PromptAssetLoader(ApplicationProvider.getApplicationContext<Context>())
    }

    @Test
    fun allPromptAssetsCanBeOpened() {
        val paths = listMarkdowns("prompts")
        assertTrue(paths.isNotEmpty())
        paths.forEach { path -> assertTrue("blank: $path", loader.read(path).isNotBlank()) }
    }

    @Test
    fun shortPromptsAreAvailableAsStringResources() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ids = listOf(
            R.string.harness_prompt_tool_call_disabled,
            R.string.harness_prompt_thinking_language_zh,
            R.string.harness_prompt_thinking_language_en,
            R.string.harness_prompt_subagent_none,
            R.string.harness_prompt_subagent_lane_system,
            R.string.harness_prompt_subagent_trigger_manual,
            R.string.harness_prompt_privilege_unavailable,
            R.string.harness_prompt_privilege_proot,
        )
        ids.forEach { id -> assertTrue(context.getString(id).isNotBlank()) }
    }

    @Test
    fun requiredVariablesAreRendered() {
        val rendered = loader.render(
            "prompts/workspace_context.md",
            mapOf(
                "WORKSPACE_PATH" to "/workspace/demo",
                "DISTRO_NAME" to "Debian",
                "TYPE_GUIDANCE" to "general",
            ),
        )
        assertTrue(rendered.contains("/workspace/demo"))
        assertFalse(rendered.contains("{{"))
    }

    @Test
    fun allKnownAssetVariablesCanBeRendered() {
        val variables = mapOf(
            "DISTRO_NAME" to "Debian",
            "PKG_MANAGER" to "apt-get install",
            "ACTIVE_SKILLS" to "",
            "TRIGGER_POLICY" to "manual",
            "DEPARTMENT_INDEX" to "- department=\"engineering\"：工程研发 / Engineering（启用 59）",
            "MARKER_TEXT" to "README.md",
            "WORKSPACE_PATH" to "/workspace/demo",
            "TYPE_GUIDANCE" to "general",
            "ROLE_NAME" to "Assistant",
            "ROLE_ID" to "assistant",
            "TASK_NAME" to "test",
            "WORKSPACE_LINE" to "工作区：/workspace/demo",
            "ROLE_PROMPT" to "be precise",
            "TASK_PROMPT" to "run tests",
            "WRITE_LINE" to "限定写入范围：app/src/ui",
            "FACTS_PACK" to "## 父级上下文事实包\n- 测试事实",
        )
        listMarkdowns("prompts").forEach { path ->
            val rendered = loader.render(path, variables)
            assertFalse("Unresolved variable in $path", rendered.contains(Regex("""\{\{[A-Z]""")))
        }
    }

    @Test
    fun subagentGuidanceUsesOnlyTheConstantDepartmentIndex() {
        val rendered = loader.render(
            "prompts/subagent_guidance.md",
            mapOf(
                "TRIGGER_POLICY" to "manual",
                "DEPARTMENT_INDEX" to "- department=\"engineering\"：工程研发 / Engineering（启用 59）",
            ),
        )

        assertTrue(rendered.contains("department + agentQuery"))
        assertTrue(rendered.contains("启用 59"))
        assertFalse(rendered.contains("agency_engineering_frontend_developer"))
        assertFalse(rendered.contains("Frontend Developer"))
    }

    /** 递归列出 assets 下指定目录中的全部 .md 文件（含子目录如 system/）。 */
    private fun listMarkdowns(assetPath: String): List<String> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val children = context.assets.list(assetPath).orEmpty().toList()
        return children.flatMap { child ->
            val full = if (assetPath.isEmpty()) child else "$assetPath/$child"
            if (child.endsWith(".md")) listOf(full) else listMarkdowns(full)
        }
    }

    @Test
    fun missingVariableFailsImmediately() {
        val error = assertThrows(IllegalStateException::class.java) {
            loader.renderTemplate("test.md", "hello {{NAME}}", emptyMap())
        }
        assertTrue(error.message.orEmpty().contains("NAME"))
    }
}
