package com.chami.pdfmaker.ui

import android.graphics.PathMeasure
import android.media.RingtoneManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chami.pdfmaker.theme.ChamiTheme
import java.io.File
import kotlin.random.Random

private fun formatFileSize(bytes: Long): String {
  return when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024f * 1024f))
    bytes >= 1024L -> "%d KB".format(bytes / 1024L)
    else -> "%d B".format(bytes)
  }
}

private data class ConfettiPiece(
  val startX: Float, // 0..1 across the confetti area
  val startY: Float, // 0..1 down the confetti area
  val fall: Float, // how far it falls, as a fraction of the area height
  val size: Float, // px
  val color: Color,
  val rotation: Float,
  val spin: Float,
  val drift: Float,
  val isDot: Boolean
)

private val CONFETTI_COLORS = listOf(
  Color(0xFF22C55E), Color(0xFF16A34A), Color(0xFFFACC15),
  Color(0xFF38BDF8), Color(0xFFF472B6), Color(0xFFFB923C)
)

/**
 * Phase 6 — success screen shown right after a file is made.
 * No scrolling: tick + confetti + file card + buttons all fit on screen.
 * Plays the system notification sound once on entry (no bundled audio).
 * The created file card is immediately visible — no slide/scroll to find it.
 */
@Composable
fun DoneScreen(
  file: File,
  theme: ChamiTheme,
  onBackHome: () -> Unit,
  onOpen: (File) -> Unit
) {
  val ctx = LocalContext.current
  var started by remember { mutableStateOf(false) }

  // Success sound, played ONCE on entry.
  LaunchedEffect(Unit) {
    started = true
    try {
      RingtoneManager.getRingtone(
        ctx,
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
      )?.play()
    } catch (_: Exception) {
      // No sound on this device — the tick + confetti still show.
    }
  }

  val tickProgress by animateFloatAsState(
    targetValue = if (started) 1f else 0f,
    animationSpec = tween(durationMillis = 700),
    label = "tick"
  )
  val confettiProgress by animateFloatAsState(
    targetValue = if (started) 1f else 0f,
    animationSpec = tween(durationMillis = 1800),
    label = "confetti"
  )

  // ~40 confetti pieces, fixed random layout per composition.
  val pieces = remember {
    val rnd = Random(42)
    List(40) { i ->
      ConfettiPiece(
        startX = rnd.nextFloat(),
        startY = rnd.nextFloat() * 0.35f,
        fall = 0.45f + rnd.nextFloat() * 0.55f,
        size = 8f + rnd.nextFloat() * 10f,
        color = CONFETTI_COLORS[i % CONFETTI_COLORS.size],
        rotation = rnd.nextFloat() * 360f,
        spin = (rnd.nextFloat() - 0.5f) * 720f,
        drift = (rnd.nextFloat() - 0.5f) * 120f,
        isDot = rnd.nextBoolean()
      )
    }
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .background(theme.bg)
      .padding(horizontal = 24.dp, vertical = 32.dp),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    // Tick + confetti area (fixed height, confetti drawn around the tick).
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(220.dp),
      contentAlignment = Alignment.Center
    ) {
      Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        for (piece in pieces) {
          val x = piece.startX * w + piece.drift * confettiProgress
          val y = piece.startY * h + piece.fall * h * confettiProgress
          val alpha = (1f - confettiProgress).coerceIn(0f, 1f)
          rotate(piece.rotation + piece.spin * confettiProgress, Offset(x, y)) {
            if (piece.isDot) {
              drawCircle(piece.color.copy(alpha = alpha), piece.size / 2f, Offset(x, y))
            } else {
              drawRect(
                piece.color.copy(alpha = alpha),
                topLeft = Offset(x - piece.size / 2f, y - piece.size / 4f),
                size = androidx.compose.ui.geometry.Size(piece.size, piece.size / 2f)
              )
            }
          }
        }
      }
      // Animated green success tick: circle scales in, checkmark draws in.
      val scale = 0.6f + 0.4f * tickProgress
      Canvas(
        Modifier
          .size(120.dp)
          .graphicsLayer(scaleX = scale, scaleY = scale, alpha = tickProgress.coerceIn(0.05f, 1f))
      ) {
        drawCircle(theme.primary, size.minDimension / 2f, center)
        val check = Path().apply {
          val s = size.minDimension
          moveTo(s * 0.28f, s * 0.52f)
          lineTo(s * 0.45f, s * 0.68f)
          lineTo(s * 0.74f, s * 0.34f)
        }
        val measure = PathMeasure()
        measure.setPath(check.asAndroidPath(), false)
        val segment = Path()
        measure.getSegment(0f, measure.length * tickProgress, segment.asAndroidPath(), true)
        drawPath(
          segment,
          Color.White,
          style = Stroke(width = size.minDimension * 0.09f, cap = StrokeCap.Round)
        )
      }
    }

    Text(
      "Done",
      fontFamily = PixelFont,
      fontSize = 28.sp,
      fontWeight = FontWeight.Bold,
      color = theme.text
    )
    Spacer(Modifier.height(4.dp))
    Text(
      "PDF created successfully",
      fontSize = 15.sp,
      color = theme.textSecondary
    )
    Spacer(Modifier.height(24.dp))

    // Created file card — immediately visible, no scrolling needed.
    Card(
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(14.dp),
      colors = CardDefaults.cardColors(containerColor = theme.card),
      border = androidx.compose.foundation.BorderStroke(1.dp, theme.cardBorder)
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text("📄", fontSize = 30.sp)
        Column(
          modifier = Modifier
            .weight(1f)
            .padding(horizontal = 12.dp)
        ) {
          Text(
            file.name,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            color = theme.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
          Spacer(Modifier.height(2.dp))
          Text(
            formatFileSize(file.length()),
            fontSize = 13.sp,
            color = theme.textSecondary
          )
        }
        Button(
          onClick = { onOpen(file) },
          colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
          shape = RoundedCornerShape(10.dp)
        ) {
          Text("OPEN", fontFamily = PixelFont, fontSize = 12.sp, color = theme.textOnPrimary)
        }
      }
    }

    Spacer(Modifier.weight(1f))

    Button(
      onClick = onBackHome,
      modifier = Modifier
        .fillMaxWidth()
        .height(56.dp),
      colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
      shape = RoundedCornerShape(14.dp)
    ) {
      Text("BACK TO HOME", fontFamily = PixelFont, fontSize = 15.sp, color = theme.textOnPrimary)
    }
  }
}
