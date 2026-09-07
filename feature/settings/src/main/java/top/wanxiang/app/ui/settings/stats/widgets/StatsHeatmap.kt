package top.wanxiang.app.ui.settings.stats.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.wanxiang.app.core.model.StatsHeatmapDay
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val CellSize = 11.dp
private val CellPadding = 1.5.dp
private val CellPitch = CellSize + CellPadding * 2
private val WeekdayLabelWidth = 20.dp
private val MonthLabelHeight = 14.dp

@Composable
fun StatsHeatmap(
    days: List<StatsHeatmapDay>,
    modifier: Modifier = Modifier,
) {
    val activeCounts = remember(days) {
        days.map { it.count }.filter { it > 0 }.sorted()
    }
    val q1 = remember(activeCounts) { quantile(activeCounts, 0.25) }
    val q2 = remember(activeCounts) { quantile(activeCounts, 0.50) }
    val q3 = remember(activeCounts) { quantile(activeCounts, 0.75) }

    val weeks = remember(days) { calendarWeeks(days) }
    val scrollState = rememberScrollState()

    LaunchedEffect(weeks.size) {
        if (weeks.isNotEmpty()) {
            scrollState.scrollTo(scrollState.maxValue)
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            // 左侧星期标签（一、三、五）
            WeekdayLabels()

            // 右侧日历网格
            Box(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(scrollState),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    // 月份标签栏
                    Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                        for (week in weeks) {
                            val firstOfMonth = week.firstOrNull { it.date.dayOfMonth == 1 }
                            Box(
                                modifier = Modifier
                                    .width(CellPitch)
                                    .height(MonthLabelHeight),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                if (firstOfMonth != null) {
                                    Text(
                                        text = "${firstOfMonth.date.monthValue}月",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                        ),
                                        softWrap = false,
                                    )
                                }
                            }
                        }
                    }

                    // 7 行网格
                    for (dayOfWeek in 0..6) {
                        Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                            for (week in weeks) {
                                val day = week.getOrNull(dayOfWeek)
                                if (day != null) {
                                    val level = calcLevel(day.count, q1, q2, q3)
                                    Box(modifier = Modifier.padding(CellPadding)) {
                                        HeatCell(level = level)
                                    }
                                } else {
                                    Spacer(modifier = Modifier.size(CellPitch))
                                }
                            }
                        }
                    }
                }
            }
        }

        // 底部图例
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "少",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                ),
            )
            Spacer(modifier = Modifier.width(4.dp))
            for (level in 0..4) {
                HeatCell(level = level, size = 9.dp)
                Spacer(modifier = Modifier.width(3.dp))
            }
            Text(
                text = "多",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                ),
            )
        }
    }
}

@Composable
private fun WeekdayLabels() {
    Column(
        modifier = Modifier
            .width(WeekdayLabelWidth)
            .padding(top = MonthLabelHeight + 2.dp),
    ) {
        val days = listOf("日", "一", "二", "三", "四", "五", "六")
        for (i in 0..6) {
            Box(
                modifier = Modifier
                    .height(CellPitch)
                    .width(WeekdayLabelWidth),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (i == 1 || i == 3 || i == 5) {
                    Text(
                        text = days[i],
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun HeatCell(
    level: Int,
    size: Dp = CellSize,
) {
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface

    val color = when (level) {
        0 -> onSurface.copy(alpha = 0.08f)
        1 -> primary.copy(alpha = 0.25f)
        2 -> primary.copy(alpha = 0.48f)
        3 -> primary.copy(alpha = 0.72f)
        else -> primary.copy(alpha = 0.95f)
    }

    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(2.5.dp))
            .background(color),
    )
}

private fun calcLevel(count: Int, q1: Int, q2: Int, q3: Int): Int {
    if (count <= 0) return 0
    if (count <= q1) return 1
    if (count <= q2) return 2
    if (count <= q3) return 3
    return 4
}

private fun quantile(sortedList: List<Int>, fraction: Double): Int {
    if (sortedList.isEmpty()) return 0
    val index = (sortedList.size * fraction).toInt().coerceIn(0, sortedList.size - 1)
    return sortedList[index]
}

private fun calendarWeeks(days: List<StatsHeatmapDay>): List<List<StatsHeatmapDay>> {
    if (days.isEmpty()) return emptyList()
    val mapByDate = days.associateBy { it.date }
    val firstDate = days.first().date
    val lastDate = days.last().date

    // Sunday = 0, Monday = 1, ... Saturday = 6
    val firstSunday = firstDate.minusDays((firstDate.dayOfWeek.value % 7).toLong())
    val lastSaturday = lastDate.plusDays(((6 - (lastDate.dayOfWeek.value % 7)) % 7).toLong())

    val weeks = mutableListOf<List<StatsHeatmapDay>>()
    var curSunday = firstSunday

    while (!curSunday.isAfter(lastSaturday)) {
        val weekList = mutableListOf<StatsHeatmapDay>()
        for (i in 0..6) {
            val d = curSunday.plusDays(i.toLong())
            weekList.add(mapByDate[d] ?: StatsHeatmapDay(date = d, count = 0))
        }
        weeks.add(weekList)
        curSunday = curSunday.plusDays(7)
    }
    return weeks
}
