package com.chami.pdfmaker.ui

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.RectF
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chami.pdfmaker.pdf.Annotation
import com.chami.pdfmaker.pdf.PageRef
import com.chami.pdfmaker.pdf.PdfEditorEngine
import com.chami.pdfmaker.pdf.PdfEngine
import com.chami.pdfmaker.pdf.PdfReader
import com.chami.pdfmaker.pdf.SaveLocation
import com.chami.pdfmaker.theme.ChamiTheme
import com.chami.pdfmaker.theme.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

private enum class EditorMode(val label: String, val hint: String) {
  SELECT("SELECT", "Tap a tool above. Nothing is drawn in Select."),
  HIGHLIGHT("HIGHLIGHT", "Drag across text to highlight it."),
  UNDERLINE("UNDERLINE", "Drag across text to underline it."),
  STRIKE("STRIKE", "Drag across text to strike it through."),
  TEXT("TEXT", "Tap anywhere on the page, then type the text."),
  PEN("PEN", "Draw freehand on the page."),
  SIGN("SIGN", "Tap where the signature goes. Draw it first if you have none saved."),
  IMAGE("IMAGE", "Pick an image, then tap where it goes."),
  REDACT("REDACT", "Drag over private text to black it out. This cannot be undone after saving."),
  ERASE("ERASE", "Tap an annotation to remove it.")
}

private data class EditorSnapshot(
  val pages: List<PageRef>,
  val annotations: Map<Int, List<Annotation>>
)

@Composable
private fun EditorChip(label: String, active: Boolean, theme: ChamiTheme, onClick: () -> Unit) {
  Box(
    Modifier
      .clip(RoundedCornerShape(6.dp))
      .background(if (active) theme.primary else theme.card)
      .border(1.dp, theme.cardBorder, RoundedCornerShape(6.dp))
      .clickable { onClick() }
      .padding(horizontal = 10.dp, vertical = 7.dp),
    contentAlignment = Alignment.Center
  ) {
    Text(
      label,
      fontFamily = PixelFont,
      fontSize = 11.sp,
      fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
      color = if (active) theme.textOnPrimary else theme.text
    )
  }
}

// Strokes are in signature-pad pixels; scale them into a 600 x 300
// transparent bitmap (no white fill, so no white box on the page).
private fun strokesToBitmap(strokes: List<List<Offset>>, w: Int, h: Int): Bitmap {
  val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
  val canvas = android.graphics.Canvas(bmp)
  val paint = android.graphics.Paint().apply {
    color = AndroidColor.BLACK
    strokeWidth = 6f
    style = android.graphics.Paint.Style.STROKE
    strokeCap = android.graphics.Paint.Cap.ROUND
    strokeJoin = android.graphics.Paint.Join.ROUND
    isAntiAlias = true
  }
  for (stroke in strokes) {
    if (stroke.size < 2) continue
    val path = android.graphics.Path().apply {
      moveTo(stroke[0].x, stroke[0].y)
      for (i in 1 until stroke.size) lineTo(stroke[i].x, stroke[i].y)
    }
    canvas.drawPath(path, paint)
  }
  return bmp
}

@Composable
fun PdfEditorScreen(theme: ChamiTheme, onBack: () -> Unit) {
  val ctx = LocalContext.current
  val activity = ctx as Activity
  val haptic = LocalHapticFeedback.current
  val tm = remember { ThemeManager(ctx) }
  val scope = rememberCoroutineScope()

  var pdfUri by remember { mutableStateOf<Uri?>(null) }
  var totalPages by remember { mutableStateOf(0) }
  var pages by remember { mutableStateOf<List<PageRef>>(emptyList()) }
  var currentPage by remember { mutableStateOf(0) }
  var zoom by remember { mutableStateOf(1.5f) }
  var pageBitmap by remember { mutableStateOf<Bitmap?>(null) }
  var mode by remember { mutableStateOf(EditorMode.SELECT) }
  var annotations by remember { mutableStateOf<Map<Int, List<Annotation>>>(emptyMap()) }
  var undoStack by remember { mutableStateOf<List<EditorSnapshot>>(emptyList()) }
  var redoStack by remember { mutableStateOf<List<EditorSnapshot>>(emptyList()) }
  var drawColour by remember { mutableStateOf(AndroidColor.YELLOW) }
  var penWidth by remember { mutableStateOf(4f) }
  var showPageNumbers by remember { mutableStateOf(true) }
  var watermarkText by remember { mutableStateOf("") }
  var goToText by remember { mutableStateOf("") }
  var isProcessing by remember { mutableStateOf(false) }
  var resultFile by remember { mutableStateOf<File?>(null) }

  // Text-box dialog state
  var showTextDialog by remember { mutableStateOf(false) }
  var pendingTextPos by remember { mutableStateOf<Offset?>(null) }
  var textDialogText by remember { mutableStateOf("") }
  var textDialogSize by remember { mutableStateOf(24f) }
  var textDialogColour by remember { mutableStateOf(AndroidColor.BLACK) }

  // Signature dialog state
  var showSignDialog by remember { mutableStateOf(false) }
  var pendingSignPos by remember { mutableStateOf<Offset?>(null) }
  var sigStrokes by remember { mutableStateOf<List<List<Offset>>>(emptyList()) }
  var sigCurrent by remember { mutableStateOf<List<Offset>>(emptyList()) }
  var sigPadSize by remember { mutableStateOf(IntSize.Zero) }
  var savedSigAvailable by remember { mutableStateOf(false) }

  // Drag / pen state (screen pixels, converted to PDF points on commit)
  var dragStart by remember { mutableStateOf<Offset?>(null) }
  var dragCurrent by remember { mutableStateOf<Offset?>(null) }
  var penPoints by remember { mutableStateOf<List<Offset>>(emptyList()) }
  var pageViewSize by remember { mutableStateOf(IntSize.Zero) }
  var pendingStampBitmap by remember { mutableStateOf<Bitmap?>(null) }
  var pickImageRequested by remember { mutableStateOf(false) }

  fun vibrate() {
    if (tm.hapticOn()) {
      try {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
      } catch (_: Exception) {}
    }
  }

  fun toPdfX(x: Float): Float =
    if (pageViewSize.width > 0) x / pageViewSize.width * 595f else 0f

  fun toPdfY(y: Float): Float =
    if (pageViewSize.height > 0) y / pageViewSize.height * 842f else 0f

  fun toPdfRect(a: Offset, b: Offset): RectF = RectF(
    min(toPdfX(a.x), toPdfX(b.x)),
    min(toPdfY(a.y), toPdfY(b.y)),
    max(toPdfX(a.x), toPdfX(b.x)),
    max(toPdfY(a.y), toPdfY(b.y))
  )

  fun commit(newPages: List<PageRef>, newAnnotations: Map<Int, List<Annotation>>, newPage: Int) {
    undoStack = (undoStack + EditorSnapshot(pages, annotations)).takeLast(50)
    redoStack = emptyList()
    pages = newPages
    annotations = newAnnotations
    currentPage = newPage.coerceIn(0, (newPages.size - 1).coerceAtLeast(0))
  }

  fun addAnnotation(a: Annotation) {
    val list = (annotations[a.page] ?: emptyList()) + a
    commit(pages, annotations + (a.page to list), currentPage)
  }

  fun undo() {
    if (undoStack.isEmpty()) return
    redoStack = redoStack + EditorSnapshot(pages, annotations)
    val s = undoStack.last()
    undoStack = undoStack.dropLast(1)
    pages = s.pages
    annotations = s.annotations
    currentPage = currentPage.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
  }

  fun redo() {
    if (redoStack.isEmpty()) return
    undoStack = undoStack + EditorSnapshot(pages, annotations)
    val s = redoStack.last()
    redoStack = redoStack.dropLast(1)
    pages = s.pages
    annotations = s.annotations
    currentPage = currentPage.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
  }

  fun eraseAt(offset: Offset) {
    val list = annotations[currentPage] ?: return
    val px = toPdfX(offset.x)
    val py = toPdfY(offset.y)
    val idx = list.indexOfLast { PdfEditorEngine.annotationBounds(it).contains(px, py) }
    if (idx >= 0) {
      val newList = list.toMutableList().apply { removeAt(idx) }
      commit(pages, annotations + (currentPage to newList), currentPage)
    } else {
      Toast.makeText(ctx, "No annotation there", Toast.LENGTH_SHORT).show()
    }
  }

  fun placeSavedSignature(offset: Offset) {
    scope.launch(Dispatchers.IO) {
      val bmp = PdfEditorEngine.loadSavedSignature(ctx)
      withContext(Dispatchers.Main) {
        if (bmp == null) {
          showSignDialog = true
          pendingSignPos = offset
        } else {
          addAnnotation(
            Annotation.Signature(currentPage, bmp, toPdfX(offset.x), toPdfY(offset.y), 180f, 90f)
          )
          mode = EditorMode.SELECT
        }
      }
    }
  }

  fun handleTap(offset: Offset) {
    when (mode) {
      EditorMode.TEXT -> {
        pendingTextPos = offset
        textDialogText = ""
        showTextDialog = true
      }
      EditorMode.SIGN -> {
        pendingSignPos = offset
        if (savedSigAvailable) placeSavedSignature(offset)
        else showSignDialog = true
      }
      EditorMode.IMAGE -> {
        val bmp = pendingStampBitmap
        if (bmp == null) {
          Toast.makeText(ctx, "Pick an image first", Toast.LENGTH_SHORT).show()
          pickImageRequested = true
        } else {
          addAnnotation(
            Annotation.ImageStamp(currentPage, bmp, toPdfX(offset.x), toPdfY(offset.y), 150f, 150f)
          )
          pendingStampBitmap = null
          mode = EditorMode.SELECT
        }
      }
      EditorMode.ERASE -> eraseAt(offset)
      else -> {
        // SELECT and drag modes: a tap alone does nothing.
      }
    }
  }

  fun handleDragStart(offset: Offset) {
    when (mode) {
      EditorMode.HIGHLIGHT, EditorMode.UNDERLINE, EditorMode.STRIKE, EditorMode.REDACT -> {
        dragStart = offset
        dragCurrent = offset
      }
      EditorMode.PEN -> penPoints = listOf(offset)
      else -> {}
    }
  }

  fun handleDragMove(offset: Offset) {
    when (mode) {
      EditorMode.HIGHLIGHT, EditorMode.UNDERLINE, EditorMode.STRIKE, EditorMode.REDACT -> {
        dragCurrent = offset
      }
      EditorMode.PEN -> penPoints = penPoints + offset
      else -> {}
    }
  }

  fun handleDragEnd() {
    val start = dragStart
    val end = dragCurrent
    if (start != null && end != null) {
      val rect = toPdfRect(start, end)
      if (rect.width() > 4f && rect.height() > 4f) {
        when (mode) {
          EditorMode.HIGHLIGHT -> addAnnotation(Annotation.Highlight(currentPage, rect, drawColour))
          EditorMode.UNDERLINE -> addAnnotation(Annotation.Underline(currentPage, rect, drawColour))
          EditorMode.STRIKE -> addAnnotation(Annotation.Strike(currentPage, rect, drawColour))
          EditorMode.REDACT -> addAnnotation(Annotation.Redact(currentPage, rect))
          else -> {}
        }
      }
    }
    if (mode == EditorMode.PEN && penPoints.size >= 2) {
      val pts = penPoints.map { PointF(toPdfX(it.x), toPdfY(it.y)) }
      addAnnotation(Annotation.PenPath(currentPage, pts, drawColour, penWidth))
    }
    dragStart = null
    dragCurrent = null
    penPoints = emptyList()
  }

  fun movePage(dir: Int) {
    val to = currentPage + dir
    if (to !in pages.indices) return
    val newPages = pages.toMutableList().apply {
      val item = removeAt(currentPage)
      add(to, item)
    }
    // NOTE: annotations stay with the output page number, not the
    // source page. Reorder first, then annotate.
    commit(newPages, annotations, to)
  }

  fun deletePage() {
    if (pages.size <= 1) {
      Toast.makeText(ctx, "Cannot delete the only page", Toast.LENGTH_SHORT).show()
      return
    }
    val newPages = pages.toMutableList().apply { removeAt(currentPage) }
    val shifted = mutableMapOf<Int, List<Annotation>>()
    annotations.forEach { (k, v) ->
      when {
        k < currentPage -> shifted[k] = v
        k > currentPage -> shifted[k - 1] = v
      }
    }
    commit(newPages, shifted, min(currentPage, newPages.size - 1))
  }

  fun rotatePage() {
    val ref = pages.getOrNull(currentPage) ?: return
    val newPages = pages.toMutableList().apply {
      set(currentPage, ref.copy(rotation = (ref.rotation + 90) % 360))
    }
    commit(newPages, annotations, currentPage)
  }

  fun addBlankPage() {
    val newPages = pages.toMutableList().apply {
      add(currentPage + 1, PageRef(blank = true))
    }
    commit(newPages, annotations, currentPage + 1)
  }

  fun saveCopy() {
    val uri = pdfUri ?: return
    // Phase 1 ad hook: the file is built AFTER the ad closes (or
    // immediately if no ad is ready). The save is never blocked.
    // Phase 6: ads removed — build directly, no interstitial.
        run {
      isProcessing = true
      scope.launch {
        val out = PdfEngine.outFile(ctx, "Edited_${System.currentTimeMillis()}.pdf")
        val ok = withContext(Dispatchers.IO) {
          PdfEditorEngine.saveAnnotatedPdf(
            ctx, uri, pages, annotations, watermarkText.trim(), showPageNumbers, out
          )
        }
        isProcessing = false
        if (ok) resultFile = out
        else Toast.makeText(ctx, "Failed to save edited PDF", Toast.LENGTH_SHORT).show()
      }
    }
  }

  val pdfPicker = rememberLauncherForActivityResult(
    ActivityResultContracts.GetContent()
  ) { uri: Uri? ->
    if (uri == null) return@rememberLauncherForActivityResult
    scope.launch(Dispatchers.IO) {
      val count = PdfEngine.pageCount(ctx, uri)
      val sigDir = File(ctx.filesDir, "signatures")
      val hasSig = sigDir.exists() &&
        (sigDir.listFiles()?.any { it.name.endsWith(".png") } == true)
      withContext(Dispatchers.Main) {
        if (count <= 0) {
          Toast.makeText(ctx, "Could not open that PDF", Toast.LENGTH_SHORT).show()
          return@withContext
        }
        pdfUri = uri
        totalPages = count
        pages = (0 until count).map { PageRef(sourcePage = it) }
        currentPage = 0
        annotations = emptyMap()
        undoStack = emptyList()
        redoStack = emptyList()
        resultFile = null
        savedSigAvailable = hasSig
      }
    }
  }

  val imagePicker = rememberLauncherForActivityResult(
    ActivityResultContracts.GetContent()
  ) { uri: Uri? ->
    if (uri == null) return@rememberLauncherForActivityResult
    scope.launch(Dispatchers.IO) {
      val bmp = PdfEngine.loadBitmap(ctx, uri, 1200)
      withContext(Dispatchers.Main) {
        if (bmp == null) {
          Toast.makeText(ctx, "Could not load that image", Toast.LENGTH_SHORT).show()
        } else {
          pendingStampBitmap = bmp
          Toast.makeText(ctx, "Tap the page to place the image", Toast.LENGTH_SHORT).show()
        }
      }
    }
  }

  val imagePagePicker = rememberLauncherForActivityResult(
    ActivityResultContracts.GetContent()
  ) { uri: Uri? ->
    if (uri == null) return@rememberLauncherForActivityResult
    val newPages = pages.toMutableList().apply {
      add(currentPage + 1, PageRef(imageUri = uri))
    }
    commit(newPages, annotations, currentPage + 1)
  }

  // The IMAGE tap handler (above) can ask for the picker before this
  // launcher exists in the code below, so it goes through a flag.
  LaunchedEffect(pickImageRequested) {
    if (pickImageRequested) {
      pickImageRequested = false
      imagePicker.launch("image/*")
    }
  }

  val saveLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.CreateDocument("application/pdf")
  ) { uri: Uri? ->
    if (uri != null && resultFile != null) {
      if (SaveLocation.copyToUri(ctx, resultFile!!, uri)) {
        Toast.makeText(ctx, "Saved to chosen folder", Toast.LENGTH_SHORT).show()
      } else {
        Toast.makeText(ctx, "Could not save there", Toast.LENGTH_SHORT).show()
      }
    }
  }

  // Render the current page (one page at a time, like the v1.0 Reader).
  // Rotation is applied here too, so the preview matches the saved copy.
  LaunchedEffect(pdfUri, currentPage, zoom, pages) {
    val uri = pdfUri ?: return@LaunchedEffect
    val ref = pages.getOrNull(currentPage) ?: return@LaunchedEffect
    val bmp = withContext(Dispatchers.IO) {
      val raw = when {
        ref.blank -> null
        ref.imageUri != null -> PdfEngine.loadBitmap(ctx, ref.imageUri, 1200)
        ref.sourcePage >= 0 -> PdfReader.renderPageBitmap(ctx, uri, ref.sourcePage, zoom)
        else -> null
      }
      if (raw != null && ref.rotation != 0) {
        val m = Matrix().apply { postRotate(ref.rotation.toFloat()) }
        Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true).also { raw.recycle() }
      } else {
        raw
      }
    }
    val old = pageBitmap
    pageBitmap = bmp
    old?.recycle()
  }

  val highlightColours = listOf(
    "Yellow" to AndroidColor.YELLOW,
    "Green" to AndroidColor.GREEN,
    "Pink" to AndroidColor.rgb(0xF4, 0x72, 0xB5),
    "Blue" to AndroidColor.rgb(0x3B, 0x82, 0xF6),
    "Red" to AndroidColor.RED,
    "Black" to AndroidColor.BLACK
  )

  ToolScreenContainer("PDF Editor", theme, onBack) {
    if (pdfUri == null) {
      Column(
        Modifier.weight(1f),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Text(
          "Pick a PDF to edit. Your original is never changed — saving always makes a copy.",
          fontFamily = PixelFont, fontSize = 12.sp, color = theme.text,
          textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        Button(
          onClick = { vibrate(); pdfPicker.launch("application/pdf") },
          modifier = Modifier.fillMaxWidth().height(52.dp),
          colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
          shape = RoundedCornerShape(8.dp)
        ) {
          Text("SELECT PDF", fontFamily = PixelFont, color = theme.textOnPrimary, fontSize = 14.sp, fontWeight = FontWeight.Black)
        }
      }
      return@ToolScreenContainer
    }

    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
      // ---- Mode banner: one mode active at a time, always labelled ----
      if (mode != EditorMode.SELECT) {
        Row(
          Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(theme.primary.copy(alpha = 0.12f))
            .border(1.dp, theme.primary, RoundedCornerShape(10.dp))
            .padding(10.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Column(Modifier.weight(1f)) {
            Text(
              "MODE: ${mode.label}",
              fontFamily = PixelFont, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = theme.primaryDark
            )
            Text(mode.hint, fontFamily = PixelFont, fontSize = 11.sp, color = theme.text)
          }
          Spacer(Modifier.width(8.dp))
          Button(
            onClick = { vibrate(); mode = EditorMode.SELECT },
            colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
            shape = RoundedCornerShape(8.dp)
          ) {
            Text("DONE", fontFamily = PixelFont, color = theme.textOnPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
          }
        }
        Spacer(Modifier.height(10.dp))
      }

      // ---- Mode toolbar ----
      Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        EditorMode.values().forEach { m ->
          EditorChip(m.label, mode == m, theme) { vibrate(); mode = m }
        }
      }
      Spacer(Modifier.height(8.dp))

      // ---- Colour + pen width ----
      Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Colour:", fontFamily = PixelFont, fontSize = 11.sp, color = theme.text)
        highlightColours.forEach { (name, value) ->
          Box(
            Modifier
              .size(30.dp)
              .clip(RoundedCornerShape(15.dp))
              .background(Color(value))
              .border(
                if (drawColour == value) 3.dp else 1.dp,
                if (drawColour == value) theme.primary else theme.cardBorder,
                RoundedCornerShape(15.dp)
              )
              .clickable { drawColour = value },
            contentAlignment = Alignment.Center
          ) {
            if (drawColour == value) {
              Text(
                "✓",
                color = if (value == AndroidColor.YELLOW || value == AndroidColor.GREEN) Color.Black else Color.White,
                fontSize = 12.sp, fontWeight = FontWeight.Bold
              )
            }
          }
        }
        Text("Colour: ${highlightColours.firstOrNull { it.second == drawColour }?.first ?: ""}", fontFamily = PixelFont, fontSize = 10.sp, color = theme.textSecondary)
        if (mode == EditorMode.PEN) {
          listOf(2f, 4f, 8f).forEach { w ->
            EditorChip("${w.toInt()}px", penWidth == w, theme) { penWidth = w }
          }
        }
      }
      if (mode == EditorMode.IMAGE && pendingStampBitmap == null) {
        Spacer(Modifier.height(6.dp))
        Text("No image picked yet — tap IMAGE, pick one, then tap the page.", fontFamily = PixelFont, fontSize = 11.sp, color = theme.textSecondary)
      }
      if (mode == EditorMode.SIGN && savedSigAvailable) {
        Spacer(Modifier.height(6.dp))
        Text("Saved signature found — tap the page to place it.", fontFamily = PixelFont, fontSize = 11.sp, color = theme.textSecondary)
      }

      Spacer(Modifier.height(10.dp))

      // ---- Page navigation + zoom ----
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(
          onClick = { if (currentPage > 0) currentPage-- },
          enabled = currentPage > 0,
          shape = RoundedCornerShape(8.dp)
        ) {
          Text("← PREV", fontFamily = PixelFont, color = theme.text, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Text(
          "Page ${currentPage + 1} / ${pages.size}",
          fontFamily = PixelFont, fontSize = 12.sp, color = theme.text,
          modifier = Modifier.weight(1f),
          textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        OutlinedButton(
          onClick = { if (currentPage < pages.size - 1) currentPage++ },
          enabled = currentPage < pages.size - 1,
          shape = RoundedCornerShape(8.dp)
        ) {
          Text("NEXT →", fontFamily = PixelFont, color = theme.text, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
      }
      Spacer(Modifier.height(6.dp))
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
          value = goToText,
          onValueChange = { goToText = it.filter { c -> c.isDigit() } },
          label = { Text("Go to page", fontFamily = PixelFont, fontSize = 10.sp) },
          modifier = Modifier.weight(1f), singleLine = true
        )
        OutlinedButton(
          onClick = {
            val n = goToText.toIntOrNull()
            if (n != null && n in 1..pages.size) currentPage = n - 1
            else Toast.makeText(ctx, "Page 1–${pages.size}", Toast.LENGTH_SHORT).show()
          },
          shape = RoundedCornerShape(8.dp)
        ) {
          Text("GO", fontFamily = PixelFont, color = theme.text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        OutlinedButton(
          onClick = { zoom = when (zoom) { 1f -> 1.5f; 1.5f -> 2f; else -> 1f } },
          shape = RoundedCornerShape(8.dp)
        ) {
          Text("ZOOM ${zoom}x", fontFamily = PixelFont, color = theme.text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
      }

      Spacer(Modifier.height(10.dp))

      // ---- Page canvas ----
      Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Box(
          Modifier
            .width((320f * zoom).dp)
            .aspectRatio(595f / 842f)
            .onSizeChanged { pageViewSize = it }
            .background(Color.White)
            .border(1.dp, theme.cardBorder)
            .pointerInput(mode, currentPage) {
              detectTapGestures(onTap = { offset -> handleTap(offset) })
            }
            .pointerInput(mode, currentPage) {
              detectDragGestures(
                onDragStart = { offset -> handleDragStart(offset) },
                onDrag = { change, _ -> handleDragMove(change.position) },
                onDragEnd = { handleDragEnd() },
                onDragCancel = {
                  dragStart = null
                  dragCurrent = null
                  penPoints = emptyList()
                }
              )
            }
        ) {
          if (pageBitmap != null) {
            Image(
              bitmap = pageBitmap!!.asImageBitmap(),
              contentDescription = "Page ${currentPage + 1}",
              contentScale = ContentScale.FillBounds,
              modifier = Modifier.fillMaxSize()
            )
          } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
              Text("Blank page", fontFamily = PixelFont, fontSize = 12.sp, color = theme.textSecondary)
            }
          }
          // Saved annotations + live drag preview, drawn on top.
          Canvas(Modifier.fillMaxSize()) {
            val anns = annotations[currentPage] ?: emptyList()
            for (a in anns) {
              when (a) {
                is Annotation.Highlight -> drawRect(
                  color = Color(a.colour).copy(alpha = 0.35f),
                  topLeft = Offset(a.rect.left / 595f * size.width, a.rect.top / 842f * size.height),
                  size = Size(a.rect.width() / 595f * size.width, a.rect.height() / 842f * size.height)
                )
                is Annotation.Underline -> drawLine(
                  color = Color(a.colour),
                  start = Offset(a.rect.left / 595f * size.width, a.rect.bottom / 842f * size.height),
                  end = Offset(a.rect.right / 595f * size.width, a.rect.bottom / 842f * size.height),
                  strokeWidth = 3f
                )
                is Annotation.Strike -> {
                  val mid = (a.rect.top + a.rect.bottom) / 2f / 842f * size.height
                  drawLine(
                    color = Color(a.colour),
                    start = Offset(a.rect.left / 595f * size.width, mid),
                    end = Offset(a.rect.right / 595f * size.width, mid),
                    strokeWidth = 3f
                  )
                }
                is Annotation.Redact -> drawRect(
                  color = Color.Black,
                  topLeft = Offset(a.rect.left / 595f * size.width, a.rect.top / 842f * size.height),
                  size = Size(a.rect.width() / 595f * size.width, a.rect.height() / 842f * size.height)
                )
                is Annotation.TextBox -> {
                  val paint = android.graphics.Paint().apply {
                    color = a.colour
                    textSize = a.size / 595f * size.width
                    isAntiAlias = true
                  }
                  drawContext.canvas.nativeCanvas.drawText(
                    a.text, a.x / 595f * size.width, a.y / 842f * size.height, paint
                  )
                }
                is Annotation.PenPath -> {
                  if (a.points.size >= 2) {
                    val path = Path().apply {
                      moveTo(a.points[0].x / 595f * size.width, a.points[0].y / 842f * size.height)
                      for (i in 1 until a.points.size) {
                        lineTo(a.points[i].x / 595f * size.width, a.points[i].y / 842f * size.height)
                      }
                    }
                    drawPath(path, color = Color(a.colour), style = Stroke(width = a.width / 595f * size.width))
                  }
                }
                is Annotation.Signature -> drawImage(
                  image = a.bitmap.asImageBitmap(),
                  dstOffset = IntOffset((a.x / 595f * size.width).roundToInt(), (a.y / 842f * size.height).roundToInt()),
                  dstSize = IntSize((a.w / 595f * size.width).roundToInt(), (a.h / 842f * size.height).roundToInt())
                )
                is Annotation.ImageStamp -> drawImage(
                  image = a.bitmap.asImageBitmap(),
                  dstOffset = IntOffset((a.x / 595f * size.width).roundToInt(), (a.y / 842f * size.height).roundToInt()),
                  dstSize = IntSize((a.w / 595f * size.width).roundToInt(), (a.h / 842f * size.height).roundToInt())
                )
              }
            }
            val ds = dragStart
            val dc = dragCurrent
            if (ds != null && dc != null &&
              (mode == EditorMode.HIGHLIGHT || mode == EditorMode.UNDERLINE ||
                mode == EditorMode.STRIKE || mode == EditorMode.REDACT)
            ) {
              drawRect(
                color = Color(drawColour).copy(alpha = 0.35f),
                topLeft = Offset(min(ds.x, dc.x), min(ds.y, dc.y)),
                size = Size(kotlin.math.abs(dc.x - ds.x), kotlin.math.abs(dc.y - ds.y))
              )
            }
            if (penPoints.size >= 2) {
              val path = Path().apply {
                moveTo(penPoints[0].x, penPoints[0].y)
                for (i in 1 until penPoints.size) lineTo(penPoints[i].x, penPoints[i].y)
              }
              drawPath(path, color = Color(drawColour), style = Stroke(width = penWidth))
            }
          }
        }
      }

      Spacer(Modifier.height(10.dp))

      // ---- Organise pages ----
      Text("ORGANISE PAGES", fontFamily = PixelFont, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = theme.primaryDark)
      Spacer(Modifier.height(6.dp))
      Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        EditorChip("← MOVE", currentPage > 0, theme) { vibrate(); movePage(-1) }
        EditorChip("MOVE →", currentPage < pages.size - 1, theme) { vibrate(); movePage(1) }
        EditorChip("DELETE PAGE", pages.size > 1, theme) { vibrate(); deletePage() }
        EditorChip("ROTATE 90°", true, theme) { vibrate(); rotatePage() }
        EditorChip("+ BLANK PAGE", true, theme) { vibrate(); addBlankPage() }
        EditorChip("+ IMAGE PAGE", true, theme) { vibrate(); imagePagePicker.launch("image/*") }
      }
      Spacer(Modifier.height(4.dp))
      Text(
        "Tip: reorder, delete and rotate first, then annotate — annotations stay with the page number, not the page.",
        fontFamily = PixelFont, fontSize = 10.sp, color = theme.textSecondary
      )

      Spacer(Modifier.height(12.dp))

      // ---- Stamps ----
      Text("STAMPS — TAP TO PLACE ON THIS PAGE", fontFamily = PixelFont, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = theme.primaryDark)
      Spacer(Modifier.height(6.dp))
      Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        EditorChip("APPROVED", true, theme) {
          vibrate()
          addAnnotation(Annotation.TextBox(currentPage, 150f, 100f, "APPROVED", 28f, AndroidColor.rgb(0x16, 0xA3, 0x4A)))
        }
        EditorChip("CONFIDENTIAL", true, theme) {
          vibrate()
          addAnnotation(Annotation.TextBox(currentPage, 120f, 100f, "CONFIDENTIAL", 28f, AndroidColor.RED))
        }
        EditorChip("DATE", true, theme) {
          vibrate()
          val date = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date())
          addAnnotation(Annotation.TextBox(currentPage, 180f, 100f, date, 24f, AndroidColor.BLACK))
        }
      }

      Spacer(Modifier.height(12.dp))

      // ---- Watermark + page numbers ----
      OutlinedTextField(
        value = watermarkText,
        onValueChange = { watermarkText = it },
        label = { Text("Watermark (optional, every page)", fontFamily = PixelFont, fontSize = 10.sp) },
        modifier = Modifier.fillMaxWidth(), singleLine = true
      )
      Spacer(Modifier.height(6.dp))
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Page numbers", fontFamily = PixelFont, fontSize = 12.sp, color = theme.text, modifier = Modifier.weight(1f))
        Switch(checked = showPageNumbers, onCheckedChange = { showPageNumbers = it })
      }

      Spacer(Modifier.height(10.dp))

      // ---- Undo / Redo / Pick another PDF ----
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
          onClick = { vibrate(); undo() },
          enabled = undoStack.isNotEmpty(),
          modifier = Modifier.weight(1f).height(42.dp),
          shape = RoundedCornerShape(8.dp)
        ) {
          Text("↩ UNDO", fontFamily = PixelFont, color = theme.text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        OutlinedButton(
          onClick = { vibrate(); redo() },
          enabled = redoStack.isNotEmpty(),
          modifier = Modifier.weight(1f).height(42.dp),
          shape = RoundedCornerShape(8.dp)
        ) {
          Text("REDO ↪", fontFamily = PixelFont, color = theme.text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        OutlinedButton(
          onClick = { vibrate(); pdfPicker.launch("application/pdf") },
          modifier = Modifier.weight(1f).height(42.dp),
          shape = RoundedCornerShape(8.dp)
        ) {
          Text("NEW PDF", fontFamily = PixelFont, color = theme.text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
      }

      Spacer(Modifier.height(10.dp))
      if (isProcessing) {
        LinearProgressIndicator(Modifier.fillMaxWidth(), color = theme.primary)
        Spacer(Modifier.height(8.dp))
      }
      resultFile?.let { file ->
        ResultCard(file, theme)
        Spacer(Modifier.height(8.dp))
        Button(
          onClick = { saveLauncher.launch(file.name) },
          modifier = Modifier.fillMaxWidth().height(48.dp),
          colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
          shape = RoundedCornerShape(8.dp)
        ) {
          Text("SAVE TO FOLDER…", fontFamily = PixelFont, color = theme.textOnPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
      }
    }

    Button(
      onClick = { vibrate(); saveCopy() },
      enabled = !isProcessing && pdfUri != null,
      modifier = Modifier.fillMaxWidth().height(56.dp),
      colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
      shape = RoundedCornerShape(8.dp)
    ) {
      Text("SAVE AS COPY", fontFamily = PixelFont, fontWeight = FontWeight.Black, fontSize = 14.sp, color = theme.textOnPrimary)
    }
  }

  // ---- Text-box dialog ----
  if (showTextDialog) {
    AlertDialog(
      onDismissRequest = { showTextDialog = false },
      confirmButton = {
        Button(
          onClick = {
            val pos = pendingTextPos
            if (pos != null && textDialogText.isNotBlank()) {
              addAnnotation(
                Annotation.TextBox(
                  currentPage, toPdfX(pos.x), toPdfY(pos.y),
                  textDialogText, textDialogSize, textDialogColour
                )
              )
              mode = EditorMode.SELECT
            }
            showTextDialog = false
          },
          colors = ButtonDefaults.buttonColors(containerColor = theme.primary)
        ) {
          Text("ADD TEXT", fontFamily = PixelFont, color = theme.textOnPrimary, fontSize = 11.sp)
        }
      },
      dismissButton = {
        TextButton(onClick = { showTextDialog = false }) {
          Text("CANCEL", fontFamily = PixelFont, color = theme.text)
        }
      },
      title = { Text("Add text", fontFamily = PixelFont, fontWeight = FontWeight.Bold, fontSize = 15.sp) },
      text = {
        Column {
          OutlinedTextField(
            value = textDialogText,
            onValueChange = { textDialogText = it },
            placeholder = { Text("Type the text…", fontFamily = PixelFont, fontSize = 11.sp) },
            modifier = Modifier.fillMaxWidth()
          )
          Spacer(Modifier.height(10.dp))
          Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(18f, 24f, 32f).forEach { sz ->
              EditorChip("${sz.toInt()}pt", textDialogSize == sz, theme) { textDialogSize = sz }
            }
          }
          Spacer(Modifier.height(10.dp))
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(
              AndroidColor.BLACK, AndroidColor.RED,
              AndroidColor.BLUE, AndroidColor.rgb(0x16, 0xA3, 0x4A)
            ).forEach { value ->
              Box(
                Modifier
                  .size(30.dp)
                  .clip(RoundedCornerShape(15.dp))
                  .background(Color(value))
                  .border(
                    if (textDialogColour == value) 3.dp else 1.dp,
                    if (textDialogColour == value) theme.primary else theme.cardBorder,
                    RoundedCornerShape(15.dp)
                  )
                  .clickable { textDialogColour = value }
              )
            }
          }
        }
      }
    )
  }

  // ---- Signature dialog ----
  if (showSignDialog) {
    AlertDialog(
      onDismissRequest = { showSignDialog = false },
      confirmButton = {
        Button(
          onClick = {
            val pos = pendingSignPos
            if (sigStrokes.isEmpty() && sigCurrent.isEmpty()) {
              Toast.makeText(ctx, "Draw your signature first", Toast.LENGTH_SHORT).show()
              return@Button
            }
            val all = sigStrokes + listOf(sigCurrent).filter { it.size >= 2 }
            val sx = if (sigPadSize.width > 0) 600f / sigPadSize.width else 1f
            val sy = if (sigPadSize.height > 0) 300f / sigPadSize.height else 1f
            val scaled = all.map { stroke -> stroke.map { Offset(it.x * sx, it.y * sy) } }
            val bmp = strokesToBitmap(scaled, 600, 300)
            scope.launch(Dispatchers.IO) {
              PdfEditorEngine.saveSignatureBitmap(ctx, bmp)
              withContext(Dispatchers.Main) {
                if (pos != null) {
                  addAnnotation(
                    Annotation.Signature(currentPage, bmp, toPdfX(pos.x), toPdfY(pos.y), 180f, 90f)
                  )
                }
                savedSigAvailable = true
                mode = EditorMode.SELECT
                showSignDialog = false
                sigStrokes = emptyList()
                sigCurrent = emptyList()
                Toast.makeText(ctx, "Signature saved for reuse", Toast.LENGTH_SHORT).show()
              }
            }
          },
          colors = ButtonDefaults.buttonColors(containerColor = theme.primary)
        ) {
          Text("SAVE & PLACE", fontFamily = PixelFont, color = theme.textOnPrimary, fontSize = 11.sp)
        }
      },
      dismissButton = {
        TextButton(
          onClick = {
            showSignDialog = false
            sigStrokes = emptyList()
            sigCurrent = emptyList()
          }
        ) {
          Text("CANCEL", fontFamily = PixelFont, color = theme.text)
        }
      },
      title = { Text("Draw your signature", fontFamily = PixelFont, fontWeight = FontWeight.Bold, fontSize = 15.sp) },
      text = {
        Column {
          Text(
            "Draw with your finger. It is saved on this phone and offered next time you tap SIGN.",
            fontFamily = PixelFont, fontSize = 11.sp, color = theme.textSecondary
          )
          Spacer(Modifier.height(8.dp))
          Box(
            Modifier
              .fillMaxWidth()
              .height(180.dp)
              .clip(RoundedCornerShape(8.dp))
              .background(Color.White)
              .border(1.dp, theme.cardBorder, RoundedCornerShape(8.dp))
              .onSizeChanged { sigPadSize = it }
              .pointerInput(Unit) {
                detectDragGestures(
                  onDragStart = { offset -> sigCurrent = listOf(offset) },
                  onDrag = { change, _ -> sigCurrent = sigCurrent + change.position },
                  onDragEnd = {
                    if (sigCurrent.size >= 2) sigStrokes = sigStrokes + listOf(sigCurrent)
                    sigCurrent = emptyList()
                  },
                  onDragCancel = { sigCurrent = emptyList() }
                )
              }
          ) {
            Canvas(Modifier.fillMaxSize()) {
              for (stroke in sigStrokes + listOf(sigCurrent).filter { it.size >= 2 }) {
                val path = Path().apply {
                  moveTo(stroke[0].x, stroke[0].y)
                  for (i in 1 until stroke.size) lineTo(stroke[i].x, stroke[i].y)
                }
                drawPath(path, color = Color.Black, style = Stroke(width = 5f))
              }
            }
          }
          Spacer(Modifier.height(8.dp))
          OutlinedButton(
            onClick = { sigStrokes = emptyList(); sigCurrent = emptyList() },
            shape = RoundedCornerShape(8.dp)
          ) {
            Text("CLEAR", fontFamily = PixelFont, color = theme.text, fontSize = 11.sp)
          }
        }
      }
    )
  }
}
