package top.wanxiang.app.core.model

import java.time.LocalDate

enum class StatsDateRangePreset {
    ALL_TIME,
    LAST_30_DAYS,
    PREVIOUS_MONTH,
    PREVIOUS_QUARTER,
    CUSTOM,
}

data class StatsDateRange(
    val preset: StatsDateRangePreset,
    val start: LocalDate?,
    val end: LocalDate?,
) {
    val isAllTime: Boolean get() = preset == StatsDateRangePreset.ALL_TIME

    fun contains(date: LocalDate): Boolean {
        if (isAllTime) return true
        if (start != null && date.isBefore(start)) return false
        if (end != null && date.isAfter(end)) return false
        return true
    }

    companion object {
        fun allTime(): StatsDateRange = StatsDateRange(
            preset = StatsDateRangePreset.ALL_TIME,
            start = null,
            end = null,
        )

        fun last30Days(now: LocalDate = LocalDate.now()): StatsDateRange = StatsDateRange(
            preset = StatsDateRangePreset.LAST_30_DAYS,
            start = now.minusDays(29),
            end = now,
        )

        fun previousMonth(now: LocalDate = LocalDate.now()): StatsDateRange {
            val firstOfCurrentMonth = now.withDayOfMonth(1)
            val lastOfPrevMonth = firstOfCurrentMonth.minusDays(1)
            val firstOfPrevMonth = lastOfPrevMonth.withDayOfMonth(1)
            return StatsDateRange(
                preset = StatsDateRangePreset.PREVIOUS_MONTH,
                start = firstOfPrevMonth,
                end = lastOfPrevMonth,
            )
        }

        fun previousQuarter(now: LocalDate = LocalDate.now()): StatsDateRange {
            val currentQuarterStartMonth = ((now.monthValue - 1) / 3) * 3 + 1
            val currentQuarterStart = LocalDate.of(now.year, currentQuarterStartMonth, 1)
            val prevQuarterEnd = currentQuarterStart.minusDays(1)
            val prevQuarterStartMonth = ((prevQuarterEnd.monthValue - 1) / 3) * 3 + 1
            val prevQuarterStart = LocalDate.of(prevQuarterEnd.year, prevQuarterStartMonth, 1)
            return StatsDateRange(
                preset = StatsDateRangePreset.PREVIOUS_QUARTER,
                start = prevQuarterStart,
                end = prevQuarterEnd,
            )
        }

        fun custom(start: LocalDate, end: LocalDate): StatsDateRange {
            require(!end.isBefore(start)) { "结束日期不能早于起始日期" }
            return StatsDateRange(
                preset = StatsDateRangePreset.CUSTOM,
                start = start,
                end = end,
            )
        }
    }
}

data class StatsSummary(
    val totalConversations: Int,
    val totalMessages: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedTokens: Long,
    val launchCount: Int,
)

data class StatsRankItem(
    val id: String,
    val label: String,
    val value: Int,
    val providerId: String? = null,
)

data class StatsHeatmapDay(
    val date: LocalDate,
    val count: Int,
)

data class StatsTokenBucket(
    val inputTokens: Long = 0L,
    val outputTokens: Long = 0L,
    val cachedTokens: Long = 0L,
    val uncategorizedTokens: Long = 0L,
    val activityCount: Int = 0,
) {
    val totalTokens: Long get() = inputTokens + outputTokens + uncategorizedTokens
    val chartWeight: Long get() = if (totalTokens > 0) totalTokens else activityCount.toLong()

    fun add(
        input: Long = 0L,
        output: Long = 0L,
        cached: Long = 0L,
        uncategorized: Long = 0L,
        activity: Int = 0,
    ): StatsTokenBucket = StatsTokenBucket(
        inputTokens = this.inputTokens + input,
        outputTokens = this.outputTokens + output,
        cachedTokens = this.cachedTokens + cached,
        uncategorizedTokens = this.uncategorizedTokens + uncategorized,
        activityCount = this.activityCount + activity,
    )
}

data class StatsTrendDay(
    val date: LocalDate,
    val providerTokens: Map<String, StatsTokenBucket>,
)

data class StatsSnapshot(
    val range: StatsDateRange,
    val summary: StatsSummary,
    val heatmap: List<StatsHeatmapDay>,
    val trend: List<StatsTrendDay>,
    val modelRank: List<StatsRankItem>,
    val assistantRank: List<StatsRankItem>,
    val topicRank: List<StatsRankItem>,
)
