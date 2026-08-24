package websnag.elopenmike.com.ui.common

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import websnag.elopenmike.com.ui.theme.EmeraldSuccess
import java.util.Locale

/**
 * Live ticking session duration timer with an ambient pulsing focus indicator.
 */
@Composable
fun FocusSessionTimer(
    sessionStartedAtEpochMs: Long?,
    modifier: Modifier = Modifier,
    isLarge: Boolean = false,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    showPulsingDot: Boolean = true
) {
    if (sessionStartedAtEpochMs == null) return

    var currentDurationMs by remember(sessionStartedAtEpochMs) {
        mutableLongStateOf((System.currentTimeMillis() - sessionStartedAtEpochMs).coerceAtLeast(0L))
    }

    LaunchedEffect(sessionStartedAtEpochMs) {
        while (true) {
            currentDurationMs = (System.currentTimeMillis() - sessionStartedAtEpochMs).coerceAtLeast(0L)
            delay(1000L)
        }
    }

    val totalSeconds = currentDurationMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    val formattedTime = if (hours > 0) {
        String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (showPulsingDot) {
            Box(
                modifier = Modifier
                    .size(if (isLarge) 12.dp else 8.dp)
                    .scale(pulseScale)
                    .clip(CircleShape)
                    .background(EmeraldSuccess)
            )
            Spacer(modifier = Modifier.width(if (isLarge) 10.dp else 6.dp))
        }

        Text(
            text = formattedTime,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = if (isLarge) 28.sp else 16.sp,
            letterSpacing = if (isLarge) 1.5.sp else 0.5.sp,
            color = textColor
        )
    }
}
