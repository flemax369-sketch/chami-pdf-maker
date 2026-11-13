package com.chami.pdfmaker.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
 * Phase 6 — slide-to-action, used by EVERY tool instead of a tap button
 * (Slide to Convert / Merge / Split / Protect / Compress / Save PDF ...).
 * Drag the thumb to the end (~85% of the track) to confirm.
 * Haptic is always on: tick on slide start, small tick past 50%,
 * stronger tick on complete. Thumb animates back if released early.
 */
@Composable
fun SlideToAction(
  label: String,
  theme: ChamiTheme,
  enabled: Boolean = true,
  onSlideComplete: () -> Unit,
  modifier: Modifier = Modifier
) {
  val haptic = LocalHapticFeedback.current
  val scope = rememberCoroutineScope()
  val density = LocalDensity.current
  val thumbSize = 56.dp
  val trackHeight = 64.dp
  val thumbPx = with(density) { thumbSize.toPx() }
  val edgePaddingPx = with(density) { 4.dp.toPx() }
  var trackWidthPx by remember { mutableStateOf(0f) }
  val maxOffset = (trackWidthPx - thumbPx - edgePaddingPx * 2).coerceAtLeast(0f)
  val offset = remember { Animatable(0f) }
  var halfTicked by remember { mutableStateOf(false) }
  var completed by remember { mutableStateOf(false) }

  val trackColor = if (enabled) Color(0xFFDCFCE7) else Color(0xFFE2E8F0)
  val thumbColor = if (enabled) theme.primary else Color(0xFF94A3B8)
  val labelColor = if (enabled) theme.text else Color(0xFF94A3B8)

  Box(
    modifier = modifier
      .fillMaxWidth()
      .height(trackHeight)
      .onSizeChanged { trackWidthPx = it.width.toFloat() }
      .clip(RoundedCornerShape(32.dp))
      .background(trackColor),
    contentAlignment = Alignment.Center
  ) {
    // Green fill that grows behind the thumb as it slides.
    if (enabled && offset.value > 0f) {
      Box(
        Modifier
          .align(Alignment.CenterStart)
          .fillMaxWidth()
          .height(trackHeight),
        contentAlignment = Alignment.CenterStart
      ) {
        Box(
          Modifier
            .size(width = (offset.value + thumbPx + edgePaddingPx * 2).let { px ->
              with(density) { px.toDp() }
            }, height = trackHeight)
            .background(theme.primary.copy(alpha = 0.25f))
        )
      }
    }
    Text(
      text = label,
      fontFamily = PixelFont,
      fontSize = 14.sp,
      fontWeight = FontWeight.Bold,
      color = labelColor
    )
    Box(
      modifier = Modifier
        .align(Alignment.CenterStart)
        .padding(start = 4.dp)
        .offset { IntOffset(offset.value.roundToInt(), 0) }
        .size(thumbSize)
        .clip(CircleShape)
        .background(thumbColor)
        .pointerInput(enabled, maxOffset) {
          if (!enabled) return@pointerInput
          detectDragGestures(
            onDragStart = {
              halfTicked = false
              try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
            },
            onDragEnd = {
              scope.launch {
                if (maxOffset > 0f && offset.value >= 0.85f * maxOffset) {
                  if (!completed) {
                    completed = true
                    try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
                    onSlideComplete()
                  }
                }
                offset.animateTo(0f, tween(300))
                completed = false
              }
            },
            onDragCancel = {
              scope.launch { offset.animateTo(0f, tween(300)) }
            },
            onDrag = { change, dragAmount ->
              change.consume()
              val next = (offset.value + dragAmount.x).coerceIn(0f, maxOffset)
              scope.launch { offset.snapTo(next) }
              if (!halfTicked && maxOffset > 0f && next >= 0.5f * maxOffset) {
                halfTicked = true
                try { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) } catch (_: Exception) {}
              }
            }
          )
        },
      contentAlignment = Alignment.Center
    ) {
      Text("→", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    }
  }
}
