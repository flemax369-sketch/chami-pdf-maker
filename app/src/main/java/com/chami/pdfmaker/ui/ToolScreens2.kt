package com.chami.pdfmaker.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chami.pdfmaker.pdf.*
import com.chami.pdfmaker.theme.ChamiTheme
import com.chami.pdfmaker.theme.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

// ---------------------------------------------------------------------------
// ToolScreens (part 2): Merge, Compress, Resize/Convert, OCR, Reader, Protect.
// Shares the helpers in ToolScreens.kt (same package).
// ---------------------------------------------------------------------------

@Composable
fun MergePdfScreen(theme: ChamiTheme, onBack: () -> Unit) {
  val ctx = LocalContext.current
  val haptic = LocalHapticFeedback.current
  val tm = remember { ThemeManager(ctx) }
  val scope = rememberCoroutineScope()
  val density = LocalDensity.current
  var pdfUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
  var pdfPreviews by remember { mutableStateOf<List<Pair<Int, Bitmap?>>>(emptyList()) }
  var pdfSizes by remember { mutableStateOf<List<Long>>(emptyList()) }
  var previewJob by remember { mutableStateOf<Job?>(null) }
  var isProcessing by remember { mutableStateOf(false) }
  var draggingUri by remember { mutableStateOf<Uri?>(null) }
  var dragOffsetY by remember { mutableStateOf(0f) }

  var doneFile by remember { mutableStateOf<File?>(null) }
  var showDone by remember { mutableStateOf(false) }
  val singleSave = rememberSingleFileSaver { file -> doneFile = file; showDone = true }

  fun vibrate() {
    if (tm.hapticOn()) {
      try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
    }
  }

  fun refreshPreviews(uris: List<Uri>) {
    previewJob?.cancel()
    previewJob = scope.launch(Dispatchers.IO) {
      val previews = uris.map { u ->
        val count = PdfEngine.pageCount(ctx, u)
        val bmp = PdfReader.renderPageBitmap(ctx, u, 0, 1.5f)
        Pair(count, bmp)
      }
      val sizes = uris.map { PdfCompress.originalSizeBytes(ctx, it).coerceAtLeast(0) }
      withContext(Dispatchers.Main) {
        if (pdfUris == uris) {
          pdfPreviews = previews
          pdfSizes = sizes
        }
      }
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
    val s = pdfSizes.toMutableList()
    if (from in s.indices && to in s.indices) {
      val si = s.removeAt(from)
      s.add(to, si)
      pdfSizes = s
    }
  }

  fun merge() {
    if (pdfUris.size < 2) {
      Toast.makeText(ctx, "Select at least 2 PDFs to merge", Toast.LENGTH_SHORT).show()
      return
    }
    isProcessing = true
    scope.launch {
      val out = PdfEngine.outFile(ctx, "MERGED_${System.currentTimeMillis()}.pdf")
      val sources = pdfUris.map { it to null }
      val ok = withContext(Dispatchers.IO) { PdfEngine.copyPages(ctx, sources, out) }
      isProcessing = false
      if (ok) singleSave(out)
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
      ChamiSecondaryButton(
        text = "Choose PDFs (${pdfUris.size} selected)",
        onClick = { vibrate(); picker.launch("application/pdf") },
        modifier = Modifier.fillMaxWidth()
      )
      HintText("Merged pages are rendered as images — text in the merged file is not selectable.")

      if (pdfPreviews.isNotEmpty()) {
        Spacer(Modifier.height(Ds.s16))
        val totalCombinedPages = pdfPreviews.sumOf { it.first }
        Text(
          "$totalCombinedPages total pages combined",
          fontWeight = FontWeight.Bold, fontSize = Ds.BODY_SP.sp, color = theme.text
        )
        Spacer(Modifier.height(Ds.s8))
        HintText("Long-press a row and drag to reorder. Arrow buttons move it one step.")
        Spacer(Modifier.height(Ds.s8))
        val rowPx = with(density) { 112.dp.toPx() }
        Column(verticalArrangement = Arrangement.spacedBy(Ds.s12)) {
          pdfPreviews.forEachIndexed { index, pair ->
            val rowUri = pdfUris.getOrNull(index)
            val isDragging = draggingUri != null && draggingUri == rowUri
            Row(
              Modifier
                .fillMaxWidth()
                .graphicsLayer {
                  translationY = if (isDragging) dragOffsetY else 0f
                  scaleX = if (isDragging) 1.02f else 1f
                  scaleY = if (isDragging) 1.02f else 1f
                  shadowElevation = if (isDragging) 12f else 0f
                }
                .clip(RoundedCornerShape(Ds.s8))
                .background(theme.card)
                .border(1.dp, theme.cardBorder, RoundedCornerShape(Ds.s8))
                .pointerInput(rowUri) {
                  detectDragGesturesAfterLongPress(
                    onDragStart = {
                      draggingUri = rowUri
                      dragOffsetY = 0f
                      try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
                    },
                    onDrag = { change, dragAmount ->
                      change.consume()
                      dragOffsetY += dragAmount.y
                      val cur = pdfUris.indexOf(rowUri)
                      if (cur < 0) return@detectDragGesturesAfterLongPress
                      val halfPx = rowPx / 2f
                      if (dragOffsetY > halfPx && cur < pdfUris.lastIndex) {
                        moveFile(cur, cur + 1)
                        dragOffsetY -= rowPx
                      } else if (dragOffsetY < -halfPx && cur > 0) {
                        moveFile(cur, cur - 1)
                        dragOffsetY += rowPx
                      }
                    },
                    onDragEnd = { draggingUri = null; dragOffsetY = 0f },
                    onDragCancel = { draggingUri = null; dragOffsetY = 0f }
                  )
                }
                .padding(Ds.s8),
              verticalAlignment = Alignment.CenterVertically
            ) {
              Icon(Icons.Filled.DragHandle, contentDescription = "Drag to reorder", tint = theme.textSecondary, modifier = Modifier.size(24.dp))
              Spacer(Modifier.width(Ds.s8))
              pair.second?.let { bmp ->
                Image(
                  bitmap = bmp.asImageBitmap(),
                  contentDescription = "PDF ${index + 1}",
                  modifier = Modifier.size(56.dp).clip(RoundedCornerShape(Ds.s4)),
                  contentScale = ContentScale.Fit
                )
              }
              Spacer(Modifier.width(Ds.s12))
              Column(Modifier.weight(1f)) {
                Text(
                  displayNameOf(ctx, pdfUris[index]) ?: "Doc ${index + 1}",
                  fontSize = Ds.BODY_SP.sp, color = theme.text, fontWeight = FontWeight.Bold,
                  maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                  "${pair.first} pages • ${formatSizeText(pdfSizes.getOrNull(index) ?: 0)}",
                  fontSize = Ds.HINT_SP.sp, color = theme.textSecondary
                )
              }
              Column(verticalArrangement = Arrangement.spacedBy(Ds.s4)) {
                ChamiIconButton(
                  icon = Icons.Filled.KeyboardArrowUp, description = "Move up",
                  onClick = { vibrate(); moveFile(index, index - 1) }
                )
                ChamiIconButton(
                  icon = Icons.Filled.KeyboardArrowDown, description = "Move down",
                  onClick = { vibrate(); moveFile(index, index + 1) }
                )
              }
              ChamiIconButton(
                icon = Icons.Filled.Close, description = "Remove file",
                onClick = {
                  vibrate()
                  val m = pdfUris.toMutableList()
                  m.removeAt(index)
                  pdfUris = m
                  refreshPreviews(m)
                }
              )
            }
          }
        }
      }

      Spacer(Modifier.height(Ds.s16))
      if (isProcessing) {
        LinearProgressIndicator(Modifier.fillMaxWidth(), color = theme.primary)
        Spacer(Modifier.height(Ds.s8))
        Text("Merging PDF pages...", fontSize = Ds.HINT_SP.sp, color = theme.text)
      }
    }

    SlideToAction(
      label = "Slide to Merge",
      theme = theme,
      enabled = !isProcessing && pdfUris.size >= 2,
      onSlideComplete = { vibrate(); merge() }
    )
  }
}

// 6. COMPRESS KB — Images / PDF tabs (48dp). Images: multi-select,
// quality slider 10-100% (default 70%) with live estimated size,
// presets Under 100 / 200 / 500 KB / 1 MB + custom KB field. If the
// target cannot be reached, the user is told BEFORE sliding
// ("Smallest possible is about X KB"). PDF: the quality slider uses
// the exact quality (no hidden 3-preset mapping); pages are
// re-encoded as images, so text becomes an image — the UI says so
// plainly. Done shows actual before/after + % saved, and says plainly
// if the output is not smaller (the smaller file is kept).
@Composable
fun CompressKbScreen(theme: ChamiTheme, onBack: () -> Unit) {
  val ctx = LocalContext.current
  val haptic = LocalHapticFeedback.current
  val tm = remember { ThemeManager(ctx) }
  val scope = rememberCoroutineScope()

  var selectedTab by rememberSaveable { mutableStateOf(0) } // 0: IMAGE, 1: PDF
  var quality by rememberSaveable { mutableStateOf(70) }

  // --- IMAGE TAB STATE ---
  var imageUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
  var imageOrigKb by remember { mutableStateOf(0L) }
  var imagePreview by remember { mutableStateOf<Bitmap?>(null) }
  var targetKb by rememberSaveable { mutableStateOf<Int?>(null) }
  var customInput by rememberSaveable { mutableStateOf("") }
  var minPossibleKb by remember { mutableStateOf<Long?>(null) }
  var isImageProcessing by remember { mutableStateOf(false) }
  var imageProgressText by remember { mutableStateOf("") }
  var imageResults by remember { mutableStateOf<List<String>>(emptyList()) }

  // --- PDF TAB STATE ---
  var pdfUri by remember { mutableStateOf<Uri?>(null) }
  var pdfOrigBytes by remember { mutableStateOf(0L) }
  var pdfTotalPages by remember { mutableStateOf(0) }
  var pdfPreviewBmp by remember { mutableStateOf<Bitmap?>(null) }
  var isPdfProcessing by remember { mutableStateOf(false) }
  var pdfProgressText by remember { mutableStateOf("") }

  // --- DONE STATE ---
  var doneFiles by remember { mutableStateOf<List<DoneFileInfo>?>(null) }
  var doneSummary by remember { mutableStateOf<List<String>>(emptyList()) }
  var showDone by remember { mutableStateOf(false) }
  val folderSave = rememberFolderSaver(
    onSaved = { files ->
      doneFiles = files.map { DoneFileInfo(it, if (selectedTab == 0) "image/jpeg" else "application/pdf", formatSizeText(it.length())) }
      showDone = true
    },
    onCancelled = { Toast.makeText(ctx, "Save cancelled — files kept in Chami folder", Toast.LENGTH_SHORT).show() }
  )
  val singleSave = rememberSingleFileSaver { file ->
    doneFiles = listOf(DoneFileInfo(file, "application/pdf", formatSizeText(file.length())))
    showDone = true
  }

  fun vibrate() {
    if (tm.hapticOn()) {
      try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
    }
  }

  val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selected ->
    if (selected.isNotEmpty()) {
      imageUris = imageUris + selected
      showDone = false
      doneFiles = null
      imageResults = emptyList()
      scope.launch(Dispatchers.IO) {
        val first = selected.firstOrNull() ?: return@launch
        val bmp = PdfEngine.loadBitmap(ctx, first, 1200)
        var size = 0L
        try {
          ctx.contentResolver.openInputStream(first)?.use { input ->
            var total = 0L
            val buf = ByteArray(8192)
            var n: Int
            while (input.read(buf).also { n = it } != -1) total += n
            size = total / 1024L
          }
        } catch (_: Exception) {}
        withContext(Dispatchers.Main) {
          imagePreview = bmp
          imageOrigKb = size
        }
      }
    }
  }

  // Smallest-possible estimate for the first image: JPEG at quality 20
  // of a 1200px preview, scaled up by pixel count. Shown BEFORE sliding.
  LaunchedEffect(imageUris, targetKb) {
    val first = imageUris.firstOrNull()
    if (first == null) { minPossibleKb = null; return@LaunchedEffect }
    minPossibleKb = withContext(Dispatchers.IO) {
      try {
        val bmp = PdfEngine.loadBitmap(ctx, first, 1200) ?: return@withContext null
        val baos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 20, baos)
        val previewBytes = baos.size().toLong()
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(first)?.use { BitmapFactory.decodeStream(it, null, opts) }
        val fullPixels = (opts.outWidth * opts.outHeight).toLong().coerceAtLeast(1)
        val prevPixels = (bmp.width * bmp.height).toLong().coerceAtLeast(1)
        bmp.recycle()
        (previewBytes * fullPixels / prevPixels / 1024L).coerceAtLeast(1)
      } catch (_: Exception) { null }
    }
  }

  val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { selected ->
    pdfUri = selected
    showDone = false
    doneFiles = null
    pdfPreviewBmp = null
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

  // If the compressed file is not smaller, keep the smaller one: copy
  // the original over the output so the saved file is never bigger.
  fun keepSmaller(originalUri: Uri, out: File): Boolean {
    return try {
      val origBytes = PdfCompress.originalSizeBytes(ctx, originalUri)
      if (origBytes > 0 && out.exists() && out.length() > origBytes) {
        ctx.contentResolver.openInputStream(originalUri)?.use { input ->
          FileOutputStream(out).use { o -> input.copyTo(o) }
        }
        true // output was not smaller; original kept
      } else false
    } catch (_: Exception) { false }
  }

  val estimatedImageKb: Long? = if (imageUris.isEmpty()) null
    else targetKb?.toLong() ?: (imageOrigKb * quality / 100L).coerceAtLeast(1)
  val targetUnreachable = targetKb != null && minPossibleKb != null && targetKb!! < minPossibleKb!!

  fun compressImages() {
    if (imageUris.isEmpty()) return
    isImageProcessing = true
    imageResults = emptyList()
    scope.launch {
      val outs = mutableListOf<File>()
      val lines = mutableListOf<String>()
      imageUris.forEachIndexed { i, u ->
        withContext(Dispatchers.Main) { imageProgressText = "File ${i + 1} of ${imageUris.size}" }
        val origKb = (PdfCompress.originalSizeBytes(ctx, u) / 1024L).coerceAtLeast(0)
        val tgt = targetKb ?: (origKb * quality / 100L).toInt().coerceAtLeast(10)
        val out = PdfEngine.outFile(ctx, "Compressed_${i + 1}_${System.currentTimeMillis()}.jpg")
        val ok = withContext(Dispatchers.IO) { PdfEngine.compressImageToKb(ctx, u, tgt, out) }
        if (ok && out.exists()) {
          val notSmaller = keepSmaller(u, out)
          val afterKb = out.length() / 1024L
          val savedPct = if (origKb > 0) ((origKb - afterKb) * 100 / origKb) else 0
          lines.add(
            if (notSmaller || afterKb >= origKb) "${displayNameOf(ctx, u) ?: "Image ${i + 1}"}: ${origKb} KB to ${afterKb} KB — not smaller, original kept"
            else "${displayNameOf(ctx, u) ?: "Image ${i + 1}"}: ${origKb} KB to ${afterKb} KB (${savedPct}% saved)"
          )
          outs.add(out)
        } else {
          lines.add("${displayNameOf(ctx, u) ?: "Image ${i + 1}"}: could not compress")
        }
      }
      isImageProcessing = false
      imageProgressText = ""
      imageResults = lines
      if (outs.isNotEmpty()) {
        doneSummary = lines
        folderSave(outs.map { it to "image/jpeg" })
      } else {
        Toast.makeText(ctx, "Compression failed", Toast.LENGTH_SHORT).show()
      }
    }
  }

  fun compressPdf() {
    val u = pdfUri ?: return
    isPdfProcessing = true
    pdfProgressText = "Compressing PDF pages..."
    scope.launch {
      val out = PdfEngine.outFile(ctx, "Compressed_${System.currentTimeMillis()}.pdf")
      val res = withContext(Dispatchers.IO) { compressPdfWithQuality(ctx, u, quality, out) }
      isPdfProcessing = false
      pdfProgressText = ""
      if (res.ok && out.exists()) {
        val origKb = pdfOrigBytes / 1024L
        val afterKb = out.length() / 1024L
        val notSmaller = afterKb >= origKb
        if (notSmaller) keepSmaller(u, out)
        val finalKb = out.length() / 1024L
        val savedPct = if (origKb > 0) ((origKb - finalKb) * 100 / origKb).coerceAtLeast(0) else 0
        doneSummary = if (notSmaller) {
          listOf("Before: ${origKb} KB — after: ${finalKb} KB.", "The output was not smaller, so the original file was kept.")
        } else {
          listOf("Before: ${origKb} KB — after: ${finalKb} KB.", "Saved $savedPct%.")
        }
        singleSave(out)
      } else {
        Toast.makeText(ctx, "PDF compression failed", Toast.LENGTH_SHORT).show()
      }
    }
  }

  if (showDone && doneFiles != null) {
    MultiDoneScreen(
      files = doneFiles!!,
      title = "Compressed",
      summaryLines = doneSummary,
      theme = theme,
      onBackHome = onBack,
      onOpen = { ShareHelper.openFile(ctx, it, if (selectedTab == 0) "image/jpeg" else "application/pdf") }
    )
    return
  }

  ToolScreenContainer("Compress KB", theme, onBack) {
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(Ds.s8))
          .background(theme.card)
          .padding(Ds.s4),
        horizontalArrangement = Arrangement.spacedBy(Ds.s4)
      ) {
        listOf("Compress image" to 0, "Compress PDF" to 1).forEach { (label, tab) ->
          Box(
            modifier = Modifier
              .weight(1f)
              .height(Ds.minTouch)
              .clip(RoundedCornerShape(Ds.s8))
              .background(if (selectedTab == tab) theme.primary else Color.Transparent)
              .clickable { vibrate(); selectedTab = tab },
            contentAlignment = Alignment.Center
          ) {
            Text(
              label,
              fontWeight = FontWeight.Bold,
              fontSize = Ds.HINT_SP.sp,
              color = if (selectedTab == tab) Color.White else theme.text
            )
          }
        }
      }

      Spacer(Modifier.height(Ds.s16))

      if (selectedTab == 0) {
        ChamiSecondaryButton(
          text = if (imageUris.isNotEmpty()) "${imageUris.size} image${if (imageUris.size == 1) "" else "s"} chosen" else "Select images",
          onClick = { vibrate(); imagePicker.launch("image/*") },
          modifier = Modifier.fillMaxWidth()
        )
        if (imagePreview != null) {
          Spacer(Modifier.height(Ds.s16))
          SectionText("Original preview", color = theme.text)
          Spacer(Modifier.height(Ds.s8))
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(Ds.s8))
              .background(theme.card)
              .padding(Ds.s12),
            horizontalAlignment = Alignment.CenterHorizontally
          ) {
            Image(
              bitmap = imagePreview!!.asImageBitmap(),
              contentDescription = "Original Preview",
              modifier = Modifier.fillMaxWidth().height(320.dp).clip(RoundedCornerShape(Ds.s8)),
              contentScale = ContentScale.Fit
            )
            Spacer(Modifier.height(Ds.s8))
            Text("Original size: ${if (imageOrigKb > 0) "$imageOrigKb KB" else "—"}", fontSize = Ds.BODY_SP.sp, color = theme.text, fontWeight = FontWeight.Bold)
            estimatedImageKb?.let {
              Text("Estimated output: about $it KB", fontSize = Ds.HINT_SP.sp, color = theme.primaryDark)
            }
          }
        }
        Spacer(Modifier.height(Ds.s16))
        SectionText("Target size", color = theme.text)
        Spacer(Modifier.height(Ds.s8))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Ds.s8)) {
          listOf(100 to "Under 100 KB", 200 to "Under 200 KB", 500 to "Under 500 KB", 1024 to "Under 1 MB").forEach { (kb, label) ->
            ChamiChip(label, targetKb == kb, { vibrate(); targetKb = kb; customInput = "" })
          }
        }
        Spacer(Modifier.height(Ds.s12))
        DebouncedTextField(
          value = customInput,
          onValueChange = {
            customInput = it.filter { ch -> ch.isDigit() }
            targetKb = customInput.toIntOrNull()?.coerceAtLeast(10)
          },
          label = "Custom target KB (optional)", keyboardType = KeyboardType.Number
        )
        if (targetUnreachable) {
          Spacer(Modifier.height(Ds.s8))
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = ChamiColors.warningAmber, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(Ds.s8))
            Text(
              "Smallest possible is about ${minPossibleKb} KB — this target cannot be reached.",
              fontSize = Ds.HINT_SP.sp, color = ChamiColors.warningAmber
            )
          }
        }
        Spacer(Modifier.height(Ds.s16))
        Text("Quality: $quality%", fontWeight = FontWeight.Bold, fontSize = Ds.BODY_SP.sp, color = theme.text)
        HintText("Used when no target size is set. Lower quality = smaller file.")
        Slider(
          value = quality.toFloat(),
          onValueChange = { quality = it.toInt() },
          valueRange = 10f..100f,
          colors = SliderDefaults.colors(thumbColor = theme.primary, activeTrackColor = theme.primary)
        )
        if (imageResults.isNotEmpty()) {
          Spacer(Modifier.height(Ds.s12))
          Column(verticalArrangement = Arrangement.spacedBy(Ds.s4)) {
            imageResults.forEach { line ->
              Text(line, fontSize = Ds.HINT_SP.sp, color = theme.text)
            }
          }
        }
        Spacer(Modifier.height(Ds.s16))
        if (isImageProcessing) {
          LinearProgressIndicator(Modifier.fillMaxWidth(), color = theme.primary)
          Spacer(Modifier.height(Ds.s8))
          Text(imageProgressText, fontSize = Ds.HINT_SP.sp, color = theme.text)
        }
      } else {
        ChamiSecondaryButton(
          text = if (pdfUri != null) "PDF selected ($pdfTotalPages pages)" else "Select PDF",
          onClick = { vibrate(); pdfPicker.launch("application/pdf") },
          modifier = Modifier.fillMaxWidth()
        )
        if (pdfUri != null) {
          Spacer(Modifier.height(Ds.s16))
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(Ds.s8))
              .background(theme.card)
              .padding(Ds.s12),
            verticalAlignment = Alignment.CenterVertically
          ) {
            pdfPreviewBmp?.let { bmp ->
              Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "PDF Preview",
                modifier = Modifier.size(120.dp).clip(RoundedCornerShape(Ds.s8)),
                contentScale = ContentScale.Fit
              )
              Spacer(Modifier.width(Ds.s12))
            }
            Column {
              val kb = pdfOrigBytes / 1024L
              Text("Original size: ${formatSizeText(pdfOrigBytes)}", fontSize = Ds.BODY_SP.sp, color = theme.text, fontWeight = FontWeight.Bold)
              Text("Total pages: $pdfTotalPages", fontSize = Ds.HINT_SP.sp, color = theme.primaryDark)
              val estKb = (kb * quality / 100L).coerceAtLeast(1)
              Text("Estimated output: about $estKb KB", fontSize = Ds.HINT_SP.sp, color = theme.primaryDark)
            }
          }
          Spacer(Modifier.height(Ds.s16))
          Text("Quality: $quality%", fontWeight = FontWeight.Bold, fontSize = Ds.BODY_SP.sp, color = theme.text)
          HintText("Lower quality = smaller file. Pages are re-encoded as images, so text in the output becomes an image and is not selectable.")
          Slider(
            value = quality.toFloat(),
            onValueChange = { quality = it.toInt() },
            valueRange = 10f..100f,
            colors = SliderDefaults.colors(thumbColor = theme.primary, activeTrackColor = theme.primary)
          )
        }
        Spacer(Modifier.height(Ds.s16))
        if (isPdfProcessing) {
          LinearProgressIndicator(Modifier.fillMaxWidth(), color = theme.primary)
          Spacer(Modifier.height(Ds.s8))
          Text(if (pdfProgressText.isNotEmpty()) pdfProgressText else "Compressing PDF pages...", fontSize = Ds.HINT_SP.sp, color = theme.text)
        }
      }
    }

    if (selectedTab == 0) {
      SlideToAction(
        label = "Slide to Compress",
        theme = theme,
        enabled = !isImageProcessing && imageUris.isNotEmpty() && !targetUnreachable,
        onSlideComplete = { vibrate(); compressImages() }
      )
    } else {
      SlideToAction(
        label = "Slide to Compress",
        theme = theme,
        enabled = !isPdfProcessing && pdfUri != null,
        onSlideComplete = { vibrate(); compressPdf() }
      )
    }
  }
}

// Re-encodes an image file at the given JPEG/WebP quality (the engine's
// resizeConvert always writes quality 90, so the slider needs this).
private fun reencodeAtQuality(ctx: Context, file: File, format: Bitmap.CompressFormat, quality: Int): Boolean {
  return try {
    val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return false
    var ok = false
    FileOutputStream(file).use { ok = bmp.compress(format, quality.coerceIn(10, 100), it) }
    bmp.recycle()
    ok
  } catch (_: Exception) { false }
}

private fun sourceFormatOf(ctx: Context, uri: Uri): String {
  val mime = try { ctx.contentResolver.getType(uri) } catch (_: Exception) { null }
  val name = displayNameOf(ctx, uri) ?: ""
  return when {
    mime?.contains("png", ignoreCase = true) == true || name.endsWith(".png", ignoreCase = true) -> "PNG"
    mime?.contains("webp", ignoreCase = true) == true || name.endsWith(".webp", ignoreCase = true) -> "WEBP"
    else -> "JPG"
  }
}

// 8. IMAGE RESIZER & 10. FORMAT CONVERT — one shared screen; the title
// MUST be "Format Convert" when entered from its tile. Resizer: single
// image, presets, lock-ratio (on by default), percentage shortcuts,
// exact-KB target reported within ±5% on Done. Convert: batch,
// target JPG/PNG/WebP, files already in the target format are marked
// "Already JPG" and skipped, quality slider only for lossy targets,
// per-file progress + result list, one save folder for the batch.
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
  var widthStr by rememberSaveable { mutableStateOf("800") }
  var heightStr by rememberSaveable { mutableStateOf("800") }
  var lockRatio by rememberSaveable { mutableStateOf(true) }
  var formatChoice by rememberSaveable { mutableStateOf("JPG") }
  var quality by rememberSaveable { mutableStateOf(90) }
  var targetKbStr by rememberSaveable { mutableStateOf("") }
  var isProcessing by remember { mutableStateOf(false) }
  var progressText by remember { mutableStateOf("") }
  var batchResults by remember { mutableStateOf<List<String>>(emptyList()) }

  var doneFiles by remember { mutableStateOf<List<DoneFileInfo>?>(null) }
  var doneSummary by remember { mutableStateOf<List<String>>(emptyList()) }
  var showDone by remember { mutableStateOf(false) }
  val folderSave = rememberFolderSaver(
    onSaved = { files ->
      val mime = if (formatChoice == "PNG") "image/png" else if (formatChoice == "WEBP") "image/webp" else "image/jpeg"
      doneFiles = files.map { DoneFileInfo(it, mime, formatSizeText(it.length())) }
      showDone = true
    },
    onCancelled = { Toast.makeText(ctx, "Save cancelled — files kept in Chami folder", Toast.LENGTH_SHORT).show() }
  )
  val singleSave = rememberSingleFileSaver { file ->
    val mime = if (formatChoice == "PNG") "image/png" else if (formatChoice == "WEBP") "image/webp" else "image/jpeg"
    doneFiles = listOf(DoneFileInfo(file, mime, formatSizeText(file.length())))
    showDone = true
  }

  fun vibrate() {
    if (tm.hapticOn()) {
      try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
    }
  }

  fun applyRatioFromWidth(w: Int) {
    val (ow, oh) = origDimensions ?: return
    if (lockRatio && ow > 0) heightStr = ((w.toLong() * oh / ow).toInt().coerceAtLeast(1)).toString()
  }
  fun applyRatioFromHeight(h: Int) {
    val (ow, oh) = origDimensions ?: return
    if (lockRatio && oh > 0) widthStr = ((h.toLong() * ow / oh).toInt().coerceAtLeast(1)).toString()
  }

  val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { selected ->
    imageUri = selected
    showDone = false
    doneFiles = null
    previewBitmap = null
    batchResults = emptyList()
    if (selected != null) {
      formatChoice = sourceFormatOf(ctx, selected)
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
      doneFiles = null
      batchResults = emptyList()
      val first = selected.firstOrNull()
      if (first != null && previewBitmap == null) {
        scope.launch(Dispatchers.IO) {
          val bmp = PdfEngine.loadBitmap(ctx, first, 1200)
          withContext(Dispatchers.Main) { previewBitmap = bmp }
        }
      }
    }
  }
  val widthInt = widthStr.toIntOrNull()
  val heightInt = heightStr.toIntOrNull()
  val dimsValid = widthInt != null && heightInt != null && widthInt > 0 && heightInt > 0
  val upscaleFactor: Float? = origDimensions?.let { (ow, oh) ->
    if (widthInt != null && heightInt != null && ow > 0 && oh > 0) {
      maxOf(widthInt.toFloat() / ow, heightInt.toFloat() / oh)
    } else null
  }
  val upscaleWarning = upscaleFactor != null && upscaleFactor > 2f

  fun convertSingle() {
    val u = imageUri ?: return
    if (!dimsValid) {
      Toast.makeText(ctx, "Enter a width and height above 0", Toast.LENGTH_SHORT).show()
      return
    }
    val w = widthInt!!
    val h = heightInt!!
    val format = when (formatChoice) {
      "PNG" -> Bitmap.CompressFormat.PNG
      "WEBP" -> Bitmap.CompressFormat.WEBP
      else -> Bitmap.CompressFormat.JPEG
    }
    val ext = formatChoice.lowercase()
    val kbTarget = targetKbStr.toIntOrNull()
    isProcessing = true
    progressText = "Resizing..."
    scope.launch {
      val origKb = (PdfCompress.originalSizeBytes(ctx, u) / 1024L).coerceAtLeast(0)
      val tmp = PdfEngine.outFile(ctx, "Resized_${w}x${h}_${System.currentTimeMillis()}.$ext")
      val ok = withContext(Dispatchers.IO) { PdfEngine.resizeConvert(ctx, u, w, h, format, tmp) }
      var finalFile = tmp
      var kbNote: String? = null
      if (ok && formatChoice != "PNG" && quality != 90) {
        withContext(Dispatchers.IO) { reencodeAtQuality(ctx, tmp, format, quality) }
      }
      if (ok && kbTarget != null && kbTarget > 0) {
        if (formatChoice == "JPG") {
          val out2 = PdfEngine.outFile(ctx, "Resized_${kbTarget}KB_${System.currentTimeMillis()}.jpg")
          val ok2 = withContext(Dispatchers.IO) { PdfEngine.compressImageToKb(ctx, Uri.fromFile(tmp), kbTarget, out2) }
          if (ok2) {
            finalFile = out2
            val actualKb = out2.length() / 1024L
            val devPct = ((actualKb - kbTarget) * 100.0 / kbTarget)
            kbNote = if (kotlin.math.abs(devPct) <= 5.0) {
              "Exact-KB target ${kbTarget} KB — actual ${actualKb} KB (within ±5%)."
            } else {
              "Exact-KB target ${kbTarget} KB — actual ${actualKb} KB (outside ±5%, closest the engine could reach)."
            }
          }
        } else {
          withContext(Dispatchers.Main) {
            Toast.makeText(ctx, "Exact KB target works with JPG — saved at chosen size instead", Toast.LENGTH_LONG).show()
          }
        }
      }
      isProcessing = false
      progressText = ""
      if (ok) {
        val afterKb = finalFile.length() / 1024L
        doneSummary = listOfNotNull(
          "Before: ${origDimensions?.let { "${it.first}x${it.second} px" } ?: "—"} (${origKb} KB).",
          "After: ${w}x${h} px (${afterKb} KB).",
          kbNote
        )
        singleSave(finalFile)
      } else {
        Toast.makeText(ctx, "Failed to resize", Toast.LENGTH_SHORT).show()
      }
    }
  }

  fun convertBatch() {
    if (imageUris.isEmpty()) return
    if (!dimsValid) {
      Toast.makeText(ctx, "Enter a width and height above 0", Toast.LENGTH_SHORT).show()
      return
    }
    val w = widthInt!!
    val h = heightInt!!
    val format = when (formatChoice) {
      "PNG" -> Bitmap.CompressFormat.PNG
      "WEBP" -> Bitmap.CompressFormat.WEBP
      else -> Bitmap.CompressFormat.JPEG
    }
    val ext = formatChoice.lowercase()
    isProcessing = true
    batchResults = emptyList()
    scope.launch {
      val outs = mutableListOf<File>()
      val lines = mutableListOf<String>()
      var done = 0
      var skipped = 0
      for ((idx, u) in imageUris.withIndex()) {
        withContext(Dispatchers.Main) { progressText = "File ${idx + 1} of ${imageUris.size}" }
        val srcFmt = sourceFormatOf(ctx, u)
        val name = displayNameOf(ctx, u) ?: "Image ${idx + 1}"
        if (srcFmt == formatChoice) {
          skipped++
          lines.add("$name: Already $formatChoice — skipped")
          continue
        }
        val out = PdfEngine.outFile(ctx, "Converted_${idx + 1}_${System.currentTimeMillis()}.$ext")
        val ok = withContext(Dispatchers.IO) { PdfEngine.resizeConvert(ctx, u, w, h, format, out) }
        if (ok && out.exists()) {
          if (formatChoice != "PNG" && quality != 90) {
            withContext(Dispatchers.IO) { reencodeAtQuality(ctx, out, format, quality) }
          }
          outs.add(out)
          done++
          lines.add("$name: converted to $formatChoice (${formatSizeText(out.length())})")
        } else {
          lines.add("$name: could not convert")
        }
        withContext(Dispatchers.Main) { batchResults = lines.toList() }
      }
      isProcessing = false
      progressText = ""
      batchResults = lines
      if (outs.isNotEmpty()) {
        doneSummary = lines + listOf("$done converted, $skipped already $formatChoice and skipped.")
        folderSave(outs.map { it to if (formatChoice == "PNG") "image/png" else if (formatChoice == "WEBP") "image/webp" else "image/jpeg" })
      } else {
        Toast.makeText(ctx, "Nothing to convert — all files are already $formatChoice", Toast.LENGTH_LONG).show()
      }
    }
  }

  if (showDone && doneFiles != null) {
    MultiDoneScreen(
      files = doneFiles!!,
      title = if (isBatch) "Converted" else "Resized",
      summaryLines = doneSummary,
      theme = theme,
      onBackHome = onBack,
      onOpen = {
        ShareHelper.openFile(ctx, it, if (formatChoice == "PNG") "image/png" else if (formatChoice == "WEBP") "image/webp" else "image/jpeg")
      }
    )
    return
  }

  ToolScreenContainer(title, theme, onBack) {
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
      if (isBatch) {
        ChamiSecondaryButton(
          text = if (imageUris.isNotEmpty()) "${imageUris.size} images chosen" else "Select images",
          onClick = { vibrate(); batchPicker.launch("image/*") },
          modifier = Modifier.fillMaxWidth()
        )
        if (imageUris.isNotEmpty()) {
          Spacer(Modifier.height(Ds.s12))
          Column(verticalArrangement = Arrangement.spacedBy(Ds.s4)) {
            imageUris.forEachIndexed { i, uri ->
              val srcFmt = sourceFormatOf(ctx, uri)
              val already = srcFmt == formatChoice
              Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                  "${i + 1}. ${displayNameOf(ctx, uri) ?: "image"} ($srcFmt)",
                  fontSize = Ds.BODY_SP.sp, color = theme.text,
                  modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                if (already) {
                  Text(
                    "Already $formatChoice",
                    fontSize = Ds.HINT_SP.sp, color = theme.primaryDark, fontWeight = FontWeight.SemiBold
                  )
                }
                ChamiIconButton(
                  icon = Icons.Filled.Close, description = "Remove",
                  onClick = { vibrate(); imageUris = imageUris.filterIndexed { idx, _ -> idx != i } }
                )
              }
            }
          }
          if (isProcessing || batchResults.isNotEmpty()) {
            Spacer(Modifier.height(Ds.s8))
            Text(progressText.ifBlank { "Converted ${batchResults.size} files" }, fontSize = Ds.HINT_SP.sp, color = theme.primaryDark, fontWeight = FontWeight.Bold)
            Column(verticalArrangement = Arrangement.spacedBy(Ds.s4)) {
              batchResults.forEach { line ->
                Text(line, fontSize = Ds.HINT_SP.sp, color = theme.text)
              }
            }
          }
        }
      } else {
        ChamiSecondaryButton(
          text = if (imageUri != null) "Image loaded" else "Select image",
          onClick = { vibrate(); picker.launch("image/*") },
          modifier = Modifier.fillMaxWidth()
        )
      }

      if (previewBitmap != null) {
        Spacer(Modifier.height(Ds.s16))
        SectionText("Preview", color = theme.text)
        Spacer(Modifier.height(Ds.s8))
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Ds.s8))
            .background(theme.card)
            .padding(Ds.s12),
          horizontalAlignment = Alignment.CenterHorizontally
        ) {
          Image(
            bitmap = previewBitmap!!.asImageBitmap(),
            contentDescription = "Preview",
            modifier = Modifier.fillMaxWidth().height(320.dp).clip(RoundedCornerShape(Ds.s8)),
            contentScale = ContentScale.Fit
          )
          Spacer(Modifier.height(Ds.s8))
          origDimensions?.let { (w, h) ->
            Text("Original: ${w}x$h px", fontSize = Ds.HINT_SP.sp, color = theme.text)
          }
          Text("Target: ${widthStr}x${heightStr} px ($formatChoice)", fontSize = Ds.HINT_SP.sp, color = theme.primaryDark, fontWeight = FontWeight.Bold)
        }
      }

      Spacer(Modifier.height(Ds.s16))
      SectionText("Presets", color = theme.text)
      Spacer(Modifier.height(Ds.s8))
      Row(horizontalArrangement = Arrangement.spacedBy(Ds.s8)) {
        listOf(
          "WhatsApp" to ("1080" to "1080"),
          "Instagram post" to ("1080" to "1350"),
          "Instagram story" to ("1080" to "1920"),
          "Passport" to ("413" to "531"),
          "A4" to ("1240" to "1754")
        ).forEach { (label, wh) ->
          val isSel = widthStr == wh.first && heightStr == wh.second
          ChamiChip(label, isSel, {
            vibrate()
            widthStr = wh.first
            heightStr = wh.second
          })
        }
      }

      Spacer(Modifier.height(Ds.s16))
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Lock aspect ratio", fontSize = Ds.BODY_SP.sp, color = theme.text)
        Switch(checked = lockRatio, onCheckedChange = { lockRatio = it })
      }
      Spacer(Modifier.height(Ds.s8))
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Ds.s8)) {
        DebouncedTextField(
          value = widthStr,
          onValueChange = {
            widthStr = it.filter { ch -> ch.isDigit() }
            widthStr.toIntOrNull()?.let { w -> applyRatioFromWidth(w) }
          },
          label = "Width (px)", keyboardType = KeyboardType.Number,
          isError = !dimsValid, modifier = Modifier.weight(1f)
        )
        DebouncedTextField(
          value = heightStr,
          onValueChange = {
            heightStr = it.filter { ch -> ch.isDigit() }
            heightStr.toIntOrNull()?.let { h -> applyRatioFromHeight(h) }
          },
          label = "Height (px)", keyboardType = KeyboardType.Number,
          isError = !dimsValid, modifier = Modifier.weight(1f)
        )
      }
      if (!dimsValid) {
        Spacer(Modifier.height(Ds.s4))
        Text("Enter a width and height above 0.", fontSize = Ds.HINT_SP.sp, color = ChamiColors.errorRed)
      }
      Spacer(Modifier.height(Ds.s12))
      Row(horizontalArrangement = Arrangement.spacedBy(Ds.s8)) {
        listOf("25%" to 0.25f, "50%" to 0.5f, "75%" to 0.75f, "Original" to 1f).forEach { (label, pct) ->
          ChamiChip(label, false, {
            vibrate()
            origDimensions?.let { (ow, oh) ->
              widthStr = (ow * pct).toInt().coerceAtLeast(1).toString()
              heightStr = (oh * pct).toInt().coerceAtLeast(1).toString()
            }
          })
        }
      }
      if (upscaleWarning) {
        Spacer(Modifier.height(Ds.s12))
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.Filled.Warning, contentDescription = null, tint = ChamiColors.warningAmber, modifier = Modifier.size(24.dp))
          Spacer(Modifier.width(Ds.s8))
          Text(
            "Upscaling more than 2x will look blurry — the output is stretched, not sharper.",
            fontSize = Ds.HINT_SP.sp, color = ChamiColors.warningAmber
          )
        }
      }

      Spacer(Modifier.height(Ds.s16))
      SectionText("Format", color = theme.text)
      Spacer(Modifier.height(Ds.s8))
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Ds.s8)) {
        listOf("JPG", "PNG", "WEBP").forEach { fmt ->
          ChamiChip(fmt, formatChoice == fmt, { vibrate(); formatChoice = fmt })
        }
      }
      if (formatChoice != "PNG") {
        Spacer(Modifier.height(Ds.s16))
        Text("Quality: $quality%", fontWeight = FontWeight.Bold, fontSize = Ds.BODY_SP.sp, color = theme.text)
        Slider(
          value = quality.toFloat(),
          onValueChange = { quality = it.toInt() },
          valueRange = 10f..100f,
          colors = SliderDefaults.colors(thumbColor = theme.primary, activeTrackColor = theme.primary)
        )
      } else {
        Spacer(Modifier.height(Ds.s8))
        HintText("PNG is lossless — no quality slider.")
      }

      if (!isBatch) {
        Spacer(Modifier.height(Ds.s16))
        DebouncedTextField(
          value = targetKbStr,
          onValueChange = { targetKbStr = it.filter { ch -> ch.isDigit() } },
          label = "Resize to exact KB (JPG only, optional)", keyboardType = KeyboardType.Number
        )
        HintText("When set, quality is adjusted after resizing to land within ±5% of the target.")
      }

      Spacer(Modifier.height(Ds.s16))
      if (isProcessing) {
        LinearProgressIndicator(Modifier.fillMaxWidth(), color = theme.primary)
        Spacer(Modifier.height(Ds.s8))
        Text(progressText, fontSize = Ds.HINT_SP.sp, color = theme.text)
      }
    }

    if (isBatch) {
      SlideToAction(
        label = "Slide to Convert All",
        theme = theme,
        enabled = !isProcessing && imageUris.isNotEmpty() && dimsValid,
        onSlideComplete = { vibrate(); convertBatch() }
      )
    } else {
      SlideToAction(
        label = "Slide to Resize",
        theme = theme,
        enabled = !isProcessing && imageUri != null && dimsValid,
        onSlideComplete = { vibrate(); convertSingle() }
      )
    }
  }
}

// 9. OCR TEXT — language chips (English default, Hindi, Spanish),
// result in a selectable editable text area with Copy + word count,
// Export TXT and Export searchable PDF as two explicit choices.
// NOTE: OcrHelper in this copy is ocrImage(ctx, uri) only — Latin
// script, no language parameter — so the language is passed nowhere
// yet; the ocrImage(bitmap, language) overload does not exist here
// and the Devanagari recogniser is not wired in this copy. The UI
// says so honestly instead of pretending the choice changes the
// recogniser. Empty result shows "No text found" with a Retake
// action, never a fake placeholder as extracted text.
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
  var hasResult by remember { mutableStateOf(false) }
  var noTextFound by remember { mutableStateOf(false) }
  var ocrLang by rememberSaveable { mutableStateOf("English") }

  var doneFile by remember { mutableStateOf<File?>(null) }
  var doneMime by remember { mutableStateOf("text/plain") }
  var showDone by remember { mutableStateOf(false) }
  val singleSaveTxt = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
    val file = doneFile
    if (uri != null && file != null) {
      scope.launch {
        val ok = withContext(Dispatchers.IO) { SaveLocation.copyToUri(ctx, file, uri) }
        if (ok) { doneMime = "text/plain"; showDone = true }
        else Toast.makeText(ctx, "Could not save to chosen folder", Toast.LENGTH_SHORT).show()
      }
    }
  }
  val singleSavePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
    val file = doneFile
    if (uri != null && file != null) {
      scope.launch {
        val ok = withContext(Dispatchers.IO) { SaveLocation.copyToUri(ctx, file, uri) }
        if (ok) { doneMime = "application/pdf"; showDone = true }
        else Toast.makeText(ctx, "Could not save to chosen folder", Toast.LENGTH_SHORT).show()
      }
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
    hasResult = false
    noTextFound = false
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
      // No language overload exists in this copy: ocrImage(ctx, uri)
      // reads Latin script. The chosen language is saved with exports.
      val txt = withContext(Dispatchers.IO) { ocrImage(ctx, u) }
      isProcessing = false
      if (txt.isBlank()) {
        extractedText = ""
        hasResult = false
        noTextFound = true
      } else {
        extractedText = txt
        hasResult = true
        noTextFound = false
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

  ToolScreenContainer("OCR Text", theme, onBack) {
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
      ChamiSecondaryButton(
        text = if (imageUri != null) "Photo selected" else "Select photo",
        onClick = { vibrate(); picker.launch("image/*") },
        modifier = Modifier.fillMaxWidth()
      )

      Spacer(Modifier.height(Ds.s16))
      SectionText("Text language", color = theme.text)
      Spacer(Modifier.height(Ds.s8))
      Row(horizontalArrangement = Arrangement.spacedBy(Ds.s8)) {
        listOf("English", "Hindi", "Spanish").forEach { lang ->
          ChamiChip(lang, ocrLang == lang, { vibrate(); ocrLang = lang })
        }
      }
      Spacer(Modifier.height(Ds.s4))
      HintText("The language is saved with your export. In this build the on-device recogniser reads Latin script only.")

      if (previewBitmap != null) {
        Spacer(Modifier.height(Ds.s16))
        SectionText("Preview", color = theme.text)
        Spacer(Modifier.height(Ds.s8))
        Image(
          bitmap = previewBitmap!!.asImageBitmap(),
          contentDescription = "OCR Preview",
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 380.dp)
            .clip(RoundedCornerShape(Ds.s8))
            .background(theme.card),
          contentScale = ContentScale.Fit
        )
      }

      Spacer(Modifier.height(Ds.s16))
      if (isProcessing) {
        LinearProgressIndicator(Modifier.fillMaxWidth(), color = theme.primary)
        Spacer(Modifier.height(Ds.s8))
        Text("Running on-device OCR...", fontSize = Ds.HINT_SP.sp, color = theme.text)
      }

      if (noTextFound) {
        Column(
          Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Ds.s8))
            .background(theme.card)
            .padding(Ds.s16),
          horizontalAlignment = Alignment.CenterHorizontally
        ) {
          Icon(Icons.Filled.Search, contentDescription = null, tint = theme.textSecondary, modifier = Modifier.size(24.dp))
          Spacer(Modifier.height(Ds.s8))
          Text("No text found", fontSize = Ds.SECTION_SP.sp, fontWeight = FontWeight.Bold, color = theme.text)
          Spacer(Modifier.height(Ds.s4))
          HintText("Try a clearer, brighter photo of the text.")
          Spacer(Modifier.height(Ds.s12))
          ChamiSecondaryButton(text = "Retake / choose another photo", onClick = { vibrate(); picker.launch("image/*") }, modifier = Modifier.fillMaxWidth())
        }
      }

      if (hasResult) {
        val wordCount = extractedText.split(Regex("\\s+")).count { it.isNotBlank() }
        Column(
          Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Ds.s8))
            .background(theme.card)
            .padding(Ds.s16)
        ) {
          Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Recognised text", fontWeight = FontWeight.Bold, color = theme.text, fontSize = Ds.BODY_SP.sp)
            Text("$wordCount words", fontSize = Ds.HINT_SP.sp, color = theme.textSecondary)
          }
          Spacer(Modifier.height(Ds.s8))
          OutlinedTextField(
            value = extractedText,
            onValueChange = { extractedText = it },
            modifier = Modifier.fillMaxWidth().height(200.dp),
            shape = RoundedCornerShape(Ds.s8)
          )
          Spacer(Modifier.height(Ds.s12))
          ChamiSecondaryButton(
            text = "Copy text",
            onClick = {
              vibrate()
              val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
              cm.setPrimaryClip(ClipData.newPlainText("OCR Text", extractedText))
              Toast.makeText(ctx, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth()
          )
          Spacer(Modifier.height(Ds.s12))
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Ds.s8)) {
            ChamiPrimaryButton(
              text = "Export TXT",
              onClick = {
                vibrate()
                val f = saveTextToFile(ctx, extractedText, "OCR_${ocrLang}_${System.currentTimeMillis()}.txt")
                doneFile = f
                singleSaveTxt.launch(f.name)
              },
              modifier = Modifier.weight(1f)
            )
            ChamiPrimaryButton(
              text = "Export searchable PDF",
              onClick = {
                vibrate()
                val out = PdfEngine.outFile(ctx, "OCR_${ocrLang}_${System.currentTimeMillis()}.pdf")
                val ok = textToPdf(ctx, "OCR Text ($ocrLang)", extractedText, outFile = out)
                if (ok) {
                  doneFile = out
                  singleSavePdf.launch(out.name)
                } else Toast.makeText(ctx, "Failed to make PDF", Toast.LENGTH_SHORT).show()
              },
              modifier = Modifier.weight(1f)
            )
          }
          Spacer(Modifier.height(Ds.s8))
          HintText("The PDF holds the recognised text as selectable text (no image layer in this build).")
        }
      }
    }

    SlideToAction(
      label = "Slide to Extract Text",
      theme = theme,
      enabled = !isProcessing && imageUri != null,
      onSlideComplete = { vibrate(); runOcr() }
    )
  }
}

// Reader bookmark store: same SharedPreferences file/key scheme as
// ReaderTextTools.saveBookmark/getBookmarks ("chami_bookmarks",
// "bm_<sanitised file name>"), plus removal (no remove helper exists
// there).
private fun bookmarkPrefsKey(fileName: String): String =
  "bm_" + fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")

private fun removeBookmark(ctx: Context, fileName: String, page: Int) {
  val prefs = ctx.getSharedPreferences("chami_bookmarks", Context.MODE_PRIVATE)
  val key = bookmarkPrefsKey(fileName)
  val set = prefs.getStringSet(key, emptySet())?.toMutableSet() ?: mutableSetOf()
  set.remove(page.toString())
  prefs.edit().putStringSet(key, set).apply()
}

// 10. PDF READER — lazy per-page search with progress + cancel (one
// page is rendered and OCR'd at a time, never the whole document up
// front), bookmarks PERSISTED per file in SharedPreferences, page
// indicator "3 / 24" with tap-to-go-to-page, pinch zoom 1x-5x that
// re-renders sharper at the zoomed scale, overflow menu to open the
// file in Split / Merge / Compress. Keeps its custom header area but
// uses Ds typography/spacing throughout.
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
  var pdfUri by remember { mutableStateOf<Uri?>(initialFile?.let { Uri.fromFile(it) }) }
  var totalPages by remember { mutableStateOf(0) }
  var currentPage by remember { mutableStateOf(0) }
  // Render scale: 2.0x at fit; raised (debounced) while zoomed so the
  // zoomed page re-renders sharper instead of upscaling a bitmap.
  var scale by remember { mutableStateOf(2.0f) }
  var isNightMode by remember { mutableStateOf(false) }
  var pageInput by remember { mutableStateOf("1") }
  var currentBitmap by remember { mutableStateOf<Bitmap?>(null) }
  var isLoadingPage by remember { mutableStateOf(false) }
  var zoom by remember { mutableStateOf(1f) }
  var pan by remember { mutableStateOf(Offset.Zero) }
  val pageBitmapCache = remember { mutableStateMapOf<Int, Bitmap>() }
  var searchQuery by remember { mutableStateOf("") }
  var searchPages by remember { mutableStateOf<List<Int>>(emptyList()) }
  var searchIndex by remember { mutableStateOf(0) }
  var searching by remember { mutableStateOf(false) }
  var searchProgress by remember { mutableStateOf("") }
  var searchJob by remember { mutableStateOf<Job?>(null) }
  val bookmarks = remember { mutableStateListOf<Int>() }
  var showGoToPage by remember { mutableStateOf(false) }
  var goToInput by remember { mutableStateOf("") }
  var showOverflow by remember { mutableStateOf(false) }

  val fileName: String = pdfUri?.let { displayNameOf(ctx, it) } ?: initialFile?.name ?: "file"

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

  fun loadBookmarks() {
    bookmarks.clear()
    bookmarks.addAll(getBookmarks(ctx, fileName))
  }

  fun toggleBookmark(page1Based: Int) {
    if (bookmarks.contains(page1Based)) {
      bookmarks.remove(page1Based)
      removeBookmark(ctx, fileName, page1Based)
    } else {
      bookmarks.add(page1Based)
      saveBookmark(ctx, fileName, page1Based)
    }
  }

  // Lazy search: render + OCR one page at a time, with progress and a
  // cancel button. The job is cancelled between pages, so cancelling
  // stops promptly instead of finishing the whole document.
  fun runSearch() {
    val q = searchQuery.trim()
    val u = pdfUri
    if (q.isEmpty() || u == null) return
    searchJob?.cancel()
    searching = true
    searchPages = emptyList()
    searchIndex = 0
    searchJob = scope.launch {
      val hits = mutableListOf<Int>()
      var renderer: android.graphics.pdf.PdfRenderer? = null
      var pfd: android.os.ParcelFileDescriptor? = null
      try {
        pfd = withContext(Dispatchers.IO) { ctx.contentResolver.openFileDescriptor(u, "r") }
        if (pfd == null) {
          withContext(Dispatchers.Main) { searching = false }
          return@launch
        }
        renderer = android.graphics.pdf.PdfRenderer(pfd)
        val count = renderer.pageCount
        for (i in 0 until count) {
          if (searchJob?.isActive == false) break
          withContext(Dispatchers.Main) { searchProgress = "Searching page ${i + 1} of $count" }
          val text = withContext(Dispatchers.IO) {
            try {
              val page = renderer!!.openPage(i)
              val w = page.width; val h = page.height
              val maxSide = maxOf(w, h)
              val s = if (maxSide > 1200) 1200f / maxSide else 1.5f
              val bmp = Bitmap.createBitmap((w * s).toInt(), (h * s).toInt(), Bitmap.Config.ARGB_8888)
              bmp.eraseColor(AndroidColor.WHITE)
              page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
              page.close()
              val tmp = File(ctx.cacheDir, "search_page_$i.jpg")
              FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
              bmp.recycle()
              ocrImage(ctx, Uri.fromFile(tmp))
            } catch (_: Exception) { "" }
          }
          if (text.contains(q, ignoreCase = true)) hits.add(i + 1)
        }
      } catch (_: Exception) {
      } finally {
        try { renderer?.close() } catch (_: Exception) {}
        try { pfd?.close() } catch (_: Exception) {}
      }
      searching = false
      searchPages = hits
      searchIndex = 0
      if (hits.isNotEmpty()) jumpToPage(hits[0])
      else Toast.makeText(ctx, "No matches for \"$q\"", Toast.LENGTH_SHORT).show()
    }
  }

  val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { selected ->
    pdfUri = selected
    if (selected != null) {
      pageBitmapCache.values.forEach { try { it.recycle() } catch (_: Exception) {} }
      pageBitmapCache.clear()
      currentBitmap = null
      totalPages = 0
      currentPage = 0
      pageInput = "1"
      searchPages = emptyList()
      bookmarks.clear()
    }
  }

  LaunchedEffect(pdfUri) {
    val u = pdfUri ?: return@LaunchedEffect
    if (totalPages == 0) {
      totalPages = withContext(Dispatchers.IO) { PdfEngine.pageCount(ctx, u) }
    }
    loadBookmarks()
  }

  LaunchedEffect(currentPage) { zoom = 1f; pan = Offset.Zero }

  // Re-render sharper after the pinch ends (debounced): render scale
  // follows the display zoom, up to 5x.
  LaunchedEffect(zoom) {
    delay(300)
    scale = (2.0f * zoom).coerceIn(2.0f, 5.0f)
  }

  androidx.compose.runtime.DisposableEffect(Unit) {
    onDispose {
      pageBitmapCache.values.forEach { try { it.recycle() } catch (_: Exception) {} }
      pageBitmapCache.clear()
    }
  }

  LaunchedEffect(pdfUri, currentPage, scale) {
    val u = pdfUri ?: return@LaunchedEffect
    if (scale <= 2.0f) {
      pageBitmapCache[currentPage]?.let { cached ->
        currentBitmap = cached
        isLoadingPage = false
        return@LaunchedEffect
      }
    }
    isLoadingPage = true
    withContext(Dispatchers.IO) {
      val bmp = PdfReader.renderPageBitmap(ctx, u, currentPage, scale)
      withContext(Dispatchers.Main) {
        if (bmp != null && scale <= 2.0f) {
          pageBitmapCache[currentPage] = bmp
          val keep = (currentPage - 1)..(currentPage + 1)
          val evict = pageBitmapCache.keys.filter { it !in keep }
          for (k in evict) {
            pageBitmapCache.remove(k)?.let { try { it.recycle() } catch (_: Exception) {} }
          }
        }
        currentBitmap = bmp
        isLoadingPage = false
      }
    }
  }

  if (showGoToPage) {
    AlertDialog(
      onDismissRequest = { showGoToPage = false },
      title = { Text("Go to page", fontSize = Ds.SECTION_SP.sp, fontWeight = FontWeight.Bold, color = theme.text) },
      text = {
        OutlinedTextField(
          value = goToInput,
          onValueChange = { goToInput = it.filter { ch -> ch.isDigit() } },
          label = { Text("Page 1–$totalPages", fontSize = Ds.HINT_SP.sp) },
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          modifier = Modifier.fillMaxWidth(),
          isError = goToInput.toIntOrNull()?.let { it !in 1..totalPages } == true
        )
      },
      confirmButton = {
        TextButton(onClick = {
          goToInput.toIntOrNull()?.let { jumpToPage(it) }
          showGoToPage = false
        }) { Text("Go", color = theme.primary, fontSize = Ds.BUTTON_SP.sp, fontWeight = FontWeight.SemiBold) }
      },
      dismissButton = {
        TextButton(onClick = { showGoToPage = false }) { Text("Cancel", color = theme.text) }
      }
    )
  }

  ToolScreenContainer("PDF Reader", theme, onBack) {
    if (pdfUri == null) {
      Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Filled.PictureAsPdf, contentDescription = null, tint = theme.textSecondary, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(Ds.s20))
        ChamiPrimaryButton(text = "Open PDF file", onClick = { vibrate(); picker.launch("application/pdf") }, modifier = Modifier.fillMaxWidth())
      }
    } else {
      Column(Modifier.fillMaxSize()) {
        // Controls row: page indicator (tap to go to page), zoom, night
        // mode, overflow menu.
        Row(
          Modifier.fillMaxWidth().clip(RoundedCornerShape(Ds.s8)).background(theme.card).padding(Ds.s8),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          TextButton(
            onClick = { goToInput = "${currentPage + 1}"; showGoToPage = true },
            modifier = Modifier.height(Ds.minTouch)
          ) {
            Text(
              "${currentPage + 1} / $totalPages",
              fontSize = Ds.BODY_SP.sp, fontWeight = FontWeight.Bold, color = theme.text
            )
          }
          Row(verticalAlignment = Alignment.CenterVertically) {
            ChamiIconButton(
              icon = Icons.Filled.Remove, description = "Zoom out",
              onClick = { vibrate(); zoom = (zoom - 0.25f).coerceIn(1f, 5f); if (zoom == 1f) pan = Offset.Zero }
            )
            Text("${(zoom * 100).toInt()}%", fontSize = Ds.HINT_SP.sp, fontWeight = FontWeight.Bold, color = theme.text)
            ChamiIconButton(
              icon = Icons.Filled.Add, description = "Zoom in",
              onClick = { vibrate(); zoom = (zoom + 0.25f).coerceIn(1f, 5f) }
            )
            ChamiIconButton(
              icon = if (isNightMode) Icons.Filled.LightMode else Icons.Filled.DarkMode,
              description = if (isNightMode) "Day mode" else "Night mode",
              onClick = { vibrate(); isNightMode = !isNightMode }
            )
            Box {
              ChamiIconButton(
                icon = Icons.Filled.MoreVert, description = "More actions",
                onClick = { showOverflow = true }
              )
              DropdownMenu(expanded = showOverflow, onDismissRequest = { showOverflow = false }) {
                // NOTE: the file is passed by opening the tool; the
                // target screen picks its own file in v1.2 (passing
                // the Uri through needs a MainActivity change, which
                // is outside this file).
                DropdownMenuItem(
                  text = { Text("Open in Split", fontSize = Ds.BODY_SP.sp) },
                  onClick = { showOverflow = false; onOpenTool?.invoke("split") }
                )
                DropdownMenuItem(
                  text = { Text("Open in Merge", fontSize = Ds.BODY_SP.sp) },
                  onClick = { showOverflow = false; onOpenTool?.invoke("merge") }
                )
                DropdownMenuItem(
                  text = { Text("Open in Compress", fontSize = Ds.BODY_SP.sp) },
                  onClick = { showOverflow = false; onOpenTool?.invoke("compress") }
                )
              }
            }
          }
        }

        Spacer(Modifier.height(Ds.s8))
        Row(
          Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(Ds.s8)
        ) {
          OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text("Search text in PDF", fontSize = Ds.HINT_SP.sp) },
            singleLine = true,
            modifier = Modifier.weight(1f).height(52.dp),
            shape = RoundedCornerShape(Ds.s8)
          )
          ChamiIconButton(
            icon = Icons.Filled.Search, description = "Search",
            onClick = { vibrate(); runSearch() }
          )
        }
        if (searching) {
          Spacer(Modifier.height(Ds.s4))
          Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(searchProgress, fontSize = Ds.HINT_SP.sp, color = theme.textSecondary)
            TextButton(onClick = { searchJob?.cancel(); searching = false }, modifier = Modifier.height(Ds.minTouch)) {
              Text("Cancel", color = ChamiColors.errorRed, fontSize = Ds.BODY_SP.sp, fontWeight = FontWeight.SemiBold)
            }
          }
          LinearProgressIndicator(Modifier.fillMaxWidth(), color = theme.primary)
        } else if (searchPages.isNotEmpty()) {
          Spacer(Modifier.height(Ds.s4))
          Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Text(
              "Found on pages: ${searchPages.joinToString(", ")}",
              fontSize = Ds.HINT_SP.sp, color = theme.primaryDark, fontWeight = FontWeight.Bold
            )
            TextButton(
              onClick = {
                vibrate()
                val next = (searchIndex + 1) % searchPages.size
                searchIndex = next
                jumpToPage(searchPages[next])
              },
              modifier = Modifier.height(Ds.minTouch)
            ) {
              Text("Next match", fontSize = Ds.HINT_SP.sp, fontWeight = FontWeight.Bold, color = theme.primary)
            }
          }
        }

        Spacer(Modifier.height(Ds.s8))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          Text("Zoom", fontSize = Ds.HINT_SP.sp, fontWeight = FontWeight.Bold, color = theme.textSecondary)
          Slider(
            value = zoom,
            onValueChange = { zoom = it; if (zoom == 1f) pan = Offset.Zero },
            valueRange = 1f..5f,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(thumbColor = theme.primary, activeTrackColor = theme.primary)
          )
          TextButton(onClick = { zoom = 1f; pan = Offset.Zero }, modifier = Modifier.height(Ds.minTouch)) {
            Text("Fit", fontSize = Ds.HINT_SP.sp, fontWeight = FontWeight.Bold, color = theme.primary)
          }
        }

        Spacer(Modifier.height(Ds.s8))
        if (isLoadingPage) {
          LinearProgressIndicator(Modifier.fillMaxWidth(), color = theme.primary)
        }

        Box(
          Modifier
            .weight(1f)
            .fillMaxWidth()
            .clip(RoundedCornerShape(Ds.s8))
            .background(Color(0xFF121212)),
          contentAlignment = Alignment.Center
        ) {
          currentBitmap?.let { bmp ->
            val nightFilter = if (isNightMode) {
              androidx.compose.ui.graphics.ColorFilter.colorMatrix(
                androidx.compose.ui.graphics.ColorMatrix(
                  floatArrayOf(
                    -1f, 0f, 0f, 0f, 255f,
                    0f, -1f, 0f, 0f, 255f,
                    0f, 0f, -1f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f
                  )
                )
              )
            } else null
            Image(
              bitmap = bmp.asImageBitmap(),
              contentDescription = "Page ${currentPage + 1}",
              colorFilter = nightFilter,
              modifier = Modifier
                .fillMaxSize()
                .padding(Ds.s8)
                .graphicsLayer(
                  scaleX = zoom,
                  scaleY = zoom,
                  translationX = pan.x,
                  translationY = pan.y
                )
                .pointerInput(Unit) {
                  detectTransformGestures { _, panChange, zoomChange, _ ->
                    zoom = (zoom * zoomChange).coerceIn(1f, 5f)
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

        Spacer(Modifier.height(Ds.s8))
        Row(
          Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(Ds.s8)
        ) {
          ChamiIconButton(
            icon = if (bookmarks.contains(currentPage + 1)) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
            description = if (bookmarks.contains(currentPage + 1)) "Remove bookmark" else "Bookmark this page",
            onClick = { vibrate(); toggleBookmark(currentPage + 1) }
          )
          if (bookmarks.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(Ds.s8), modifier = Modifier.weight(1f)) {
              items(bookmarks.sorted()) { b ->
                Row(
                  Modifier
                    .height(Ds.minTouch)
                    .clip(RoundedCornerShape(50))
                    .background(theme.primary.copy(alpha = 0.15f))
                    .border(1.dp, theme.primary, RoundedCornerShape(50))
                    .clickable { vibrate(); jumpToPage(b) }
                    .padding(start = Ds.s12),
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Text("Page $b", fontSize = Ds.HINT_SP.sp, fontWeight = FontWeight.Bold, color = theme.primaryDark)
                  ChamiIconButton(
                    icon = Icons.Filled.Close, description = "Delete bookmark",
                    onClick = {
                      vibrate()
                      bookmarks.remove(b)
                      removeBookmark(ctx, fileName, b)
                    }
                  )
                }
              }
            }
          } else {
            Text("Tap the bookmark icon to save this page.", fontSize = Ds.HINT_SP.sp, color = theme.textSecondary)
          }
        }
        Spacer(Modifier.height(Ds.s8))
        if (totalPages > 1) {
          LazyRow(
            horizontalArrangement = Arrangement.spacedBy(Ds.s8),
            modifier = Modifier.fillMaxWidth()
          ) {
            items((1..totalPages).toList()) { p ->
              val isSel = p - 1 == currentPage
              Box(
                Modifier
                  .size(Ds.minTouch)
                  .clip(RoundedCornerShape(Ds.s8))
                  .background(if (isSel) theme.primary else theme.card)
                  .border(1.dp, theme.cardBorder, RoundedCornerShape(Ds.s8))
                  .clickable {
                    vibrate()
                    currentPage = p - 1
                    pageInput = "$p"
                  },
                contentAlignment = Alignment.Center
              ) {
                Text(
                  "$p",
                  fontSize = Ds.BODY_SP.sp,
                  fontWeight = FontWeight.Bold,
                  color = if (isSel) theme.textOnPrimary else theme.text
                )
              }
            }
          }
          Spacer(Modifier.height(Ds.s8))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Ds.s8)) {
          ChamiSecondaryButton(
            text = "Prev",
            onClick = {
              vibrate()
              if (currentPage > 0) {
                currentPage--
                pageInput = "${currentPage + 1}"
              }
            },
            modifier = Modifier.weight(1f)
          )
          ChamiSecondaryButton(
            text = "Next",
            onClick = {
              vibrate()
              if (currentPage < totalPages - 1) {
                currentPage++
                pageInput = "${currentPage + 1}"
              }
            },
            modifier = Modifier.weight(1f)
          )
        }
      }
    }
  }
}

// 11. PROTECT PDF — Lock / Unlock segmented control, title stays
// "Protect PDF". Lock: password with a real vector eye, strength hint
// under the field, mismatch error under confirm as typed, three
// permission switches (printing / copying / editing, all ON by
// default) that are actually applied via patchPdfPermissions.
// Unlock distinguishes "wrong password" from "unsupported format".
// Output is always a NEW file (NAME_protected / NAME_unlocked); the
// original is never overwritten. NOTE: encryption here is the app's
// own Standard V1/R2 (RC4) format via PdfProtector — modern readers
// may warn about it; AES-256 is a separate engine upgrade.
@Composable
fun ProtectPdfScreen(theme: ChamiTheme, onBack: () -> Unit) {
  val ctx = LocalContext.current
  val haptic = LocalHapticFeedback.current
  val tm = remember { ThemeManager(ctx) }
  val scope = rememberCoroutineScope()

  var mode by rememberSaveable { mutableStateOf("Protect") }

  var selectedUri by remember { mutableStateOf<Uri?>(null) }
  var selectedName by remember { mutableStateOf("") }
  var pageCount by remember { mutableStateOf(0) }
  var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
  var password by remember { mutableStateOf("") }
  var confirmPassword by remember { mutableStateOf("") }
  var passwordVisible by remember { mutableStateOf(false) }
  var allowPrint by rememberSaveable { mutableStateOf(true) }
  var allowCopy by rememberSaveable { mutableStateOf(true) }
  var allowEdit by rememberSaveable { mutableStateOf(true) }

  var unlockUri by remember { mutableStateOf<Uri?>(null) }
  var unlockName by remember { mutableStateOf("") }
  var unlockPassword by remember { mutableStateOf("") }
  var unlockPasswordVisible by remember { mutableStateOf(false) }
  var unlockError by remember { mutableStateOf<String?>(null) }

  var isProcessing by remember { mutableStateOf(false) }
  var progressStatus by remember { mutableStateOf("") }

  var doneFile by remember { mutableStateOf<File?>(null) }
  var showDone by remember { mutableStateOf(false) }
  val singleSave = rememberSingleFileSaver { file -> doneFile = file; showDone = true }

  fun vibrate() {
    if (tm.hapticOn()) {
      try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
    }
  }

  val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
    if (uri != null) {
      vibrate()
      selectedUri = uri
      selectedName = baseNameOf(ctx, uri, "Document")
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
      unlockName = baseNameOf(ctx, uri, "Document")
      unlockError = null
      showDone = false
      doneFile = null
    }
  }

  fun protect() {
    val uri = selectedUri ?: return
    isProcessing = true
    progressStatus = "Encrypting document..."
    scope.launch(Dispatchers.IO) {
      val out = PdfEngine.outFile(ctx, "${selectedName}_protected_${System.currentTimeMillis()}.pdf")
      val ok = PdfProtector.protectPdf(ctx, uri, password, out) { current, total ->
        progressStatus = "Encrypting page $current of $total..."
      }
      var patched = true
      if (ok && (!allowPrint || !allowCopy || !allowEdit)) {
        patched = patchPdfPermissions(out, password, allowPrint = allowPrint, allowCopy = allowCopy, allowEdit = allowEdit)
      }
      withContext(Dispatchers.Main) {
        isProcessing = false
        if (ok) {
          if (!patched) {
            Toast.makeText(ctx, "Protected, but permissions could not be applied", Toast.LENGTH_LONG).show()
          }
          singleSave(out)
        } else {
          Toast.makeText(ctx, "Failed to encrypt PDF", Toast.LENGTH_SHORT).show()
        }
      }
    }
  }

  fun unlock() {
    val uri = unlockUri ?: return
    if (unlockPassword.isBlank()) {
      unlockError = "Enter the PDF password."
      return
    }
    isProcessing = true
    unlockError = null
    scope.launch {
      val tmp = File(ctx.cacheDir, "unlock_in_${System.currentTimeMillis()}.pdf")
      val out = PdfEngine.outFile(ctx, "${unlockName}_unlocked_${System.currentTimeMillis()}.pdf")
      val result: String? = withContext(Dispatchers.IO) {
        try {
          ctx.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tmp).use { o -> input.copyTo(o) }
          }
        } catch (_: Exception) { }
        if (!tmp.exists()) return@withContext "Could not read that file."
        val bytes = try { tmp.readBytes() } catch (_: Exception) { ByteArray(0) }
        val text = String(bytes, Charsets.ISO_8859_1)
        if (!text.contains("/Encrypt")) return@withContext "This file is not protected by a password."
        if (!PdfProtector.verifyPassword(tmp, unlockPassword)) {
          return@withContext "Wrong password — try again."
        }
        val ok = PdfProtector.unlockPdf(tmp, out, unlockPassword) ||
          unlockProtectedPdf(tmp, unlockPassword, out)
        if (!ok) {
          return@withContext "This file uses an encryption type this app can't unlock. Only Standard encryption (as used by Chami) is supported on-device."
        }
        null // success
      }
      isProcessing = false
      if (result == null) {
        Toast.makeText(ctx, "Unlocked", Toast.LENGTH_SHORT).show()
        singleSave(out)
      } else {
        unlockError = result
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

  val strength = when {
    password.isEmpty() -> ""
    password.length < 4 -> "Too short — at least 4 characters"
    password.length < 8 && !(password.any { it.isDigit() } && password.any { it.isLetter() }) -> "Weak"
    password.length < 8 -> "OK"
    password.any { it.isDigit() } && password.any { it.isLetter() } && password.any { !it.isLetterOrDigit() } -> "Strong"
    password.any { it.isDigit() } && password.any { it.isLetter() } -> "Strong"
    else -> "OK"
  }
  val mismatch = confirmPassword.isNotEmpty() && password != confirmPassword
  val canEncrypt = selectedUri != null && password.length >= 4 && password == confirmPassword && !isProcessing

  ToolScreenContainer("Protect PDF", theme, onBack) {
    Column(
      Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(Ds.s16)
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(Ds.s8))
          .background(theme.card)
          .padding(Ds.s4),
        horizontalArrangement = Arrangement.spacedBy(Ds.s4)
      ) {
        listOf("Protect", "Unlock").forEach { m ->
          Box(
            modifier = Modifier
              .weight(1f)
              .height(Ds.minTouch)
              .clip(RoundedCornerShape(Ds.s8))
              .background(if (mode == m) theme.primary else Color.Transparent)
              .clickable { vibrate(); mode = m },
            contentAlignment = Alignment.Center
          ) {
            Text(
              m.uppercase(),
              fontWeight = FontWeight.Bold,
              fontSize = Ds.HINT_SP.sp,
              color = if (mode == m) Color.White else theme.text
            )
          }
        }
      }

      if (mode == "Unlock") {
        Column(
          Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Ds.tileRadius))
            .background(theme.card)
            .border(1.dp, theme.cardBorder, RoundedCornerShape(Ds.tileRadius))
            .padding(Ds.s16)
        ) {
          SectionText("Unlock a protected PDF", color = theme.text)
          Spacer(Modifier.height(Ds.s12))
          ChamiSecondaryButton(
            text = if (unlockUri == null) "Choose protected PDF" else "Change PDF file",
            onClick = { vibrate(); unlockPicker.launch("application/pdf") },
            modifier = Modifier.fillMaxWidth()
          )
          if (unlockUri != null) {
            Spacer(Modifier.height(Ds.s16))
            OutlinedTextField(
              value = unlockPassword,
              onValueChange = { unlockPassword = it; unlockError = null },
              label = { Text("Enter PDF password", fontSize = Ds.HINT_SP.sp) },
              singleLine = true,
              visualTransformation = if (unlockPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
              trailingIcon = {
                ChamiIconButton(
                  icon = if (unlockPasswordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                  description = if (unlockPasswordVisible) "Hide password" else "Show password",
                  onClick = { unlockPasswordVisible = !unlockPasswordVisible }
                )
              },
              modifier = Modifier.fillMaxWidth(),
              shape = RoundedCornerShape(Ds.buttonRadius),
              isError = unlockError != null
            )
            if (unlockError != null) {
              Spacer(Modifier.height(Ds.s4))
              Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Error, contentDescription = null, tint = ChamiColors.errorRed, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(Ds.s8))
                Text(unlockError!!, fontSize = Ds.HINT_SP.sp, color = ChamiColors.errorRed)
              }
            }
            Spacer(Modifier.height(Ds.s8))
            HintText("Removes the password from PDFs protected by Chami (Standard encryption). The unlocked file is a new file — the original stays protected.")
          }
        }
        if (isProcessing) {
          LinearProgressIndicator(Modifier.fillMaxWidth(), color = theme.primary)
        }
      } else {
        Column(
          Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Ds.tileRadius))
            .background(theme.card)
            .border(1.dp, theme.cardBorder, RoundedCornerShape(Ds.tileRadius))
            .padding(Ds.s16)
        ) {
          SectionText("1. Select PDF document", color = theme.text)
          Spacer(Modifier.height(Ds.s12))
          ChamiSecondaryButton(
            text = if (selectedUri == null) "Choose PDF file" else "Change PDF file",
            onClick = { vibrate(); pdfPicker.launch("application/pdf") },
            modifier = Modifier.fillMaxWidth()
          )
          if (selectedUri != null) {
            Spacer(Modifier.height(Ds.s16))
            Row(
              Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Ds.tileRadius))
                .background(theme.bg)
                .border(1.dp, theme.cardBorder, RoundedCornerShape(Ds.tileRadius))
                .padding(Ds.s12),
              verticalAlignment = Alignment.CenterVertically
            ) {
              if (previewBitmap != null) {
                Image(
                  bitmap = previewBitmap!!.asImageBitmap(),
                  contentDescription = "Document page 1",
                  modifier = Modifier
                    .size(56.dp, 72.dp)
                    .clip(RoundedCornerShape(Ds.s8))
                    .border(1.dp, theme.cardBorder, RoundedCornerShape(Ds.s8)),
                  contentScale = ContentScale.Crop
                )
              } else {
                Box(
                  Modifier
                    .size(56.dp, 72.dp)
                    .clip(RoundedCornerShape(Ds.s8))
                    .background(theme.card),
                  contentAlignment = Alignment.Center
                ) {
                  Icon(Icons.Filled.PictureAsPdf, contentDescription = null, tint = theme.textSecondary, modifier = Modifier.size(24.dp))
                }
              }
              Spacer(Modifier.width(Ds.s12))
              Column(Modifier.weight(1f)) {
                Text(selectedName, fontWeight = FontWeight.Bold, fontSize = Ds.BODY_SP.sp, color = theme.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(Ds.s4))
                Text("Total pages: $pageCount", fontSize = Ds.HINT_SP.sp, color = theme.textSecondary)
              }
            }
          }
        }

        if (selectedUri != null) {
          Column(
            Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(Ds.tileRadius))
              .background(theme.card)
              .border(1.dp, theme.cardBorder, RoundedCornerShape(Ds.tileRadius))
              .padding(Ds.s16)
          ) {
            SectionText("2. Set encryption password", color = theme.text)
            Spacer(Modifier.height(Ds.s16))
            OutlinedTextField(
              value = password,
              onValueChange = { password = it },
              label = { Text("Enter document password", fontSize = Ds.HINT_SP.sp) },
              singleLine = true,
              visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
              trailingIcon = {
                ChamiIconButton(
                  icon = if (passwordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                  description = if (passwordVisible) "Hide password" else "Show password",
                  onClick = { passwordVisible = !passwordVisible }
                )
              },
              modifier = Modifier.fillMaxWidth(),
              shape = RoundedCornerShape(Ds.buttonRadius)
            )
            if (strength.isNotEmpty()) {
              Spacer(Modifier.height(Ds.s4))
              Text(
                "Strength: $strength",
                fontSize = Ds.HINT_SP.sp,
                color = if (strength.startsWith("Weak") || strength.startsWith("Too short")) ChamiColors.errorRed else theme.primary
              )
            }
            Spacer(Modifier.height(Ds.s12))
            OutlinedTextField(
              value = confirmPassword,
              onValueChange = { confirmPassword = it },
              label = { Text("Confirm document password", fontSize = Ds.HINT_SP.sp) },
              singleLine = true,
              visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
              modifier = Modifier.fillMaxWidth(),
              shape = RoundedCornerShape(Ds.buttonRadius),
              isError = mismatch
            )
            if (mismatch) {
              Spacer(Modifier.height(Ds.s4))
              Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Error, contentDescription = null, tint = ChamiColors.errorRed, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(Ds.s8))
                Text("Passwords do not match", fontSize = Ds.HINT_SP.sp, color = ChamiColors.errorRed)
              }
            }
            Spacer(Modifier.height(Ds.s16))
            SectionText("Permissions", color = theme.text)
            Spacer(Modifier.height(Ds.s8))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
              Text("Allow printing", fontSize = Ds.BODY_SP.sp, color = theme.text)
              Switch(checked = allowPrint, onCheckedChange = { allowPrint = it })
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
              Text("Allow copying text", fontSize = Ds.BODY_SP.sp, color = theme.text)
              Switch(checked = allowCopy, onCheckedChange = { allowCopy = it })
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
              Text("Allow editing", fontSize = Ds.BODY_SP.sp, color = theme.text)
              Switch(checked = allowEdit, onCheckedChange = { allowEdit = it })
            }
            Spacer(Modifier.height(Ds.s12))
            HintText("Keep this password safe — it cannot be recovered. The protected file is a new file; the original is never changed.")
          }
        }

        if (isProcessing) {
          LinearProgressIndicator(Modifier.fillMaxWidth(), color = theme.primary)
          Spacer(Modifier.height(Ds.s4))
          Text(if (progressStatus.isNotEmpty()) progressStatus else "Encrypting...", fontSize = Ds.HINT_SP.sp, color = theme.text)
        }
      }
    }

    if (mode == "Unlock") {
      SlideToAction(
        label = "Slide to Unlock PDF",
        theme = theme,
        enabled = !isProcessing && unlockUri != null && unlockPassword.isNotBlank(),
        onSlideComplete = { vibrate(); unlock() }
      )
    } else {
      SlideToAction(
        label = "Slide to Protect PDF",
        theme = theme,
        enabled = canEncrypt,
        onSlideComplete = { vibrate(); protect() }
      )
    }
  }
}
