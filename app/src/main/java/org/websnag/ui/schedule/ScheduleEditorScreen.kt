package org.websnag.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.websnag.core.model.FilterMode
import org.websnag.core.model.ScheduleDay
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleEditorScreen(
    scheduleId: String?,
    viewModel: ScheduleViewModel,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val existingSchedule = remember(uiState.schedules, scheduleId) {
        uiState.schedules.firstOrNull { it.id == scheduleId }
    }

    var name by remember(existingSchedule) {
        mutableStateOf(existingSchedule?.name ?: "")
    }

    var selectedProfileId by remember(existingSchedule, uiState.availableProfiles) {
        mutableStateOf(
            existingSchedule?.profileId
                ?: uiState.availableProfiles.firstOrNull()?.id
                ?: "profile-deep-work"
        )
    }

    var startHour by remember(existingSchedule) { mutableIntStateOf(existingSchedule?.startHour ?: 9) }
    var startMinute by remember(existingSchedule) { mutableIntStateOf(existingSchedule?.startMinute ?: 0) }

    var endHour by remember(existingSchedule) { mutableIntStateOf(existingSchedule?.endHour ?: 17) }
    var endMinute by remember(existingSchedule) { mutableIntStateOf(existingSchedule?.endMinute ?: 0) }

    var selectedDays by remember(existingSchedule) {
        mutableStateOf(
            existingSchedule?.daysOfWeek ?: setOf(
                ScheduleDay.MON, ScheduleDay.TUE, ScheduleDay.WED, ScheduleDay.THU, ScheduleDay.FRI
            )
        )
    }

    var showStartTimeDialog by remember { mutableStateOf(false) }
    var showEndTimeDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (scheduleId == null) "New Focus Routine" else "Edit Routine",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Button(
                        onClick = {
                            viewModel.saveSchedule(
                                id = scheduleId,
                                name = name,
                                profileId = selectedProfileId,
                                startHour = startHour,
                                startMinute = startMinute,
                                endHour = endHour,
                                endMinute = endMinute,
                                daysOfWeek = selectedDays,
                                isEnabled = existingSchedule?.isEnabled ?: true
                            )
                            onNavigateBack()
                        },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text("Save", fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // 1. Routine Name
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Routine Name") },
                placeholder = { Text("e.g. Workday Focus, Evening Winddown") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                )
            )

            // 2. Bound Profile Selector
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Focus Profile to Activate",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "Choose which apps are blocked or allowed when this schedule is active.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(4.dp))

                    uiState.availableProfiles.forEach { profile ->
                        val isSelected = profile.id == selectedProfileId
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { selectedProfileId = profile.id },
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected)
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                else
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            ),
                            border = if (isSelected)
                                androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                            else null
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Shield,
                                        contentDescription = null,
                                        tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Column {
                                        Text(
                                            text = profile.name,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp,
                                            color = MaterialTheme.colorScheme.onBackground
                                        )
                                        Text(
                                            text = "${if (profile.filterMode == FilterMode.ALLOWLIST) "Allowlist" else "Blocklist"} • ${if (profile.filterMode == FilterMode.ALLOWLIST) "${profile.blockedPackages.size} Allowed" else "${profile.blockedPackages.size} Blocked"}",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Selected",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 3. Time Windows (Start & End)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "Time Window",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onBackground
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Start Time Card
                        TimeSelectorButton(
                            label = "Start Time",
                            hour = startHour,
                            minute = startMinute,
                            modifier = Modifier.weight(1f),
                            onClick = { showStartTimeDialog = true }
                        )

                        // End Time Card
                        TimeSelectorButton(
                            label = "End Time",
                            hour = endHour,
                            minute = endMinute,
                            modifier = Modifier.weight(1f),
                            onClick = { showEndTimeDialog = true }
                        )
                    }
                }
            }

            // 4. Repeat Days
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Repeat Days",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onBackground
                        )

                        // Quick presets: Weekdays / Every Day
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(
                                onClick = {
                                    selectedDays = setOf(
                                        ScheduleDay.MON, ScheduleDay.TUE, ScheduleDay.WED,
                                        ScheduleDay.THU, ScheduleDay.FRI
                                    )
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("Weekdays", fontSize = 11.sp)
                            }
                            TextButton(
                                onClick = { selectedDays = ScheduleDay.values().toSet() },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("Every Day", fontSize = 11.sp)
                            }
                        }
                    }

                    // 7 day circular chips
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        ScheduleDay.values().forEach { day ->
                            val isSelected = day in selectedDays
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isSelected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                    )
                                    .clickable {
                                        selectedDays = if (isSelected) {
                                            if (selectedDays.size > 1) selectedDays - day else selectedDays
                                        } else {
                                            selectedDays + day
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = day.shortName,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
        }
    }

    // Start Time Picker Modal
    if (showStartTimeDialog) {
        SimpleTimePickerDialog(
            initialHour = startHour,
            initialMinute = startMinute,
            onDismiss = { showStartTimeDialog = false },
            onConfirm = { h, m ->
                startHour = h
                startMinute = m
                showStartTimeDialog = false
            }
        )
    }

    // End Time Picker Modal
    if (showEndTimeDialog) {
        SimpleTimePickerDialog(
            initialHour = endHour,
            initialMinute = endMinute,
            onDismiss = { showEndTimeDialog = false },
            onConfirm = { h, m ->
                endHour = h
                endMinute = m
                showEndTimeDialog = false
            }
        )
    }
}

@Composable
fun TimeSelectorButton(
    label: String,
    hour: Int,
    minute: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val amPm = if (hour < 12) "AM" else "PM"
    val h12 = if (hour == 0) 12 else if (hour > 12) hour - 12 else hour
    val timeFormatted = String.format(Locale.getDefault(), "%d:%02d %s", h12, minute, amPm)

    Card(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.6f)
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = label,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.AccessTime,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = timeFormatted,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimpleTimePickerDialog(
    initialHour: Int,
    initialMinute: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit
) {
    val timePickerState = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        is24Hour = false
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = { onConfirm(timePickerState.hour, timePickerState.minute) },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("Confirm")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        text = {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TimePicker(state = timePickerState)
            }
        }
    )
}
