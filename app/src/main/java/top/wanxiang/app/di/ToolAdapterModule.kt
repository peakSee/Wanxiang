package top.wanxiang.app.di

import top.wanxiang.app.core.tools.ToolRuntimeAdapter
import top.wanxiang.app.runtime.tools.CodexToolInstaller
import top.wanxiang.app.runtime.tools.HelloToolInstaller
import top.wanxiang.app.runtime.tools.HermesToolInstaller
import top.wanxiang.app.runtime.tools.OpenClawToolInstaller
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
abstract class ToolAdapterModule {
    @Binds
    @IntoSet
    abstract fun bindHelloToolInstaller(installer: HelloToolInstaller): ToolRuntimeAdapter

    @Binds
    @IntoSet
    abstract fun bindCodexToolInstaller(installer: CodexToolInstaller): ToolRuntimeAdapter

    @Binds
    @IntoSet
    abstract fun bindOpenClawToolInstaller(installer: OpenClawToolInstaller): ToolRuntimeAdapter

    @Binds
    @IntoSet
    abstract fun bindHermesToolInstaller(installer: HermesToolInstaller): ToolRuntimeAdapter
}
