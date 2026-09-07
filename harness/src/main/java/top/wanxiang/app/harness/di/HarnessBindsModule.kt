package top.wanxiang.app.harness.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import top.wanxiang.app.harness.checkpoint.ConversationRewinder
import top.wanxiang.app.harness.checkpoint.SessionForkConversationRewinder
import top.wanxiang.app.harness.prompt.DefaultPrivilegeSectionRenderer
import top.wanxiang.app.harness.prompt.PrivilegeSectionRenderer
import top.wanxiang.app.harness.projection.LiveMessagePort
import top.wanxiang.app.harness.projection.SessionMessageProjector

/** Harness 模块内的 Hilt 端点绑定：可测接缝在此收口。 */
@Module
@InstallIn(SingletonComponent::class)
abstract class HarnessBindsModule {

    @Binds
    @Singleton
    abstract fun bindPrivilegeSectionRenderer(
        impl: DefaultPrivilegeSectionRenderer,
    ): PrivilegeSectionRenderer

    /** 实时消息窄端口 → 会话消息投影器（CapabilityEventWriter / HarnessLoop 共用） */
    @Binds
    @Singleton
    abstract fun bindLiveMessagePort(
        impl: SessionMessageProjector,
    ): LiveMessagePort

    /** 对话回退 fork 处理器 → 会话树派生实现（RewindController 的可选注入点收口） */
    @Binds
    @Singleton
    abstract fun bindConversationRewinder(
        impl: SessionForkConversationRewinder,
    ): ConversationRewinder
}
