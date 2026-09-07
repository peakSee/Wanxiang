package top.wanxiang.app.harness.prompt

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import top.wanxiang.app.core.model.ExecutionMode
import top.wanxiang.app.runtime.privilege.PrivilegeManager

/**
 * 系统提示词中"执行权限章节"的渲染端口。
 *
 * 独立成接口是为了让 SystemPromptBuilder 的单元测试可以注入固定行为，
 * 而不必构造完整的 PrivilegeManager 运行时依赖。
 */
fun interface PrivilegeSectionRenderer {
    suspend fun render(): String
}

/** 默认实现：根据当前 PrivilegeManager 状态选择 PROOT / Shizuku / Root 章节。 */
@Singleton
class DefaultPrivilegeSectionRenderer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val privilegeManager: PrivilegeManager,
    private val promptAssets: PromptAssetLoader,
) : PrivilegeSectionRenderer {

    override suspend fun render(): String {
        val privilegeInfo = runCatching { privilegeManager.getPrivilegeInfo() }.getOrNull()
        return when {
            privilegeInfo == null ->
                context.getString(top.wanxiang.app.harness.R.string.harness_prompt_privilege_unavailable)
            privilegeInfo.mode == ExecutionMode.PROOT || !privilegeInfo.modeActive ->
                context.getString(top.wanxiang.app.harness.R.string.harness_prompt_privilege_proot)
            privilegeInfo.mode == ExecutionMode.SHIZUKU ->
                promptAssets.render("prompts/privilege_shizuku.md")
            else ->
                promptAssets.render("prompts/privilege_root.md")
        }
    }
}
