package com.chami.pdfmaker.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chami.pdfmaker.theme.ChamiTheme
import kotlinx.coroutines.launch

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

  fun rotate90(bmp: Bitmap): Bitmap {
    val m = Matrix().apply { postRotate(90f) }
    return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
  }

  // Reorder helper — used by the arrow buttons.
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

// Crop ratios offered in the crop sheet. Label to ratio (null = Original).
private val CROP_RATIOS: List<Pair<String, Float?>> = listOf(
  "Original" to null,
  "A4" to 1.414f,
  "4:3" to 1.333f,
  "1:1" to 1.0f,
  "3:2" to 1.5f
)

// Shared page list used by BOTH Images to PDF and Scan to PDF, so the
// behaviour is identical in both tools.
// - Each card: thumbnail, "Page N", CROP, ROTATE (inside the crop
//   sheet), <- -> reorder arrows, REMOVE.
// - Drag is deliberately NOT here: no detectReorder / reorderable
//   APIs are used (they may not be in this project). Arrows are the
//   reorder method.
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

  // Apply a new bitmap to one page: save it to a temp file first and
  // replace BOTH the bitmap and the Uri, otherwise the PDF would use
  // the original uncropped image. closeSheet = true for a ratio tap
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

  // Crop sheet for the page being edited.
  cropIndex?.let { index ->
    val page = pages.getOrNull(index)
    if (page != null) {
      AlertDialog(
        onDismissRequest = { if (!cropBusy) cropIndex = null },
        title = {
          Text(
            "CROP PAGE ${index + 1}",
            fontFamily = PixelFont, fontWeight = FontWeight.Bold,
            fontSize = 14.sp, color = theme.text
          )
        },
        text = {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CROP_RATIOS.forEach { (label, ratio) ->
              Button(
                onClick = {
                  replacePage(index, ImagePageEditor.cropToRatio(page.bitmap, ratio), closeSheet = true)
                },
                enabled = !cropBusy,
                modifier = Modifier.fillMaxWidth().height(44.dp),
                colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
                shape = RoundedCornerShape(8.dp)
              ) {
                Text(label, fontFamily = PixelFont, color = theme.textOnPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
              }
            }
            Button(
              onClick = {
                replacePage(index, ImagePageEditor.rotate90(page.bitmap), closeSheet = false)
              },
              enabled = !cropBusy,
              modifier = Modifier.fillMaxWidth().height(44.dp),
              colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
              shape = RoundedCornerShape(8.dp)
            ) {
              Text("ROTATE 90°", fontFamily = PixelFont, color = theme.textOnPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            if (cropBusy) {
              Text("Saving edit…", fontFamily = PixelFont, fontSize = 11.sp, color = theme.textSecondary)
            }
          }
        },
        confirmButton = {
          TextButton(onClick = { cropIndex = null }, enabled = !cropBusy) {
            Text("DONE", fontFamily = PixelFont, color = theme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
          }
        }
      )
    }
  }

  if (pages.isNotEmpty()) {
    Text(
      "PREVIEW (${pages.size} PAGES):",
      fontFamily = PixelFont, fontWeight = FontWeight.Bold,
      fontSize = 12.sp, color = theme.primaryDark
    )
    Spacer(Modifier.height(8.dp))
    LazyRow(
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      modifier = Modifier.fillMaxWidth()
    ) {
      itemsIndexed(pages) { index, page ->
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          modifier = Modifier
            .width(110.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(theme.card)
            .border(1.dp, theme.cardBorder, RoundedCornerShape(8.dp))
            .padding(6.dp)
        ) {
          Image(
            bitmap = page.bitmap.asImageBitmap(),
            contentDescription = "Page ${index + 1}",
            modifier = Modifier
              .size(88.dp)
              .clip(RoundedCornerShape(4.dp)),
            contentScale = ContentScale.Crop
          )
          Spacer(Modifier.height(4.dp))
          Text("Page ${index + 1}", fontFamily = PixelFont, fontSize = 10.sp, color = theme.text)
          if (page.cropped) {
            Text("Edited", fontFamily = PixelFont, fontSize = 9.sp, color = theme.primary)
          }
          Spacer(Modifier.height(4.dp))
          Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(
              onClick = { onPagesChange(ImagePageEditor.move(pages, index, index - 1)) },
              enabled = index > 0,
              contentPadding = PaddingValues(0.dp),
              modifier = Modifier.weight(1f).height(32.dp),
              shape = RoundedCornerShape(6.dp),
              border = androidx.compose.foundation.BorderStroke(1.dp, theme.cardBorder),
              colors = ButtonDefaults.outlinedButtonColors(contentColor = theme.text)
            ) {
              Text("←", fontFamily = PixelFont, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = theme.text)
            }
            OutlinedButton(
              onClick = { onPagesChange(ImagePageEditor.move(pages, index, index + 1)) },
              enabled = index < pages.lastIndex,
              contentPadding = PaddingValues(0.dp),
              modifier = Modifier.weight(1f).height(32.dp),
              shape = RoundedCornerShape(6.dp),
              border = androidx.compose.foundation.BorderStroke(1.dp, theme.cardBorder),
              colors = ButtonDefaults.outlinedButtonColors(contentColor = theme.text)
            ) {
              Text("→", fontFamily = PixelFont, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = theme.text)
            }
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
