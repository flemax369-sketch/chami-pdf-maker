package com.chami.pdfmaker.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.chami.pdfmaker.theme.ChamiTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// A page = Uri the PDF is built from + the bitmap currently shown
// (after crop / rotate). After a crop or rotate, uri is the temp
// file from bitmapToUri — never the original image.
data class PageItem(val uri: Uri, val bitmap: Bitmap, val cropped: Boolean = false)

object ImagePageEditor {

  // Crop to a ratio, centre-cropped. No library, no internet.
  // ratio: null = original, 1.414f = A4, 1.333f = 4:3, 1.0f = 1:1, 1.5f = 3:2
  fun cropToRatio(bmp: Bitmap, ratio: Float?): Bitmap {
    if (ratio == null) return bmp
    val w = bmp.width; val h = bmp.height
    val cur = w.toFloat() / h
    return if (cur > ratio) {
      val nw = (h * ratio).toInt()
      Bitmap.createBitmap(bmp, (w - nw) / 2, 0, nw, h)
    } else {
      val nh = (w / ratio).toInt()
      Bitmap.createBitmap(bmp, 0, (h - nh) / 2, w, nh)
    }
  }

  // Crop to a normalised rectangle (fractions 0..1 of the bitmap) and,
  // if zoom > 1, centre-crop further by 1/zoom — that is what pinch to
  // zoom does in the crop dialog. The rect is clamped inside the bitmap.
  fun cropToRect(bmp: Bitmap, rect: Rect, zoom: Float): Bitmap {
    var l = rect.left.coerceIn(0f, 1f)
    var t = rect.top.coerceIn(0f, 1f)
    var r = rect.right.coerceIn(0f, 1f)
    var b = rect.bottom.coerceIn(0f, 1f)
    if (zoom > 1f) {
      val cx = (l + r) / 2f; val cy = (t + b) / 2f
      val hw = (r - l) / 2f / zoom; val hh = (b - t) / 2f / zoom
      l = (cx - hw).coerceIn(0f, 1f); r = (cx + hw).coerceIn(0f, 1f)
      t = (cy - hh).coerceIn(0f, 1f); b = (cy + hh).coerceIn(0f, 1f)
    }
    val x = (l * bmp.width).toInt().coerceIn(0, bmp.width - 1)
    val y = (t * bmp.height).toInt().coerceIn(0, bmp.height - 1)
    val w = ((r - l) * bmp.width).toInt().coerceIn(1, bmp.width - x)
    val h = ((b - t) * bmp.height).toInt().coerceIn(1, bmp.height - y)
    if (w <= 0 || h <= 0) return bmp
    return Bitmap.createBitmap(bmp, x, y, w, h)
  }

  // FREE CROP — perspective warp of a quadrilateral (4 free corners).
  // srcQuad: 4 points in bitmap pixels, ordered TL, TR, BR, BL.
  // Uses android.graphics.Matrix.setPolyToPoly (platform API, no library).
  // If the quad is an axis-aligned rectangle this is exactly a rect crop.
  fun warpQuad(bmp: Bitmap, srcQuad: List<Offset>, outW: Int, outH: Int): Bitmap {
    if (srcQuad.size != 4) return bmp
    val w = outW.coerceAtLeast(1); val h = outH.coerceAtLeast(1)
    val src = floatArrayOf(
      srcQuad[0].x, srcQuad[0].y, srcQuad[1].x, srcQuad[1].y,
      srcQuad[2].x, srcQuad[2].y, srcQuad[3].x, srcQuad[3].y
    )
    val dst = floatArrayOf(0f, 0f, w.toFloat(), 0f, w.toFloat(), h.toFloat(), 0f, h.toFloat())
    val m = Matrix()
    if (!m.setPolyToPoly(src, 0, dst, 0, 4)) return bmp
    return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
  }

  // Crop to a free quadrilateral given as fractions (0..1) of the bitmap.
  // zoom > 1 shrinks the quad toward its centre first (pinch-to-zoom).
  // Output size follows the quad's own edge lengths, so the result is
  // straightened, not stretched — and it IS the cropped image (Phase 5:
  // the old rect crop was not visibly applying).
  fun cropToQuad(bmp: Bitmap, quadFractions: List<Offset>, zoom: Float = 1f): Bitmap {
    if (quadFractions.size != 4) return bmp
    var pts = quadFractions.map { Offset(it.x * bmp.width, it.y * bmp.height) }
    if (zoom > 1f) {
      val cx = pts.sumOf { it.x.toDouble() }.toFloat() / 4f
      val cy = pts.sumOf { it.y.toDouble() }.toFloat() / 4f
      pts = pts.map { Offset(cx + (it.x - cx) / zoom, cy + (it.y - cy) / zoom) }
    }
    pts = pts.map { Offset(it.x.coerceIn(0f, bmp.width.toFloat()), it.y.coerceIn(0f, bmp.height.toFloat())) }
    val topLen = kotlin.math.hypot((pts[1].x - pts[0].x).toDouble(), (pts[1].y - pts[0].y).toDouble()).toFloat()
    val botLen = kotlin.math.hypot((pts[2].x - pts[3].x).toDouble(), (pts[2].y - pts[3].y).toDouble()).toFloat()
    val leftLen = kotlin.math.hypot((pts[3].x - pts[0].x).toDouble(), (pts[3].y - pts[0].y).toDouble()).toFloat()
    val rightLen = kotlin.math.hypot((pts[2].x - pts[1].x).toDouble(), (pts[2].y - pts[1].y).toDouble()).toFloat()
    val outW = ((topLen + botLen) / 2f).toInt().coerceIn(1, bmp.width)
    val outH = ((leftLen + rightLen) / 2f).toInt().coerceIn(1, bmp.height)
    if (outW <= 0 || outH <= 0) return bmp
    return warpQuad(bmp, pts, outW, outH)
  }

  // AUTO-DETECT EDGES — lightweight, honest detector, NOT ML / OpenCV.
  // It is a luminance bounding box only: it finds the bounding box of
  // the darker-than-background content, NOT the true paper edges, so on
  // a busy background it can be wrong — the user then drags the corners.
  // Phase 6 improvements over the old fixed-threshold version:
  // downscale to ~300px wide, adaptive threshold from the MEAN luminance
  // of the downscaled image (mean * 0.9, clamped 60..235) instead of a
  // fixed 235, 2% inset, full-frame fallback when almost nothing is found.
  fun detectDocumentQuad(bmp: Bitmap): List<Offset> {
    val full = listOf(Offset(0f, 0f), Offset(1f, 0f), Offset(1f, 1f), Offset(0f, 1f))
    return try {
      val scale = 300f / bmp.width.coerceAtLeast(1)
      val small = if (scale < 1f) Bitmap.createScaledBitmap(bmp, 300, (bmp.height * scale).toInt().coerceAtLeast(1), true) else bmp
      val w = small.width; val h = small.height
      val pixels = IntArray(w * h)
      small.getPixels(pixels, 0, w, 0, 0, w, h)
      // Pass 1: mean luminance, so the threshold adapts to bright/dark pages.
      var lumSum = 0.0
      for (px in pixels) {
        val r = (px shr 16) and 0xFF; val g = (px shr 8) and 0xFF; val b = px and 0xFF
        lumSum += 0.299 * r + 0.587 * g + 0.114 * b
      }
      val meanLum = (lumSum / pixels.size.coerceAtLeast(1)).toFloat()
      val threshold = (meanLum * 0.9f).coerceIn(60f, 235f)
      // Pass 2: bounding box of pixels darker than the adaptive threshold.
      var minX = w; var minY = h; var maxX = -1; var maxY = -1; var count = 0
      for (y in 0 until h) {
        for (x in 0 until w) {
          val px = pixels[y * w + x]
          val r = (px shr 16) and 0xFF; val g = (px shr 8) and 0xFF; val b = px and 0xFF
          val lum = (0.299f * r + 0.587f * g + 0.114f * b)
          if (lum < threshold) {
            count++
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
          }
        }
      }
      if (small !== bmp) small.recycle()
      if (count < 50 || maxX < 0) return full
      val marginX = 0.02f; val marginY = 0.02f
      val l = ((minX.toFloat() / w) - marginX).coerceIn(0f, 1f)
      val t = ((minY.toFloat() / h) - marginY).coerceIn(0f, 1f)
      val r = ((maxX.toFloat() / w) + marginX).coerceIn(0f, 1f)
      val b = ((maxY.toFloat() / h) + marginY).coerceIn(0f, 1f)
      if (r - l < 0.1f || b - t < 0.1f) return full
      listOf(Offset(l, t), Offset(r, t), Offset(r, b), Offset(l, b))
    } catch (_: Exception) {
      full
    }
  }

  fun rotate90(bmp: Bitmap): Bitmap {
    val m = Matrix().apply { postRotate(90f) }
    return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
  }

  // Scan filters (mode: 0 = Original, 1 = Black & white, 2 = Grayscale,
  // 3 = Enhance, 4 = High contrast). Pure ColorMatrix work on a copy —
  // no library, offline. B&W and Grayscale both desaturate; B&W adds a
  // little contrast so text reads darker, Grayscale keeps tones as-is.
  fun applyFilter(bmp: Bitmap, mode: Int): Bitmap {
    if (mode == 0) return bmp
    val out = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    val paint = Paint()
    val cm = ColorMatrix()
    when (mode) {
      1 -> { // Black & white
        cm.setSaturation(0f)
        val contrast = ColorMatrix(floatArrayOf(
          1.2f, 0f, 0f, 0f, -10f,
          0f, 1.2f, 0f, 0f, -10f,
          0f, 0f, 1.2f, 0f, -10f,
          0f, 0f, 0f, 1f, 0f
        ))
        cm.postConcat(contrast)
      }
      2 -> cm.setSaturation(0f) // Grayscale
      3 -> { // Enhance: a little more contrast + brightness, colour kept
        val enhance = ColorMatrix(floatArrayOf(
          1.15f, 0f, 0f, 0f, 8f,
          0f, 1.15f, 0f, 0f, 8f,
          0f, 0f, 1.15f, 0f, 8f,
          0f, 0f, 0f, 1f, 0f
        ))
        cm.postConcat(enhance)
      }
      4 -> { // High contrast black & white (kept for compatibility)
        cm.setSaturation(0f)
        val contrast = ColorMatrix(floatArrayOf(
          1.4f, 0f, 0f, 0f, -20f,
          0f, 1.4f, 0f, 0f, -20f,
          0f, 0f, 1.4f, 0f, -20f,
          0f, 0f, 0f, 1f, 0f
        ))
        cm.postConcat(contrast)
      }
    }
    paint.colorFilter = ColorMatrixColorFilter(cm)
    canvas.drawBitmap(bmp, 0f, 0f, paint)
    return out
  }

  // Brightness adjust, delta -100..100. Applied on a copy.
  fun adjustBrightness(bmp: Bitmap, delta: Float): Bitmap {
    val out = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    val paint = Paint()
    val cm = ColorMatrix(floatArrayOf(
      1f, 0f, 0f, 0f, delta,
      0f, 1f, 0f, 0f, delta,
      0f, 0f, 1f, 0f, delta,
      0f, 0f, 0f, 1f, 0f
    ))
    paint.colorFilter = ColorMatrixColorFilter(cm)
    canvas.drawBitmap(bmp, 0f, 0f, paint)
    return out
  }

  // Reorder helper — used by drag and the arrow buttons.
  fun move(list: List<PageItem>, from: Int, to: Int): List<PageItem> {
    if (from !in list.indices || to !in list.indices) return list
    val m = list.toMutableList()
    val item = m.removeAt(from); m.add(to, item)
    return m
  }

  // Save a cropped/rotated bitmap back to a temp file so PdfEngine can
  // read it as a Uri. Returns the new Uri.
  suspend fun bitmapToUri(ctx: Context, bmp: Bitmap, name: String): Uri =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
      val f = java.io.File(ctx.cacheDir, name)
      java.io.FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
      Uri.fromFile(f)
    }
}

// Crop ratios offered in the crop editor. Label to ratio (null = Original).
private val CROP_RATIOS: List<Pair<String, Float?>> = listOf(
  "Original" to null,
  "A4" to 1.414f,
  "4:3" to 1.333f,
  "1:1" to 1.0f,
  "3:2" to 1.5f
)

// PERFORMANCE: downscale a page bitmap to ~200px wide for the thumbnail
// strip and cache it in a plain (non-state) map keyed by page Uri, so a
// recomposition never decodes / scales the full bitmap again. The Uri
// changes whenever a page is cropped/rotated (bitmapToUri), so a stale
// thumbnail cannot survive an edit. Callers prune keys that are no
// longer in the page list.
private fun thumbnailBitmap(cache: MutableMap<Uri, ImageBitmap>, page: PageItem): ImageBitmap {
  cache[page.uri]?.let { return it }
  val bmp = page.bitmap
  val thumb: ImageBitmap = if (bmp.width <= 200) {
    bmp.asImageBitmap()
  } else {
    val h = (bmp.height * (200f / bmp.width)).toInt().coerceAtLeast(1)
    Bitmap.createScaledBitmap(bmp, 200, h, true).asImageBitmap()
  }
  cache[page.uri] = thumb
  return thumb
}

// FULL-SCREEN crop editor (Phase 6): a Dialog with usePlatformDefaultWidth
// = false, so it fills the screen instead of a small AlertDialog. The
// image is large and centred, the crop frame is a TRUE 4-corner QUAD
// POLYGON drawn with Canvas (actual quad lines + corner handles, NOT the
// bounding rectangle), with a light rule-of-thirds grid over the image.
// Corner handles: 48dp touch target, ~20dp visible dot, draggable freely
// in x and y, clamped inside the image, cannot cross the opposite corner.
// Edge midpoint handles (4) drag that edge's two corners together.
// While a handle is dragged a circular 2x magnifier loupe shows the area
// around the finger, positioned above it. Preset ratios only set a
// starting rectangle — corners can then be dragged freely from there.
// Pinch zoom 1-3x still works. onApply calls cropToQuad, so the crop
// VISIBLY applies. Signature unchanged from Phase 5.
@Composable
fun CropEditorDialog(
  bitmap: Bitmap,
  theme: ChamiTheme,
  onApply: (Bitmap) -> Unit,
  onDismiss: () -> Unit,
  onRotate: () -> Unit,
  // Phase 5: optional detected quad (from Auto-detect) to start from.
  initialQuad: List<Offset>? = null
) {
  var quad by remember(bitmap, initialQuad) {
    mutableStateOf(
      initialQuad?.takeIf { it.size == 4 }
        ?: listOf(Offset(0f, 0f), Offset(1f, 0f), Offset(1f, 1f), Offset(0f, 1f))
    )
  }
  var cropRect by remember(bitmap, initialQuad) {
    mutableStateOf(
      initialQuad?.takeIf { it.size == 4 }?.let { q ->
        Rect(q.minOf { it.x }, q.minOf { it.y }, q.maxOf { it.x }, q.maxOf { it.y })
      } ?: Rect(0f, 0f, 1f, 1f)
    )
  }
  var zoom by remember(bitmap) { mutableStateOf(1f) }
  val density = LocalDensity.current

  fun quadFromRect(r: Rect): List<Offset> =
    listOf(Offset(r.left, r.top), Offset(r.right, r.top), Offset(r.right, r.bottom), Offset(r.left, r.bottom))

  fun ratioRect(ratio: Float?): Rect {
    if (ratio == null) return Rect(0f, 0f, 1f, 1f)
    val imgAspect = bitmap.width.toFloat() / bitmap.height
    return if (ratio >= imgAspect) {
      val h = imgAspect / ratio
      Rect(0f, (1f - h) / 2f, 1f, (1f + h) / 2f)
    } else {
      val w = ratio / imgAspect
      Rect((1f - w) / 2f, 0f, (1f + w) / 2f, 1f)
    }
  }

  fun syncRectFromQuad() {
    cropRect = Rect(quad.minOf { it.x }, quad.minOf { it.y }, quad.maxOf { it.x }, quad.maxOf { it.y })
  }

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false)
  ) {
    Column(
      modifier = Modifier.fillMaxSize().background(Color.Black).padding(12.dp)
    ) {
      // Top bar: CANCEL (left), title, APPLY (right).
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        TextButton(onClick = onDismiss) {
          Text("CANCEL", fontFamily = PixelFont, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Text(
          "FREE CROP",
          fontFamily = PixelFont, fontWeight = FontWeight.Bold,
          fontSize = 14.sp, color = Color.White
        )
        Button(
          onClick = { onApply(ImagePageEditor.cropToQuad(bitmap, quad, zoom)) },
          colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
          shape = RoundedCornerShape(8.dp),
          contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)
        ) {
          Text("APPLY", fontFamily = PixelFont, color = theme.textOnPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
      }
      Spacer(Modifier.height(8.dp))
      Text(
        "Drag a green corner freely to crop — corners move on their own, so tilted pages straighten. Pinch to zoom.",
        fontFamily = PixelFont, fontSize = 11.sp, color = Color(0xFFCBD5E1)
      )
      Spacer(Modifier.height(8.dp))

      // Large centred image area (fills the remaining height).
      BoxWithConstraints(
        modifier = Modifier.weight(1f).fillMaxWidth()
      ) {
        val boxW = with(density) { maxWidth.toPx() }
        val boxH = with(density) { maxHeight.toPx() }
        val imgAspect = bitmap.width.toFloat() / bitmap.height.toFloat()
        val boxAspect = boxW / boxH.coerceAtLeast(1f)
        // Display size: fit the whole image inside the box, centred.
        val dispW = if (boxAspect > imgAspect) boxH * imgAspect else boxW
        val dispH = if (boxAspect > imgAspect) boxH else boxW / imgAspect
        val offXPx = (boxW - dispW) / 2f
        val offYPx = (boxH - dispH) / 2f
        val minSide = 0.08f
        // Which handle is being dragged: 0..3 = corner, 4..7 = edge midpoint
        // (edge = handle - 4: 0 top, 1 right, 2 bottom, 3 left). Null = none.
        // The loupe follows this handle's point.
        var activeHandle by remember { mutableStateOf<Int?>(null) }

        fun moveCorner(corner: Int, dx: Float, dy: Float) {
          val fx = dx / dispW; val fy = dy / dispH
          val cur = quad[corner]
          val opp = quad[(corner + 2) % 4]
          val nx = (cur.x + fx).coerceIn(0f, 1f)
          val ny = (cur.y + fy).coerceIn(0f, 1f)
          val clampedX = if (corner == 0 || corner == 3) nx.coerceAtMost(opp.x - minSide) else nx.coerceAtLeast(opp.x + minSide)
          val clampedY = if (corner == 0 || corner == 1) ny.coerceAtMost(opp.y - minSide) else ny.coerceAtLeast(opp.y + minSide)
          quad = quad.toMutableList().also { it[corner] = Offset(clampedX, clampedY) }
          syncRectFromQuad()
        }

        fun moveEdge(edge: Int, dx: Float, dy: Float) {
          val fx = dx / dispW; val fy = dy / dispH
          val ends = when (edge) {
            0 -> 0 to 1 // top
            1 -> 1 to 2 // right
            2 -> 2 to 3 // bottom
            else -> 3 to 0 // left
          }
          val next = quad.toMutableList()
          for (c in listOf(ends.first, ends.second)) {
            val cur = quad[c]
            val opp = quad[(c + 2) % 4]
            var nx = (cur.x + fx).coerceIn(0f, 1f)
            var ny = (cur.y + fy).coerceIn(0f, 1f)
            nx = if (c == 0 || c == 3) nx.coerceAtMost(opp.x - minSide) else nx.coerceAtLeast(opp.x + minSide)
            ny = if (c == 0 || c == 1) ny.coerceAtMost(opp.y - minSide) else ny.coerceAtLeast(opp.y + minSide)
            next[c] = Offset(nx, ny)
          }
          quad = next
          syncRectFromQuad()
        }

        fun edgeMid(edge: Int): Offset {
          val ends = when (edge) {
            0 -> 0 to 1
            1 -> 1 to 2
            2 -> 2 to 3
            else -> 3 to 0
          }
          val a = quad[ends.first]; val b = quad[ends.second]
          return Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)
        }

        // The displayed image, exactly dispW x dispH, centred in the box.
        Box(
          modifier = Modifier
            .offset { IntOffset(offXPx.roundToInt(), offYPx.roundToInt()) }
            .size(
              width = with(density) { dispW.toDp() },
              height = with(density) { dispH.toDp() }
            )
            .pointerInput(bitmap) {
              detectTransformGestures { _, _, zoomChange, _ ->
                zoom = (zoom * zoomChange).coerceIn(1f, 3f)
              }
            }
        ) {
          Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Page to crop",
            modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = zoom, scaleY = zoom),
            contentScale = ContentScale.FillBounds
          )

          // TRUE QUAD POLYGON + rule-of-thirds grid, drawn on Canvas over
          // the image. This draws the actual quad lines, NOT the bounding
          // rectangle — what you see is the crop you get.
          Canvas(modifier = Modifier.fillMaxSize()) {
            val grid = Color.White.copy(alpha = 0.35f)
            val thirdW = size.width / 3f
            val thirdH = size.height / 3f
            drawLine(grid, Offset(thirdW, 0f), Offset(thirdW, size.height), strokeWidth = 1f)
            drawLine(grid, Offset(thirdW * 2f, 0f), Offset(thirdW * 2f, size.height), strokeWidth = 1f)
            drawLine(grid, Offset(0f, thirdH), Offset(size.width, thirdH), strokeWidth = 1f)
            drawLine(grid, Offset(0f, thirdH * 2f), Offset(size.width, thirdH * 2f), strokeWidth = 1f)
            val pts = quad.map { Offset(it.x * size.width, it.y * size.height) }
            for (i in 0 until 4) {
              drawLine(theme.primary, pts[i], pts[(i + 1) % 4], strokeWidth = 4f)
            }
          }

          // Corner handles: 48dp touch target, 20dp visible dot inside.
          quad.forEachIndexed { corner, pt ->
            Box(
              Modifier
                .offset {
                  IntOffset(
                    (pt.x * dispW).roundToInt() - with(density) { 24.dp.toPx() }.roundToInt(),
                    (pt.y * dispH).roundToInt() - with(density) { 24.dp.toPx() }.roundToInt()
                  )
                }
                .size(48.dp)
                .pointerInput(corner, bitmap) {
                  detectDragGestures(
                    onDragStart = { activeHandle = corner },
                    onDragEnd = { activeHandle = null },
                    onDragCancel = { activeHandle = null },
                    onDrag = { change, dragAmount ->
                      change.consume()
                      moveCorner(corner, dragAmount.x, dragAmount.y)
                    }
                  )
                },
              contentAlignment = Alignment.Center
            ) {
              Box(
                Modifier.size(20.dp).clip(CircleShape).background(theme.primary)
                  .border(2.dp, Color.White, CircleShape)
              )
            }
          }

          // Edge midpoint handles: 48dp touch target, 16dp visible dot.
          for (edge in 0 until 4) {
            val mid = edgeMid(edge)
            Box(
              Modifier
                .offset {
                  IntOffset(
                    (mid.x * dispW).roundToInt() - with(density) { 24.dp.toPx() }.roundToInt(),
                    (mid.y * dispH).roundToInt() - with(density) { 24.dp.toPx() }.roundToInt()
                  )
                }
                .size(48.dp)
                // NOTE: keys are (edge, bitmap) only — NOT quad. Quad
                // changes on every drag move; keying on it would restart
                // pointerInput and cancel the drag mid-gesture.
                .pointerInput(edge, bitmap) {
                  detectDragGestures(
                    onDragStart = { activeHandle = edge + 4 },
                    onDragEnd = { activeHandle = null },
                    onDragCancel = { activeHandle = null },
                    onDrag = { change, dragAmount ->
                      change.consume()
                      moveEdge(edge, dragAmount.x, dragAmount.y)
                    }
                  )
                },
              contentAlignment = Alignment.Center
            ) {
              Box(
                Modifier.size(16.dp).clip(CircleShape).background(Color.White)
                  .border(2.dp, theme.primary, CircleShape)
              )
            }
          }

          // MAGNIFIER LOUPE: while a corner/edge handle is dragged, a
          // circular 2x zoomed view of the area around the finger,
          // positioned above the finger (clamped inside the image area).
          activeHandle?.let { handle ->
            val focus = if (handle < 4) quad[handle] else edgeMid(handle - 4)
            val fx = focus.x * dispW
            val fy = focus.y * dispH
            val loupePx = with(density) { 120.dp.toPx() }
            val half = loupePx / 2f
            val loupeX = (fx - half).coerceIn(0f, (dispW - loupePx).coerceAtLeast(0f))
            val loupeY = (fy - loupePx - with(density) { 24.dp.toPx() }).coerceIn(0f, (dispH - loupePx).coerceAtLeast(0f))
            Box(
              Modifier
                .offset { IntOffset(loupeX.roundToInt(), loupeY.roundToInt()) }
                .size(120.dp)
                .clip(CircleShape)
                .background(Color.Black)
                .border(2.dp, Color.White, CircleShape)
            ) {
              Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                  .size(
                    width = with(density) { (dispW * 2f).toDp() },
                    height = with(density) { (dispH * 2f).toDp() }
                  )
                  .offset {
                    IntOffset(
                      (half - fx * 2f).roundToInt(),
                      (half - fy * 2f).roundToInt()
                    )
                  },
                contentScale = ContentScale.FillBounds
              )
            }
          }
        }
      }

      Spacer(Modifier.height(8.dp))
      Text(
        "Zoom: ${"%.1f".format(zoom)}x",
        fontFamily = PixelFont, fontSize = 11.sp, color = Color(0xFFCBD5E1)
      )
      Spacer(Modifier.height(8.dp))

      // Preset ratios — set a starting rectangle only.
      Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth()
      ) {
        CROP_RATIOS.forEach { (label, ratio) ->
          OutlinedButton(
            onClick = { cropRect = ratioRect(ratio); quad = quadFromRect(cropRect) },
            modifier = Modifier.weight(1f).height(36.dp),
            shape = RoundedCornerShape(6.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF475569)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
            contentPadding = PaddingValues(0.dp)
          ) {
            Text(label, fontFamily = PixelFont, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
          }
        }
      }
      Spacer(Modifier.height(8.dp))

      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Auto-detect edges (adaptive luminance bounding box — not ML /
        // OpenCV, see ImagePageEditor.detectDocumentQuad). Sets the corners.
        OutlinedButton(
          onClick = {
            val detected = ImagePageEditor.detectDocumentQuad(bitmap)
            quad = detected
            syncRectFromQuad()
          },
          modifier = Modifier.weight(1f).height(44.dp),
          shape = RoundedCornerShape(8.dp),
          border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF475569)),
          colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
        ) {
          Text("AUTO-DETECT EDGES", fontFamily = PixelFont, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        Button(
          onClick = onRotate,
          modifier = Modifier.weight(1f).height(44.dp),
          colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
          shape = RoundedCornerShape(8.dp)
        ) {
          Text("ROTATE 90°", fontFamily = PixelFont, color = theme.textOnPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
      }
      Spacer(Modifier.height(8.dp))
      Button(
        onClick = { onApply(ImagePageEditor.cropToQuad(bitmap, quad, zoom)) },
        modifier = Modifier.fillMaxWidth().height(48.dp),
        colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
        shape = RoundedCornerShape(8.dp)
      ) {
        Text("APPLY CROP", fontFamily = PixelFont, color = theme.textOnPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
      }
    }
  }
}

// Shared page list used by BOTH Images to PDF and Scan to PDF, so the
// behaviour is identical in both tools. Phase 6 reorder:
// - NO "PREVIEW (X PAGES):" header, NO "Hold a page, then drag it to
//   move it." line, NO drag handle, NO arrow buttons, NO CROP/REMOVE
//   buttons on the cards (crop/delete/filter/adjust now live as icons
//   on the main preview — PageList only accepts those callbacks).
// - Tap a thumbnail to select it (green border + green checkmark).
// - Long-press HOLD the thumbnail itself to pick it up: the dragged
//   card LIFTS (scales ~1.08x, shadow), other thumbnails SLIDE ASIDE
//   live (the list is reordered as soon as the dragged card passes the
//   HALF card width threshold, not a full card width), the LazyRow
//   auto-scrolls at the edges while dragging, haptic ticks on pickup
//   and on drop (haptic is always on), page numbers update instantly
//   because the list itself is reordered on every threshold crossing.
// - After the last thumbnail a big "+" tile calls onAddPages.
// - Thumbnails use the cached ~200px downscale (thumbnailBitmap), never
//   the full bitmap per recomposition.
// No ads in this file (Phase 6: all ads removed).
@Composable
fun PageList(
  pages: List<PageItem>,
  theme: ChamiTheme,
  selectedIndex: Int,
  onSelect: (Int) -> Unit,
  onPagesChange: (List<PageItem>) -> Unit,
  onAddPages: () -> Unit,
  onCrop: (Int) -> Unit,
  onDelete: (Int) -> Unit,
  onFilter: (Int) -> Unit,
  onAdjust: (Int) -> Unit
) {
  val density = LocalDensity.current
  val haptic = LocalHapticFeedback.current
  val scope = rememberCoroutineScope()
  val listState = rememberLazyListState()
  // Cached thumbnails, keyed by page Uri (see thumbnailBitmap).
  val thumbCache = remember { HashMap<Uri, ImageBitmap>() }
  thumbCache.keys.retainAll(pages.map { it.uri }.toSet())
  // Drag state lives here (not per-item) so it survives the list being
  // reordered under the dragged card. Explicit State objects so the
  // pointerInput drag callbacks always read the current values.
  val draggingIndexState = remember { mutableStateOf<Int?>(null) }
  val dragOffsetXState = remember { mutableStateOf(0f) }
  val latestPages by rememberUpdatedState(pages)
  val latestOnPagesChange by rememberUpdatedState(onPagesChange)
  val cardWidthDp = 120.dp
  val spacingDp = 10.dp
  val cardFullPx = with(density) { (cardWidthDp + spacingDp).toPx() }
  val halfPx = cardFullPx / 2f
  val dragShadowPx = with(density) { 12.dp.toPx() }

  LazyRow(
    state = listState,
    horizontalArrangement = Arrangement.spacedBy(spacingDp),
    modifier = Modifier.fillMaxWidth()
  ) {
    itemsIndexed(pages, key = { _, page -> page.uri.toString() }) { index, page ->
      val isSelected = index == selectedIndex
      val isDragging = index == draggingIndexState.value
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
          .width(cardWidthDp)
          .graphicsLayer {
            translationX = if (isDragging) dragOffsetXState.value else 0f
            val s = if (isDragging) 1.08f else 1f
            scaleX = s
            scaleY = s
            shadowElevation = if (isDragging) dragShadowPx else 0f
          }
          .clip(RoundedCornerShape(8.dp))
          .background(theme.card)
          .border(
            width = if (isSelected) 2.dp else 1.dp,
            color = if (isSelected) theme.primary else theme.cardBorder,
            shape = RoundedCornerShape(8.dp)
          )
          .padding(6.dp)
          .clickable { onSelect(index) }
          // Long-press HOLD the thumbnail itself, then drag. The
          // pointerInput key is the stable page Uri (NOT the index):
          // the index changes as the list reorders, and re-keying on
          // it would cancel the drag mid-gesture. Current index/list
          // are read from the State objects above, live.
          .pointerInput(page.uri) {
            detectDragGesturesAfterLongPress(
              onDragStart = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                draggingIndexState.value = latestPages.indexOfFirst { it.uri == page.uri }
                dragOffsetXState.value = 0f
              },
              onDrag = { change, dragAmount ->
                change.consume()
                dragOffsetXState.value += dragAmount.x
                val cur = draggingIndexState.value ?: return@detectDragGesturesAfterLongPress
                val current = latestPages
                // Auto-scroll the LazyRow at the edges while dragging.
                val info = listState.layoutInfo
                val firstVisible = info.visibleItemsInfo.firstOrNull()?.index ?: 0
                val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                if (dragAmount.x < 0f && cur <= firstVisible + 1) {
                  scope.launch { listState.scrollBy(dragAmount.x) }
                } else if (dragAmount.x > 0f && cur >= lastVisible - 1) {
                  scope.launch { listState.scrollBy(dragAmount.x) }
                }
                // Live reorder at HALF a card width: move the page in
                // the list now, so the other thumbnails slide aside
                // and show the drop gap. Keep the offset relative to
                // the card's new position so it stays under the finger.
                if (dragOffsetXState.value > halfPx && cur < current.lastIndex) {
                  latestOnPagesChange(ImagePageEditor.move(current, cur, cur + 1))
                  draggingIndexState.value = cur + 1
                  dragOffsetXState.value -= cardFullPx
                } else if (dragOffsetXState.value < -halfPx && cur > 0) {
                  latestOnPagesChange(ImagePageEditor.move(current, cur, cur - 1))
                  draggingIndexState.value = cur - 1
                  dragOffsetXState.value += cardFullPx
                }
              },
              onDragEnd = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                draggingIndexState.value = null
                dragOffsetXState.value = 0f
              },
              onDragCancel = {
                draggingIndexState.value = null
                dragOffsetXState.value = 0f
              }
            )
          }
      ) {
        Box(Modifier.size(110.dp)) {
          Image(
            bitmap = thumbnailBitmap(thumbCache, page),
            contentDescription = "Page ${index + 1}",
            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp)),
            contentScale = ContentScale.Crop
          )
          if (isSelected) {
            Box(
              Modifier
                .align(Alignment.TopEnd)
                .size(22.dp)
                .clip(CircleShape)
                .background(theme.primary)
                .border(1.dp, Color.White, CircleShape),
              contentAlignment = Alignment.Center
            ) {
              Text("✓", fontFamily = PixelFont, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
          }
        }
        Spacer(Modifier.height(4.dp))
        Text("Page ${index + 1}", fontFamily = PixelFont, fontSize = 10.sp, color = theme.text)
        if (page.cropped) {
          Text("Edited", fontFamily = PixelFont, fontSize = 9.sp, color = theme.primary)
        }
      }
    }
    // Big "+" tile after the last thumbnail — same size as a card.
    item(key = "add_pages") {
      Box(
        Modifier
          .width(cardWidthDp)
          .height(140.dp)
          .clip(RoundedCornerShape(8.dp))
          .background(Color(0xFFDCFCE7))
          .border(1.dp, theme.primary, RoundedCornerShape(8.dp))
          .clickable { onAddPages() },
        contentAlignment = Alignment.Center
      ) {
        Text("+", fontFamily = PixelFont, fontSize = 36.sp, fontWeight = FontWeight.Bold, color = theme.primaryDark)
      }
    }
  }
}

// Old 3-arg PageList, kept so existing call sites keep compiling. It
// delegates to the new overload; crop is still handled here internally
// (onCrop opens this file's crop editor) and delete removes the page,
// because the old call sites have no main-preview icons of their own.
// onCrop/onDelete/onFilter/onAdjust are accepted by the new overload
// but the old one only needs crop + delete.
@Composable
fun PageList(
  pages: List<PageItem>,
  theme: ChamiTheme,
  onPagesChange: (List<PageItem>) -> Unit
) {
  val ctx = LocalContext.current
  val scope = rememberCoroutineScope()
  var cropIndex by remember { mutableStateOf<Int?>(null) }
  var cropBusy by remember { mutableStateOf(false) }

  // Apply a new bitmap to one page: save it to a temp file first and
  // replace BOTH the bitmap and the Uri, otherwise the PDF would use
  // the original uncropped image. closeSheet = true for a crop apply
  // (crop done), false for ROTATE (stay open so a ratio can follow).
  fun replacePage(index: Int, newBitmap: Bitmap, closeSheet: Boolean) {
    val page = pages.getOrNull(index) ?: return
    if (cropBusy) return
    cropBusy = true
    scope.launch {
      try {
        val newUri = ImagePageEditor.bitmapToUri(
          ctx, newBitmap, "page_${index}_${System.currentTimeMillis()}.jpg"
        )
        val updated = pages.toMutableList()
        if (index in updated.indices) {
          updated[index] = PageItem(newUri, newBitmap, cropped = true)
          onPagesChange(updated)
        }
        if (closeSheet) cropIndex = null
      } catch (_: Exception) {
        Toast.makeText(ctx, "Could not apply that edit", Toast.LENGTH_SHORT).show()
      }
      cropBusy = false
    }
  }

  // Crop editor for the page being edited (full-screen, Phase 6).
  cropIndex?.let { index ->
    val page = pages.getOrNull(index)
    if (page != null) {
      CropEditorDialog(
        bitmap = page.bitmap,
        theme = theme,
        onApply = { bmp -> replacePage(index, bmp, closeSheet = true) },
        onDismiss = { if (!cropBusy) cropIndex = null },
        onRotate = { replacePage(index, ImagePageEditor.rotate90(page.bitmap), closeSheet = false) }
      )
    }
  }

  PageList(
    pages = pages,
    theme = theme,
    selectedIndex = 0,
    onSelect = { },
    onPagesChange = onPagesChange,
    onAddPages = { },
    onCrop = { cropIndex = it },
    onDelete = { idx -> onPagesChange(pages.filterIndexed { i, _ -> i != idx }) },
    onFilter = { },
    onAdjust = { }
  )
}
