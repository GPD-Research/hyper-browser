package org.gpdresearch.hyperbrowser

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * What a long-running operation is doing. [fraction] is null while the work cannot be counted,
 * which is what turns the bar from a meter into a bouncing block.
 */
data class BusyState(
    val label: String,
    val fraction: Float? = null,
    val detail: String = "",
)

/** Work under this never shows anything: a flash of progress reads as a glitch, not as feedback. */
const val BUSY_APPEARANCE_DELAY_MS = 1000L

/** Once shown the bar stays for at least this long, so it cannot blink out as it arrives. */
private const val BUSY_MIN_VISIBLE_MS = 400L

private const val BUSY_CELLS = 10
private const val BUSY_BLOCK_CELLS = 3
private const val BUSY_FRAME_MS = 90L

/**
 * The whole indicator vocabulary of the app: "[###-------]" with the block sweeping back and forth
 * while [fraction] is null, and filling from the left once there is a total to measure against.
 */
@Composable
fun AsciiBusyBar(fraction: Float?, modifier: Modifier = Modifier) {
    val cells = if (fraction == null) bouncingCells() else filledCells(fraction)
    Text(
        text = "[$cells]",
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier,
    )
}

/** A bar with its caption, and the percentage spelled out whenever it is known. */
@Composable
fun AsciiBusyLine(state: BusyState, modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier,
    ) {
        Text(
            text = state.label,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 6.dp),
        ) {
            AsciiBusyBar(state.fraction)
            state.fraction?.let { value ->
                Text(
                    text = "${(value.coerceIn(0f, 1f) * 100).toInt()}%",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (state.detail.isNotBlank()) {
            Text(
                text = state.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * Covers the app while [state] is set, but only once the work has run past
 * [BUSY_APPEARANCE_DELAY_MS]; anything quicker finishes without the user ever seeing a bar.
 */
@Composable
fun BusyOverlay(state: BusyState?, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    var shownAt by remember { mutableLongStateOf(0L) }
    // The operation clears its state the moment it ends, so the last one is kept to draw with:
    // without it the bar would blank out before the minimum visible time was served.
    var shown by remember { mutableStateOf<BusyState?>(null) }
    SideEffect { if (state != null) shown = state }

    LaunchedEffect(state != null) {
        if (state != null) {
            delay(BUSY_APPEARANCE_DELAY_MS)
            shownAt = SystemClock.uptimeMillis()
            visible = true
        } else {
            if (visible) {
                val held = SystemClock.uptimeMillis() - shownAt
                if (held < BUSY_MIN_VISIBLE_MS) delay(BUSY_MIN_VISIBLE_MS - held)
            }
            visible = false
        }
    }

    val current = shown
    if (!visible || current == null) return
    Box(
        modifier = modifier
            .fillMaxSize()
            // Swallows everything aimed at the panes behind: a second command started against a
            // tree that is already being rewritten acts on files that may no longer be there.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) { awaitPointerEvent() } } }
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            AsciiBusyLine(current)
        }
    }
}

/**
 * An in-place bar for a wait that belongs to one pane or panel rather than the whole app. It
 * follows the same rule as the overlay: nothing at all for the first [BUSY_APPEARANCE_DELAY_MS].
 */
@Composable
fun DelayedBusyLine(label: String, fraction: Float? = null, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(BUSY_APPEARANCE_DELAY_MS)
        visible = true
    }
    if (!visible) return
    AsciiBusyLine(BusyState(label = label, fraction = fraction), modifier = modifier)
}

/** Sweeps the block from end to end: position 0..travel, then back down again. */
@Composable
private fun bouncingCells(): String {
    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(BUSY_FRAME_MS)
            frame += 1
        }
    }
    val travel = BUSY_CELLS - BUSY_BLOCK_CELLS
    val step = frame % (travel * 2)
    val start = if (step <= travel) step else travel * 2 - step
    return buildString {
        repeat(BUSY_CELLS) { cell ->
            append(if (cell >= start && cell < start + BUSY_BLOCK_CELLS) '#' else '-')
        }
    }
}

private fun filledCells(fraction: Float): String {
    val filled = (fraction.coerceIn(0f, 1f) * BUSY_CELLS).toInt().coerceIn(0, BUSY_CELLS)
    return "#".repeat(filled) + "-".repeat(BUSY_CELLS - filled)
}
