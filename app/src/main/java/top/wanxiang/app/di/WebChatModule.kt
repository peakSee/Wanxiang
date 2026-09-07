package top.wanxiang.app.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import top.wanxiang.app.runtime.webchat.WebChatAgentGateway
import top.wanxiang.app.webchat.WanXiangWebChatAgentGateway

@Module
@InstallIn(SingletonComponent::class)
abstract class WebChatModule {
    @Binds
    abstract fun bindWebChatAgentGateway(impl: WanXiangWebChatAgentGateway): WebChatAgentGateway
}
