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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
  // Downscales to ~200px wide, finds the bounding box of non-white
  // pixels (luminance < 235), insets it by a small margin, and returns
  // it as 4 corner fractions (TL, TR, BR, BL). If almost nothing is
  // found (blank / very bright page) it returns the full frame.
  fun detectDocumentQuad(bmp: Bitmap): List<Offset> {
    val full = listOf(Offset(0f, 0f), Offset(1f, 0f), Offset(1f, 1f), Offset(0f, 1f))
    return try {
      val scale = 200f / bmp.width.coerceAtLeast(1)
      val small = if (scale < 1f) Bitmap.createScaledBitmap(bmp, 200, (bmp.height * scale).toInt().coerceAtLeast(1), true) else bmp
      val w = small.width; val h = small.height
      val pixels = IntArray(w * h)
      small.getPixels(pixels, 0, w, 0, 0, w, h)
      var minX = w; var minY = h; var maxX = -1; var maxY = -1; var count = 0
      for (y in 0 until h) {
        for (x in 0 until w) {
          val px = pixels[y * w + x]
          val r = (px shr 16) and 0xFF; val g = (px shr 8) and 0xFF; val b = px and 0xFF
          val lum = (0.299f * r + 0.587f * g + 0.114f * b)
          if (lum < 235f) {
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

// Adobe-like crop editor: draggable corner + edge handles over the image,
// pinch to zoom, preset ratios, rotate. The crop rectangle is kept as
// fractions (0..1) of the bitmap, so it is correct at any display size.
// onApply receives the cropped bitmap; onRotate rotates the source bitmap
// and the dialog stays open with the new image.
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
  // FREE CROP (Phase 5): the crop frame is 4 free corners (TL, TR, BR, BL)
  // as fractions of the bitmap — each corner drags freely in x AND y, so
  // the crop is a quadrilateral with perspective straightening, not a
  // rectangle. Reset whenever a new bitmap is shown (e.g. after rotate).
  var quad by remember(bitmap, initialQuad) {
    mutableStateOf(
      initialQuad?.takeIf { it.size == 4 }
        ?: listOf(Offset(0f, 0f), Offset(1f, 0f), Offset(1f, 1f), Offset(0f, 1f))
    )
  }
  // Bounding rect of the quad, kept only so preset ratios can set a
  // starting frame (corners can then be dragged freely from there).
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

  AlertDialog(
    onDismissRequest = onDismiss,
        title = {
      Text(
        "FREE CROP — MOVE THE CORNERS",
        fontFamily = PixelFont, fontWeight = FontWeight.Bold,
        fontSize = 14.sp, color = theme.text
      )
    },
    text = {
      Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.verticalScroll(rememberScrollState())
      ) {
        Text(
          "Drag a green corner freely to crop — corners move on their own, so tilted pages straighten. Pinch to zoom.",
          fontFamily = PixelFont, fontSize = 11.sp, color = theme.textSecondary
        )
        BoxWithConstraints(
          modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(bitmap.width.toFloat() / bitmap.height)
            .pointerInput(Unit) {
              detectTransformGestures { _, _, zoomChange, _ ->
                zoom = (zoom * zoomChange).coerceIn(1f, 3f)
              }
            }
        ) {
          val wPx = with(density) { maxWidth.toPx() }
          val hPx = with(density) { maxHeight.toPx() }
          val minSide = 0.15f

          // FREE CORNER: each corner moves freely in x and y (no longer
          // locked to a rectangle). Clamped inside the image; kept at
          // least minSide away from the opposite corner so the quad
          // cannot collapse or invert.
          fun moveCorner(corner: Int, dx: Float, dy: Float) {
            val fx = dx / wPx; val fy = dy / hPx
            val cur = quad[corner]
            val opp = quad[(corner + 2) % 4]
            val nx = (cur.x + fx).coerceIn(0f, 1f)
            val ny = (cur.y + fy).coerceIn(0f, 1f)
            val clampedX = if (corner == 0 || corner == 3) nx.coerceAtMost(opp.x - minSide) else nx.coerceAtLeast(opp.x + minSide)
            val clampedY = if (corner == 0 || corner == 1) ny.coerceAtMost(opp.y - minSide) else ny.coerceAtLeast(opp.y + minSide)
            quad = quad.toMutableList().also { it[corner] = Offset(clampedX, clampedY) }
            cropRect = Rect(quad.minOf { it.x }, quad.minOf { it.y }, quad.maxOf { it.x }, quad.maxOf { it.y })
          }

          Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Page to crop",
            modifier = Modifier
              .fillMaxWidth()
              .graphicsLayer(scaleX = zoom, scaleY = zoom),
            contentScale = ContentScale.FillWidth
          )

          // Crop frame overlay: bounding box of the free quad (the 4
          // handles below sit exactly on the quad's corners, so the
          // frame you see is the quad you get).
          Box(
            Modifier
              .offset {
                IntOffset(
                  (cropRect.left * wPx).roundToInt(),
                  (cropRect.top * hPx).roundToInt()
                )
              }
              .size(
                width = with(density) { (cropRect.width * wPx).toDp() },
                height = with(density) { (cropRect.height * hPx).toDp() }
              )
              .border(2.dp, theme.primary, RoundedCornerShape(2.dp))
          )
          // Free corner handles (0 = top-left, 1 = top-right,
          // 2 = bottom-right, 3 = bottom-left), positioned on the quad.
          quad.forEachIndexed { corner, pt ->
            Box(
              Modifier
                .offset {
                  IntOffset(
                    (pt.x * wPx).roundToInt() - 10.dp.roundToPx(),
                    (pt.y * hPx).roundToInt() - 10.dp.roundToPx()
                  )
                }
                .size(20.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(theme.primary)
                .border(2.dp, theme.card, RoundedCornerShape(10.dp))
                .pointerInput(corner) {
                  detectDragGestures { change, dragAmount ->
                    change.consume()
                    moveCorner(corner, dragAmount.x, dragAmount.y)
                  }
                }
            )
          }
        }

        Text(
          "Zoom: ${"%.1f".format(zoom)}x",
          fontFamily = PixelFont, fontSize = 11.sp, color = theme.textSecondary
        )

        // Preset ratios.
        Row(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          modifier = Modifier.fillMaxWidth()
        ) {
          CROP_RATIOS.forEach { (label, ratio) ->
            OutlinedButton(
              onClick = { cropRect = ratioRect(ratio); quad = quadFromRect(cropRect) },
              modifier = Modifier.weight(1f).height(36.dp),
              shape = RoundedCornerShape(6.dp),
              border = androidx.compose.foundation.BorderStroke(1.dp, theme.cardBorder),
              colors = ButtonDefaults.outlinedButtonColors(contentColor = theme.text),
              contentPadding = PaddingValues(0.dp)
            ) {
              Text(label, fontFamily = PixelFont, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = theme.text)
            }
          }
        }

        // Auto-detect edges (luminance bounding box — not ML, see
        // ImagePageEditor.detectDocumentQuad). Sets the 4 corners.
        OutlinedButton(
          onClick = {
            val detected = ImagePageEditor.detectDocumentQuad(bitmap)
            quad = detected
            cropRect = Rect(detected.minOf { it.x }, detected.minOf { it.y }, detected.maxOf { it.x }, detected.maxOf { it.y })
          },
          modifier = Modifier.fillMaxWidth().height(40.dp),
          shape = RoundedCornerShape(8.dp),
          border = androidx.compose.foundation.BorderStroke(1.dp, theme.cardBorder),
          colors = ButtonDefaults.outlinedButtonColors(contentColor = theme.text)
        ) {
          Text("AUTO-DETECT EDGES", fontFamily = PixelFont, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = theme.text)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(
            onClick = onRotate,
            modifier = Modifier.weight(1f).height(44.dp),
            colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
            shape = RoundedCornerShape(8.dp)
          ) {
            Text("ROTATE 90°", fontFamily = PixelFont, color = theme.textOnPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
          }
          Button(
            onClick = { onApply(ImagePageEditor.cropToQuad(bitmap, quad, zoom)) },
            modifier = Modifier.weight(1f).height(44.dp),
            colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
            shape = RoundedCornerShape(8.dp)
          ) {
            Text("APPLY CROP", fontFamily = PixelFont, color = theme.textOnPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Text("DONE", fontFamily = PixelFont, color = theme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
      }
    }
  )
}

// Shared page list used by BOTH Images to PDF and Scan to PDF, so the
// behaviour is identical in both tools.
// - Each card: thumbnail, "Page N", CROP (free-crop editor), REMOVE.
// - Reorder (Phase 5): HOLD the image/card itself, then drag left/right.
//   There is no drag handle and no arrow buttons any more. When the drag
//   passes one card width the page moves.
// - onPagesChange receives the new full list after every edit.
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
  val density = LocalDensity.current

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

  // Crop editor for the page being edited (Adobe-like: handles + pinch).
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

  if (pages.isNotEmpty()) {
    Text(
      "PREVIEW (${pages.size} PAGES):",
      fontFamily = PixelFont, fontWeight = FontWeight.Bold,
      fontSize = 12.sp, color = theme.primaryDark
    )
    Text(
      "Hold a page, then drag it to move it.",
      fontFamily = PixelFont, fontSize = 11.sp, color = theme.textSecondary
    )
    Spacer(Modifier.height(8.dp))
    LazyRow(
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      modifier = Modifier.fillMaxWidth()
    ) {
      itemsIndexed(pages, key = { _, page -> page.uri.toString() }) { index, page ->
        var dragOffsetX by remember { mutableStateOf(0f) }
        val dragThresholdPx = with(density) { 120.dp.toPx() }
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          modifier = Modifier
            .width(132.dp)
            .graphicsLayer { translationX = dragOffsetX }
            .clip(RoundedCornerShape(8.dp))
            .background(theme.card)
            .border(1.dp, theme.cardBorder, RoundedCornerShape(8.dp))
            .padding(6.dp)
            // Phase 5: hold the CARD ITSELF (image included) to drag it.
            // Long-press starts the drag; a plain tap still works for
            // the buttons below, which are children of this card.
            .pointerInput(index) {
              detectDragGesturesAfterLongPress(
                onDrag = { change, dragAmount ->
                  change.consume()
                  dragOffsetX += dragAmount.x
                  if (dragOffsetX > dragThresholdPx && index < pages.lastIndex) {
                    onPagesChange(ImagePageEditor.move(pages, index, index + 1))
                    dragOffsetX = 0f
                  } else if (dragOffsetX < -dragThresholdPx && index > 0) {
                    onPagesChange(ImagePageEditor.move(pages, index, index - 1))
                    dragOffsetX = 0f
                  }
                },
                onDragEnd = { dragOffsetX = 0f },
                onDragCancel = { dragOffsetX = 0f }
              )
            }
        ) {
          Image(
            bitmap = page.bitmap.asImageBitmap(),
            contentDescription = "Page ${index + 1} — hold and drag to reorder",
            modifier = Modifier
              .size(110.dp)
              .clip(RoundedCornerShape(4.dp)),
            contentScale = ContentScale.Crop
          )
          Spacer(Modifier.height(4.dp))
          Text("Page ${index + 1}", fontFamily = PixelFont, fontSize = 10.sp, color = theme.text)
          if (page.cropped) {
            Text("Edited", fontFamily = PixelFont, fontSize = 9.sp, color = theme.primary)
          }
          Spacer(Modifier.height(4.dp))
          Button(
            onClick = { cropIndex = index },
            modifier = Modifier.fillMaxWidth().height(32.dp),
            colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
            shape = RoundedCornerShape(6.dp),
            contentPadding = PaddingValues(0.dp)
          ) {
            Text("CROP", fontFamily = PixelFont, color = theme.textOnPrimary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
          }
          Spacer(Modifier.height(4.dp))
          OutlinedButton(
            onClick = { onPagesChange(pages.filterIndexed { i, _ -> i != index }) },
            modifier = Modifier.fillMaxWidth().height(32.dp),
            shape = RoundedCornerShape(6.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, theme.cardBorder),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = theme.text),
            contentPadding = PaddingValues(0.dp)
          ) {
            Text("✕ REMOVE", fontFamily = PixelFont, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = theme.text)
          }
        }
      }
    }
  }
}
