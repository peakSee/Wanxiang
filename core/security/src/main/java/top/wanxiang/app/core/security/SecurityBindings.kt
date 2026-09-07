package top.wanxiang.app.core.security

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import top.wanxiang.app.core.common.logging.SensitiveDataRedactor
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SecurityBindings {
    @Binds
    @Singleton
    abstract fun bindSensitiveDataRedactor(implementation: SecretRedactor): SensitiveDataRedactor
}
