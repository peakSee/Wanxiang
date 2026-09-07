package top.wanxiang.app.harness.mcp

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Production binding: the Linux-backed channel factory is the only one Hilt needs to wire. */
@Module
@InstallIn(SingletonComponent::class)
abstract class McpStdioChannelFactoryModule {
    @Binds
    @Singleton
    abstract fun bindMcpStdioChannelFactory(impl: LinuxMcpStdioChannelFactory): McpStdioChannelFactory
}
