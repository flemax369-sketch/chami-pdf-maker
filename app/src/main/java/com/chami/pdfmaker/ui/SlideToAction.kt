package com.chami.pdfmaker.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chami.pdfmaker.theme.ChamiTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Slide-to-action, used by EVERY tool instead of a tap button
 * (Slide to Convert / Merge / Split / Protect / Compress / Save PDF ...).
 * Drag the thumb to 90% of the track to confirm (spec §5).
 *
 * JANK FIX (audit §15/§18): the thumb offset is plain Float state
 * updated DIRECTLY in the drag callback — no coroutine is launched
 * per drag event (the old code launched one per pointer move).
 * The only coroutine is the single snap-back animation launched
 * once per gesture end / cancel.
 *
 * Haptics: light tick on slide start, ticks at 25% / 50% / 75%,
 * success haptic on complete. Releasing before 90% slides back
 * with no action.
 *
 * DISABLED STATE: pass [disabledReason] (e.g. "Select at least 2 PDFs
 * to merge") and it is shown under the track, so the user always knows
 * WHY the slide is unavailable. Call sites that do not pass one get a
 * generic "finish the steps above" line — pass a real reason wherever
 * the tool knows it.
 */
@Composable
fun SlideToAction(
  label: String,
  theme: ChamiTheme,
  enabled: Boolean = true,
  disabledReason: String? = null,
  onSlideComplete: () -> Unit,
  modifier: Modifier = Modifier
) {
  val haptic = LocalHapticFeedback.current
  val scope = rememberCoroutineScope()
  val density = LocalDensity.current
  // Spec §3/§5: track 56dp, handle 48dp (Ds.slideHeight / Ds.slideHandle).
  val thumbSize = Ds.slideHandle
  val trackHeight = Ds.slideHeight
  val thumbPx = with(density) { thumbSize.toPx() }
  val edgePaddingPx = with(density) { Ds.s4.toPx() }
  var trackWidthPx by remember { mutableStateOf(0f) }
  val maxOffset = (trackWidthPx - thumbPx - edgePaddingPx * 2).coerceAtLeast(0f)
  // Plain state, updated synchronously on drag — never via a coroutine.
  var dragOffset by remember { mutableFloatStateOf(0f) }
  var lastTickMilestone by remember { mutableIntStateOf(0) }
  var completed by remember { mutableStateOf(false) }

  val trackColor = if (enabled) ChamiColors.lightGreen else theme.cardBorder
  val thumbColor = if (enabled) ChamiColors.brightGreen else ChamiColors.secondaryText.copy(alpha = 0.6f)
  val labelColor = if (enabled) ChamiColors.deepGreen else ChamiColors.secondaryText

  /** One snap-back animation per gesture end — the only coroutine here. */
  fun animateOffsetTo(target: Float) {
    val start = dragOffset
    scope.launch {
      animate(start, target, animationSpec = tween(300)) { value, _ ->
        dragOffset = value
      }
    }
  }

  Column(modifier = modifier.fillMaxWidth()) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(trackHeight)
        .onSizeChanged { trackWidthPx = it.width.toFloat() }
        // Half the track height — the track is a pill, so the radius is
        // derived from its height, not a spacing token.
        .clip(RoundedCornerShape(trackHeight / 2))
        .background(trackColor),
      contentAlignment = Alignment.Center
    ) {
      // Green fill that grows behind the thumb as it slides.
      if (enabled && dragOffset > 0f) {
        Box(
          Modifier
            .align(Alignment.CenterStart)
            .size(
              width = with(density) { (dragOffset + thumbPx + edgePaddingPx * 2).toDp() },
              height = trackHeight
            )
            .background(ChamiColors.brightGreen.copy(alpha = 0.25f))
        )
      }
      Text(
        text = label,
        fontSize = Ds.BUTTON_SP.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = (Ds.BUTTON_SP * 1.4).sp,
        color = labelColor
      )
      Box(
        modifier = Modifier
          .align(Alignment.CenterStart)
          .padding(start = Ds.s4)
          .offset { IntOffset(dragOffset.roundToInt(), 0) }
          .size(thumbSize)
          .clip(CircleShape)
          .background(thumbColor)
          .pointerInput(enabled, maxOffset) {
            if (!enabled) return@pointerInput
            detectDragGestures(
              onDragStart = {
                lastTickMilestone = 0
                try { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) } catch (_: Exception) {}
              },
              onDragEnd = {
                if (maxOffset > 0f && dragOffset >= 0.9f * maxOffset) {
                  if (!completed) {
                    completed = true
                    try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
                    onSlideComplete()
                    // Reset at once so a second slide within the 300 ms
                    // snap-back is not swallowed.
                    completed = false
                  }
                }
                animateOffsetTo(0f)
              },
              onDragCancel = {
                animateOffsetTo(0f)
              },
              onDrag = { change, dragAmount ->
                change.consume()
                // Direct state update — no coroutine per drag event.
                dragOffset = (dragOffset + dragAmount.x).coerceIn(0f, maxOffset)
                if (maxOffset > 0f) {
                  val pct = (dragOffset / maxOffset * 100).toInt()
                  val milestone = when {
                    pct >= 75 -> 75
                    pct >= 50 -> 50
                    pct >= 25 -> 25
                    else -> 0
                  }
                  if (milestone > lastTickMilestone) {
                    lastTickMilestone = milestone
                    try { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) } catch (_: Exception) {}
                  }
                }
              }
            )
          },
        contentAlignment = Alignment.Center
      ) {
        Icon(
          Icons.Filled.ArrowForward,
          contentDescription = "Slide",
          tint = Color.White,
          modifier = Modifier.size(24.dp)
        )
      }
    }
    if (!enabled) {
      Spacer(Modifier.height(Ds.s4))
      Text(
        text = disabledReason ?: "Not ready yet — finish the steps above first.",
        fontSize = Ds.HINT_SP.sp,
        lineHeight = (Ds.HINT_SP * 1.4).sp,
        color = ChamiColors.secondaryText
      )
    }
  }
}
