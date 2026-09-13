package websnag.elopenmike.com.ui.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import websnag.elopenmike.com.core.activity.ActivityGranularity
import websnag.elopenmike.com.core.model.FilterMode
import websnag.elopenmike.com.core.model.FocusSessionRecord
import websnag.elopenmike.com.ui.theme.EmeraldSuccess
import websnag.elopenmike.com.ui.theme.RoseBlock
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale

const val ACTIVITY_BAR_TAG = "activityBar"

@Composable
fun ActivityScreen(
    viewModel: ActivityViewModel
) {
    // Lifecycle-aware so the minute refresh stops while the app is in the background.
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(8.dp))
            PeriodControls(
                uiState = uiState,
                onGranularitySelected = viewModel::selectGranularity,
                onPreviousPeriod = viewModel::showPreviousPeriod,
                onNextPeriod = viewModel::showNextPeriod,
                onCurrentPeriod = viewModel::showCurrentPeriod
            )
        }

        item {
            // Brick-style Split Header: period total | daily average
            BrickSplitHeaderCard(uiState = uiState)
        }

        item {
            FocusBarChartCard(uiState = uiState, onBarSelected = viewModel::selectBar)
        }

        val dayLabel = uiState.selectedDayLabel
        if (dayLabel == null) {
            item { MonthDrillDownHintCard() }
        } else {
            // Selected Day Session Drilldown
            item {
                Column {
                    Text(
                        text = "Sessions for $dayLabel",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    val count = uiState.selectedDaySessions.size
                    val ongoing = if (uiState.selectedDayHasOngoingSession) " • session in progress" else ""
                    Text(
                        text = "Total Focus: ${formatFocusDuration(uiState.selectedDayFocusMs)} • " +
                            "$count ${if (count == 1) "session" else "sessions"}$ongoing",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (uiState.selectedDaySessions.isEmpty() && !uiState.selectedDayHasOngoingSession) {
                item {
                    EmptyDayActivityCard(dayLabel = dayLabel)
                }
            } else {
                items(uiState.selectedDaySessions) { session ->
                    SessionRecordCard(session = session)
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

/** Week · Month · Year selector, the selected period, previous/next and a way back to the current period. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PeriodControls(
    uiState: ActivityUiState,
    onGranularitySelected: (ActivityGranularity) -> Unit,
    onPreviousPeriod: () -> Unit,
    onNextPeriod: () -> Unit,
    onCurrentPeriod: () -> Unit
) {
    val periodName = uiState.granularity.label.lowercase()
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // Content-sized pills that wrap to another line rather than clipping at large text sizes.
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ActivityGranularity.entries.forEach { granularity ->
                val selected = granularity == uiState.granularity
                Surface(
                    selected = selected,
                    onClick = { onGranularitySelected(granularity) },
                    modifier = Modifier.semantics { role = Role.RadioButton },
                    shape = RoundedCornerShape(50),
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                    contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                ) {
                    Text(
                        text = granularity.label,
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onPreviousPeriod) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = "Previous $periodName"
                )
            }
            Text(
                text = uiState.periodLabel,
                modifier = Modifier
                    .weight(1f)
                    .semantics {
                        heading()
                        liveRegion = LiveRegionMode.Polite
                    },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground
            )
            IconButton(onClick = onNextPeriod, enabled = !uiState.isCurrentPeriod) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Next $periodName"
                )
            }
        }

        if (!uiState.isCurrentPeriod) {
            TextButton(onClick = onCurrentPeriod) {
                Text("This $periodName")
            }
        }
    }
}

/**
 * Brick UX Split Header: Week total [3h 5m] | Daily average [0h 26m]
 */
@Composable
private fun BrickSplitHeaderCard(uiState: ActivityUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            // The streak describes the days up to today, so it only accompanies the current period.
            if (uiState.isCurrentPeriod && uiState.currentStreakDays > 0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFFF97316).copy(alpha = 0.15f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.LocalFireDepartment,
                                contentDescription = null,
                                tint = Color(0xFFF97316),
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "${uiState.currentStreakDays}d streak",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFF97316)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
            }

            // Split metric columns
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SplitMetric(
                    label = uiState.totalLabel,
                    value = formatFocusDuration(uiState.totalFocusMs),
                    caption = null,
                    modifier = Modifier.weight(1f)
                )

                // Vertical hairline divider
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                )

                SplitMetric(
                    label = "Daily average",
                    value = formatFocusDuration(uiState.dailyAverageFocusMs),
                    caption = "over ${uiState.averageDays} ${if (uiState.averageDays == 1) "day" else "days"}",
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun SplitMetric(label: String, value: String, caption: String?, modifier: Modifier) {
    Column(
        modifier = modifier
            .padding(horizontal = 4.dp)
            .semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.headlineLarge.copy(
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold
            ),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (caption != null) {
            Text(
                text = caption,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Focus bar chart: 7 daily bars for a week, one per calendar day for a month, 12 monthly bars for a
 * year. Every slot stays selectable and readable even at zero, and bars scale to the period's
 * largest value, so an all-zero period draws no bars rather than a fake minimum.
 */
@Composable
private fun FocusBarChartCard(
    uiState: ActivityUiState,
    onBarSelected: (LocalDate) -> Unit
) {
    val isYear = uiState.granularity == ActivityGranularity.YEAR
    val peak = uiState.bars.maxOfOrNull { it.focusMs } ?: 0L

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isYear) "Focus by month" else "Focus by day",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (peak == 0L) "No recorded focus" else "Peak ${formatFocusDuration(peak)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.TrendingUp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Selection feedback in one wrapping line, so no per-bar label has to fit a narrow slot.
            // Not a live region: its total ticks each minute during a session, and each bar already
            // reports its own selected state and spoken total.
            Text(
                text = uiState.selectedBarSummary,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(12.dp))

            // A month's day slots are narrow, so dragging across the chart also selects the day under
            // the finger. The year view skips this because selecting a month bar opens that month.
            val bars by rememberUpdatedState(uiState.bars)
            val layoutDirection = LocalLayoutDirection.current
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(isYear, layoutDirection) {
                        if (isYear) return@pointerInput
                        fun selectAt(x: Float) {
                            val slot = (x / size.width * bars.size).toInt().coerceIn(0, bars.lastIndex)
                            val bar = bars[if (layoutDirection == LayoutDirection.Rtl) bars.lastIndex - slot else slot]
                            if (!bar.isFuture && !bar.isSelected) onBarSelected(bar.date)
                        }
                        detectHorizontalDragGestures(onDragStart = { selectAt(it.x) }) { change, _ ->
                            selectAt(change.position.x)
                        }
                    }
            ) {
                uiState.bars.forEach { bar ->
                    FocusBar(
                        bar = bar,
                        clickLabel = if (isYear) "Open month" else "Show day",
                        onClick = { onBarSelected(bar.date) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun FocusBar(
    bar: ActivityBar,
    clickLabel: String,
    onClick: () -> Unit,
    modifier: Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    Column(
        modifier = modifier
            .clickable(enabled = !bar.isFuture, onClickLabel = clickLabel, role = Role.Button, onClick = onClick)
            .testTag(ACTIVITY_BAR_TAG)
            // Read the date and total instead of the separate axis text.
            .clearAndSetSemantics {
                contentDescription = bar.description
                selected = bar.isSelected
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // The bar area holds only the bar, so every height stays proportional to the period peak.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(MAX_BAR_HEIGHT),
            contentAlignment = Alignment.BottomCenter
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.62f)
                    .widthIn(max = 22.dp)
                    .height(MAX_BAR_HEIGHT * bar.heightFraction)
                    .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                    .background(
                        when {
                            bar.isSelected -> primary
                            bar.isCurrent -> primary.copy(alpha = 0.8f)
                            else -> primary.copy(alpha = 0.35f)
                        }
                    )
            )
        }

        // Baseline keeps zero-value slots visible without inventing a bar height.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        )

        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .size(4.dp)
                .clip(CircleShape)
                .background(if (bar.isSelected) primary else Color.Transparent)
        )
        Spacer(modifier = Modifier.height(2.dp))

        Text(
            text = bar.axisLabel,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.wrapContentWidth(unbounded = true),
            fontWeight = if (bar.isSelected || bar.isCurrent) FontWeight.Bold else FontWeight.Normal,
            color = if (bar.isSelected) primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private val MAX_BAR_HEIGHT = 110.dp

@Composable
private fun MonthDrillDownHintCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Text(
            text = "Select a month to see its daily focus and sessions.",
            modifier = Modifier.padding(20.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SessionRecordCard(session: FocusSessionRecord) {
    val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
    val startDate = Date(session.startTimeEpochMs)
    val endDate = Date(session.endTimeEpochMs)

    val durationText = formatFocusDuration(session.durationSeconds * 1000)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Schedule,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = session.profileName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (session.filterMode == FilterMode.ALLOWLIST)
                            EmeraldSuccess.copy(alpha = 0.15f)
                        else
                            MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = if (session.filterMode == FilterMode.ALLOWLIST) "Allowlist" else "Blocklist",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (session.filterMode == FilterMode.ALLOWLIST) EmeraldSuccess else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = "${timeFormat.format(startDate)} - ${timeFormat.format(endDate)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = durationText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.primary
                )
                if (session.interceptionsPrevented > 0) {
                    Text(
                        text = "${session.interceptionsPrevented} blocked",
                        style = MaterialTheme.typography.labelSmall,
                        color = RoseBlock
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyDayActivityCard(dayLabel: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Schedule,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "No Sessions on $dayLabel",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Hold to lock on the WebSnag focus tab or tap an NFC tag to log focused time.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
