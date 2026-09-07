package top.wanxiang.app.core.model

sealed interface RuntimeState {
    data object NotInitialized : RuntimeState

    data class Initializing(
        val step: String,
        val progress: Float,
        val detail: String? = null,
    ) : RuntimeState

    data object Ready : RuntimeState

    data class Error(
        val throwable: Throwable,
    ) : RuntimeState
}
