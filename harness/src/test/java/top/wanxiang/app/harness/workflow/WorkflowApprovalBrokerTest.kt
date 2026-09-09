package top.wanxiang.app.harness.workflow

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import top.wanxiang.app.core.model.workflow.*

class WorkflowApprovalBrokerTest {
    @Test fun parallelApprovalsAreQueuedAndNeverLost() = runBlocking {
        withTimeout(2000) {
            val broker = WorkflowApprovalBroker()
            val first = async(start = CoroutineStart.UNDISPATCHED) { broker.await(WorkflowApprovalRequest("run", "one", "one", "")) }
            val second = async(start = CoroutineStart.UNDISPATCHED) { broker.await(WorkflowApprovalRequest("run", "two", "two", "")) }
            assertEquals("one", broker.currentRequest.value?.nodeId)
            assertTrue(broker.decide("run", "one", WorkflowApprovalDecision(true)))
            assertTrue(first.await().approved)
            broker.currentRequest.first { it?.nodeId == "two" }
            assertTrue(broker.decide("run", "two", WorkflowApprovalDecision(false)))
            assertFalse(second.await().approved)
            assertNull(broker.currentRequest.value)
        }
    }

    @Test fun cancellationUnblocksNextExecution() = runBlocking {
        val broker = WorkflowApprovalBroker()
        val first = async(start = CoroutineStart.UNDISPATCHED) { broker.await(WorkflowApprovalRequest("a", "one", "one", "")) }
        val second = async(start = CoroutineStart.UNDISPATCHED) { broker.await(WorkflowApprovalRequest("b", "two", "two", "")) }
        broker.cancelExecution("a")
        first.join()
        assertTrue(first.isCancelled)
        assertEquals("b", broker.currentRequest.value?.executionId)
        broker.decide("b", "two", WorkflowApprovalDecision(true))
        second.await()
        assertNull(broker.currentRequest.value)
    }
}
