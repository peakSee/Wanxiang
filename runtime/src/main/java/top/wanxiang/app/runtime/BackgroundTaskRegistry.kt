package top.wanxiang.app.runtime

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Tracks application-owned long-running work that is not a Linux background process. */
@Singleton
class BackgroundTaskRegistry @Inject constructor() {
    private val _activeTasks = MutableStateFlow<Set<String>>(emptySet())
    val activeTasks: StateFlow<Set<String>> = _activeTasks.asStateFlow()

    @Synchronized
    fun start(id: String) {
        _activeTasks.value = _activeTasks.value + id
    }

    @Synchronized
    fun finish(id: String) {
        _activeTasks.value = _activeTasks.value - id
    }
}
