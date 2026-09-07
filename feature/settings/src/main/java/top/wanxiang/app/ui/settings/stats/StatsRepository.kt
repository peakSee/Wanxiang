package top.wanxiang.app.ui.settings.stats

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import top.wanxiang.app.core.database.AiModelRepository
import top.wanxiang.app.core.database.HarnessRuntimeRepository
import top.wanxiang.app.core.database.HarnessSessionRepository
import top.wanxiang.app.core.datastore.SettingsDataStore
import top.wanxiang.app.core.model.StatsDateRange
import top.wanxiang.app.core.model.StatsHeatmapDay
import top.wanxiang.app.core.model.StatsRankItem
import top.wanxiang.app.core.model.StatsSnapshot
import top.wanxiang.app.core.model.StatsSummary
import top.wanxiang.app.core.model.StatsTokenBucket
import top.wanxiang.app.core.model.StatsTrendDay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StatsRepository @Inject constructor(
    private val runtimeRepository: HarnessRuntimeRepository,
    private val sessionRepository: HarnessSessionRepository,
    private val aiModelRepository: AiModelRepository,
    private val settingsDataStore: SettingsDataStore,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun buildSnapshot(
        range: StatsDateRange,
        now: LocalDate = LocalDate.now(),
    ): StatsSnapshot = withContext(Dispatchers.IO) {
        val zone = ZoneId.systemDefault()
        val startEpochMs = range.start?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()
        val endEpochMs = range.end?.plusDays(1)?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()

        // 1. 基础汇总
        val totalSessions = sessionRepository.countInRange(startEpochMs, endEpochMs)
        val totalMessages = runtimeRepository.countEntriesInRange(startEpochMs, endEpochMs)
        val launchCount = settingsDataStore.appLaunchCount.first()

        // 2. 所有模型与会话缓存
        val allModels = aiModelRepository.observeAll().first().associateBy { it.id }
        val allSessions = sessionRepository.listAll().associateBy { it.id }

        // 3. 消息详情拉取以做 Token 与 Provider 聚合
        val messagesInRange = runtimeRepository.listEntriesInRange(startEpochMs, endEpochMs)

        var totalInputTokens = 0L
        var totalOutputTokens = 0L
        var totalCachedTokens = 0L

        val modelUsageCounts = mutableMapOf<String, Int>()
        val providerUsageCounts = mutableMapOf<String, Int>()
        val assistantUsageCounts = mutableMapOf<String, Int>()

        // 趋势图聚合桶
        val trendStart = if (range.isAllTime) now.minusDays(29) else (range.start ?: now.minusDays(29))
        val trendEnd = if (range.isAllTime) now else (range.end ?: now)
        val trendBuckets = mutableMapOf<LocalDate, MutableMap<String, StatsTokenBucket>>()

        var dayCursor = trendStart
        while (!dayCursor.isAfter(trendEnd)) {
            trendBuckets[dayCursor] = mutableMapOf()
            dayCursor = dayCursor.plusDays(1)
        }

        for (msg in messagesInRange) {
            val msgDate = Instant.ofEpochMilli(msg.createdAt).atZone(zone).toLocalDate()
            val session = allSessions[msg.sessionId]
            val sessionModelId = session?.modelId
            val modelEntity = sessionModelId?.let { allModels[it] }

            val providerName = modelEntity?.provider?.ifBlank { "默认" } ?: "内置"
            val modelName = modelEntity?.name ?: sessionModelId ?: "通用助手"

            // 统计助手/工作区维度
            val workspaceLabel = session?.workspace?.takeIf { it.isNotBlank() }
                ?.substringAfterLast('/')?.ifBlank { null }
                ?: session?.title?.takeIf { it.isNotBlank() }
                ?: "默认工程"
            assistantUsageCounts[workspaceLabel] = (assistantUsageCounts[workspaceLabel] ?: 0) + 1

            when (msg.customType) {
                "user" -> {
                    val userText = runCatching {
                        json.parseToJsonElement(msg.payloadJson).jsonObject["text"]?.jsonPrimitive?.content
                    }.getOrNull().orEmpty()
                    val estimatedPrompt = estimateTokens(userText)
                    totalInputTokens += estimatedPrompt

                    if (!msgDate.isBefore(trendStart) && !msgDate.isAfter(trendEnd)) {
                        val dayMap = trendBuckets.getOrPut(msgDate) { mutableMapOf() }
                        val bucket = dayMap[providerName] ?: StatsTokenBucket()
                        dayMap[providerName] = bucket.add(input = estimatedPrompt.toLong(), activity = 1)
                    }
                }
                "assistant" -> {
                    val root = runCatching { json.parseToJsonElement(msg.payloadJson).jsonObject }.getOrNull()
                    val text = root?.get("text")?.jsonPrimitive?.content.orEmpty()
                    val reasoning = root?.get("reasoning")?.jsonPrimitive?.content.orEmpty()

                    val promptTok = root?.get("promptTokens")?.jsonPrimitive?.content?.toIntOrNull()
                    val compTok = root?.get("completionTokens")?.jsonPrimitive?.content?.toIntOrNull()
                    val cachedTok = root?.get("cachedTokens")?.jsonPrimitive?.content?.toIntOrNull()

                    val finalPrompt = (promptTok ?: 0).toLong()
                    val finalOutput = (compTok ?: (estimateTokens(text) + estimateTokens(reasoning))).toLong()
                    val finalCached = (cachedTok ?: 0).toLong()

                    totalInputTokens += finalPrompt
                    totalOutputTokens += finalOutput
                    totalCachedTokens += finalCached

                    modelUsageCounts[modelName] = (modelUsageCounts[modelName] ?: 0) + 1
                    providerUsageCounts[providerName] = (providerUsageCounts[providerName] ?: 0) + 1

                    if (!msgDate.isBefore(trendStart) && !msgDate.isAfter(trendEnd)) {
                        val dayMap = trendBuckets.getOrPut(msgDate) { mutableMapOf() }
                        val bucket = dayMap[providerName] ?: StatsTokenBucket()
                        dayMap[providerName] = bucket.add(
                            input = finalPrompt,
                            output = finalOutput,
                            cached = finalCached,
                            activity = 1,
                        )
                    }
                }
                "tool_call" -> {
                    modelUsageCounts[modelName] = (modelUsageCounts[modelName] ?: 0) + 1
                }
            }
        }

        // 4. 热力图（近 180 天打卡矩阵）
        val heatmapStartEpoch = now.minusDays(180).atStartOfDay(zone).toInstant().toEpochMilli()
        val rawHeatmapRows = runtimeRepository.listEntriesInRange(heatmapStartEpoch, null)
            .filter { it.createdAt > 0L }
            .groupingBy {
                runCatching {
                    Instant.ofEpochMilli(it.createdAt).atZone(zone).toLocalDate().toString()
                }.getOrDefault("")
            }
            .eachCount()

        val heatmapDays = mutableListOf<StatsHeatmapDay>()
        var hCursor = now.minusDays(180)
        while (!hCursor.isAfter(now)) {
            val dateStr = hCursor.toString()
            val count = rawHeatmapRows[dateStr] ?: 0
            heatmapDays.add(StatsHeatmapDay(date = hCursor, count = count))
            hCursor = hCursor.plusDays(1)
        }

        // 5. 话题会话排行
        val topicRankRows = messagesInRange.groupingBy { it.sessionId }.eachCount()
            .entries.sortedByDescending { it.value }.take(20)
        val topicRank = topicRankRows.map {
            StatsRankItem(
                id = it.key,
                label = allSessions[it.key]?.title?.ifBlank { "未命名会话" } ?: "未命名会话",
                value = it.value,
            )
        }

        // 6. 模型排行
        val modelRank = modelUsageCounts.entries
            .sortedByDescending { it.value }
            .take(20)
            .map { StatsRankItem(id = it.key, label = it.key, value = it.value) }

        // 7. 助手/工作区排行
        val assistantRank = assistantUsageCounts.entries
            .sortedByDescending { it.value }
            .take(20)
            .map { StatsRankItem(id = it.key, label = it.key, value = it.value) }

        // 8. 趋势数据组装
        val trendList = trendBuckets.entries.map { (date, map) ->
            StatsTrendDay(date = date, providerTokens = map)
        }

        StatsSnapshot(
            range = range,
            summary = StatsSummary(
                totalConversations = totalSessions,
                totalMessages = totalMessages,
                inputTokens = totalInputTokens,
                outputTokens = totalOutputTokens,
                cachedTokens = totalCachedTokens,
                launchCount = launchCount,
            ),
            heatmap = heatmapDays,
            trend = trendList,
            modelRank = modelRank,
            assistantRank = assistantRank,
            topicRank = topicRank,
        )
    }

    private fun estimateTokens(text: String): Int {
        if (text.isBlank()) return 0
        var tokens = 0
        for (ch in text) {
            tokens += if (ch.code > 127) 2 else 1
        }
        return (tokens * 0.75).toInt().coerceAtLeast(1)
    }
}
