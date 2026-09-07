package top.wanxiang.app.harness

import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * 单回合多工具调用的受限并发调度器。
 *
 * 约束：
 * - 只读类工具（由调用方通过 [isParallelSafe] 判定）在 [parallelism] 个许可内并发执行；
 * - 变更类工具（写文件/命令/下载/MCP 等）全局互斥，避免同一工作区内的副作用互相踩踏；
 * - 任一工具触发审批暂停（[Pause.abort]）后，尚未开始的工具不再启动，在途工具自然跑完，
 *   与原串行"中途暂停、后续调用不执行"的语义保持一致；
 * - 取消沿结构化并发传播：外层 Job 被取消时，所有在途工具被打断并向上抛出
 *   CancellationException，由 HarnessLoop 的悬空调用修复逻辑收尾。
 */
@Singleton
class ToolRoundDispatcher @Inject constructor() {

    class Pause private constructor() {
        private val aborted = AtomicBoolean(false)
        fun abort() { aborted.set(true) }
        fun isAborted(): Boolean = aborted.get()

        companion object {
            fun create(): Pause = Pause()
        }
    }

    suspend fun <T> dispatch(
        items: List<T>,
        parallelism: Int = DEFAULT_PARALLELISM,
        isParallelSafe: (T) -> Boolean,
        run: suspend (T, Pause) -> Unit,
    ) {
        if (items.isEmpty()) return
        if (items.size == 1 || parallelism <= 1) {
            val pause = Pause.create()
            items.forEach { item ->
                if (pause.isAborted()) return
                run(item, pause)
            }
            return
        }
        val pause = Pause.create()
        val mutationMutex = Mutex()
        val permits = Semaphore(parallelism)
        coroutineScope {
            items.forEach { item ->
                launch {
                    permits.withPermit {
                        if (pause.isAborted()) return@withPermit
                        if (isParallelSafe(item)) run(item, pause)
                        else mutationMutex.withLock { run(item, pause) }
                    }
                }
            }
        }
    }

    companion object {
        const val DEFAULT_PARALLELISM = 4
    }
}
