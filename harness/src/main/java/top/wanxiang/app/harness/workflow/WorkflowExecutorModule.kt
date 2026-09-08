package top.wanxiang.app.harness.workflow

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
abstract class WorkflowExecutorModule {
    @Binds @IntoSet abstract fun bindPassthrough(impl: PassthroughNodeExecutor): NodeExecutor
    @Binds @IntoSet abstract fun bindApproval(impl: ApprovalNodeExecutor): NodeExecutor
    @Binds @IntoSet abstract fun bindLinux(impl: LinuxNodeExecutor): NodeExecutor
    @Binds @IntoSet abstract fun bindAgent(impl: AgentNodeExecutor): NodeExecutor
    @Binds @IntoSet abstract fun bindCondition(impl: ConditionNodeExecutor): NodeExecutor
    @Binds @IntoSet abstract fun bindDelay(impl: DelayNodeExecutor): NodeExecutor
    @Binds @IntoSet abstract fun bindSetVariable(impl: SetVariableNodeExecutor): NodeExecutor
    @Binds @IntoSet abstract fun bindHostAction(impl: HostActionNodeExecutor): NodeExecutor

    @Binds abstract fun bindAgentExecutionPort(impl: HarnessWorkflowAgentExecutionPort): WorkflowAgentExecutionPort
}
