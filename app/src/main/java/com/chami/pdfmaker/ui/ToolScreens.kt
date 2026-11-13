package com.chami.pdfmaker.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.chami.pdfmaker.pdf.*
import com.chami.pdfmaker.theme.ChamiTheme
import com.chami.pdfmaker.theme.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

// ---------------------------------------------------------------------------
// PHASE 6 — NO ADS in this build (user decision 2026-11-13): no interstitial
// before a file is made, no banner, no AdMob anywhere in this file. Build
// actions are called directly. AdsManager is never referenced here.
// ---------------------------------------------------------------------------

// --- Local PDF helpers (Phase 6) ------------------------------------------
// PdfEngine.imagesToPdfWithOptions / PdfProtector.unlockPdf / setPermissions
// / PdfCompress.compressPdfImages do NOT exist in this project, so the
// Phase 6 extras are implemented here with only verified APIs (PdfDocument,
// PdfEngine, PdfCompress, PdfProtector.verifyPassword) — no new deps.

// Standard PDF padding string (PDF spec, Algorithm 3.2) — same bytes as
// PdfProtector's private PADDING.
private val PDF_PADDING = byteArrayOf(
  0x28.toByte(), 0xBF.toByte(), 0x4E.toByte(), 0x5E.toByte(), 0x4E.toByte(), 0x75.toByte(), 0x8A.toByte(), 0x41.toByte(),
  0x64.toByte(), 0x00.toByte(), 0x4E.toByte(), 0x56.toByte(), 0xFF.toByte(), 0xFA.toByte(), 0x01.toByte(), 0x08.toByte(),
  0x2E.toByte(), 0x2E.toByte(), 0x00.toByte(), 0xB6.toByte(), 0xD0.toByte(), 0x68.toByte(), 0x3E.toByte(), 0x80.toByte(),
  0x2F.toByte(), 0x0C.toByte(), 0xA9.toByte(), 0xFE.toByte(), 0x64.toByte(), 0x53.toByte(), 0x69.toByte(), 0x7A.toByte()
)

private fun rc4Crypt(key: ByteArray, data: ByteArray): ByteArray {
  val s = IntArray(256) { it }
  var j = 0
  for (i in 0 until 256) {
    j = (j + s[i] + (key[i % key.size].toInt() and 0xFF)) and 0xFF
    val t = s[i]; s[i] = s[j]; s[j] = t
  }
  val out = ByteArray(data.size)
  var x = 0; var y = 0
  for (i in data.indices) {
    x = (x + 1) and 0xFF
    y = (y + s[x]) and 0xFF
    val t = s[x]; s[x] = s[y]; s[y] = t
    val k = s[(s[x] + s[y]) and 0xFF]
    out[i] = (data[i].toInt() xor k).toByte()
  }
  return out
}

private fun pdfMd5(vararg parts: ByteArray): ByteArray {
  val d = MessageDigest.getInstance("MD5")
  parts.forEach { d.update(it) }
  return d.digest()
}

private fun pdfPadPassword(pw: String): ByteArray {
  val raw = pw.toByteArray(Charsets.ISO_8859_1)
  val out = ByteArray(32)
  val n = minOf(raw.size, 32)
  System.arraycopy(raw, 0, out, 0, n)
  if (n < 32) System.arraycopy(PDF_PADDING, 0, out, n, 32 - n)
  return out
}

private fun pdfHexToBytes(hex: String): ByteArray {
  val clean = hex.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
  val out = ByteArray(clean.length / 2)
  for (i in out.indices) {
    out[i] = ((Character.digit(clean[i * 2], 16) shl 4) + Character.digit(clean[i * 2 + 1], 16)).toByte()
  }
  return out
}

private fun pdfBytesToHex(b: ByteArray): String {
  val sb = StringBuilder()
  b.forEach { sb.append(String.format("%02x", it.toInt() and 0xFF)) }
  return sb.toString()
}

// Images -> PDF with the Phase 6 options, drawn with plain PdfDocument
// (no new dependency): page size A4 / Letter / Original (page sized to the
// image's own aspect), optional page numbers, optional watermark text,
// image quality via JPEG re-encode (High 92 / Medium 80 / Low 65).
private fun buildImagesPdfWithOptions(
  ctx: Context,
  pages: List<PageItem>,
  outFile: File,
  pageSize: String,
  pageNumbers: Boolean,
  watermark: String,
  quality: Int
): Boolean {
  if (pages.isEmpty()) return false
  return try {
    val doc = PdfDocument()
    val (stdW, stdH) = when (pageSize) {
      "Letter" -> 612 to 792
      else -> 595 to 842 // A4 (and the cap used for Original)
    }
    var pageNo = 1
    pages.forEach { page ->
      var bmp = page.bitmap
      try {
        val baos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(10, 100), baos)
        val decoded = BitmapFactory.decodeByteArray(baos.toByteArray(), 0, baos.size())
        if (decoded != null) bmp = decoded
      } catch (_: Exception) { }
      val (pageW, pageH) = if (pageSize == "Original") {
        val scale = minOf(595f / bmp.width, 842f / bmp.height)
        (bmp.width * scale).toInt().coerceAtLeast(100) to (bmp.height * scale).toInt().coerceAtLeast(100)
      } else {
        stdW to stdH
      }
      val info = PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create()
      val pg = doc.startPage(info)
      val c = pg.canvas
      c.drawColor(AndroidColor.WHITE)
      val scale = minOf((pageW - 72f) / bmp.width, (pageH - 72f) / bmp.height)
      val w = bmp.width * scale
      val h = bmp.height * scale
      val dst = RectF((pageW - w) / 2, (pageH - h) / 2, (pageW + w) / 2, (pageH + h) / 2)
      c.drawBitmap(bmp, null, dst, null)
      if (watermark.isNotBlank()) {
        val wp = Paint().apply {
          color = AndroidColor.argb(60, 0, 0, 0)
          textSize = 36f
          typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
          textAlign = Paint.Align.CENTER
        }
        c.save()
        c.rotate(-30f, pageW / 2f, pageH / 2f)
        c.drawText(watermark, pageW / 2f, pageH / 2f, wp)
        c.restore()
      }
      if (pageNumbers) {
        val np = Paint().apply {
          color = AndroidColor.DKGRAY
          textSize = 12f
          textAlign = Paint.Align.CENTER
        }
        c.drawText("$pageNo", pageW / 2f, pageH - 18f, np)
      }
      doc.finishPage(pg)
      if (bmp !== page.bitmap) bmp.recycle()
      pageNo++
    }
    FileOutputStream(outFile).use { doc.writeTo(it) }
    doc.close()
    true
  } catch (e: Exception) {
    e.printStackTrace()
    false
  }
}

// Unlock a Standard-security V1/R2 (RC4) PDF — the format this app's own
// PdfProtector writes. RC4 is a stream cipher, so every encrypted stream
// decrypts in place at exactly the same length: no object offsets change,
// the xref table stays valid, and we only blank out the "/Encrypt N 0 R"
// reference in the trailer. The password is verified first (U entry must
// match) so a wrong password never produces a corrupt file. Returns false
// for files this cannot decrypt (e.g. AES / R5+ / other encrypt handlers).
private fun unlockProtectedPdf(input: File, password: String, out: File): Boolean {
  return try {
    val bytes = input.readBytes()
    val text = String(bytes, Charsets.ISO_8859_1)
    val oHex = Regex("""/O\s*<([0-9a-fA-F]+)>""").find(text)?.groupValues?.get(1) ?: return false
    val idHex = Regex("""/ID\s*\[\s*<([0-9a-fA-F]+)>""").find(text)?.groupValues?.get(1) ?: return false
    val pVal = Regex("""/P\s*(-?[0-9]+)""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: return false
    val oBytes = pdfHexToBytes(oHex)
    val idBytes = pdfHexToBytes(idHex)
    val pBytes = byteArrayOf(
      (pVal and 0xFF).toByte(), ((pVal shr 8) and 0xFF).toByte(),
      ((pVal shr 16) and 0xFF).toByte(), ((pVal shr 24) and 0xFF).toByte()
    )
    val encKey = pdfMd5(pdfPadPassword(password), oBytes, pBytes, idBytes).copyOfRange(0, 5)
    val uHex = Regex("""/U\s*<([0-9a-fA-F]+)>""").find(text)?.groupValues?.get(1) ?: return false
    if (!pdfBytesToHex(rc4Crypt(encKey, PDF_PADDING)).equals(uHex, ignoreCase = true)) return false
    val result = bytes.copyOf()
    var searchFrom = 0
    while (true) {
      val objMatch = Regex("""(\d+)\s+0\s+obj\b""").find(text, searchFrom) ?: break
      val objNum = objMatch.groupValues[1].toIntOrNull() ?: break
      val endObjIdx = text.indexOf("endobj", objMatch.range.last)
      if (endObjIdx == -1) break
      val streamIdx = text.indexOf("stream", objMatch.range.last)
      if (streamIdx != -1 && streamIdx < endObjIdx) {
        var dataStart = streamIdx + "stream".length
        if (text.getOrNull(dataStart) == '\r') dataStart++
        if (text.getOrNull(dataStart) == '\n') dataStart++
        val dictText = text.substring(objMatch.range.last, streamIdx)
        val len = Regex("""/Length\s+(\d+)""").find(dictText)?.groupValues?.get(1)?.toIntOrNull()
        if (len != null && dataStart + len <= result.size) {
          val md = MessageDigest.getInstance("MD5")
          md.update(encKey)
          md.update(
            byteArrayOf(
              (objNum and 0xFF).toByte(), ((objNum shr 8) and 0xFF).toByte(),
              ((objNum shr 16) and 0xFF).toByte(), 0.toByte(), 0.toByte()
            )
          )
          val objKey = md.digest().copyOfRange(0, minOf(encKey.size + 5, 16))
          val plain = rc4Crypt(objKey, result.copyOfRange(dataStart, dataStart + len))
          plain.copyInto(result, dataStart)
        }
      }
      searchFrom = endObjIdx + 6
    }
    var outText = String(result, Charsets.ISO_8859_1)
    val encRef = Regex("""/Encrypt\s+\d+\s+0\s+R""").find(outText) ?: return false
    outText = outText.replace(encRef.value, " ".repeat(encRef.value.length))
    out.writeBytes(outText.toByteArray(Charsets.ISO_8859_1))
    true
  } catch (_: Exception) {
    false
  }
}

// Apply print/copy permissions to a PDF this app just protected.
// PdfProtector hardcodes /P -4 (everything allowed), so the permission bits
// are patched in place: /P and /U are recomputed consistently, and the /P
// swap is length-preserving ("/P -4 >>" -> "/P -8 >>" / "/P -20>>" /
// "/P -24>>") so the xref offsets stay valid. Returns false if the patch
// could not be applied — the file is then left as the plain protected PDF.
private fun patchPdfPermissions(
  file: File,
  password: String,
  allowPrint: Boolean,
  allowCopy: Boolean
): Boolean {
  return try {
    var pVal = -4
    if (!allowPrint) pVal -= 4
    if (!allowCopy) pVal -= 16
    if (pVal == -4) return true
    val text = String(file.readBytes(), Charsets.ISO_8859_1)
    val oHex = Regex("""/O\s*<([0-9a-fA-F]+)>""").find(text)?.groupValues?.get(1) ?: return false
    val idHex = Regex("""/ID\s*\[\s*<([0-9a-fA-F]+)>""").find(text)?.groupValues?.get(1) ?: return false
    val oBytes = pdfHexToBytes(oHex)
    val idBytes = pdfHexToBytes(idHex)
    val pBytes = byteArrayOf(
      (pVal and 0xFF).toByte(), ((pVal shr 8) and 0xFF).toByte(),
      ((pVal shr 16) and 0xFF).toByte(), ((pVal shr 24) and 0xFF).toByte()
    )
    val encKey = pdfMd5(pdfPadPassword(password), oBytes, pBytes, idBytes).copyOfRange(0, 5)
    val newU = pdfBytesToHex(rc4Crypt(encKey, PDF_PADDING))
    val uMatch = Regex("""/U\s*<[0-9a-fA-F]{64}>""").find(text) ?: return false
    var outText = text.replace(uMatch.value, "/U <$newU>")
    val pReplacement = when (pVal) {
      -8 -> "/P -8 >>"
      -20 -> "/P -20>>"
      -24 -> "/P -24>>"
      else -> return false
    }
    if (!outText.contains("/P -4 >>")) return false
    outText = outText.replace("/P -4 >>", pReplacement)
    file.writeBytes(outText.toByteArray(Charsets.ISO_8859_1))
    true
  } catch (_: Exception) {
    false
  }
}

// Compress-PDF quality slider (0-100) mapped onto the existing
// PdfCompress presets. NOTE: PdfCompress re-renders every page as an image,
// so text in the output is rasterised, not kept as selectable text —
// PdfCompress.compressPdfImages (re-encode only the images, keep text)
// does not exist in this project.
private fun compressPdfWithQuality(ctx: Context, uri: Uri, quality: Int, out: File): PdfCompressResult {
  val preset = when {
    quality >= 70 -> PdfCompress.PRESETS[0] // High: 1.0x, q70
    quality >= 40 -> PdfCompress.PRESETS[1] // Medium: 0.75x, q50
    else -> PdfCompress.PRESETS[2] // Low: 0.6x, q40
  }
  return PdfCompress.compressPdf(ctx, uri, preset, out)
}

@Composable
fun ToolScreenContainer(
  title: String,
  theme: ChamiTheme,
  onBack: () -> Unit,
  content: @Composable ColumnScope.() -> Unit
) {
  val ctx = LocalContext.current
  val haptic = LocalHapticFeedback.current
  val tm = remember { ThemeManager(ctx) }

  fun vibrate() {
    if (tm.hapticOn()) {
      try {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
      } catch (_: Exception) {}
    }
  }

  BackHandler {
    vibrate()
    onBack()
  }

  Column(
    Modifier
      .fillMaxSize()
      .background(theme.bg)
      .statusBarsPadding()
      .padding(16.dp)
  ) {
    Row(
      Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically
    ) {
      OutlinedButton(
        onClick = {
          vibrate()
          onBack()
        },
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, theme.cardBorder),
        colors = ButtonDefaults.outlinedButtonColors(
          containerColor = theme.card,
          contentColor = theme.text
        ),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
      ) {
        Text("← BACK", color = theme.text, fontFamily = PixelFont, fontSize = 12.sp, fontWeight = FontWeight.Bold)
      }
      Spacer(Modifier.width(12.dp))
      Text(
        text = title,
        fontFamily = PixelFont,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 18.sp,
        color = theme.text,
        maxLines = 1,
        modifier = Modifier.weight(1f)
      )
      Box(
        Modifier
          .clip(RoundedCornerShape(6.dp))
          .background(theme.primary.copy(alpha = 0.12f))
          .padding(horizontal = 8.dp, vertical = 3.dp)
      ) {
        Text(
          "OFFLINE",
          fontFamily = PixelFont,
          fontWeight = FontWeight.Bold,
          fontSize = 10.sp,
          color = theme.primary
        )
      }
    }
    Spacer(Modifier.height(14.dp))
    content()
  }
}

@Composable
fun PrimaryButton(
  theme: ChamiTheme,
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  height: Dp = 56.dp
) {
  Button(
    onClick = onClick,
    enabled = enabled,
    modifier = modifier.fillMaxWidth().height(height),
    colors = ButtonDefaults.buttonColors(
      containerColor = theme.primary,
      contentColor = theme.textOnPrimary,
      disabledContainerColor = theme.cardBorder,
      disabledContentColor = theme.textSecondary
    ),
    shape = RoundedCornerShape(8.dp)
  ) {
    Text(text, fontFamily = PixelFont, color = theme.textOnPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
  }
}

@Composable
fun ResultCard(file: File, theme: ChamiTheme, mimeType: String = "application/pdf") {
  val ctx = LocalContext.current
  val haptic = LocalHapticFeedback.current
  val tm = remember { ThemeManager(ctx) }

  fun vibrate() {
    if (tm.hapticOn()) {
      try {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
      } catch (_: Exception) {}
    }
  }

  Column(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(16.dp))
      .background(theme.card)
      .border(1.dp, theme.cardBorder, RoundedCornerShape(16.dp))
      .padding(16.dp)
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Box(
        Modifier
          .size(36.dp)
          .clip(CircleShape)
          .background(theme.primary.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center
      ) {
        Text("✓", color = theme.primary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
      }
      Spacer(Modifier.width(10.dp))
      Column {
        Text("Document Ready", fontFamily = PixelFont, fontWeight = FontWeight.Bold, color = theme.text, fontSize = 15.sp)
        Text("Saved to internal device storage", fontFamily = PixelFont, color = theme.textSecondary, fontSize = 11.sp)
      }
    }
    Spacer(Modifier.height(10.dp))
    Box(
      Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(10.dp))
        .background(theme.bg)
        .border(1.dp, theme.cardBorder, RoundedCornerShape(10.dp))
        .padding(10.dp)
    ) {
      Column {
        Text(file.name, fontFamily = PixelFont, fontWeight = FontWeight.SemiBold, color = theme.text, fontSize = 12.sp, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Text("${file.length() / 1024} KB • Location: /Chami/${file.name}", fontFamily = PixelFont, color = theme.textSecondary, fontSize = 11.sp)
      }
    }
    Spacer(Modifier.height(14.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(
        onClick = {
          vibrate()
          ShareHelper.openFile(ctx, file, mimeType)
        },
        modifier = Modifier.weight(1f).height(44.dp),
        colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
        shape = RoundedCornerShape(10.dp)
      ) {
        Text("OPEN FILE", fontFamily = PixelFont, color = theme.textOnPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
      }
      OutlinedButton(
        onClick = {
          vibrate()
          ShareHelper.shareFile(ctx, file, mimeType)
        },
        modifier = Modifier.weight(1f).height(44.dp),
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, theme.cardBorder),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = theme.text)
      ) {
        Text("SHARE", fontFamily = PixelFont, color = theme.text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
      }
    }
  }
}

// --- Phase 6 shared pieces (used by Images to PDF + Scan to PDF) ---------
// NOTE: the "new PageList overload" from the brief does not exist in
// ImagePageEditor.kt (only the old 3-arg PageList, which still prints the
// "PREVIEW (X PAGES):" / "Hold a page..." lines), so the Phase 6 page list
// is implemented here instead — same hold-to-drag reorder, tap to select,
// big "+" tile — without depending on another file changing.

@Composable
private fun Phase6Chip(label: String, selected: Boolean, theme: ChamiTheme, onClick: () -> Unit) {
  Box(
    Modifier
      .clip(RoundedCornerShape(16.dp))
      .background(if (selected) theme.primary else theme.card)
      .border(1.dp, theme.cardBorder, RoundedCornerShape(16.dp))
      .clickable { onClick() }
      .padding(horizontal = 12.dp, vertical = 8.dp),
    contentAlignment = Alignment.Center
  ) {
    Text(
      if (selected) "✓ $label" else label,
      fontFamily = PixelFont,
      fontSize = 11.sp,
      fontWeight = FontWeight.Bold,
      color = if (selected) theme.textOnPrimary else theme.text
    )
  }
}

// Big main preview (340dp, full width) with the left-corner vertical icon
// stack: Crop on top, then Delete, then Filter, then Adjust.
@Composable
private fun BigPagePreview(
  page: PageItem?,
  label: String,
  theme: ChamiTheme,
  onCrop: () -> Unit,
  onDelete: () -> Unit,
  onFilter: () -> Unit,
  onAdjust: () -> Unit,
  height: Dp = 340.dp
) {
  Box(
    Modifier
      .fillMaxWidth()
      .height(height)
      .clip(RoundedCornerShape(8.dp))
      .background(theme.card)
      .border(1.dp, theme.cardBorder, RoundedCornerShape(8.dp))
  ) {
    if (page != null) {
      Image(
        bitmap = page.bitmap.asImageBitmap(),
        contentDescription = label,
        modifier = Modifier.fillMaxSize().padding(4.dp),
        contentScale = ContentScale.Fit
      )
    } else {
      Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("No page yet", fontFamily = PixelFont, fontSize = 12.sp, color = theme.textSecondary)
      }
    }
    Column(
      Modifier.align(Alignment.TopStart).padding(8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      listOf(
        "◫" to onCrop,
        "✕" to onDelete,
        "✦" to onFilter,
        "◐" to onAdjust
      ).forEach { (glyph, action) ->
        Box(
          Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(theme.primary)
            .clickable { action() },
          contentAlignment = Alignment.Center
        ) {
          Text(glyph, color = theme.textOnPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
      }
    }
  }
}

// Thumbnail strip: tap a thumbnail to change the big preview, hold a
// thumbnail and drag it left/right to reorder, big "+" tile at the end.
@Composable
private fun PageThumbnailStrip(
  pages: List<PageItem>,
  selectedIndex: Int,
  theme: ChamiTheme,
  onSelect: (Int) -> Unit,
  onMove: (Int, Int) -> Unit,
  onAddPages: () -> Unit
) {
  val density = LocalDensity.current
  LazyRow(
    horizontalArrangement = Arrangement.spacedBy(10.dp),
    modifier = Modifier.fillMaxWidth()
  ) {
    itemsIndexed(pages, key = { _, page -> page.uri.toString() }) { index, page ->
      val isSel = index == selectedIndex
      var dragOffsetX by remember { mutableStateOf(0f) }
      val dragThresholdPx = with(density) { 96.dp.toPx() }
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
          Modifier
            .size(96.dp)
            .graphicsLayer { translationX = dragOffsetX }
            .pointerInput(index) {
              detectDragGesturesAfterLongPress(
                onDrag = { change, dragAmount ->
                  change.consume()
                  dragOffsetX += dragAmount.x
                  if (dragOffsetX > dragThresholdPx && index < pages.lastIndex) {
                    onMove(index, index + 1)
                    dragOffsetX = 0f
                  } else if (dragOffsetX < -dragThresholdPx && index > 0) {
                    onMove(index, index - 1)
                    dragOffsetX = 0f
                  }
                },
                onDragEnd = { dragOffsetX = 0f },
                onDragCancel = { dragOffsetX = 0f }
              )
            }
            .clip(RoundedCornerShape(8.dp))
            .background(theme.card)
            .border(2.dp, if (isSel) theme.primary else theme.cardBorder, RoundedCornerShape(8.dp))
            .clickable { onSelect(index) }
        ) {
          Image(
            bitmap = page.bitmap.asImageBitmap(),
            contentDescription = "Page ${index + 1}",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
          )
          if (isSel) {
            Box(
              Modifier
                .align(Alignment.TopEnd)
                .size(20.dp)
                .clip(CircleShape)
                .background(theme.primary),
              contentAlignment = Alignment.Center
            ) {
              Text("✓", color = theme.textOnPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
          }
        }
        Spacer(Modifier.height(3.dp))
        Text(
          "Page ${index + 1}",
          fontFamily = PixelFont,
          fontSize = 10.sp,
          fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
          color = if (isSel) theme.primary else theme.text
        )
      }
    }
    item {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
          Modifier
            .size(96.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(theme.card)
            .border(2.dp, theme.primary, RoundedCornerShape(8.dp))
            .clickable { onAddPages() },
          contentAlignment = Alignment.Center
        ) {
          Text("+", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = theme.primary)
        }
        Spacer(Modifier.height(3.dp))
        Text("Add", fontFamily = PixelFont, fontSize = 10.sp, color = theme.text)
      }
    }
  }
}

@Composable
private fun FilterPickerDialog(
  selected: Int,
  theme: ChamiTheme,
  onPick: (Int) -> Unit,
  onDismiss: () -> Unit
) {
  val names = listOf("Original", "B&W", "Grayscale", "Enhance")
  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text("FILTER", fontFamily = PixelFont, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = theme.text)
    },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          names.take(2).forEachIndexed { i, n ->
            Phase6Chip(n, selected == i, theme) { onPick(i) }
          }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          names.drop(2).forEachIndexed { k, n ->
            val i = k + 2
            Phase6Chip(n, selected == i, theme) { onPick(i) }
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

@Composable
private fun BrightnessDialog(
  value: Float,
  theme: ChamiTheme,
  onValueChange: (Float) -> Unit,
  onApply: () -> Unit,
  onDismiss: () -> Unit
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text("ADJUST BRIGHTNESS", fontFamily = PixelFont, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = theme.text)
    },
    text = {
      Column {
        Text("Brightness: ${value.toInt()}", fontFamily = PixelFont, fontSize = 12.sp, color = theme.textSecondary)
        Slider(
          value = value,
          onValueChange = onValueChange,
          valueRange = -100f..100f,
          colors = SliderDefaults.colors(thumbColor = theme.primary, activeTrackColor = theme.primary)
        )
      }
    },
    confirmButton = {
      Button(
        onClick = onApply,
        colors = ButtonDefaults.buttonColors(containerColor = theme.primary)
      ) {
        Text("APPLY", fontFamily = PixelFont, color = theme.textOnPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("CANCEL", fontFamily = PixelFont, color = theme.text)
      }
    }
  )
}

@Composable
private fun AddPageSourceDialog(
  theme: ChamiTheme,
  onCamera: () -> Unit,
  onGallery: () -> Unit,
  onDismiss: () -> Unit
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text("ADD PAGE", fontFamily = PixelFont, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = theme.text)
    },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
          onClick = onCamera,
          modifier = Modifier.fillMaxWidth().height(48.dp),
          colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
          shape = RoundedCornerShape(8.dp)
        ) {
          Text("📷 CAMERA", fontFamily = PixelFont, color = theme.textOnPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        OutlinedButton(
          onClick = onGallery,
          modifier = Modifier.fillMaxWidth().height(48.dp),
          shape = RoundedCornerShape(8.dp),
          border = androidx.compose.foundation.BorderStroke(1.dp, theme.cardBorder),
          colors = ButtonDefaults.outlinedButtonColors(contentColor = theme.text)
        ) {
          Text("🖼️ GALLERY", fontFamily = PixelFont, color = theme.text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Text("CANCEL", fontFamily = PixelFont, color = theme.text)
      }
    }
  )
}

// 1. IMAGES TO PDF — Phase 6 redesign:
// - Top line is ONLY "Select photos from gallery".
// - Big preview (340dp) shows the SELECTED page; tapping a thumbnail
//   changes it. Left-corner icon stack: Crop / Delete / Filter / Adjust.
// - CHOOSE IMAGES button only when no pages are selected; after that the
//   big "+" tile in the thumbnail strip adds more.
// - No "PREVIEW (X PAGES):" / hold-to-drag hint lines.
// - Extras: page size A4/Letter/Original, page numbers, watermark,
//   image quality — wired into the build via buildImagesPdfWithOptions.
// - Slide to Convert (haptic on complete). After the slide the
//   save-location picker opens directly, then DoneScreen.
@Composable
fun ImagesToPdfScreen(theme: ChamiTheme, onBack: () -> Unit) {
  val ctx = LocalContext.current
  val haptic = LocalHapticFeedback.current
  val tm = remember { ThemeManager(ctx) }
  val scope = rememberCoroutineScope()
  var pages by remember { mutableStateOf<List<PageItem>>(emptyList()) }
  var selectedIndex by remember { mutableStateOf(0) }
  var isProcessing by remember { mutableStateOf(false) }
  var cropIndex by remember { mutableStateOf<Int?>(null) }
  var cropBusy by remember { mutableStateOf(false) }
  var showFilters by remember { mutableStateOf(false) }
  var filterMode by remember { mutableStateOf(0) }
  var filterOriginal by remember { mutableStateOf<Bitmap?>(null) }
  var showAdjust by remember { mutableStateOf(false) }
  var brightness by remember { mutableStateOf(0f) }
  var pageSize by remember { mutableStateOf("A4") }
  var pageNumbers by remember { mutableStateOf(false) }
  var watermark by remember { mutableStateOf("") }
  var quality by remember { mutableStateOf("High") }

  // After-convert flow: build -> save picker opens directly -> DoneScreen.
  var pendingSaveFile by remember { mutableStateOf<File?>(null) }
  var doneFile by remember { mutableStateOf<File?>(null) }
  var showDone by remember { mutableStateOf(false) }
  var launchSaveFor by remember { mutableStateOf<File?>(null) }
  val saveLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.CreateDocument("application/pdf")
  ) { uri ->
    val file = pendingSaveFile
    if (uri != null && file != null) {
      if (SaveLocation.copyToUri(ctx, file, uri)) {
        Toast.makeText(ctx, "Saved to chosen folder", Toast.LENGTH_SHORT).show()
      } else {
        Toast.makeText(ctx, "Could not save to chosen folder", Toast.LENGTH_SHORT).show()
      }
    }
    if (file != null) {
      doneFile = file
      showDone = true
    }
  }
  LaunchedEffect(launchSaveFor) {
    val f = launchSaveFor
    if (f != null) {
      pendingSaveFile = f
      saveLauncher.launch(f.name)
      launchSaveFor = null
    }
  }

  val safeSelected = selectedIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0))

  fun vibrate() {
    if (tm.hapticOn()) {
      try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
    }
  }

  // Apply an edited bitmap to one page: save to a temp file first so the
  // PDF uses the edited image. Declared before applyFilterMode — local
  // funs are only visible after their declaration point.
  fun replacePage(index: Int, newBitmap: Bitmap, closeCrop: Boolean = true) {
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
          pages = updated
        }
        if (closeCrop) cropIndex = null
      } catch (_: Exception) {
        Toast.makeText(ctx, "Could not apply that edit", Toast.LENGTH_SHORT).show()
      }
      cropBusy = false
    }
  }

  fun applyFilterMode(mode: Int) {
    val page = pages.getOrNull(safeSelected) ?: return
    if (filterOriginal == null) filterOriginal = page.bitmap
    val base = filterOriginal ?: page.bitmap
    filterMode = mode
    if (mode == 0) replacePage(safeSelected, base, closeCrop = true)
    else replacePage(safeSelected, ImagePageEditor.applyFilter(base, mode), closeCrop = true)
  }

  fun deleteSelected() {
    if (pages.isEmpty()) return
    pages = pages.filterIndexed { i, _ -> i != safeSelected }
    selectedIndex = (safeSelected - 1).coerceAtLeast(0)
    filterOriginal = null
    filterMode = 0
  }

  // Filters never stack and Original restores the page: reset when the
  // selected page changes.
  LaunchedEffect(safeSelected) { filterOriginal = null; filterMode = 0 }

  // Multi-select picker. APPENDS to the existing pages — never replaces.
  val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selected ->
    if (selected.isNotEmpty()) {
      showDone = false
      doneFile = null
      scope.launch(Dispatchers.IO) {
        val added = selected.mapNotNull { uri ->
          PdfEngine.loadBitmap(ctx, uri, 1600)?.let { bmp -> PageItem(uri, bmp) }
        }
        withContext(Dispatchers.Main) {
          val firstNew = pages.size
          pages = pages + added
          selectedIndex = firstNew
        }
      }
    }
  }

  fun buildPdf() {
    if (pages.isEmpty()) {
      Toast.makeText(ctx, "Please select at least 1 image", Toast.LENGTH_SHORT).show()
      return
    }
    isProcessing = true
    scope.launch {
      val out = PdfEngine.outFile(ctx, "Chami_${System.currentTimeMillis()}.pdf")
      val q = when (quality) { "Medium" -> 80; "Low" -> 65; else -> 92 }
      val ok = withContext(Dispatchers.IO) {
        buildImagesPdfWithOptions(ctx, pages, out, pageSize, pageNumbers, watermark.trim(), q)
      }
      isProcessing = false
      if (ok) launchSaveFor = out
      else Toast.makeText(ctx, "Failed to create PDF", Toast.LENGTH_SHORT).show()
    }
  }

  // Crop editor for the selected page.
  cropIndex?.let { index ->
    val page = pages.getOrNull(index)
    if (page != null) {
      CropEditorDialog(
        bitmap = page.bitmap,
        theme = theme,
        onApply = { bmp -> filterOriginal = null; replacePage(index, bmp, closeCrop = true) },
        onDismiss = { if (!cropBusy) cropIndex = null },
        onRotate = { filterOriginal = null; replacePage(index, ImagePageEditor.rotate90(page.bitmap), closeCrop = false) }
      )
    }
  }

  if (showFilters) {
    FilterPickerDialog(
      selected = filterMode,
      theme = theme,
      onPick = { mode ->
        vibrate()
        applyFilterMode(mode)
        showFilters = false
      },
      onDismiss = { showFilters = false }
    )
  }

  if (showAdjust) {
    val page = pages.getOrNull(safeSelected)
    BrightnessDialog(
      value = brightness,
      theme = theme,
      onValueChange = { brightness = it },
      onApply = {
        showAdjust = false
        if (page != null) {
          replacePage(safeSelected, ImagePageEditor.adjustBrightness(page.bitmap, brightness), closeCrop = true)
          brightness = 0f
        }
      },
      onDismiss = { showAdjust = false; brightness = 0f }
    )
  }

  // Done screen is the full screen — the file is visible immediately,
  // no scrolling down to a result card.
  if (showDone && doneFile != null) {
    DoneScreen(
      file = doneFile!!,
      theme = theme,
      onBackHome = onBack,
      onOpen = { ShareHelper.openFile(ctx, it, "application/pdf") }
    )
    return
  }

  ToolScreenContainer("Images to PDF", theme, onBack) {
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
      Text("Select photos from gallery", fontFamily = PixelFont, fontSize = 12.sp, color = theme.text)
      Spacer(Modifier.height(14.dp))

      if (pages.isEmpty()) {
        Button(
          onClick = {
            vibrate()
            picker.launch("image/*")
          },
          modifier = Modifier.fillMaxWidth().height(48.dp),
          colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
          shape = RoundedCornerShape(8.dp)
        ) {
          Text("CHOOSE IMAGES", fontFamily = PixelFont, color = theme.textOnPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
      } else {
        BigPagePreview(
          page = pages.getOrNull(safeSelected),
          label = "Page ${safeSelected + 1}",
          theme = theme,
          onCrop = { cropIndex = safeSelected },
          onDelete = { vibrate(); deleteSelected() },
          onFilter = { showFilters = true },
          onAdjust = { showAdjust = true }
        )
        Spacer(Modifier.height(8.dp))
        Text(
          "Page ${safeSelected + 1} of ${pages.size}",
          fontFamily = PixelFont, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = theme.text
        )
        Spacer(Modifier.height(10.dp))
        PageThumbnailStrip(
          pages = pages,
          selectedIndex = safeSelected,
          theme = theme,
          onSelect = { selectedIndex = it },
          onMove = { from, to ->
            pages = ImagePageEditor.move(pages, from, to)
            selectedIndex = to
          },
          onAddPages = {
            vibrate()
            picker.launch("image/*")
          }
        )

        Spacer(Modifier.height(14.dp))
        Text("PAGE SIZE", fontFamily = PixelFont, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = theme.primaryDark)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          listOf("A4", "Letter", "Original").forEach { size ->
            Phase6Chip(size, pageSize == size, theme) {
              vibrate()
              pageSize = size
            }
          }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          Phase6Chip("Page numbers", pageNumbers, theme) {
            vibrate()
            pageNumbers = !pageNumbers
          }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
          value = watermark,
          onValueChange = { watermark = it },
          label = { Text("Watermark (optional)", fontFamily = PixelFont, fontSize = 11.sp) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(8.dp)
        )
        Spacer(Modifier.height(12.dp))
        Text("IMAGE QUALITY", fontFamily = PixelFont, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = theme.primaryDark)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          listOf("High", "Medium", "Low").forEach { q ->
            Phase6Chip(q, quality == q, theme) {
              vibrate()
              quality = q
            }
          }
        }
      }

      Spacer(Modifier.height(16.dp))
      if (isProcessing) {
        LinearProgressIndicator(Modifier.fillMaxWidth(), color = theme.primary)
        Spacer(Modifier.height(8.dp))
        Text("Creating PDF on IO thread...", fontFamily = PixelFont, fontSize = 11.sp, color = theme.text)
      }
    }

    SlideToAction(
      label = "Slide to Convert",
      theme = theme,
      enabled = !isProcessing && pages.isNotEmpty(),
      onSlideComplete = {
        vibrate()
        buildPdf()
      }
    )
  }
}

// 2. SCAN TO PDF — Phase 6: same redesign as Images to PDF (big preview
// showing the selected page, left-corner Crop/Delete/Filter/Adjust stack,
// thumbnail strip with big "+" tile, Slide to Save PDF, haptic on slide).
// CAMERA: opening Scan opens the camera directly (ScanCameraScreen, full
// screen) while no pages exist yet. After pages exist the preview is shown;
// the "+" tile offers Camera / Gallery. Auto-capture toggle is passed to
// the camera screen; batch scan = the camera stays open after each capture
// (ScanCameraScreen stays open; onCaptured only appends a page).
@Composable
fun ScanToPdfScreen(theme: ChamiTheme, onBack: () -> Unit) {
  val ctx = LocalContext.current
  val haptic = LocalHapticFeedback.current
  val tm = remember { ThemeManager(ctx) }
  val scope = rememberCoroutineScope()
  var pages by remember { mutableStateOf<List<PageItem>>(emptyList()) }
  var selectedPage by remember { mutableStateOf(0) }
  var filterMode by remember { mutableStateOf(0) }
  var showFilters by remember { mutableStateOf(false) }
  var scanOriginal by remember { mutableStateOf<Bitmap?>(null) }
  var autoQuad by remember { mutableStateOf<List<Offset>?>(null) }
  var isProcessing by remember { mutableStateOf(false) }
  var cropIndex by remember { mutableStateOf<Int?>(null) }
  var editBusy by remember { mutableStateOf(false) }
  var showAdjust by remember { mutableStateOf(false) }
  var brightness by remember { mutableStateOf(0f) }
  var autoCapture by remember { mutableStateOf(false) }
  var showCamera by remember { mutableStateOf(false) }
  var showAddSource by remember { mutableStateOf(false) }

  // After-convert flow: build -> save picker opens directly -> DoneScreen.
  var pendingSaveFile by remember { mutableStateOf<File?>(null) }
  var doneFile by remember { mutableStateOf<File?>(null) }
  var showDone by remember { mutableStateOf(false) }
  var launchSaveFor by remember { mutableStateOf<File?>(null) }
  val saveLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.CreateDocument("application/pdf")
  ) { uri ->
    val file = pendingSaveFile
    if (uri != null && file != null) {
      if (SaveLocation.copyToUri(ctx, file, uri)) {
        Toast.makeText(ctx, "Saved to chosen folder", Toast.LENGTH_SHORT).show()
      } else {
        Toast.makeText(ctx, "Could not save to chosen folder", Toast.LENGTH_SHORT).show()
      }
    }
    if (file != null) {
      doneFile = file
      showDone = true
    }
  }
  LaunchedEffect(launchSaveFor) {
    val f = launchSaveFor
    if (f != null) {
      pendingSaveFile = f
      saveLauncher.launch(f.name)
      launchSaveFor = null
    }
  }

  val safeSelected = selectedPage.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
  val filterNames = listOf("Original", "B&W", "Grayscale", "Enhance")

  fun vibrate() {
    if (tm.hapticOn()) {
      try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
    }
  }

  // Runtime camera permission launcher.
  val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
    if (isGranted) {
      showCamera = true
    } else {
      Toast.makeText(ctx, "Camera permission needed. You can also pick from gallery.", Toast.LENGTH_LONG).show()
    }
  }

  fun requestCamera() {
    val permission = Manifest.permission.CAMERA
    if (ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED) {
      showCamera = true
    } else {
      permissionLauncher.launch(permission)
    }
  }

  // Open the camera directly on first entry (no pages yet).
  LaunchedEffect(Unit) {
    if (pages.isEmpty()) requestCamera()
  }

  // Apply an edited bitmap to one page (crop / rotate / filter / adjust).
  // Declared before applyFilterMode — local funs are only visible after
  // their declaration point.
  fun replacePage(index: Int, newBitmap: Bitmap, closeCrop: Boolean = true) {
    val page = pages.getOrNull(index) ?: return
    if (editBusy) return
    editBusy = true
    scope.launch {
      try {
        val newUri = ImagePageEditor.bitmapToUri(
          ctx, newBitmap, "scan_${index}_${System.currentTimeMillis()}.jpg"
        )
        val updated = pages.toMutableList()
        if (index in updated.indices) {
          updated[index] = PageItem(newUri, newBitmap, cropped = true)
          pages = updated
        }
        if (closeCrop) cropIndex = null
      } catch (_: Exception) {
        Toast.makeText(ctx, "Could not apply that edit", Toast.LENGTH_SHORT).show()
      }
      editBusy = false
    }
  }

  fun applyFilterMode(mode: Int) {
    val page = pages.getOrNull(safeSelected) ?: return
    if (scanOriginal == null) scanOriginal = page.bitmap
    val base = scanOriginal ?: page.bitmap
    filterMode = mode
    if (mode == 0) replacePage(safeSelected, base, closeCrop = true)
    else replacePage(safeSelected, ImagePageEditor.applyFilter(base, mode), closeCrop = true)
  }

  fun deleteSelected() {
    if (pages.isEmpty()) return
    pages = pages.filterIndexed { i, _ -> i != safeSelected }
    selectedPage = (safeSelected - 1).coerceAtLeast(0)
    scanOriginal = null
    filterMode = 0
  }

  // Filters never stack and Original restores the page: reset when the
  // selected page changes.
  LaunchedEffect(safeSelected) { scanOriginal = null; filterMode = 0; showFilters = false }

  // Gallery: multi-select. Each selected image APPENDS a page.
  val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selected ->
    if (selected.isNotEmpty()) {
      showDone = false
      doneFile = null
      scope.launch(Dispatchers.IO) {
        val added = selected.mapNotNull { uri ->
          PdfEngine.loadBitmap(ctx, uri, 1600)?.let { bmp -> PageItem(uri, bmp) }
        }
        withContext(Dispatchers.Main) {
          val firstNew = pages.size
          pages = pages + added
          selectedPage = firstNew
        }
      }
    }
  }

  // Builds the PDF from the CURRENT page order and Uris.
  fun buildPdf() {
    if (pages.isEmpty()) {
      Toast.makeText(ctx, "Capture or select a document first", Toast.LENGTH_SHORT).show()
      return
    }
    isProcessing = true
    scope.launch {
      val out = PdfEngine.outFile(ctx, "Scan_${System.currentTimeMillis()}.pdf")
      val ok = withContext(Dispatchers.IO) {
        PdfEngine.imagesToPdf(ctx, pages.map { it.uri }, out)
      }
      isProcessing = false
      if (ok) launchSaveFor = out
      else Toast.makeText(ctx, "Failed to create PDF", Toast.LENGTH_SHORT).show()
    }
  }

  // Crop editor for the selected page.
  cropIndex?.let { index ->
    val page = pages.getOrNull(index)
    if (page != null) {
      CropEditorDialog(
        bitmap = page.bitmap,
        theme = theme,
        onApply = { bmp -> scanOriginal = null; replacePage(index, bmp, closeCrop = true) },
        onDismiss = { if (!editBusy) { cropIndex = null; autoQuad = null } },
        onRotate = { scanOriginal = null; replacePage(index, ImagePageEditor.rotate90(page.bitmap), closeCrop = false) },
        initialQuad = autoQuad
      )
    }
  }

  if (showFilters) {
    FilterPickerDialog(
      selected = filterMode,
      theme = theme,
      onPick = { mode ->
        vibrate()
        applyFilterMode(mode)
        showFilters = false
      },
      onDismiss = { showFilters = false }
    )
  }

  if (showAdjust) {
    val page = pages.getOrNull(safeSelected)
    BrightnessDialog(
      value = brightness,
      theme = theme,
      onValueChange = { brightness = it },
      onApply = {
        showAdjust = false
        if (page != null) {
          replacePage(safeSelected, ImagePageEditor.adjustBrightness(page.bitmap, brightness), closeCrop = true)
          brightness = 0f
        }
      },
      onDismiss = { showAdjust = false; brightness = 0f }
    )
  }

  if (showAddSource) {
    AddPageSourceDialog(
      theme = theme,
      onCamera = {
        showAddSource = false
        requestCamera()
      },
      onGallery = {
        showAddSource = false
        galleryLauncher.launch("image/*")
      },
      onDismiss = { showAddSource = false }
    )
  }

  // Done screen is the full screen — the file is visible immediately.
  if (showDone && doneFile != null) {
    DoneScreen(
      file = doneFile!!,
      theme = theme,
      onBackHome = onBack,
      onOpen = { ShareHelper.openFile(ctx, it, "application/pdf") }
    )
    return
  }

  // Camera first: while no pages exist yet the camera opens directly.
  // Batch scan: onCaptured only appends — the camera screen stays open
  // until the user closes it (onClose), then the preview is shown.
  if (showCamera) {
    ScanCameraScreen(
      theme = theme,
      autoCapture = autoCapture,
      onCaptured = { bmp ->
        scope.launch(Dispatchers.IO) {
          val f = File(ctx.cacheDir, "scan_${System.currentTimeMillis()}.jpg")
          FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
          withContext(Dispatchers.Main) {
            val firstNew = pages.size
            pages = pages + PageItem(Uri.fromFile(f), bmp)
            selectedPage = firstNew
          }
        }
      },
      onGallery = {
        showCamera = false
        galleryLauncher.launch("image/*")
      },
      onClose = { showCamera = false }
    )
    return
  }

  ToolScreenContainer("Scan to PDF", theme, onBack) {
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
      if (pages.isEmpty()) {
        Text("Point the camera at a page, or pick pages from the gallery.", fontFamily = PixelFont, fontSize = 12.sp, color = theme.text)
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(
            onClick = {
              vibrate()
              requestCamera()
            },
            modifier = Modifier.weight(1f).height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
            shape = RoundedCornerShape(8.dp)
          ) {
            Text("📷 CAMERA", fontFamily = PixelFont, color = theme.textOnPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
          }
          Button(
            onClick = {
              vibrate()
              galleryLauncher.launch("image/*")
            },
            modifier = Modifier.weight(1f).height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
            shape = RoundedCornerShape(8.dp)
          ) {
            Text("🖼️ GALLERY", fontFamily = PixelFont, color = theme.textOnPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
          }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          Phase6Chip("Auto-capture", autoCapture, theme) {
            vibrate()
            autoCapture = !autoCapture
          }
        }
      } else {
        val page = pages[safeSelected]
        BigPagePreview(
          page = page,
          label = "Page ${safeSelected + 1}",
          theme = theme,
          onCrop = { cropIndex = safeSelected },
          onDelete = { vibrate(); deleteSelected() },
          onFilter = { showFilters = true },
          onAdjust = { showAdjust = true }
        )
        Spacer(Modifier.height(8.dp))
        Row(
          Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.Center,
          verticalAlignment = Alignment.CenterVertically
        ) {
          OutlinedButton(
            onClick = { if (safeSelected > 0) selectedPage = safeSelected - 1 },
            enabled = safeSelected > 0,
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
          ) { Text("‹", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = theme.text) }
          Spacer(Modifier.width(12.dp))
          Text(
            "Page ${safeSelected + 1} of ${pages.size}",
            fontFamily = PixelFont, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = theme.text
          )
          Spacer(Modifier.width(12.dp))
          OutlinedButton(
            onClick = { if (safeSelected < pages.lastIndex) selectedPage = safeSelected + 1 },
            enabled = safeSelected < pages.lastIndex,
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
          ) { Text("›", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = theme.text) }
        }
        Spacer(Modifier.height(10.dp))
        PageThumbnailStrip(
          pages = pages,
          selectedIndex = safeSelected,
          theme = theme,
          onSelect = { selectedPage = it },
          onMove = { from, to ->
            pages = ImagePageEditor.move(pages, from, to)
            selectedPage = to
          },
          onAddPages = {
            vibrate()
            showAddSource = true
          }
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedButton(
            onClick = {
              vibrate()
              deleteSelected()
              requestCamera()
            },
            modifier = Modifier.weight(1f).height(44.dp),
            shape = RoundedCornerShape(8.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, theme.cardBorder),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = theme.text)
          ) {
            Text("📷 RETAKE PAGE", fontFamily = PixelFont, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = theme.text)
          }
          OutlinedButton(
            onClick = {
              vibrate()
              val pg = pages.getOrNull(safeSelected)
              if (pg != null) {
                autoQuad = ImagePageEditor.detectDocumentQuad(pg.bitmap)
                cropIndex = safeSelected
              }
            },
            modifier = Modifier.weight(1f).height(44.dp),
            shape = RoundedCornerShape(8.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, theme.cardBorder),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = theme.text)
          ) {
            Text("AUTO-DETECT EDGES", fontFamily = PixelFont, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = theme.text)
          }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          Phase6Chip("Auto-capture", autoCapture, theme) {
            vibrate()
            autoCapture = !autoCapture
          }
          if (filterMode != 0) {
            Phase6Chip(filterNames[filterMode], true, theme) { showFilters = true }
          }
        }
      }

      Spacer(Modifier.height(16.dp))
      if (isProcessing) {
        LinearProgressIndicator(Modifier.fillMaxWidth(), color = theme.primary)
        Spacer(Modifier.height(8.dp))
        Text("Building scanned PDF document...", fontFamily = PixelFont, fontSize = 11.sp, color = theme.text)
      }
    }

    SlideToAction(
      label = "Slide to Save PDF",
      theme = theme,
      enabled = !isProcessing && pages.isNotEmpty(),
      onSlideComplete = {
        vibrate()
        buildPdf()
      }
    )
  }
}

// 3. PDF TO IMAGES — Phase 6 extras: JPG/PNG choice, resolution choice,
// page range. Renders with PdfReader.renderPageBitmap (verified API) so
// format / resolution / range are honoured — PdfEngine.pdfToImages is
// fixed JPG-85 / all-pages. Slide to Extract, then DoneScreen with the
// first extracted image (no folder picker: images go to internal storage).
@Composable
fun PdfToImagesScreen(theme: ChamiTheme, onBack: () -> Unit) {
  val ctx = LocalContext.current
  val haptic = LocalHapticFeedback.current
  val tm = remember { ThemeManager(ctx) }
  val scope = rememberCoroutineScope()
  var pdfUri by remember { mutableStateOf<Uri?>(null) }
  var totalPages by remember { mutableStateOf(0) }
  var pageOnePreview by remember { mutableStateOf<Bitmap?>(null) }
  var isProcessing by remember { mutableStateOf(false) }
  var extractedCount by remember { mutableStateOf(0) }
  var outDir by remember { mutableStateOf<File?>(null) }
  var format by remember { mutableStateOf("JPG") }
  var resolution by remember { mutableStateOf("High") }
  var rangeFrom by remember { mutableStateOf("1") }
  var rangeTo by remember { mutableStateOf("") }

  var doneFile by remember { mutableStateOf<File?>(null) }
  var showDone by remember { mutableStateOf(false) }

  fun vibrate() {
    if (tm.hapticOn()) {
      try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
    }
  }

  val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { selected ->
    pdfUri = selected
    extractedCount = 0
    pageOnePreview = null
    showDone = false
    doneFile = null
    if (selected != null) {
      scope.launch(Dispatchers.IO) {
        val n = PdfEngine.pageCount(ctx, selected)
        val bmp = PdfReader.renderPageBitmap(ctx, selected, 0, 2.0f)
        withContext(Dispatchers.Main) {
          totalPages = n
          pageOnePreview = bmp
          rangeFrom = "1"
          rangeTo = "$n"
        }
      }
    }
  }

  fun extract() {
    val u = pdfUri ?: return
    isProcessing = true
    scope.launch {
      val dir = File(ctx.getExternalFilesDir(null), "Chami/Images_${System.currentTimeMillis()}")
      if (!dir.exists()) dir.mkdirs()
      val maxPages = totalPages.coerceAtLeast(1)
      val from = (rangeFrom.toIntOrNull() ?: 1).coerceIn(1, maxPages)
      val to = (rangeTo.toIntOrNull() ?: maxPages).coerceIn(from, maxPages)
      val scale = when (resolution) { "Low" -> 1.0f; "Medium" -> 1.5f; else -> 2.0f }
      val fmt = if (format == "PNG") Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
      val ext = if (format == "PNG") "png" else "jpg"
      var count = 0
      var first: File? = null
      withContext(Dispatchers.IO) {
        for (p in from..to) {
          val bmp = PdfReader.renderPageBitmap(ctx, u, p - 1, scale) ?: continue
          val f = File(dir, "page_$p.$ext")
          try {
            FileOutputStream(f).use { bmp.compress(fmt, 90, it) }
            count++
            if (first == null) first = f
          } catch (_: Exception) { }
          bmp.recycle()
        }
      }
      isProcessing = false
      extractedCount = count
      outDir = dir
      if (count > 0 && first != null) {
        doneFile = first
        showDone = true
      } else {
        Toast.makeText(ctx, "Could not extract images", Toast.LENGTH_SHORT).show()
      }
    }
  }

  if (showDone && doneFile != null) {
    DoneScreen(
      file = doneFile!!,
      theme = theme,
      onBackHome = onBack,
      onOpen = { ShareHelper.openFile(ctx, it, if (format == "PNG") "image/png" else "image/jpeg") }
    )
    return
  }

  ToolScreenContainer("PDF to Images", theme, onBack) {
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
      Button(
        onClick = {
          vibrate()
          picker.launch("application/pdf")
        },
        modifier = Modifier.fillMaxWidth().height(48.dp),
        colors = ButtonDefaults.buttonColors(containerColor = theme.card),
        shape = RoundedCornerShape(8.dp)
      ) {
        Text(if (pdfUri != null) "PDF SELECTED ($totalPages PAGES) ✓" else "SELECT PDF", fontFamily = PixelFont, color = theme.primaryDark, fontSize = 12.sp)
      }

      if (pageOnePreview != null) {
        Spacer(Modifier.height(16.dp))
        Text("PREVIEW (PAGE 1 OF $totalPages):", fontFamily = PixelFont, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = theme.primaryDark)
        Spacer(Modifier.height(8.dp))
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(theme.card)
            .padding(10.dp),
          horizontalAlignment = Alignment.CenterHorizontally
        ) {
          Image(
            bitmap = pageOnePreview!!.asImageBitmap(),
            contentDescription = "Page 1 Preview",
            modifier = Modifier
              .fillMaxWidth()
              .heightIn(max = 420.dp)
              .clip(RoundedCornerShape(6.dp)),
            contentScale = ContentScale.Fit
          )
        }
      }

      if (pdfUri != null) {
        Spacer(Modifier.height(14.dp))
        Text("FORMAT", fontFamily = PixelFont, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = theme.primaryDark)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          listOf("JPG", "PNG").forEach { f ->
            Phase6Chip(f, format == f, theme) {
              vibrate()
              format = f
            }
          }
        }
        Spacer(Modifier.height(12.dp))
        Text("RESOLUTION", fontFamily = PixelFont, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = theme.primaryDark)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          listOf("Low", "Medium", "High").forEach { r ->
            Phase6Chip(r, resolution == r, theme) {
              vibrate()
              resolution = r
            }
          }
        }
        Spacer(Modifier.height(12.dp))
        Text("PAGE RANGE", fontFamily = PixelFont, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = theme.primaryDark)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(
            value = rangeFrom,
            onValueChange = { rangeFrom = it.filter { ch -> ch.isDigit() } },
            label = { Text("From", fontFamily = PixelFont, fontSize = 10.sp) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f)
          )
          OutlinedTextField(
            value = rangeTo,
            onValueChange = { rangeTo = it.filter { ch -> ch.isDigit() } },
            label = { Text("To", fontFamily = PixelFont, fontSize = 10.sp) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f)
          )
        }
      }

      Spacer(Modifier.height(16.dp))
      if (isProcessing) {
        LinearProgressIndicator(Modifier.fillMaxWidth(), color = theme.primary)
        Spacer(Modifier.height(8.dp))
        Text("Rendering pages...", fontFamily = PixelFont, fontSize = 11.sp, color = theme.text)
      }

      if (extractedCount > 0 && outDir != null) {
        Column(
          Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(theme.card)
            .padding(14.dp)
        ) {
          Text("Success ✓", fontFamily = PixelFont, fontWeight = FontWeight.Bold, color = theme.primaryDark, fontSize = 14.sp)
          Spacer(Modifier.height(4.dp))
          Text("Extracted $extractedCount pages as $format", fontFamily = PixelFont, color = theme.text, fontSize = 12.sp)
        }
      }
    }

    SlideToAction(
      label = "Slide to Extract",
      theme = theme,
      enabled = !isProcessing && pdfUri != null,
      onSlideComplete = {
        vibrate()
        extract()
      }
    )
  }
}

// 4. SPLIT PDF — Phase 6 extras: split by page range (from/to fields) and
// split every 5 pages (one file per 5-page chunk; the first chunk goes
// through the save picker + DoneScreen, the rest stay in internal storage
// and are counted in a toast). Selected-pages mode is kept as before.
@Composable
fun SplitPdfScreen(theme: ChamiTheme, onBack: () -> Unit) {
  val ctx = LocalContext.current
  val haptic = LocalHapticFeedback.current
  val tm = remember { ThemeManager(ctx) }
  val scope = rememberCoroutineScope()
  var pdfUri by remember { mutableStateOf<Uri?>(null) }
  var totalPages by remember { mutableStateOf(0) }
  val selectedPages = remember { mutableStateListOf<Int>() }
  var previewPageIndex by remember { mutableStateOf(1) }
  var pagePreviewBitmap by remember { mutableStateOf<Bitmap?>(null) }
  var isProcessing by remember { mutableStateOf(false) }
  var splitMode by remember { mutableStateOf("Selected") }
  var rangeFrom by remember { mutableStateOf("1") }
  var rangeTo by remember { mutableStateOf("") }

  var pendingSaveFile by remember { mutableStateOf<File?>(null) }
  var doneFile by remember { mutableStateOf<File?>(null) }
  var showDone by remember { mutableStateOf(false) }
  var launchSaveFor by remember { mutableStateOf<File?>(null) }
  val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
    val file = pendingSaveFile
    if (uri != null && file != null) {
      SaveLocation.copyToUri(ctx, file, uri)
    }
    if (file != null) {
      doneFile = file
      showDone = true
    }
  }
  LaunchedEffect(launchSaveFor) {
    val f = launchSaveFor
    if (f != null) {
      pendingSaveFile = f
      saveLauncher.launch(f.name)
      launchSaveFor = null
    }
  }

  fun vibrate() {
    if (tm.hapticOn()) {
      try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
    }
  }

  fun loadPreviewForPage(page: Int) {
    val u = pdfUri ?: return
    scope.launch(Dispatchers.IO) {
      val bmp = PdfReader.renderPageBitmap(ctx, u, page - 1, 2.0f)
      withContext(Dispatchers.Main) { pagePreviewBitmap = bmp }
    }
  }

  val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { selected ->
    pdfUri = selected
    selectedPages.clear()
    showDone = false
    doneFile = null
    pagePreviewBitmap = null
    if (selected != null) {
      scope.launch(Dispatchers.IO) {
        val n = PdfEngine.pageCount(ctx, selected)
        val bmp = PdfReader.renderPageBitmap(ctx, selected, 0, 2.0f)
        withContext(Dispatchers.Main) {
          totalPages = n
          for (i in 1..n) selectedPages.add(i)
          previewPageIndex = 1
          pagePreviewBitmap = bmp
          rangeFrom = "1"
          rangeTo = "$n"
        }
      }
    }
  }

  fun split() {
    val u = pdfUri ?: return
    if (splitMode == "Selected" && selectedPages.isEmpty()) {
      Toast.makeText(ctx, "Select at least 1 page to keep", Toast.LENGTH_SHORT).show()
      return
    }
    isProcessing = true
    scope.launch {
      val files = mutableListOf<File>()
      withContext(Dispatchers.IO) {
        when (splitMode) {
          "Every 5" -> {
            var start = 1
            while (start <= totalPages) {
              val end = minOf(start + 4, totalPages)
              val out = PdfEngine.outFile(ctx, "Split_${start}-${end}_${System.currentTimeMillis()}.pdf")
              if (PdfEngine.copyPages(ctx, listOf(u to (start..end).toSet()), out)) files.add(out)
              start = end + 1
            }
          }
          "Range" -> {
            val f = (rangeFrom.toIntOrNull() ?: 1).coerceIn(1, totalPages.coerceAtLeast(1))
            val t = (rangeTo.toIntOrNull() ?: totalPages).coerceIn(f, totalPages.coerceAtLeast(1))
            val out = PdfEngine.outFile(ctx, "Split_${f}-${t}_${System.currentTimeMillis()}.pdf")
            if (PdfEngine.copyPages(ctx, listOf(u to (f..t).toSet()), out)) files.add(out)
          }
          else -> {
            val out = PdfEngine.outFile(ctx, "Split_${System.currentTimeMillis()}.pdf")
            if (PdfEngine.copyPages(ctx, listOf(u to selectedPages.toSet()), out)) files.add(out)
          }
        }
      }
      isProcessing = false
      if (files.isNotEmpty()) {
        if (files.size > 1) {
          Toast.makeText(ctx, "Created ${files.size} files — first one opens for saving", Toast.LENGTH_LONG).show()
        }
        launchSaveFor = files[0]
      } else {
        Toast.makeText(ctx, "Failed to split PDF", Toast.LENGTH_SHORT).show()
      }
    }
  }

  if (showDone && doneFile != null) {
    DoneScreen(
      file = doneFile!!,
      theme = theme,
      onBackHome = onBack,
      onOpen = { ShareHelper.openFile(ctx, it, "application/pdf") }
    )
    return
  }

  ToolScreenContainer("Split PDF", theme, onBack) {
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
      Button(
        onClick = {
          vibrate()
          picker.launch("application/pdf")
        },
        modifier = Modifier.fillMaxWidth().height(48.dp),
        colors = ButtonDefaults.buttonColors(containerColor = theme.card),
        shape = RoundedCornerShape(8.dp)
      ) {
        Text(if (pdfUri != null) "PDF ($totalPages pages) ✓" else "SELECT PDF", fontFamily = PixelFont, color = theme.primaryDark, fontSize = 12.sp)
      }

      if (totalPages > 0) {
        Spacer(Modifier.height(12.dp))
        Text("SPLIT MODE", fontFamily = PixelFont, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = theme.primaryDark)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          listOf("Selected", "Range", "Every 5").forEach { m ->
            Phase6Chip(m, splitMode == m, theme) {
              vibrate()
              splitMode = m
            }
          }
        }
        if (splitMode == "Range") {
          Spacer(Modifier.height(10.dp))
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
              value = rangeFrom,
              onValueChange = { rangeFrom = it.filter { ch -> ch.isDigit() } },
              label = { Text("From page", fontFamily = PixelFont, fontSize = 10.sp) },
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
              modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
              value = rangeTo,
              onValueChange = { rangeTo = it.filter { ch -> ch.isDigit() } },
              label = { Text("To page", fontFamily = PixelFont, fontSize = 10.sp) },
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
              modifier = Modifier.weight(1f)
            )
          }
        }
        Spacer(Modifier.height(12.dp))
        Text("Pages to keep (${selectedPages.size}/$totalPages) - Tap to preview/toggle:", fontFamily = PixelFont, fontSize = 11.sp, color = theme.text)
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          items((1..totalPages).toList()) { p ->
            val isSelected = selectedPages.contains(p)
            val isPreviewing = previewPageIndex == p
            Box(
              Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (isSelected) theme.primary else theme.card)
                .border(if (isPreviewing) 2.dp else 0.dp, theme.primaryDark, RoundedCornerShape(6.dp))
                .clickable {
                  vibrate()
                  previewPageIndex = p
                  loadPreviewForPage(p)
                  if (isSelected) selectedPages.remove(p) else selectedPages.add(p)
                },
              contentAlignment = Alignment.Center
            ) {
              Text(
                text = "$p",
                fontFamily = PixelFont,
                fontWeight = FontWeight.Bold,
                color = if (isSelected) theme.textOnPrimary else theme.text,
                fontSize = 12.sp
              )
            }
          }
        }

        if (pagePreviewBitmap != null) {
          Spacer(Modifier.height(14.dp))
          Text("PREVIEWING PAGE $previewPageIndex:", fontFamily = PixelFont, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = theme.primaryDark)
          Spacer(Modifier.height(8.dp))
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(8.dp))
              .background(theme.card)
              .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
          ) {
            Image(
              bitmap = pagePreviewBitmap!!.asImageBitmap(),
              contentDescription = "Page $previewPageIndex",
              modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .clip(RoundedCornerShape(6.dp)),
              contentScale = ContentScale.Fit
            )
          }
        }
      }

      Spacer(Modifier.height(16.dp))
      if (isProcessing) {
        LinearProgressIndicator(Modifier.fillMaxWidth(), color = theme.primary)
        Spacer(Modifier.height(8.dp))
        Text("Extracting pages at 2x crisp rendering...", fontFamily = PixelFont, fontSize = 11.sp, color = theme.text)
      }
    }

    SlideToAction(
      label = "Slide to Split",
      theme = theme,
      enabled = !isProcessing && pdfUri != null &&
        (splitMode != "Selected" || selectedPages.isNotEmpty()),
      onSlideComplete = {
        vibrate()
        split()
      }
    )
  }
}

// 5. MERGE PDF — Phase 6 extras: reorder the selected PDFs before merging
// (up/down buttons per file — chosen over long-press drag so the order is
// exact and unambiguous) + first-page thumbnail preview of each file.
// Picker appends instead of replacing, so files can be added in batches.
@Composable
fun MergePdfScreen(theme: ChamiTheme, onBack: () -> Unit) {
  val ctx = LocalContext.current
  val haptic = LocalHapticFeedback.current
  val tm = remember { ThemeManager(ctx) }
  val scope = rememberCoroutineScope()
  var pdfUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
  var pdfPreviews by remember { mutableStateOf<List<Pair<Int, Bitmap?>>>(emptyList()) }
  var isProcessing by remember { mutableStateOf(false) }

  var pendingSaveFile by remember { mutableStateOf<File?>(null) }
  var doneFile by remember { mutableStateOf<File?>(null) }
  var showDone by remember { mutableStateOf(false) }
  var launchSaveFor by remember { mutableStateOf<File?>(null) }
  val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
    val file = pendingSaveFile
    if (uri != null && file != null) {
      SaveLocation.copyToUri(ctx, file, uri)
    }
    if (file != null) {
      doneFile = file
      showDone = true
    }
  }
  LaunchedEffect(launchSaveFor) {
    val f = launchSaveFor
    if (f != null) {
      pendingSaveFile = f
      saveLauncher.launch(f.name)
      launchSaveFor = null
    }
  }

  fun vibrate() {
    if (tm.hapticOn()) {
      try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
    }
  }

  fun refreshPreviews(uris: List<Uri>) {
    scope.launch(Dispatchers.IO) {
      val previews = uris.map { u ->
        val count = PdfEngine.pageCount(ctx, u)
        val bmp = PdfReader.renderPageBitmap(ctx, u, 0, 1.5f)
        Pair(count, bmp)
      }
      withContext(Dispatchers.Main) { pdfPreviews = previews }
    }
  }

  val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selected ->
    if (selected.isNotEmpty()) {
      showDone = false
      doneFile = null
      val combined = pdfUris + selected
      pdfUris = combined
      refreshPreviews(combined)
    }
  }

  fun moveFile(from: Int, to: Int) {
    if (from !in pdfUris.indices || to !in pdfUris.indices) return
    val m = pdfUris.toMutableList()
    val item = m.removeAt(from)
    m.add(to, item)
    pdfUris = m
    val p = pdfPreviews.toMutableList()
    if (from in p.indices && to in p.indices) {
      val pi = p.removeAt(from)
      p.add(to, pi)
      pdfPreviews = p
    }
  }

  fun merge() {
    if (pdfUris.size < 2) {
      Toast.makeText(ctx, "Select at least 2 PDFs to merge", Toast.LENGTH_SHORT).show()
      return
    }
    isProcessing = true
    scope.launch {
      val out = PdfEngine.outFile(ctx, "Merged_${System.currentTimeMillis()}.pdf")
      val sources = pdfUris.map { it to null }
      val ok = withContext(Dispatchers.IO) {
        PdfEngine.copyPages(ctx, sources, out)
      }
      isProcessing = false
      if (ok) launchSaveFor = out
      else Toast.makeText(ctx, "Failed to merge PDFs", Toast.LENGTH_SHORT).show()
    }
  }

  if (showDone && doneFile != null) {
    DoneScreen(
      file = doneFile!!,
      theme = theme,
      onBackHome = onBack,
      onOpen = { ShareHelper.openFile(ctx, it, "application/pdf") }
    )
    return
  }

  ToolScreenContainer("Merge PDF", theme, onBack) {
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
      Button(
        onClick = {
          vibrate()
          picker.launch("application/pdf")
        },
        modifier = Modifier.fillMaxWidth().height(48.dp),
        colors = ButtonDefaults.buttonColors(containerColor = theme.card),
        shape = RoundedCornerShape(8.dp)
      ) {
        Text("CHOOSE PDFS (${pdfUris.size} selected)", fontFamily = PixelFont, color = theme.primaryDark, fontSize = 12.sp)
      }

      if (pdfPreviews.isNotEmpty()) {
        Spacer(Modifier.height(14.dp))
        val totalCombinedPages = pdfPreviews.sumOf { it.first }
        Text("PREVIEW: $totalCombinedPages TOTAL PAGES COMBINED", fontFamily = PixelFont, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = theme.primaryDark)
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          pdfPreviews.forEachIndexed { index, pair ->
            Row(
              Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(theme.card)
                .padding(8.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              pair.second?.let { bmp ->
                Image(
                  bitmap = bmp.asImageBitmap(),
                  contentDescription = "PDF ${index + 1}",
                  modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(4.dp)),
                  contentScale = ContentScale.Fit
                )
              }
              Spacer(Modifier.width(10.dp))
              Column(Modifier.weight(1f)) {
                Text("Doc ${index + 1}", fontFamily = PixelFont, fontSize = 12.sp, color = theme.text, fontWeight = FontWeight.Bold)
                Text("${pair.first} pages", fontFamily = PixelFont, fontSize = 10.sp, color = theme.textSecondary)
              }
              Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                TextButton(
                  onClick = {
                    vibrate()
                    moveFile(index, index - 1)
                  },
                  enabled = index > 0,
                  contentPadding = PaddingValues(4.dp)
                ) {
                  Text("↑", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = theme.text)
                }
                TextButton(
                  onClick = {
                    vibrate()
                    moveFile(index, index + 1)
                  },
                  enabled = index < pdfUris.lastIndex,
                  contentPadding = PaddingValues(4.dp)
                ) {
                  Text("↓", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = theme.text)
                }
              }
              TextButton(
                onClick = {
                  vibrate()
                  val m = pdfUris.toMutableList()
                  m.removeAt(index)
                  pdfUris = m
                  refreshPreviews(m)
                },
                contentPadding = PaddingValues(4.dp)
              ) {
                Text("✕", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = theme.textSecondary)
              }
            }
          }
        }
      }

      Spacer(Modifier.height(16.dp))
      if (isProcessing) {
        LinearProgressIndicator(Modifier.fillMaxWidth(), color = theme.primary)
        Spacer(Modifier.height(8.dp))
        Text("Merging PDF pages on IO thread...", fontFamily = PixelFont, fontSize = 11.sp, color = theme.text)
      }
    }

    SlideToAction(
      label = "Slide to Merge",
      theme = theme,
      enabled = !isProcessing && pdfUris.size >= 2,
      onSlideComplete = {
        vibrate()
        merge()
      }
    )
  }
}

// 6. COMPRESS KB (DUAL TAB: IMAGE / PDF) — Phase 6 extras: PDF tab gets a
// quality slider (0-100) mapped onto the existing PdfCompress presets
// (see compressPdfWithQuality — the compressor re-renders pages as images,
// so text is rasterised; no image-only re-encode helper exists). Slide to
// Compress on both tabs, then save picker + DoneScreen. NO ads.
@Composable
fun CompressKbScreen(theme: ChamiTheme, onBack: () -> Unit) {
  val ctx = LocalContext.current
  val haptic = LocalHapticFeedback.current
  val tm = remember { ThemeManager(ctx) }
  val scope = rememberCoroutineScope()

  var selectedTab by remember { mutableStateOf(0) } // 0: IMAGE, 1: PDF

  // --- IMAGE TAB STATE ---
  var imageUri by remember { mutableStateOf<Uri?>(null) }
  var imageBitmap by remember { mutableStateOf<Bitmap?>(null) }
  var imageOrigKb by remember { mutableStateOf(0L) }
  var targetKb by remember { mutableStateOf(200) }
  var isCustomKb by remember { mutableStateOf(false) }
  var customInput by remember { mutableStateOf("150") }
  var isImageProcessing by remember { mutableStateOf(false) }

  // --- PDF TAB STATE ---
  var pdfUri by remember { mutableStateOf<Uri?>(null) }
  var pdfOrigBytes by remember { mutableStateOf(0L) }
  var pdfTotalPages by remember { mutableStateOf(0) }
  var pdfPreviewBmp by remember { mutableStateOf<Bitmap?>(null) }
  var pdfQuality by remember { mutableStateOf(50) }
  var isPdfProcessing by remember { mutableStateOf(false) }
  var pdfProgressText by remember { mutableStateOf("") }
  var pdfResultKb by remember { mutableStateOf(0L) }

  // After-convert flow (two mimes: image + pdf).
  var pendingSaveFile by remember { mutableStateOf<File?>(null) }
  var doneFile by remember { mutableStateOf<File?>(null) }
  var doneMime by remember { mutableStateOf("application/pdf") }
  var showDone by remember { mutableStateOf(false) }
  var launchSavePdfFor by remember { mutableStateOf<File?>(null) }
  var launchSaveImgFor by remember { mutableStateOf<File?>(null) }
  val pdfSaveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
    val file = pendingSaveFile
    if (uri != null && file != null) {
      SaveLocation.copyToUri(ctx, file, uri)
    }
    if (file != null) {
      doneFile = file
      doneMime = "application/pdf"
      showDone = true
    }
  }
  val imgSaveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { uri ->
    val file = pendingSaveFile
    if (uri != null && file != null) {
      SaveLocation.copyToUri(ctx, file, uri)
    }
    if (file != null) {
      doneFile = file
      doneMime = "image/jpeg"
      showDone = true
    }
  }
  LaunchedEffect(launchSavePdfFor) {
    val f = launchSavePdfFor
    if (f != null) {
      pendingSaveFile = f
      pdfSaveLauncher.launch(f.name)
      launchSavePdfFor = null
    }
  }
  LaunchedEffect(launchSaveImgFor) {
    val f = launchSaveImgFor
    if (f != null) {
      pendingSaveFile = f
      imgSaveLauncher.launch(f.name)
      launchSaveImgFor = null
    }
  }

  fun vibrate() {
    if (tm.hapticOn()) {
      try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
    }
  }

  val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { selected ->
    imageUri = selected
    showDone = false
    doneFile = null
    imageBitmap = null
    if (selected != null) {
      scope.launch(Dispatchers.IO) {
        val bmp = PdfEngine.loadBitmap(ctx, selected, 1200)
        var size = 0L
        try {
          ctx.contentResolver.openInputStream(selected)?.use { size = it.available() / 1024L }
        } catch (_: Exception) {}
        withContext(Dispatchers.Main) {
          imageBitmap = bmp
          imageOrigKb = size
        }
      }
    }
  }

  val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { selected ->
    pdfUri = selected
    showDone = false
    doneFile = null
    pdfPreviewBmp = null
    pdfResultKb = 0L
    if (selected != null) {
      scope.launch(Dispatchers.IO) {
        val bytes = PdfCompress.originalSizeBytes(ctx, selected)
        val pages = PdfEngine.pageCount(ctx, selected)
        val bmp = PdfReader.renderPageBitmap(ctx, selected, 0, 1.5f)
        withContext(Dispatchers.Main) {
          pdfOrigBytes = bytes
          pdfTotalPages = pages
          pdfPreviewBmp = bmp
        }
      }
    }
  }

  fun compressImage() {
    val u = imageUri ?: return
    isImageProcessing = true
    scope.launch {
      val out = PdfEngine.outFile(ctx, "Compress_${targetKb}KB_${System.currentTimeMillis()}.jpg")
      val ok = withContext(Dispatchers.IO) {
        PdfEngine.compressImageToKb(ctx, u, targetKb, out)
      }
      isImageProcessing = false
      if (ok) launchSaveImgFor = out
      else Toast.makeText(ctx, "Compression failed", Toast.LENGTH_SHORT).show()
    }
  }

  fun compressPdf() {
    val u = pdfUri ?: return
    isPdfProcessing = true
    pdfProgressText = "Compressing PDF pages..."
    scope.launch {
      val out = PdfEngine.outFile(ctx, "Compressed_${System.currentTimeMillis()}.pdf")
      val res = withContext(Dispatchers.IO) {
        compressPdfWithQuality(ctx, u, pdfQuality, out)
      }
      isPdfProcessing = false
      if (res.ok) {
        pdfResultKb = res.outKb
        launchSavePdfFor = out
      } else {
        Toast.makeText(ctx, "PDF compression failed", Toast.LENGTH_SHORT).show()
      }
    }
  }

  if (showDone && doneFile != null) {
    DoneScreen(
      file = doneFile!!,
      theme = theme,
      onBackHome = onBack,
      onOpen = { ShareHelper.openFile(ctx, it, doneMime) }
    )
    return
  }

  ToolScreenContainer("Compress KB", theme, onBack) {
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
      // TAB BAR
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(8.dp))
          .background(theme.card)
          .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
      ) {
        Box(
          modifier = Modifier
            .weight(1f)
            .height(40.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (selectedTab == 0) theme.primary else Color.Transparent)
            .clickable {
              vibrate()
              selectedTab = 0
            },
          contentAlignment = Alignment.Center
        ) {
          Text(
            "COMPRESS IMAGE",
            fontFamily = PixelFont,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            color = if (selectedTab == 0) Color.White else theme.text
          )
        }
        Box(
          modifier = Modifier
            .weight(1f)
            .height(40.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (selectedTab == 1) theme.primary else Color.Transparent)
            .clickable {
              vibrate()
              selectedTab = 1
            },
          contentAlignment = Alignment.Center
        ) {
          Text(
            "COMPRESS PDF",
            fontFamily = PixelFont,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            color = if (selectedTab == 1) Color.White else theme.text
          )
        }
      }

      Spacer(Modifier.height(14.dp))

      if (selectedTab == 0) {
        // IMAGE TAB CONTENT
        PrimaryButton(
          theme = theme,
          text = if (imageUri != null) "IMAGE CHOSEN ✓" else "SELECT IMAGE",
          height = 48.dp,
          onClick = {
            vibrate()
            imagePicker.launch("image/*")
          }
        )

        if (imageBitmap != null) {
          Spacer(Modifier.height(14.dp))
          Text("ORIGINAL IMAGE PREVIEW:", fontFamily = PixelFont, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = theme.primaryDark)
          Spacer(Modifier.height(6.dp))
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(8.dp))
              .background(theme.card)
              .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
          ) {
            Image(
              bitmap = imageBitmap!!.asImageBitmap(),
              contentDescription = "Original Preview",
              modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
                .clip(RoundedCornerShape(6.dp)),
              contentScale = ContentScale.Fit
            )
            Spacer(Modifier.height(8.dp))
            Text("Current Size: ${if (imageOrigKb > 0) "$imageOrigKb KB" else "Ready"}", fontFamily = PixelFont, fontSize = 11.sp, color = theme.text, fontWeight = FontWeight.Bold)
            Text("Target: $targetKb KB", fontFamily = PixelFont, fontSize = 10.sp, color = theme.primaryDark)
          }
        }

        Spacer(Modifier.height(14.dp))
        Text("Target Size Preset:", fontFamily = PixelFont, fontSize = 12.sp, color = theme.text)
        Spacer(Modifier.height(8.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          listOf(50, 100, 200, 500).forEach { kb ->
            val isSelected = !isCustomKb && targetKb == kb
            Box(
              Modifier
                .weight(1f)
                .height(44.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (isSelected) theme.primary else theme.card)
                .clickable {
                  vibrate()
                  isCustomKb = false
                  targetKb = kb
                },
              contentAlignment = Alignment.Center
            ) {
              Text("$kb KB", fontFamily = PixelFont, color = if (isSelected) Color.White else theme.text, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
          }
          Box(
            Modifier
              .weight(1.2f)
              .height(44.dp)
              .clip(RoundedCornerShape(8.dp))
              .background(if (isCustomKb) theme.primary else theme.card)
              .clickable {
                vibrate()
                isCustomKb = true
                val c = customInput.toIntOrNull() ?: 150
                targetKb = c.coerceAtLeast(10)
              },
            contentAlignment = Alignment.Center
          ) {
            Text("CUSTOM", fontFamily = PixelFont, color = if (isCustomKb) Color.White else theme.text, fontSize = 11.sp, fontWeight = FontWeight.Bold)
          }
        }

        if (isCustomKb) {
          Spacer(Modifier.height(12.dp))
          OutlinedTextField(
            value = customInput,
            onValueChange = {
              customInput = it.filter { ch -> ch.isDigit() }
              val c = customInput.toIntOrNull()
              if (c != null && c > 0) targetKb = c
            },
            label = { Text("Enter Target KB (e.g. 75, 150, 350)", fontFamily = PixelFont, fontSize = 10.sp) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
          )
        }

        Spacer(Modifier.height(16.dp))
        if (isImageProcessing) {
          LinearProgressIndicator(Modifier.fillMaxWidth(), color = theme.primary)
          Spacer(Modifier.height(8.dp))
          Text("Compressing to hit ${targetKb}KB...", fontFamily = PixelFont, fontSize = 11.sp, color = theme.text)
        }
      } else {
        // PDF TAB CONTENT
        PrimaryButton(
          theme = theme,
          text = if (pdfUri != null) "PDF SELECTED ($pdfTotalPages PAGES) ✓" else "SELECT PDF",
          height = 48.dp,
          onClick = {
            vibrate()
            pdfPicker.launch("application/pdf")
          }
        )

        if (pdfUri != null) {
          Spacer(Modifier.height(14.dp))
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(8.dp))
              .background(theme.card)
              .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            pdfPreviewBmp?.let { bmp ->
              Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "PDF Preview",
                modifier = Modifier
                  .size(120.dp)
                  .clip(RoundedCornerShape(6.dp)),
                contentScale = ContentScale.Fit
              )
              Spacer(Modifier.width(12.dp))
            }
            Column {
              val kb = pdfOrigBytes / 1024L
              val mb = kb / 1024f
              val sizeText = if (mb >= 1.0f) String.format("%.2f MB (%d KB)", mb, kb) else "$kb KB"
              Text("Original Size: $sizeText", fontFamily = PixelFont, fontSize = 11.sp, color = theme.text, fontWeight = FontWeight.Bold)
              Text("Total Pages: $pdfTotalPages", fontFamily = PixelFont, fontSize = 10.sp, color = theme.primaryDark)
            }
          }

          Spacer(Modifier.height(14.dp))
          Text("Quality: $pdfQuality", fontFamily = PixelFont, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = theme.text)
          Text("Lower quality = smaller file. 70+ High, 40–69 Medium, under 40 Maximum.", fontFamily = PixelFont, fontSize = 10.sp, color = theme.textSecondary)
          Slider(
            value = pdfQuality.toFloat(),
            onValueChange = { pdfQuality = it.toInt() },
            valueRange = 0f..100f,
            colors = SliderDefaults.colors(thumbColor = theme.primary, activeTrackColor = theme.primary)
          )
          Text("Pages are re-encoded as images to shrink the file.", fontFamily = PixelFont, fontSize = 10.sp, color = theme.textSecondary)
          if (pdfResultKb > 0) {
            Spacer(Modifier.height(8.dp))
            val oldKb = pdfOrigBytes / 1024L
            val savedPct = if (oldKb > 0) ((oldKb - pdfResultKb) * 100 / oldKb).coerceAtLeast(0) else 0
            Text("Last result: ${oldKb}KB → ${pdfResultKb}KB (Saved ${savedPct}%)", fontFamily = PixelFont, fontSize = 11.sp, color = theme.text, fontWeight = FontWeight.Bold)
          }
        }

        Spacer(Modifier.height(16.dp))
        if (isPdfProcessing) {
          LinearProgressIndicator(Modifier.fillMaxWidth(), color = theme.primary)
          Spacer(Modifier.height(8.dp))
          Text(if (pdfProgressText.isNotEmpty()) pdfProgressText else "Compressing PDF pages...", fontFamily = PixelFont, fontSize = 11.sp, color = theme.text)
        }
      }
    }

    if (selectedTab == 0) {
      SlideToAction(
        label = "Slide to Compress",
        theme = theme,
        enabled = !isImageProcessing && imageUri != null,
        onSlideComplete = {
          vibrate()
          compressImage()
        }
      )
    } else {
      SlideToAction(
        label = "Slide to Compress",
        theme = theme,
        enabled = !isPdfProcessing && pdfUri != null,
        onSlideComplete = {
          vibrate()
          compressPdf()
        }
      )
    }
  }
}

// 7. PASSPORT PHOTO — Slide to Generate, then save picker + DoneScreen.
// NO ads.
@Composable
fun PassportPhotoScreen(theme: ChamiTheme, onBack: () -> Unit) {
  val ctx = LocalContext.current
  val haptic = LocalHapticFeedback.current
  val tm = remember { ThemeManager(ctx) }
  val scope = rememberCoroutineScope()
  var imageUri by remember { mutableStateOf<Uri?>(null) }
  var passportPreviewBitmap by remember { mutableStateOf<Bitmap?>(null) }
  var is8UpSheet by remember { mutableStateOf(false) }
  var isProcessing by remember { mutableStateOf(false) }

  var pendingSaveFile by remember { mutableStateOf<File?>(null) }
  var doneFile by remember { mutableStateOf<File?>(null) }
  var doneMime by remember { mutableStateOf("image/jpeg") }
  var showDone by remember { mutableStateOf(false) }
  var launchSavePdfFor by remember { mutableStateOf<File?>(null) }
  var launchSaveImgFor by remember { mutableStateOf<File?>(null) }
  val pdfSaveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
    val file = pendingSaveFile
    if (uri != null && file != null) {
      SaveLocation.copyToUri(ctx, file, uri)
    }
    if (file != null) {
      doneFile = file
      doneMime = "application/pdf"
      showDone = true
    }
  }
  val imgSaveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { uri ->
    val file = pendingSaveFile
    if (uri != null && file != null) {
      SaveLocation.copyToUri(ctx, file, uri)
    }
    if (file != null) {
      doneFile = file
      doneMime = "image/jpeg"
      showDone = true
    }
  }
  LaunchedEffect(launchSavePdfFor) {
    val f = launchSavePdfFor
    if (f != null) {
      pendingSaveFile = f
      pdfSaveLauncher.launch(f.name)
      launchSavePdfFor = null
    }
  }
  LaunchedEffect(launchSaveImgFor) {
    val f = launchSaveImgFor
    if (f != null) {
      pendingSaveFile = f
      imgSaveLauncher.launch(f.name)
      launchSaveImgFor = null
    }
  }

  fun vibrate() {
    if (tm.hapticOn()) {
      try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
    }
  }

  val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { selected ->
    imageUri = selected
    showDone = false
    doneFile = null
    passportPreviewBitmap = null
    if (selected != null) {
      scope.launch(Dispatchers.IO) {
        val raw = PdfEngine.loadBitmap(ctx, selected, 1000)
        if (raw != null) {
          val side = minOf(raw.width, raw.height)
          val cropped = Bitmap.createBitmap(raw, (raw.width - side) / 2, (raw.height - side) / 2, side, side)
          val out = Bitmap.createBitmap(413, 531, Bitmap.Config.ARGB_8888)
          val c = Canvas(out)
          c.drawColor(AndroidColor.WHITE)
          c.drawBitmap(cropped, null, Rect(0, 0, 413, 531), null)
          raw.recycle()
          cropped.recycle()
          withContext(Dispatchers.Main) { passportPreviewBitmap = out }
        }
      }
    }
  }

  fun generate() {
    val u = imageUri ?: return
    isProcessing = true
    scope.launch {
      val singleOut = PdfEngine.outFile(ctx, "Passport_${System.currentTimeMillis()}.jpg")
      val ok = withContext(Dispatchers.IO) {
        PdfEngine.passportPhoto(ctx, u, singleOut)
      }
      if (ok) {
        if (is8UpSheet) {
          val sheetOut = PdfEngine.outFile(ctx, "Passport_Sheet8_${System.currentTimeMillis()}.pdf")
          withContext(Dispatchers.IO) {
            PdfEngine.passportPhotoSheet8(ctx, singleOut, sheetOut)
          }
          launchSavePdfFor = sheetOut
        } else {
          launchSaveImgFor = singleOut
        }
      } else {
        Toast.makeText(ctx, "Failed to create passport photo", Toast.LENGTH_SHORT).show()
      }
      isProcessing = false
    }
  }

  if (showDone && doneFile != null) {
    DoneScreen(
      file = doneFile!!,
      theme = theme,
      onBackHome = onBack,
      onOpen = { ShareHelper.openFile(ctx, it, doneMime) }
    )
    return
  }

  ToolScreenContainer("Passport Photo", theme, onBack) {
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
      Button(
        onClick = {
          vibrate()
          picker.launch("image/*")
        },
        modifier = Modifier.fillMaxWidth().height(48.dp),
        colors = ButtonDefaults.buttonColors(containerColor = theme.card),
        shape = RoundedCornerShape(8.dp)
      ) {
        Text(if (imageUri != null) "PHOTO SELECTED ✓" else "SELECT PHOTO", fontFamily = PixelFont, color = theme.primaryDark, fontSize = 12.sp)
      }

      if (passportPreviewBitmap != null) {
        Spacer(Modifier.height(14.dp))
        Text("PASSPORT PHOTO PREVIEW (35x45mm):", fontFamily = PixelFont, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = theme.primaryDark)
        Spacer(Modifier.height(8.dp))
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(theme.card)
            .padding(12.dp),
          horizontalAlignment = Alignment.CenterHorizontally
        ) {
          Image(
            bitmap = passportPreviewBitmap!!.asImageBitmap(),
            contentDescription = "Passport Preview",
            modifier = Modifier
              .width(138.dp)
              .height(177.dp)
              .clip(RoundedCornerShape(4.dp))
              .border(1.dp, Color.LightGray, RoundedCornerShape(4.dp)),
            contentScale = ContentScale.Fit
          )
          Spacer(Modifier.height(6.dp))
          Text(if (is8UpSheet) "Will print 8 copies on A4 PDF" else "Single high-res 300dpi JPEG", fontFamily = PixelFont, fontSize = 10.sp, color = theme.text)
        }
      }

      Spacer(Modifier.height(14.dp))
      Text("Output Format:", fontFamily = PixelFont, fontSize = 12.sp, color = theme.text)
      Spacer(Modifier.height(8.dp))
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
          Modifier
            .weight(1f)
            .height(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (!is8UpSheet) theme.primary else theme.card)
            .clickable {
              vibrate()
              is8UpSheet = false
            },
          contentAlignment = Alignment.Center
        ) {
          Text("Single Photo", fontFamily = PixelFont, color = if (!is8UpSheet) theme.textOnPrimary else theme.text, fontSize = 12.sp)
        }
        Box(
          Modifier
            .weight(1f)
            .height(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (is8UpSheet) theme.primary else theme.card)
            .clickable {
              vibrate()
              is8UpSheet = true
            },
          contentAlignment = Alignment.Center
        ) {
          Text("8-Up A4 PDF", fontFamily = PixelFont, color = if (is8UpSheet) theme.textOnPrimary else theme.text, fontSize = 12.sp)
        }
      }

      Spacer(Modifier.height(16.dp))
      if (isProcessing) {
        LinearProgressIndicator(Modifier.fillMaxWidth(), color = theme.primary)
        Spacer(Modifier.height(8.dp))
      }
    }

    SlideToAction(
      label = if (is8UpSheet) "Slide to Generate Sheet" else "Slide to Generate Photo",
      theme = theme,
      enabled = !isProcessing && imageUri != null,
      onSlideComplete = {
        vibrate()
        generate()
      }
    )
  }
}

// 8. IMAGE RESIZER & FORMAT CONVERT — Phase 6 extras: preset sizes
// (WhatsApp / Instagram / Passport / A4) and resize to an exact KB target
// (JPG only, via PdfEngine.compressImageToKb). When this screen is opened
// as "Format Convert" it is a batch tool: multi-select, convert all, live
// progress count. Slide to Resize / Convert, then save picker + DoneScreen.
@Composable
fun ResizeConvertScreen(theme: ChamiTheme, title: String = "Image Resizer", onBack: () -> Unit) {
  val ctx = LocalContext.current
  val haptic = LocalHapticFeedback.current
  val tm = remember { ThemeManager(ctx) }
  val scope = rememberCoroutineScope()
  val isBatch = title == "Format Convert"
  var imageUri by remember { mutableStateOf<Uri?>(null) }
  var imageUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
  var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
  var origDimensions by remember { mutableStateOf<Pair<Int, Int>?>(null) }
  var widthStr by remember { mutableStateOf("800") }
  var heightStr by remember { mutableStateOf("800") }
  var formatChoice by remember { mutableStateOf("JPG") }
  var targetKbStr by remember { mutableStateOf("") }
  var isProcessing by remember { mutableStateOf(false) }
  var batchProgress by remember { mutableStateOf(0) }
  var batchTotal by remember { mutableStateOf(0) }

  var pendingSaveFile by remember { mutableStateOf<File?>(null) }
  var doneFile by remember { mutableStateOf<File?>(null) }
  var doneMime by remember { mutableStateOf("image/jpeg") }
  var showDone by remember { mutableStateOf(false) }
  var launchSaveFor by remember { mutableStateOf<File?>(null) }
  val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/*")) { uri ->
    val file = pendingSaveFile
    if (uri != null && file != null) {
      SaveLocation.copyToUri(ctx, file, uri)
    }
    if (file != null) {
      doneFile = file
      showDone = true
    }
  }
  LaunchedEffect(launchSaveFor) {
    val f = launchSaveFor
    if (f != null) {
      pendingSaveFile = f
      saveLauncher.launch(f.name)
      launchSaveFor = null
    }
  }

  fun vibrate() {
    if (tm.hapticOn()) {
      try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
    }
  }

  val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { selected ->
    imageUri = selected
    showDone = false
    doneFile = null
    previewBitmap = null
    if (selected != null) {
      scope.launch(Dispatchers.IO) {
        val bmp = PdfEngine.loadBitmap(ctx, selected, 1200)
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
          ctx.contentResolver.openInputStream(selected)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (_: Exception) {}
        withContext(Dispatchers.Main) {
          previewBitmap = bmp
          if (opts.outWidth > 0 && opts.outHeight > 0) {
            origDimensions = Pair(opts.outWidth, opts.outHeight)
            widthStr = "${opts.outWidth / 2}"
            heightStr = "${opts.outHeight / 2}"
          }
        }
      }
    }
  }

  val batchPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selected ->
    if (selected.isNotEmpty()) {
      imageUris = imageUris + selected
      showDone = false
      doneFile = null
      batchProgress = 0
      val first = selected.firstOrNull()
      if (first != null && previewBitmap == null) {
        scope.launch(Dispatchers.IO) {
          val bmp = PdfEngine.loadBitmap(ctx, first, 1200)
          withContext(Dispatchers.Main) { previewBitmap = bmp }
        }
      }
    }
  }

  fun convertSingle() {
    val u = imageUri ?: return
    val w = widthStr.toIntOrNull() ?: 800
    val h = heightStr.toIntOrNull() ?: 800
    val format = when (formatChoice) {
      "PNG" -> Bitmap.CompressFormat.PNG
      "WEBP" -> Bitmap.CompressFormat.WEBP
      else -> Bitmap.CompressFormat.JPEG
    }
    val ext = formatChoice.lowercase()
    val kbTarget = targetKbStr.toIntOrNull()
    isProcessing = true
    scope.launch {
      val tmp = PdfEngine.outFile(ctx, "Resized_${w}x${h}_${System.currentTimeMillis()}.$ext")
      val ok = withContext(Dispatchers.IO) {
        PdfEngine.resizeConvert(ctx, u, w, h, format, tmp)
      }
      var finalFile = tmp
      if (ok && kbTarget != null && kbTarget > 0) {
        if (formatChoice == "JPG") {
          val out2 = PdfEngine.outFile(ctx, "Resized_${kbTarget}KB_${System.currentTimeMillis()}.jpg")
          val ok2 = withContext(Dispatchers.IO) {
            PdfEngine.compressImageToKb(ctx, Uri.fromFile(tmp), kbTarget, out2)
          }
          if (ok2) finalFile = out2
        } else {
          withContext(Dispatchers.Main) {
            Toast.makeText(ctx, "Exact KB target works with JPG — saved at chosen size instead", Toast.LENGTH_LONG).show()
          }
        }
      }
      isProcessing = false
      if (ok) {
        doneMime = if (formatChoice == "PNG") "image/png" else if (formatChoice == "WEBP") "image/webp" else "image/jpeg"
        launchSaveFor = finalFile
      } else {
        Toast.makeText(ctx, "Failed to resize", Toast.LENGTH_SHORT).show()
      }
    }
  }

  fun convertBatch() {
    if (imageUris.isEmpty()) return
    val w = widthStr.toIntOrNull() ?: 800
    val h = heightStr.toIntOrNull() ?: 800
    val format = when (formatChoice) {
      "PNG" -> Bitmap.CompressFormat.PNG
      "WEBP" -> Bitmap.CompressFormat.WEBP
      else -> Bitmap.CompressFormat.JPEG
    }
    val ext = formatChoice.lowercase()
    isProcessing = true
    batchTotal = imageUris.size
    batchProgress = 0
    scope.launch {
      var first: File? = null
      var done = 0
      for ((idx, u) in imageUris.withIndex()) {
        val out = PdfEngine.outFile(ctx, "Converted_${idx + 1}_${System.currentTimeMillis()}.$ext")
        val ok = withContext(Dispatchers.IO) {
          PdfEngine.resizeConvert(ctx, u, w, h, format, out)
        }
        if (ok) {
          if (first == null) first = out
          done++
        }
        batchProgress = done
      }
      isProcessing = false
      if (first != null) {
        doneMime = if (formatChoice == "PNG") "image/png" else if (formatChoice == "WEBP") "image/webp" else "image/jpeg"
        Toast.makeText(ctx, "Converted $done of ${imageUris.size} files — first one opens for saving", Toast.LENGTH_LONG).show()
        launchSaveFor = first
      } else {
        Toast.makeText(ctx, "Failed to convert files", Toast.LENGTH_SHORT).show()
      }
    }
  }

  if (showDone && doneFile != null) {
    DoneScreen(
      file = doneFile!!,
      theme = theme,
      onBackHome = onBack,
      onOpen = { ShareHelper.openFile(ctx, it, doneMime) }
    )
    return
  }

  ToolScreenContainer(title, theme, onBack) {
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
      if (isBatch) {
        PrimaryButton(
          theme = theme,
          text = if (imageUris.isNotEmpty()) "${imageUris.size} IMAGES CHOSEN ✓" else "SELECT IMAGES",
          height = 48.dp,
          onClick = {
            vibrate()
            batchPicker.launch("image/*")
          }
        )
        if (imageUris.isNotEmpty()) {
          Spacer(Modifier.height(10.dp))
          Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            imageUris.forEachIndexed { i, uri ->
              Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                  "${i + 1}. ${uri.lastPathSegment ?: "image"}",
                  fontFamily = PixelFont, fontSize = 11.sp, color = theme.text,
                  modifier = Modifier.weight(1f), maxLines = 1
                )
                TextButton(
                  onClick = {
                    vibrate()
                    imageUris = imageUris.filterIndexed { idx, _ -> idx != i }
                  },
                  contentPadding = PaddingValues(4.dp)
                ) {
                  Text("✕", fontSize = 12.sp, color = theme.textSecondary)
                }
              }
            }
          }
          if (isProcessing || batchProgress > 0) {
            Spacer(Modifier.height(6.dp))
            Text("Converted $batchProgress / $batchTotal", fontFamily = PixelFont, fontSize = 11.sp, color = theme.primaryDark, fontWeight = FontWeight.Bold)
          }
        }
      } else {
        PrimaryButton(
          theme = theme,
          text = if (imageUri != null) "IMAGE LOADED ✓" else "SELECT IMAGE",
          height = 48.dp,
          onClick = {
            vibrate()
            picker.launch("image/*")
          }
        )
      }

      if (previewBitmap != null) {
        Spacer(Modifier.height(14.dp))
        Text("IMAGE PREVIEW:", fontFamily = PixelFont, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = theme.primaryDark)
        Spacer(Modifier.height(6.dp))
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(theme.card)
            .padding(10.dp),
          horizontalAlignment = Alignment.CenterHorizontally
        ) {
          Image(
            bitmap = previewBitmap!!.asImageBitmap(),
            contentDescription = "Resize Preview",
            modifier = Modifier
              .fillMaxWidth()
              .height(320.dp)
              .clip(RoundedCornerShape(6.dp)),
            contentScale = ContentScale.Fit
          )
          Spacer(Modifier.height(8.dp))
          origDimensions?.let { (w, h) ->
            Text("Original: ${w}x$h px", fontFamily = PixelFont, fontSize = 10.sp, color = theme.text)
          }
          Text("Target: ${widthStr}x${heightStr} px ($formatChoice)", fontFamily = PixelFont, fontSize = 10.sp, color = theme.primaryDark, fontWeight = FontWeight.Bold)
        }
      }

      Spacer(Modifier.height(14.dp))
      Text("PRESETS", fontFamily = PixelFont, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = theme.primaryDark)
      Spacer(Modifier.height(6.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(
          "WhatsApp" to ("1080" to "1080"),
          "Instagram" to ("1080" to "1350"),
          "Passport" to ("413" to "531"),
          "A4" to ("1240" to "1754")
        ).forEach { (label, wh) ->
          val isSel = widthStr == wh.first && heightStr == wh.second
          Phase6Chip(label, isSel, theme) {
            vibrate()
            widthStr = wh.first
            heightStr = wh.second
          }
        }
      }

      Spacer(Modifier.height(14.dp))
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
          value = widthStr,
          onValueChange = { widthStr = it.filter { ch -> ch.isDigit() } },
          label = { Text("Width (px)", fontFamily = PixelFont, fontSize = 10.sp) },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
          value = heightStr,
          onValueChange = { heightStr = it.filter { ch -> ch.isDigit() } },
          label = { Text("Height (px)", fontFamily = PixelFont, fontSize = 10.sp) },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          modifier = Modifier.weight(1f)
        )
      }

      Spacer(Modifier.height(14.dp))
      Text("Format:", fontFamily = PixelFont, fontSize = 12.sp, color = theme.text)
      Spacer(Modifier.height(8.dp))
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("JPG", "PNG", "WEBP").forEach { fmt ->
          val isSelected = formatChoice == fmt
          Box(
            Modifier
              .weight(1f)
              .height(44.dp)
              .clip(RoundedCornerShape(8.dp))
              .background(if (isSelected) theme.primary else theme.card)
              .clickable {
                vibrate()
                formatChoice = fmt
              },
            contentAlignment = Alignment.Center
          ) {
            Text(fmt, fontFamily = PixelFont, color = if (isSelected) theme.textOnPrimary else theme.text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
          }
        }
      }

      if (!isBatch) {
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
          value = targetKbStr,
          onValueChange = { targetKbStr = it.filter { ch -> ch.isDigit() } },
          label = { Text("Exact size target in KB (JPG only, optional)", fontFamily = PixelFont, fontSize = 10.sp) },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          modifier = Modifier.fillMaxWidth()
        )
      }

      Spacer(Modifier.height(16.dp))
      if (isProcessing) {
        LinearProgressIndicator(Modifier.fillMaxWidth(), color = theme.primary)
        Spacer(Modifier.height(8.dp))
      }
    }

    if (isBatch) {
      SlideToAction(
        label = "Slide to Convert All",
        theme = theme,
        enabled = !isProcessing && imageUris.isNotEmpty(),
        onSlideComplete = {
          vibrate()
          convertBatch()
        }
      )
    } else {
      SlideToAction(
        label = "Slide to Resize",
        theme = theme,
        enabled = !isProcessing && imageUri != null,
        onSlideComplete = {
          vibrate()
          convertSingle()
        }
      )
    }
  }
}

// 9. OCR TEXT — Phase 6 extras: text language choice (English / Hindi /
// Spanish / French / German) and export as TXT or searchable PDF.
// NOTE: OcrHelper.ocrImage(ctx, uri) takes no language parameter, so the
// language is stored with the export (file name + PDF title) — the
// on-device recogniser reads Latin script. Export is a real file: TXT via
// saveTextToFile, searchable PDF (real selectable text) via textToPdf.
@Composable
fun OcrScreen(theme: ChamiTheme, onBack: () -> Unit) {
  val ctx = LocalContext.current
  val haptic = LocalHapticFeedback.current
  val tm = remember { ThemeManager(ctx) }
  val scope = rememberCoroutineScope()
  var imageUri by remember { mutableStateOf<Uri?>(null) }
  var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
  var isProcessing by remember { mutableStateOf(false) }
  var extractedText by remember { mutableStateOf("") }
  var ocrLang by remember { mutableStateOf("English") }

  var pendingSaveFile by remember { mutableStateOf<File?>(null) }
  var doneFile by remember { mutableStateOf<File?>(null) }
  var doneMime by remember { mutableStateOf("text/plain") }
  var showDone by remember { mutableStateOf(false) }
  var launchSaveTxtFor by remember { mutableStateOf<File?>(null) }
  var launchSavePdfFor by remember { mutableStateOf<File?>(null) }
  val txtSaveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
    val file = pendingSaveFile
    if (uri != null && file != null) {
      SaveLocation.copyToUri(ctx, file, uri)
    }
    if (file != null) {
      doneFile = file
      doneMime = "text/plain"
      showDone = true
    }
  }
  val pdfSaveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
    val file = pendingSaveFile
    if (uri != null && file != null) {
      SaveLocation.copyToUri(ctx, file, uri)
    }
    if (file != null) {
      doneFile = file
      doneMime = "application/pdf"
      showDone = true
    }
  }
  LaunchedEffect(launchSaveTxtFor) {
    val f = launchSaveTxtFor
    if (f != null) {
      pendingSaveFile = f
      txtSaveLauncher.launch(f.name)
      launchSaveTxtFor = null
    }
  }
  LaunchedEffect(launchSavePdfFor) {
    val f = launchSavePdfFor
    if (f != null) {
      pendingSaveFile = f
      pdfSaveLauncher.launch(f.name)
      launchSavePdfFor = null
    }
  }

  fun vibrate() {
    if (tm.hapticOn()) {
      try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
    }
  }

  val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { selected ->
    imageUri = selected
    extractedText = ""
    showDone = false
    doneFile = null
    previewBitmap = null
    if (selected != null) {
      scope.launch(Dispatchers.IO) {
        val bmp = PdfEngine.loadBitmap(ctx, selected, 1200)
        withContext(Dispatchers.Main) { previewBitmap = bmp }
      }
    }
  }

  fun runOcr() {
    val u = imageUri ?: return
    isProcessing = true
    scope.launch {
      val txt = withContext(Dispatchers.IO) {
        ocrImage(ctx, u)
      }
      isProcessing = false
      extractedText = if (txt.isBlank()) "[No readable text found in image]" else txt
    }
  }

  if (showDone && doneFile != null) {
    DoneScreen(
      file = doneFile!!,
      theme = theme,
      onBackHome = onBack,
      onOpen = { ShareHelper.openFile(ctx, it, doneMime) }
    )
    return
  }

  ToolScreenContainer("OCR Text", theme, onBack) {
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
      Button(
        onClick = {
          vibrate()
          picker.launch("image/*")
        },
        modifier = Modifier.fillMaxWidth().height(48.dp),
        colors = ButtonDefaults.buttonColors(containerColor = theme.card),
        shape = RoundedCornerShape(8.dp)
      ) {
        Text(if (imageUri != null) "PHOTO SELECTED ✓" else "SELECT PHOTO", fontFamily = PixelFont, color = theme.primaryDark, fontSize = 12.sp)
      }

      Spacer(Modifier.height(12.dp))
      Text("TEXT LANGUAGE", fontFamily = PixelFont, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = theme.primaryDark)
      Spacer(Modifier.height(6.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("English", "Hindi", "Spanish").forEach { lang ->
          Phase6Chip(lang, ocrLang == lang, theme) {
            vibrate()
            ocrLang = lang
          }
        }
      }
      Spacer(Modifier.height(6.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("French", "German").forEach { lang ->
          Phase6Chip(lang, ocrLang == lang, theme) {
            vibrate()
            ocrLang = lang
          }
        }
      }
      Text("On-device recogniser reads Latin script — the language is saved with your export.", fontFamily = PixelFont, fontSize = 10.sp, color = theme.textSecondary)

      if (previewBitmap != null) {
        Spacer(Modifier.height(14.dp))
        Text("IMAGE PREVIEW:", fontFamily = PixelFont, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = theme.primaryDark)
        Spacer(Modifier.height(6.dp))
        Image(
          bitmap = previewBitmap!!.asImageBitmap(),
          contentDescription = "OCR Preview",
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 380.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(theme.card),
          contentScale = ContentScale.Fit
        )
      }

      Spacer(Modifier.height(16.dp))
      if (isProcessing) {
        LinearProgressIndicator(Modifier.fillMaxWidth(), color = theme.primary)
        Spacer(Modifier.height(8.dp))
        Text("Running on-device OCR...", fontFamily = PixelFont, fontSize = 11.sp, color = theme.text)
      }

      if (extractedText.isNotBlank()) {
        Column(
          Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(theme.card)
            .padding(14.dp)
        ) {
          Text("Recognized Text:", fontFamily = PixelFont, fontWeight = FontWeight.Bold, color = theme.primaryDark, fontSize = 12.sp)
          Spacer(Modifier.height(8.dp))
          Text(extractedText, color = theme.text, fontSize = 13.sp)
          Spacer(Modifier.height(12.dp))
          Button(
            onClick = {
              vibrate()
              val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
              cm.setPrimaryClip(ClipData.newPlainText("OCR Text", extractedText))
              Toast.makeText(ctx, "Copied to clipboard ✓", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
            shape = RoundedCornerShape(8.dp)
          ) {
            Text("COPY TEXT", fontFamily = PixelFont, color = theme.textOnPrimary, fontSize = 12.sp)
          }
          Spacer(Modifier.height(8.dp))
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
              onClick = {
                vibrate()
                val f = saveTextToFile(ctx, extractedText, "OCR_${ocrLang}_${System.currentTimeMillis()}.txt")
                launchSaveTxtFor = f
              },
              modifier = Modifier.weight(1f).height(48.dp),
              colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
              shape = RoundedCornerShape(8.dp)
            ) {
              Text("EXPORT TXT", fontFamily = PixelFont, color = theme.textOnPrimary, fontSize = 11.sp)
            }
            Button(
              onClick = {
                vibrate()
                val out = PdfEngine.outFile(ctx, "OCR_${ocrLang}_${System.currentTimeMillis()}.pdf")
                val ok = textToPdf(ctx, "OCR Text ($ocrLang)", extractedText, outFile = out)
                if (ok) launchSavePdfFor = out
                else Toast.makeText(ctx, "Failed to make PDF", Toast.LENGTH_SHORT).show()
              },
              modifier = Modifier.weight(1f).height(48.dp),
              colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
              shape = RoundedCornerShape(8.dp)
            ) {
              Text("EXPORT PDF", fontFamily = PixelFont, color = theme.textOnPrimary, fontSize = 11.sp)
            }
          }
        }
      }
    }

    SlideToAction(
      label = "Slide to Extract Text",
      theme = theme,
      enabled = !isProcessing && imageUri != null,
      onSlideComplete = {
        vibrate()
        runOcr()
      }
    )
  }
}

// 10. PDF READER — Phase 6 extras: search text inside the PDF (search
// field, jump to matches) + bookmarks (bookmark the current page, tap a
// bookmark chip to jump back). Search runs the existing pdfToText OCR pass
// over the file and finds the pages containing the query.
@Composable
fun PdfReaderScreen(
  theme: ChamiTheme,
  initialFile: File? = null,
  onOpenTool: ((String) -> Unit)? = null,
  onBack: () -> Unit
) {
  val ctx = LocalContext.current
  val haptic = LocalHapticFeedback.current
  val tm = remember { ThemeManager(ctx) }
  val scope = rememberCoroutineScope()
  // Phase 4, point 6: a file tapped in History opens straight here.
  var pdfUri by remember { mutableStateOf<Uri?>(initialFile?.let { Uri.fromFile(it) }) }
  var totalPages by remember { mutableStateOf(0) }
  var currentPage by remember { mutableStateOf(0) }
  // Phase 5: render at 2x so pages stay sharp when zoomed in.
  var scale by remember { mutableStateOf(2.0f) }
  var isNightMode by remember { mutableStateOf(false) }
  var pageInput by remember { mutableStateOf("1") }
  var currentBitmap by remember { mutableStateOf<Bitmap?>(null) }
  var isLoadingPage by remember { mutableStateOf(false) }
  // Pinch-to-zoom state (Drive-like): zoom is the display zoom on the
  // already-rendered bitmap; pan moves the zoomed page. Reset on page
  // change so a new page always starts fitted.
  var zoom by remember { mutableStateOf(1f) }
  var pan by remember { mutableStateOf(Offset.Zero) }
  // Rendered page bitmaps are cached per page (in memory only) so
  // going back a page does not re-render it.
  val pageBitmapCache = remember { mutableStateMapOf<Int, Bitmap>() }
  // Phase 6: search + bookmarks.
  var searchQuery by remember { mutableStateOf("") }
  var searchPages by remember { mutableStateOf<List<Int>>(emptyList()) }
  var searchIndex by remember { mutableStateOf(0) }
  var searching by remember { mutableStateOf(false) }
  val bookmarks = remember { mutableStateListOf<Int>() }

  fun vibrate() {
    if (tm.hapticOn()) {
      try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
    }
  }

  fun jumpToPage(page1Based: Int) {
    if (page1Based in 1..totalPages) {
      currentPage = page1Based - 1
      pageInput = "$page1Based"
    }
  }

  fun runSearch() {
    val q = searchQuery.trim()
    val u = pdfUri
    if (q.isEmpty() || u == null) return
    searching = true
    scope.launch {
      val all = withContext(Dispatchers.IO) { pdfToText(ctx, u) }
      val hits = mutableListOf<Int>()
      var current = -1
      all.lines().forEach { line ->
        val m = Regex("""--- Page (\d+) ---""").find(line)
        if (m != null) {
          current = m.groupValues[1].toIntOrNull() ?: -1
        } else if (current > 0 && line.contains(q, ignoreCase = true)) {
          if (!hits.contains(current)) hits.add(current)
        }
      }
      searching = false
      searchPages = hits
      searchIndex = 0
      if (hits.isNotEmpty()) {
        jumpToPage(hits[0])
      } else {
        Toast.makeText(ctx, "No matches for \"$q\"", Toast.LENGTH_SHORT).show()
      }
    }
  }

  val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { selected ->
    pdfUri = selected
    if (selected != null) {
      totalPages = PdfEngine.pageCount(ctx, selected)
      currentPage = 0
      pageInput = "1"
      searchPages = emptyList()
      bookmarks.clear()
    }
  }

  // Page count for a file opened from History (picker sets it itself).
  LaunchedEffect(pdfUri) {
    val u = pdfUri ?: return@LaunchedEffect
    if (totalPages == 0) {
      totalPages = withContext(Dispatchers.IO) { PdfEngine.pageCount(ctx, u) }
    }
  }

  // Reset display zoom/pan whenever the page changes.
  LaunchedEffect(currentPage) { zoom = 1f; pan = Offset.Zero }

  LaunchedEffect(pdfUri, currentPage, scale) {
    val u = pdfUri ?: return@LaunchedEffect
    pageBitmapCache[currentPage]?.let { cached ->
      currentBitmap = cached
      isLoadingPage = false
      return@LaunchedEffect
    }
    isLoadingPage = true
    withContext(Dispatchers.IO) {
      val bmp = PdfReader.renderPageBitmap(ctx, u, currentPage, scale)
      withContext(Dispatchers.Main) {
        if (bmp != null) pageBitmapCache[currentPage] = bmp
        currentBitmap = bmp
        isLoadingPage = false
      }
    }
  }

  ToolScreenContainer("PDF Reader", theme, onBack) {
    if (pdfUri == null) {
      Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("PDF Reader", fontFamily = PixelFont, fontSize = 14.sp, color = theme.text)
        Spacer(Modifier.height(20.dp))
        Button(
          onClick = {
            vibrate()
            picker.launch("application/pdf")
          },
          modifier = Modifier.fillMaxWidth().height(56.dp),
          colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
          shape = RoundedCornerShape(8.dp)
        ) {
          Text("OPEN PDF FILE", fontFamily = PixelFont, fontWeight = FontWeight.Black, fontSize = 14.sp, color = theme.textOnPrimary)
        }
      }
    } else {
      Column(Modifier.fillMaxSize()) {
        // Controls row
        Row(
          Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(theme.card).padding(8.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Page: ", fontFamily = PixelFont, fontSize = 11.sp, color = theme.text)
            OutlinedTextField(
              value = pageInput,
              onValueChange = {
                pageInput = it
                val p = it.toIntOrNull()
                if (p != null && p in 1..totalPages) {
                  currentPage = p - 1
                }
              },
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
              modifier = Modifier.width(60.dp).height(44.dp)
            )
            Text(" / $totalPages", fontFamily = PixelFont, fontSize = 11.sp, color = theme.text)
          }

          Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
              onClick = {
                vibrate()
                zoom = (zoom - 0.25f).coerceIn(0.5f, 4f)
                if (zoom == 1f) pan = Offset.Zero
              },
              colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
              shape = RoundedCornerShape(6.dp),
              contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) { Text("-", fontFamily = PixelFont, color = theme.textOnPrimary, fontSize = 14.sp) }
            Spacer(Modifier.width(4.dp))
            Text("${(zoom * 100).toInt()}%", fontFamily = PixelFont, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = theme.text)
            Spacer(Modifier.width(4.dp))
            Button(
              onClick = {
                vibrate()
                zoom = (zoom + 0.25f).coerceIn(0.5f, 4f)
              },
              colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
              shape = RoundedCornerShape(6.dp),
              contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) { Text("+", fontFamily = PixelFont, color = theme.textOnPrimary, fontSize = 14.sp) }
            Spacer(Modifier.width(4.dp))
            Button(
              onClick = {
                vibrate()
                isNightMode = !isNightMode
              },
              colors = ButtonDefaults.buttonColors(containerColor = if (isNightMode) theme.primaryDark else theme.primary),
              shape = RoundedCornerShape(6.dp),
              contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) { Text(if (isNightMode) "☀️" else "🌙", fontSize = 12.sp) }
          }
        }

        Spacer(Modifier.height(8.dp))
        // Phase 6: search text inside the PDF.
        Row(
          Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text("Search text in PDF…", fontFamily = PixelFont, fontSize = 10.sp) },
            singleLine = true,
            modifier = Modifier.weight(1f).height(52.dp),
            shape = RoundedCornerShape(8.dp)
          )
          Button(
            onClick = {
              vibrate()
              runSearch()
            },
            enabled = !searching,
            colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
          ) {
            Text(if (searching) "…" else "SEARCH", fontFamily = PixelFont, color = theme.textOnPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
          }
        }
        if (searching) {
          Spacer(Modifier.height(4.dp))
          Text("Searching all pages…", fontFamily = PixelFont, fontSize = 10.sp, color = theme.textSecondary)
        } else if (searchPages.isNotEmpty()) {
          Spacer(Modifier.height(4.dp))
          Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Text(
              "Found on pages: ${searchPages.joinToString(", ")}",
              fontFamily = PixelFont, fontSize = 10.sp, color = theme.primaryDark, fontWeight = FontWeight.Bold
            )
            TextButton(
              onClick = {
                vibrate()
                val next = (searchIndex + 1) % searchPages.size
                searchIndex = next
                jumpToPage(searchPages[next])
              },
              contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
            ) {
              Text("NEXT MATCH →", fontFamily = PixelFont, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = theme.primary)
            }
          }
        }

        Spacer(Modifier.height(8.dp))
        // Zoom slider (same zoom as pinch / +/- above).
        Row(
          Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text("ZOOM", fontFamily = PixelFont, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = theme.textSecondary)
          Slider(
            value = zoom,
            onValueChange = {
              zoom = it
              if (zoom == 1f) pan = Offset.Zero
            },
            valueRange = 0.5f..4f,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(thumbColor = theme.primary, activeTrackColor = theme.primary)
          )
          TextButton(onClick = { zoom = 1f; pan = Offset.Zero }) {
            Text("FIT", fontFamily = PixelFont, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = theme.primary)
          }
        }

        Spacer(Modifier.height(8.dp))
        // Split / Merge / Compress from the reader (Phase 4, point 6).
        // Those screens pick their own file in v1.2, so these buttons
        // open the tool — pick the same file there again.
        if (onOpenTool != null) {
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("SPLIT" to "split", "MERGE" to "merge", "COMPRESS" to "compress").forEach { (label, id) ->
              OutlinedButton(
                onClick = { vibrate(); onOpenTool(id) },
                modifier = Modifier.weight(1f).height(40.dp),
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, theme.cardBorder),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = theme.text)
              ) {
                Text(label, fontFamily = PixelFont, color = theme.text, fontSize = 11.sp, fontWeight = FontWeight.Bold)
              }
            }
          }
          Spacer(Modifier.height(8.dp))
        }
        if (isLoadingPage) {
          LinearProgressIndicator(Modifier.fillMaxWidth(), color = theme.primary)
        }

        // Page view (Phase 5, Drive-like): dark background, page fitted
        // to the screen, pinch to zoom + drag to pan, double-tap toggles
        // between fit (1x) and 2.5x zoom. Rendered at 2x, so zooming
        // stays sharp; the bitmap is cached per page (see above).
        Box(
          Modifier
            .weight(1f)
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF121212)),
          contentAlignment = Alignment.Center
        ) {
          currentBitmap?.let { bmp ->
            Image(
              bitmap = bmp.asImageBitmap(),
              contentDescription = "Page ${currentPage + 1}",
              modifier = Modifier
                .fillMaxSize()
                .padding(8.dp)
                .graphicsLayer(
                  scaleX = zoom,
                  scaleY = zoom,
                  translationX = pan.x,
                  translationY = pan.y
                )
                .pointerInput(Unit) {
                  detectTransformGestures { _, panChange, zoomChange, _ ->
                    zoom = (zoom * zoomChange).coerceIn(0.5f, 4f)
                    pan += panChange
                    if (zoom == 1f) pan = Offset.Zero
                  }
                }
                .pointerInput(Unit) {
                  detectTapGestures(
                    onDoubleTap = {
                      if (zoom > 1f) { zoom = 1f; pan = Offset.Zero }
                      else zoom = 2.5f
                    }
                  )
                },
              contentScale = ContentScale.Fit
            )
          }
        }

        Spacer(Modifier.height(8.dp))
        // Phase 6: bookmarks — bookmark this page, tap a chip to jump.
        Row(
          Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          OutlinedButton(
            onClick = {
              vibrate()
              if (bookmarks.contains(currentPage)) bookmarks.remove(currentPage)
              else bookmarks.add(currentPage)
            },
            modifier = Modifier.height(40.dp),
            shape = RoundedCornerShape(8.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, theme.cardBorder),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = theme.text)
          ) {
            Text(
              if (bookmarks.contains(currentPage)) "★ BOOKMARKED" else "☆ BOOKMARK PAGE",
              fontFamily = PixelFont, color = theme.text, fontSize = 10.sp, fontWeight = FontWeight.Bold
            )
          }
          if (bookmarks.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
              items(bookmarks.sorted()) { b ->
                Box(
                  Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(theme.primary.copy(alpha = 0.15f))
                    .border(1.dp, theme.primary, RoundedCornerShape(14.dp))
                    .clickable {
                      vibrate()
                      jumpToPage(b + 1)
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                  contentAlignment = Alignment.Center
                ) {
                  Text("★ ${b + 1}", fontFamily = PixelFont, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = theme.primaryDark)
                }
              }
            }
          }
        }
        Spacer(Modifier.height(8.dp))
        // Jump to page: numbered chips (tap a page to open it).
        if (totalPages > 1) {
          LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
          ) {
            items((1..totalPages).toList()) { p ->
              val isSel = p - 1 == currentPage
              Box(
                Modifier
                  .size(40.dp)
                  .clip(RoundedCornerShape(6.dp))
                  .background(if (isSel) theme.primary else theme.card)
                  .border(1.dp, theme.cardBorder, RoundedCornerShape(6.dp))
                  .clickable {
                    vibrate()
                    currentPage = p - 1
                    pageInput = "$p"
                  },
                contentAlignment = Alignment.Center
              ) {
                Text(
                  "$p",
                  fontFamily = PixelFont,
                  fontSize = 11.sp,
                  fontWeight = FontWeight.Bold,
                  color = if (isSel) theme.textOnPrimary else theme.text
                )
              }
            }
          }
          Spacer(Modifier.height(8.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
          Button(
            onClick = {
              vibrate()
              if (currentPage > 0) {
                currentPage--
                pageInput = "${currentPage + 1}"
              }
            },
            enabled = currentPage > 0,
            modifier = Modifier.weight(1f).height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
            shape = RoundedCornerShape(8.dp)
          ) {
            Text("← PREV", fontFamily = PixelFont, color = theme.textOnPrimary, fontSize = 12.sp)
          }
          Spacer(Modifier.width(8.dp))
          Button(
            onClick = {
              vibrate()
              if (currentPage < totalPages - 1) {
                currentPage++
                pageInput = "${currentPage + 1}"
              }
            },
            enabled = currentPage < totalPages - 1,
            modifier = Modifier.weight(1f).height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
            shape = RoundedCornerShape(8.dp)
          ) {
            Text("NEXT →", fontFamily = PixelFont, color = theme.textOnPrimary, fontSize = 12.sp)
          }
        }
      }
    }
  }
}

// 11. PDF TO TEXT — Slide to Extract, then save as .TXT goes through the
// save picker + DoneScreen. Copy stays inline.
@Composable
fun PdfToTextScreen(theme: ChamiTheme, onBack: () -> Unit) {
  val ctx = LocalContext.current
  val haptic = LocalHapticFeedback.current
  val tm = remember { ThemeManager(ctx) }
  val scope = rememberCoroutineScope()
  var pdfUri by remember { mutableStateOf<Uri?>(null) }
  var totalPages by remember { mutableStateOf(0) }
  var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
  var isProcessing by remember { mutableStateOf(false) }
  var extractedText by remember { mutableStateOf("") }

  var pendingSaveFile by remember { mutableStateOf<File?>(null) }
  var doneFile by remember { mutableStateOf<File?>(null) }
  var showDone by remember { mutableStateOf(false) }
  var launchSaveFor by remember { mutableStateOf<File?>(null) }
  val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
    val file = pendingSaveFile
    if (uri != null && file != null) {
      SaveLocation.copyToUri(ctx, file, uri)
    }
    if (file != null) {
      doneFile = file
      showDone = true
    }
  }
  LaunchedEffect(launchSaveFor) {
    val f = launchSaveFor
    if (f != null) {
      pendingSaveFile = f
      saveLauncher.launch(f.name)
      launchSaveFor = null
    }
  }

  fun vibrate() {
    if (tm.hapticOn()) {
      try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
    }
  }

  val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { selected ->
    pdfUri = selected
    extractedText = ""
    showDone = false
    doneFile = null
    previewBitmap = null
    if (selected != null) {
      scope.launch(Dispatchers.IO) {
        val n = PdfEngine.pageCount(ctx, selected)
        val bmp = PdfReader.renderPageBitmap(ctx, selected, 0, 2.0f)
        withContext(Dispatchers.Main) {
          totalPages = n
          previewBitmap = bmp
        }
      }
    }
  }

  fun extract() {
    val u = pdfUri ?: return
    isProcessing = true
    scope.launch {
      val res = withContext(Dispatchers.IO) {
        pdfToText(ctx, u)
      }
      isProcessing = false
      extractedText = res
    }
  }

  if (showDone && doneFile != null) {
    DoneScreen(
      file = doneFile!!,
      theme = theme,
      onBackHome = onBack,
      onOpen = { ShareHelper.openFile(ctx, it, "text/plain") }
    )
    return
  }

  ToolScreenContainer("PDF to Text", theme, onBack) {
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
      Button(
        onClick = {
          vibrate()
          picker.launch("application/pdf")
        },
        modifier = Modifier.fillMaxWidth().height(48.dp),
        colors = ButtonDefaults.buttonColors(containerColor = theme.card),
        shape = RoundedCornerShape(8.dp)
      ) {
        Text(if (pdfUri != null) "PDF LOADED ($totalPages PAGES) ✓" else "SELECT PDF", fontFamily = PixelFont, color = theme.primaryDark, fontSize = 12.sp)
      }

      if (previewBitmap != null) {
        Spacer(Modifier.height(14.dp))
        Text("DOCUMENT PREVIEW (PAGE 1 OF $totalPages):", fontFamily = PixelFont, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = theme.primaryDark)
        Spacer(Modifier.height(6.dp))
        Image(
          bitmap = previewBitmap!!.asImageBitmap(),
          contentDescription = "PDF Page 1 Preview",
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 380.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(theme.card),
          contentScale = ContentScale.Fit
        )
      }

      Spacer(Modifier.height(16.dp))
      if (isProcessing) {
        LinearProgressIndicator(Modifier.fillMaxWidth(), color = theme.primary)
        Spacer(Modifier.height(8.dp))
        Text("Extracting text via page rendering & OCR...", fontFamily = PixelFont, fontSize = 11.sp, color = theme.text)
      }

      if (extractedText.isNotBlank()) {
        Column(
          Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(theme.card)
            .padding(14.dp)
        ) {
          Text("Extracted Content:", fontFamily = PixelFont, fontWeight = FontWeight.Bold, color = theme.primaryDark, fontSize = 12.sp)
          Spacer(Modifier.height(8.dp))
          Text(extractedText, color = theme.text, fontSize = 12.sp)
          Spacer(Modifier.height(12.dp))
          Button(
            onClick = {
              vibrate()
              val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
              cm.setPrimaryClip(ClipData.newPlainText("PDF Text", extractedText))
              Toast.makeText(ctx, "Copied all text ✓", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
            shape = RoundedCornerShape(8.dp)
          ) {
            Text("COPY ALL TEXT", fontFamily = PixelFont, color = theme.textOnPrimary, fontSize = 12.sp)
          }
          Spacer(Modifier.height(8.dp))
          OutlinedButton(
            onClick = {
              vibrate()
              val f = saveTextToFile(ctx, extractedText, "Extracted_${System.currentTimeMillis()}.txt")
              launchSaveFor = f
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(8.dp)
          ) {
            Text("SAVE AS .TXT FILE", fontFamily = PixelFont, color = theme.primaryDark, fontSize = 12.sp)
          }
        }
      }
    }

    SlideToAction(
      label = "Slide to Extract Text",
      theme = theme,
      enabled = !isProcessing && pdfUri != null,
      onSlideComplete = {
        vibrate()
        extract()
      }
    )
  }
}


// 12. PROTECT PDF — Phase 6: two modes, Protect / Unlock.
// Protect: password + permissions (Block printing / Block copying — applied
// by patching /P + /U in the protected file, see patchPdfPermissions).
// Unlock: pick a protected PDF + enter its password; the password is
// verified first, then the RC4 streams are decrypted in place (see
// unlockProtectedPdf). Works with PDFs protected by this app (Standard
// V1/R2); other encryption types are refused with a toast. NO ads — the
// build runs directly after the slide.
@Composable
fun ProtectPdfScreen(theme: ChamiTheme, onBack: () -> Unit) {
  val ctx = LocalContext.current
  val haptic = LocalHapticFeedback.current
  val tm = remember { ThemeManager(ctx) }
  val scope = rememberCoroutineScope()

  var mode by remember { mutableStateOf("Protect") }

  var selectedUri by remember { mutableStateOf<Uri?>(null) }
  var pageCount by remember { mutableStateOf(0) }
  var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
  var password by remember { mutableStateOf("") }
  var confirmPassword by remember { mutableStateOf("") }
  var passwordVisible by remember { mutableStateOf(false) }
  var blockPrint by remember { mutableStateOf(false) }
  var blockCopy by remember { mutableStateOf(false) }

  var unlockUri by remember { mutableStateOf<Uri?>(null) }
  var unlockPassword by remember { mutableStateOf("") }
  var unlockPasswordVisible by remember { mutableStateOf(false) }

  var isProcessing by remember { mutableStateOf(false) }
  var progressStatus by remember { mutableStateOf("") }

  var pendingSaveFile by remember { mutableStateOf<File?>(null) }
  var doneFile by remember { mutableStateOf<File?>(null) }
  var showDone by remember { mutableStateOf(false) }
  var launchSaveFor by remember { mutableStateOf<File?>(null) }
  val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
    val file = pendingSaveFile
    if (uri != null && file != null) {
      SaveLocation.copyToUri(ctx, file, uri)
    }
    if (file != null) {
      doneFile = file
      showDone = true
    }
  }
  LaunchedEffect(launchSaveFor) {
    val f = launchSaveFor
    if (f != null) {
      pendingSaveFile = f
      saveLauncher.launch(f.name)
      launchSaveFor = null
    }
  }

  fun vibrate() {
    if (tm.hapticOn()) {
      try {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
      } catch (_: Exception) {}
    }
  }

  val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
    if (uri != null) {
      vibrate()
      selectedUri = uri
      showDone = false
      doneFile = null
      scope.launch(Dispatchers.IO) {
        val count = PdfEngine.pageCount(ctx, uri)
        val bmp = PdfReader.renderPageBitmap(ctx, uri, 0, 1.0f)
        withContext(Dispatchers.Main) {
          pageCount = count
          previewBitmap = bmp
        }
      }
    }
  }

  val unlockPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
    if (uri != null) {
      vibrate()
      unlockUri = uri
      showDone = false
      doneFile = null
    }
  }
  fun protect() {
    val uri = selectedUri ?: return
    isProcessing = true
    progressStatus = "Encrypting document & writing crypt keys..."
    scope.launch(Dispatchers.IO) {
      val out = PdfEngine.outFile(ctx, "protected_${System.currentTimeMillis()}.pdf")
      val ok = PdfProtector.protectPdf(ctx, uri, password, out) { current, total ->
        progressStatus = "Encrypting page $current of $total..."
      }
      var patched = true
      if (ok && (blockPrint || blockCopy)) {
        patched = patchPdfPermissions(out, password, allowPrint = !blockPrint, allowCopy = !blockCopy)
      }
      withContext(Dispatchers.Main) {
        isProcessing = false
        if (ok) {
          if (!patched) {
            Toast.makeText(ctx, "Protected, but permissions could not be applied", Toast.LENGTH_LONG).show()
          } else {
            Toast.makeText(ctx, "PDF Protected Successfully! ✓", Toast.LENGTH_SHORT).show()
          }
          launchSaveFor = out
        } else {
          Toast.makeText(ctx, "Failed to encrypt PDF", Toast.LENGTH_SHORT).show()
        }
      }
    }
  }

  fun unlock() {
    val uri = unlockUri ?: return
    if (unlockPassword.isBlank()) {
      Toast.makeText(ctx, "Enter the PDF password", Toast.LENGTH_SHORT).show()
      return
    }
    isProcessing = true
    scope.launch {
      val tmp = File(ctx.cacheDir, "unlock_in_${System.currentTimeMillis()}.pdf")
      val out = PdfEngine.outFile(ctx, "Unlocked_${System.currentTimeMillis()}.pdf")
      val ok = withContext(Dispatchers.IO) {
        try {
          ctx.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tmp).use { o -> input.copyTo(o) }
          }
        } catch (_: Exception) { }
        if (!tmp.exists()) false
        else if (!PdfProtector.verifyPassword(tmp, unlockPassword)) false
        else unlockProtectedPdf(tmp, unlockPassword, out)
      }
      isProcessing = false
      if (ok) {
        Toast.makeText(ctx, "Unlocked ✓", Toast.LENGTH_SHORT).show()
        launchSaveFor = out
      } else {
        Toast.makeText(ctx, "Wrong password, or this file can't be unlocked on-device", Toast.LENGTH_LONG).show()
      }
    }
  }

  if (showDone && doneFile != null) {
    DoneScreen(
      file = doneFile!!,
      theme = theme,
      onBackHome = onBack,
      onOpen = { ShareHelper.openFile(ctx, it, "application/pdf") }
    )
    return
  }

  ToolScreenContainer("Protect PDF", theme, onBack) {
    Column(
      Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
      // Mode tabs: Protect / Unlock
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(8.dp))
          .background(theme.card)
          .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
      ) {
        listOf("Protect", "Unlock").forEach { m ->
          Box(
            modifier = Modifier
              .weight(1f)
              .height(40.dp)
              .clip(RoundedCornerShape(6.dp))
              .background(if (mode == m) theme.primary else Color.Transparent)
              .clickable {
                vibrate()
                mode = m
              },
            contentAlignment = Alignment.Center
          ) {
            Text(
              m.uppercase(),
              fontFamily = PixelFont,
              fontWeight = FontWeight.Bold,
              fontSize = 11.sp,
              color = if (mode == m) Color.White else theme.text
            )
          }
        }
      }

      if (mode == "Unlock") {
        Column(
          Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(theme.card)
            .border(1.dp, theme.cardBorder, RoundedCornerShape(16.dp))
            .padding(16.dp)
        ) {
          Text("UNLOCK A PROTECTED PDF", fontFamily = PixelFont, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = theme.text)
          Spacer(Modifier.height(12.dp))
          Button(
            onClick = {
              vibrate()
              unlockPicker.launch("application/pdf")
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = theme.card),
            shape = RoundedCornerShape(10.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, theme.cardBorder)
          ) {
            Text(
              if (unlockUri == null) "CHOOSE PROTECTED PDF" else "CHANGE PDF FILE",
              fontFamily = PixelFont,
              color = theme.primary,
              fontWeight = FontWeight.Bold,
              fontSize = 12.sp
            )
          }
          if (unlockUri != null) {
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
              value = unlockPassword,
              onValueChange = { unlockPassword = it },
              label = { Text("Enter PDF Password", fontFamily = PixelFont, fontSize = 12.sp) },
              singleLine = true,
              visualTransformation = if (unlockPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
              trailingIcon = {
                IconButton(onClick = { unlockPasswordVisible = !unlockPasswordVisible }) {
                  Text(if (unlockPasswordVisible) "👁️" else "🔒", fontSize = 16.sp)
                }
              },
              modifier = Modifier.fillMaxWidth(),
              shape = RoundedCornerShape(10.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text("Removes the password from PDFs protected by Chami (Standard encryption).", fontFamily = PixelFont, fontSize = 11.sp, color = theme.textSecondary)
          }
        }
        if (isProcessing) {
          LinearProgressIndicator(Modifier.fillMaxWidth(), color = theme.primary)
        }
      } else {
        // Step 1: Select Document
        Column(
          Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(theme.card)
            .border(1.dp, theme.cardBorder, RoundedCornerShape(16.dp))
            .padding(16.dp)
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
              Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(theme.primary.copy(alpha = 0.15f)),
              contentAlignment = Alignment.Center
            ) {
              Text("1", fontFamily = PixelFont, fontWeight = FontWeight.Bold, color = theme.primary, fontSize = 13.sp)
            }
            Spacer(Modifier.width(10.dp))
            Text(
              "SELECT PDF DOCUMENT",
              fontFamily = PixelFont,
              fontWeight = FontWeight.Bold,
              fontSize = 13.sp,
              color = theme.text
            )
          }

          Spacer(Modifier.height(12.dp))

          Button(
            onClick = {
              vibrate()
              pdfPicker.launch("application/pdf")
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = theme.card),
            shape = RoundedCornerShape(10.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, theme.cardBorder)
          ) {
            Text(
              if (selectedUri == null) "CHOOSE PDF FILE" else "CHANGE PDF FILE",
              fontFamily = PixelFont,
              color = theme.primary,
              fontWeight = FontWeight.Bold,
              fontSize = 12.sp
            )
          }

          if (selectedUri != null) {
            Spacer(Modifier.height(14.dp))
            Row(
              Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(theme.bg)
                .border(1.dp, theme.cardBorder, RoundedCornerShape(12.dp))
                .padding(10.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              if (previewBitmap != null) {
                Image(
                  bitmap = previewBitmap!!.asImageBitmap(),
                  contentDescription = "Document Page 1",
                  modifier = Modifier
                    .size(54.dp, 72.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .border(1.dp, theme.cardBorder, RoundedCornerShape(6.dp)),
                  contentScale = ContentScale.Crop
                )
              } else {
                Box(
                  Modifier
                    .size(54.dp, 72.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(theme.card),
                  contentAlignment = Alignment.Center
                ) {
                  Text("📄", fontSize = 24.sp)
                }
              }
              Spacer(Modifier.width(12.dp))
              Column(Modifier.weight(1f)) {
                Text(
                  "Document Selected",
                  fontFamily = PixelFont,
                  fontWeight = FontWeight.Bold,
                  fontSize = 13.sp,
                  color = theme.text
                )
                Spacer(Modifier.height(3.dp))
                Text(
                  "Total Pages: $pageCount",
                  fontFamily = PixelFont,
                  fontSize = 11.sp,
                  color = theme.textSecondary
                )
              }
            }
          }
        }

        // Step 2: Password Security Setup
        if (selectedUri != null) {
          Column(
            Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(16.dp))
              .background(theme.card)
              .border(1.dp, theme.cardBorder, RoundedCornerShape(16.dp))
              .padding(16.dp)
          ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Box(
                Modifier
                  .size(28.dp)
                  .clip(CircleShape)
                  .background(theme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
              ) {
                Text("2", fontFamily = PixelFont, fontWeight = FontWeight.Bold, color = theme.primary, fontSize = 13.sp)
              }
              Spacer(Modifier.width(10.dp))
              Text(
                "SET ENCRYPTION PASSWORD",
                fontFamily = PixelFont,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = theme.text
              )
            }

            Spacer(Modifier.height(14.dp))

            OutlinedTextField(
              value = password,
              onValueChange = { password = it },
              label = { Text("Enter Document Password", fontFamily = PixelFont, fontSize = 12.sp) },
              singleLine = true,
              visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
              trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                  Text(if (passwordVisible) "👁️" else "🔒", fontSize = 16.sp)
                }
              },
              modifier = Modifier.fillMaxWidth(),
              shape = RoundedCornerShape(10.dp)
            )

            Spacer(Modifier.height(10.dp))

            OutlinedTextField(
              value = confirmPassword,
              onValueChange = { confirmPassword = it },
              label = { Text("Confirm Document Password", fontFamily = PixelFont, fontSize = 12.sp) },
              singleLine = true,
              visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
              modifier = Modifier.fillMaxWidth(),
              shape = RoundedCornerShape(10.dp),
              isError = confirmPassword.isNotEmpty() && password != confirmPassword
            )

            if (confirmPassword.isNotEmpty() && password != confirmPassword) {
              Spacer(Modifier.height(4.dp))
              Text(
                "Passwords do not match",
                fontFamily = PixelFont,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.error
              )
            }

            Spacer(Modifier.height(12.dp))

            val strength = when {
              password.isEmpty() -> ""
              password.length < 5 -> "Weak"
              password.length < 8 -> "Moderate"
              password.any { it.isDigit() } && password.any { it.isLetter() } -> "Strong 🛡️"
              else -> "Good"
            }

            if (password.isNotEmpty()) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Security: ", fontFamily = PixelFont, fontSize = 11.sp, color = theme.textSecondary)
                Text(
                  strength,
                  fontFamily = PixelFont,
                  fontWeight = FontWeight.Bold,
                  fontSize = 11.sp,
                  color = if (strength.startsWith("Weak")) MaterialTheme.colorScheme.error else theme.primary
                )
              }
              Spacer(Modifier.height(12.dp))
            }

            // Step 3: Permissions
            Text("PERMISSIONS", fontFamily = PixelFont, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = theme.text)
            Spacer(Modifier.height(6.dp))
            Row(
              Modifier.fillMaxWidth().clickable {
                vibrate()
                blockPrint = !blockPrint
              },
              verticalAlignment = Alignment.CenterVertically
            ) {
              Checkbox(
                checked = blockPrint,
                onCheckedChange = { blockPrint = it },
                colors = CheckboxDefaults.colors(checkedColor = theme.primary)
              )
              Text("Block printing", fontFamily = PixelFont, fontSize = 12.sp, color = theme.text)
            }
            Row(
              Modifier.fillMaxWidth().clickable {
                vibrate()
                blockCopy = !blockCopy
              },
              verticalAlignment = Alignment.CenterVertically
            ) {
              Checkbox(
                checked = blockCopy,
                onCheckedChange = { blockCopy = it },
                colors = CheckboxDefaults.colors(checkedColor = theme.primary)
              )
              Text("Block copying text", fontFamily = PixelFont, fontSize = 12.sp, color = theme.text)
            }
          }
        }

        if (isProcessing) {
          LinearProgressIndicator(Modifier.fillMaxWidth(), color = theme.primary)
          Spacer(Modifier.height(4.dp))
          Text(if (progressStatus.isNotEmpty()) progressStatus else "Encrypting...", fontFamily = PixelFont, fontSize = 11.sp, color = theme.text)
        }
      }
    }

    if (mode == "Unlock") {
      SlideToAction(
        label = "Slide to Unlock PDF",
        theme = theme,
        enabled = !isProcessing && unlockUri != null && unlockPassword.isNotBlank(),
        onSlideComplete = {
          vibrate()
          unlock()
        }
      )
    } else {
      val canEncrypt = password.isNotBlank() && password == confirmPassword && !isProcessing
      SlideToAction(
        label = "Slide to Protect PDF",
        theme = theme,
        enabled = selectedUri != null && canEncrypt,
        onSlideComplete = {
          vibrate()
          protect()
        }
      )
    }
  }
}

// Placeholder for future tools
@Composable
fun FutureToolPlaceholderScreen(toolName: String, theme: ChamiTheme, onBack: () -> Unit) {
  val ctx = LocalContext.current
  val haptic = LocalHapticFeedback.current
  val tm = remember { ThemeManager(ctx) }

  ToolScreenContainer(toolName, theme, onBack) {
    Column(
      Modifier.fillMaxSize(),
      verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      Text(toolName, fontFamily = PixelFont, fontWeight = FontWeight.Black, fontSize = 20.sp, color = theme.primaryDark)
      Spacer(Modifier.height(12.dp))
      Text("Tool Coming Soon", fontFamily = PixelFont, fontSize = 14.sp, color = theme.text)
      Spacer(Modifier.height(8.dp))
      Text("This utility is currently in development.", fontFamily = PixelFont, fontSize = 12.sp, color = theme.text.copy(alpha = 0.7f))
      Spacer(Modifier.height(24.dp))
      Button(
        onClick = {
          if (tm.hapticOn()) {
            try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
          }
          onBack()
        },
        modifier = Modifier.fillMaxWidth().height(48.dp),
        colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
        shape = RoundedCornerShape(8.dp)
      ) {
        Text("RETURN HOME", fontFamily = PixelFont, color = theme.textOnPrimary, fontSize = 12.sp)
      }
    }
  }
}
