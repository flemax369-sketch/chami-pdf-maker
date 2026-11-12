package com.chami.pdfmaker.ui

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Rect
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
import com.chami.pdfmaker.ads.AdsManager
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
import androidx.core.content.FileProvider
import com.chami.pdfmaker.pdf.*
import com.chami.pdfmaker.theme.ChamiTheme
import com.chami.pdfmaker.theme.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

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

// 1. IMAGES TO PDF (WITH MULTI-IMAGE PREVIEW)
@Composable
fun ImagesToPdfScreen(theme: ChamiTheme, onBack: () -> Unit) {
  val ctx = LocalContext.current
  val activity = ctx as? Activity
  val haptic = LocalHapticFeedback.current
  val tm = remember { ThemeManager(ctx) }
  val scope = rememberCoroutineScope()
  var pages by remember { mutableStateOf<List<PageItem>>(emptyList()) }
  var isProcessing by remember { mutableStateOf(false) }
  var resultFile by remember { mutableStateOf<File?>(null) }
  var pendingSaveFile by remember { mutableStateOf<File?>(null) }

  fun vibrate() {
    if (tm.hapticOn()) {
      try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
    }
  }

  // Multi-select picker. APPENDS to the existing pages — never replaces.
  val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selected ->
    if (selected.isNotEmpty()) {
      resultFile = null
      scope.launch(Dispatchers.IO) {
        val added = selected.mapNotNull { uri ->
          PdfEngine.loadBitmap(ctx, uri, 1600)?.let { bmp -> PageItem(uri, bmp) }
        }
        withContext(Dispatchers.Main) { pages = pages + added }
      }
    }
  }

  // Save-location picker (Phase 1 SaveLocation). pendingSaveFile is set
  // just before launch so the callback always uses the latest file.
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
  }

  // Builds the PDF from the CURRENT page order and Uris — cropped /
  // rotated pages already point at their temp files (see PageList).
  fun buildPdf() {
    if (pages.isEmpty()) {
      Toast.makeText(ctx, "Please select at least 1 image", Toast.LENGTH_SHORT).show()
      return
    }
    isProcessing = true
    scope.launch {
      val out = PdfEngine.outFile(ctx, "Chami_${System.currentTimeMillis()}.pdf")
      val ok = withContext(Dispatchers.IO) {
        PdfEngine.imagesToPdf(ctx, pages.map { it.uri }, out)
      }
      isProcessing = false
      if (ok) resultFile = out
      else Toast.makeText(ctx, "Failed to create PDF", Toast.LENGTH_SHORT).show()
    }
  }

  ToolScreenContainer("Images to PDF", theme, onBack) {
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
      Text("Select photos from gallery. Crop, rotate and reorder pages before creating the PDF.", fontFamily = PixelFont, fontSize = 12.sp, color = theme.text)
      Spacer(Modifier.height(14.dp))

      Button(
        onClick = {
          vibrate()
          picker.launch("image/*")
        },
        modifier = Modifier.fillMaxWidth().height(48.dp),
        colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
        shape = RoundedCornerShape(8.dp)
      ) {
        Text("CHOOSE IMAGES (${pages.size} selected)", fontFamily = PixelFont, color = theme.textOnPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
      }

      if (pages.isNotEmpty()) {
        Spacer(Modifier.height(14.dp))
        // BIG preview (Phase 5): the first page, large and easy to see,
        // before the page list below. Uses the stored full bitmap.
        Box(
          Modifier
            .fillMaxWidth()
            .height(320.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(theme.card)
            .border(1.dp, theme.cardBorder, RoundedCornerShape(8.dp))
        ) {
          Image(
            bitmap = pages[0].bitmap.asImageBitmap(),
            contentDescription = "Preview of page 1",
            modifier = Modifier.fillMaxSize().padding(4.dp),
            contentScale = ContentScale.Fit
          )
        }
        Spacer(Modifier.height(12.dp))
        PageList(pages = pages, theme = theme, onPagesChange = { pages = it })
        Spacer(Modifier.height(12.dp))
        Button(
          onClick = {
            vibrate()
            picker.launch("image/*")
          },
          modifier = Modifier.fillMaxWidth().height(48.dp),
          colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
          shape = RoundedCornerShape(8.dp)
        ) {
          Text("ADD MORE IMAGES", fontFamily = PixelFont, color = theme.textOnPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
      }

      Spacer(Modifier.height(16.dp))
      if (isProcessing) {
        LinearProgressIndicator(Modifier.fillMaxWidth(), color = theme.primary)
        Spacer(Modifier.height(8.dp))
        Text("Creating PDF on IO thread...", fontFamily = PixelFont, fontSize = 11.sp, color = theme.text)
      }

      resultFile?.let { file ->
        Spacer(Modifier.height(12.dp))
        ResultCard(file, theme)
        Spacer(Modifier.height(12.dp))
        Button(
          onClick = {
            vibrate()
            pendingSaveFile = file
            saveLauncher.launch(file.name)
          },
          modifier = Modifier.fillMaxWidth().height(48.dp),
          colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
          shape = RoundedCornerShape(8.dp)
        ) {
          Text("SAVE TO FOLDER…", fontFamily = PixelFont, color = theme.textOnPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
      }
    }

    Button(
      onClick = {
        vibrate()
        if (pages.isEmpty()) {
          Toast.makeText(ctx, "Please select at least 1 image", Toast.LENGTH_SHORT).show()
          return@Button
        }
        // Ad BEFORE the file is made (Phase 1 AdsManager: capped, and
        // onDone is always called, so the file is never blocked).
        if (activity != null) AdsManager.showBeforeSave(activity) { buildPdf() }
        else buildPdf()
      },
      enabled = !isProcessing && pages.isNotEmpty(),
      modifier = Modifier.fillMaxWidth().height(56.dp),
      colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
      shape = RoundedCornerShape(8.dp)
    ) {
      Text("CONVERT TO PDF (${pages.size} PAGES)", fontFamily = PixelFont, fontWeight = FontWeight.Black, fontSize = 14.sp, color = theme.textOnPrimary)
    }
  }
}

// 2. SCAN TO PDF (FIXED CAMERA + RUNTIME PERMISSION + PREVIEW)
// 2. SCAN TO PDF — Adobe Scan style, Chami green/white (Phase 5).
// - NO mode chips and NO text-extraction tool in this screen.
// - BIG preview fills the screen (weight 1f, ContentScale.Fit) — no
//   scrolling needed to see the page. Vertical toolbar on the LEFT
//   (Retake, Crop, Rotate, Filters, Adjust). Bottom: ONLY Save PDF.
// - Filters picker: Original / B&W / Grayscale / Enhance chips, the
//   selected one is green with a checkmark, applied to the ORIGINAL
//   bitmap (so switching back to Original restores it).
// - Auto-detect edges: luminance bounding-box scan (NOT ML/OpenCV) that
//   opens the free-crop editor with 4 detected corners to drag.
// - Thumbnails: tap to switch, HOLD a thumbnail and drag to reorder.
@Composable
fun ScanToPdfScreen(theme: ChamiTheme, onBack: () -> Unit) {
  val ctx = LocalContext.current
  val activity = ctx as? Activity
  val haptic = LocalHapticFeedback.current
  val tm = remember { ThemeManager(ctx) }
  val scope = rememberCoroutineScope()
  var pages by remember { mutableStateOf<List<PageItem>>(emptyList()) }
  var selectedPage by remember { mutableStateOf(0) }
  var filterMode by remember { mutableStateOf(0) }
  var showFilters by remember { mutableStateOf(false) }
  // Unfiltered bitmap of the selected page, so filters never stack on
  // an already-filtered image and Original can restore it.
  var scanOriginal by remember { mutableStateOf<Bitmap?>(null) }
  // Detected quad (Auto-detect) handed to the crop editor, if any.
  var autoQuad by remember { mutableStateOf<List<Offset>?>(null) }
  val density = LocalDensity.current
  var isProcessing by remember { mutableStateOf(false) }
  var resultFile by remember { mutableStateOf<File?>(null) }
  var pendingSaveFile by remember { mutableStateOf<File?>(null) }
  var cropIndex by remember { mutableStateOf<Int?>(null) }
  var editBusy by remember { mutableStateOf(false) }
  var showAdjust by remember { mutableStateOf(false) }
  var brightness by remember { mutableStateOf(0f) }

  val safeSelected = selectedPage.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
  val filterNames = listOf("Original", "B&W", "Grayscale", "Enhance")

  fun vibrate() {
    if (tm.hapticOn()) {
      try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
    }
  }

  // Each page starts unfiltered; the stored original belongs to the
  // page that was selected when the filter was picked.
  LaunchedEffect(safeSelected) { scanOriginal = null; filterMode = 0; showFilters = false }

  // Apply an edited bitmap to one page (crop / rotate / filter / adjust):
  // save to a temp file first so the PDF uses the edited image.
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
  // Filters picker: apply to the ORIGINAL page bitmap (never to an
  // already-filtered one), so Original restores the page exactly.
  // NOTE: replacePage must be declared  above this function — local
  // funs in Kotlin are only visible  after their declaration point.
  fun applyFilterMode(mode: Int) {
    val page = pages.getOrNull(safeSelected) ?: return
    if (scanOriginal == null) scanOriginal = page.bitmap
    val base = scanOriginal ?: page.bitmap
    filterMode = mode
    if (mode == 0) replacePage(safeSelected, base, closeCrop = true)
    else replacePage(safeSelected, ImagePageEditor.applyFilter(base, mode), closeCrop = true)
  }


  // Camera capture. Each capture APPENDS one page (never replaces).
  val cameraPreviewLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bmp ->
    if (bmp != null) {
      resultFile = null
      scope.launch(Dispatchers.IO) {
        val f = File(ctx.cacheDir, "scan_${System.currentTimeMillis()}.jpg")
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        withContext(Dispatchers.Main) {
          pages = pages + PageItem(Uri.fromFile(f), bmp)
          selectedPage = pages.size - 1 // select the page just added
        }
      }
    }
  }

  // Gallery: multi-select now. Each selected image APPENDS a page.
  val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selected ->
    if (selected.isNotEmpty()) {
      resultFile = null
      scope.launch(Dispatchers.IO) {
        val added = selected.mapNotNull { uri ->
          PdfEngine.loadBitmap(ctx, uri, 1600)?.let { bmp -> PageItem(uri, bmp) }
        }
        withContext(Dispatchers.Main) { pages = pages + added }
      }
    }
  }

  // Runtime camera permission launcher (unchanged from v1.0)
  val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
    if (isGranted) {
      cameraPreviewLauncher.launch(null)
    } else {
      Toast.makeText(ctx, "Camera permission needed. You can also pick from gallery.", Toast.LENGTH_LONG).show()
    }
  }

  fun requestCameraOrLaunch() {
    val permission = Manifest.permission.CAMERA
    if (ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED) {
      cameraPreviewLauncher.launch(null)
    } else {
      permissionLauncher.launch(permission)
    }
  }

  // Save-location picker (Phase 1 SaveLocation). Phase 4, point 3: the
  // SAVE TO FOLDER button is ALWAYS shown — no toggle anywhere.
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
      if (ok) resultFile = out
      else Toast.makeText(ctx, "Failed to create PDF", Toast.LENGTH_SHORT).show()
    }
  }

  // Crop editor for the selected page (Adobe-like: handles + pinch).
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

  // Adjust (brightness) dialog.
  if (showAdjust) {
    val page = pages.getOrNull(safeSelected)
    AlertDialog(
      onDismissRequest = { showAdjust = false },
      title = {
        Text("ADJUST BRIGHTNESS", fontFamily = PixelFont, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = theme.text)
      },
      text = {
        Column {
          Text("Brightness: ${brightness.toInt()}", fontFamily = PixelFont, fontSize = 12.sp, color = theme.textSecondary)
          Slider(
            value = brightness,
            onValueChange = { brightness = it },
            valueRange = -100f..100f,
            colors = SliderDefaults.colors(thumbColor = theme.primary, activeTrackColor = theme.primary)
          )
        }
      },
      confirmButton = {
        Button(
          onClick = {
            showAdjust = false
            if (page != null) {
              replacePage(safeSelected, ImagePageEditor.adjustBrightness(page.bitmap, brightness), closeCrop = true)
              brightness = 0f
            }
          },
          colors = ButtonDefaults.buttonColors(containerColor = theme.primary)
        ) {
          Text("APPLY", fontFamily = PixelFont, color = theme.textOnPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
      },
      dismissButton = {
        TextButton(onClick = { showAdjust = false; brightness = 0f }) {
          Text("CANCEL", fontFamily = PixelFont, color = theme.text)
        }
      }
    )
  }

  ToolScreenContainer("Scan to PDF", theme, onBack) {
    // Phase 5: no vertical scroll here — the preview fills the screen
    // (weight 1f below) so the page is visible without sliding down.
    Column(Modifier.weight(1f)) {
      Text("Capture pages one by one with the camera, or pick several images from the gallery. Crop, rotate and reorder pages before making one PDF.", fontFamily = PixelFont, fontSize = 12.sp, color = theme.text)
      Spacer(Modifier.height(12.dp))

      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
          onClick = {
            vibrate()
            requestCameraOrLaunch()
          },
          modifier = Modifier.weight(1f).height(48.dp),
          colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
          shape = RoundedCornerShape(8.dp)
        ) {
          Text("📷 ADD PAGE — CAMERA", fontFamily = PixelFont, color = theme.textOnPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
          Text("🖼️ ADD PAGES — GALLERY", fontFamily = PixelFont, color = theme.textOnPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
      }

      Spacer(Modifier.height(8.dp))
      Text(
        "Capturing… hold steady — keep the page flat and inside the frame.",
        fontFamily = PixelFont, fontSize = 11.sp, color = theme.textSecondary
      )

      if (pages.isNotEmpty()) {
        val page = pages[safeSelected]
        Spacer(Modifier.height(14.dp))

        // Page pager with left/right arrows.
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

        // Main area: vertical toolbar on the LEFT + the BIG page preview
        // (weight 1f — it fills the screen, auto-fitted, no scrolling).
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          // Vertical toolbar, PC-style sidebar.
          Column(
            Modifier
              .width(60.dp)
              .clip(RoundedCornerShape(12.dp))
              .background(theme.card)
              .border(1.dp, theme.cardBorder, RoundedCornerShape(12.dp))
              .padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
          ) {
            val toolbarItems = listOf(
              Triple("📷", "Retake") {
                pages = pages.filterIndexed { i, _ -> i != safeSelected }
                selectedPage = (safeSelected - 1).coerceAtLeast(0)
                requestCameraOrLaunch()
              },
              Triple("◫", "Crop") { cropIndex = safeSelected },
              Triple("↻", "Rotate") {
                scanOriginal = null
                replacePage(safeSelected, ImagePageEditor.rotate90(page.bitmap), closeCrop = true)
              },
              Triple("✦", "Filters") { showFilters = !showFilters },
              Triple("◐", "Adjust") { showAdjust = true }
            )
            toolbarItems.forEach { (glyph, label, action) ->
              Column(
                Modifier
                  .fillMaxWidth()
                  .clickable {
                    vibrate()
                    action()
                  }
                  .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
              ) {
                Text(glyph, fontSize = 18.sp, color = theme.text)
                Text(label, fontFamily = PixelFont, fontSize = 9.sp, color = theme.textSecondary)
              }
            }
            if (filterMode != 0) {
              Text(
                filterNames[filterMode],
                fontFamily = PixelFont, fontSize = 8.sp, color = theme.primary,
                modifier = Modifier.padding(horizontal = 4.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
              )
            }
          }

          // Selected page — BIG, full-size, auto-fitted (ContentScale.Fit
          // on the stored full bitmap; gallery loads are 1600px, see above).
          Box(
            Modifier
              .weight(1f)
              .fillMaxSize()
              .clip(RoundedCornerShape(8.dp))
              .background(theme.card)
              .border(1.dp, theme.cardBorder, RoundedCornerShape(8.dp))
          ) {
            Image(
              bitmap = page.bitmap.asImageBitmap(),
              contentDescription = "Page ${safeSelected + 1}",
              modifier = Modifier.fillMaxSize().padding(4.dp),
              contentScale = ContentScale.Fit
            )
          }
        }

        Spacer(Modifier.height(10.dp))

        // Auto-detect edges (Phase 5): finds the document bounds and
        // opens the free-crop editor with the 4 detected corners.
        Row(
          Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
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
          Text(
            "Drag the 4 corners in the crop editor",
            fontFamily = PixelFont, fontSize = 10.sp, color = theme.textSecondary,
            modifier = Modifier.weight(1f)
          )
        }

        // Filters picker (Phase 5): the SELECTED filter is green with a
        // checkmark, and it is really applied (to the original bitmap).
        if (showFilters) {
          Spacer(Modifier.height(10.dp))
          LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(filterNames) { fIndex, fName ->
              val isSel = filterMode == fIndex
              Box(
                Modifier
                  .clip(RoundedCornerShape(16.dp))
                  .background(if (isSel) theme.primary else theme.card)
                  .border(1.dp, theme.cardBorder, RoundedCornerShape(16.dp))
                  .clickable {
                    vibrate()
                    applyFilterMode(fIndex)
                  }
                  .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
              ) {
                Text(
                  if (isSel) "✓ $fName" else fName,
                  fontFamily = PixelFont,
                  fontSize = 11.sp,
                  fontWeight = FontWeight.Bold,
                  color = if (isSel) theme.textOnPrimary else theme.text
                )
              }
            }
          }
        }

        Spacer(Modifier.height(12.dp))

        // Multi-page preview strip (hidden once the PDF is made, so the
        // result card has room): tap to switch, HOLD a thumbnail and
        // drag it left/right to reorder.
        if (resultFile == null) {
        // Multi-page preview strip: tap a thumbnail to switch pages.
        Text(
          "PAGES — TAP TO SWITCH:",
          fontFamily = PixelFont, fontWeight = FontWeight.Bold,
          fontSize = 12.sp, color = theme.primaryDark
        )
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          itemsIndexed(pages) { index, p ->
            val isSel = index == safeSelected
            var dragOffsetX by remember { mutableStateOf(0f) }
            val dragThresholdPx = with(density) { 96.dp.toPx() }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
              Box(
                Modifier
                  .size(96.dp)
                  .graphicsLayer { translationX = dragOffsetX }
                  // Phase 5: hold the THUMBNAIL itself, then drag it.
                  .pointerInput(index) {
                    detectDragGesturesAfterLongPress(
                      onDrag = { change, dragAmount ->
                        change.consume()
                        dragOffsetX += dragAmount.x
                        if (dragOffsetX > dragThresholdPx && index < pages.lastIndex) {
                          pages = ImagePageEditor.move(pages, index, index + 1)
                          selectedPage = index + 1
                          dragOffsetX = 0f
                        } else if (dragOffsetX < -dragThresholdPx && index > 0) {
                          pages = ImagePageEditor.move(pages, index, index - 1)
                          selectedPage = index - 1
                          dragOffsetX = 0f
                        }
                      },
                      onDragEnd = { dragOffsetX = 0f },
                      onDragCancel = { dragOffsetX = 0f }
                    )
                  }
                  .clip(RoundedCornerShape(8.dp))
                  .background(theme.card)
                  .border(
                    2.dp,
                    if (isSel) theme.primary else theme.cardBorder,
                    RoundedCornerShape(8.dp)
                  )
                  .clickable { vibrate(); selectedPage = index }
              ) {
                Image(
                  bitmap = p.bitmap.asImageBitmap(),
                  contentDescription = "Page ${index + 1}",
                  modifier = Modifier.fillMaxSize(),
                  contentScale = ContentScale.Crop
                )
                if (isSel) {
                  Box(
                    Modifier
                      .align(Alignment.TopEnd)
                      .offset(4.dp, (-4).dp)
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
                fontFamily = PixelFont, fontSize = 10.sp,
                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                color = if (isSel) theme.primary else theme.text
              )
              TextButton(
                onClick = {
                  vibrate()
                  pages = pages.filterIndexed { i, _ -> i != index }
                  selectedPage = (selectedPage - 1).coerceAtLeast(0)
                },
                contentPadding = PaddingValues(0.dp)
              ) {
                Text("✕ Remove", fontFamily = PixelFont, fontSize = 9.sp, color = theme.textSecondary)
              }
            }
          }
        }
        }
      }

      Spacer(Modifier.height(16.dp))
      if (isProcessing) {
        LinearProgressIndicator(Modifier.fillMaxWidth(), color = theme.primary)
        Spacer(Modifier.height(8.dp))
        Text("Building scanned PDF document...", fontFamily = PixelFont, fontSize = 11.sp, color = theme.text)
      }

      resultFile?.let { file ->
        Spacer(Modifier.height(12.dp))
        ResultCard(file, theme)
        Spacer(Modifier.height(12.dp))
        Button(
          onClick = {
            vibrate()
            pendingSaveFile = file
            saveLauncher.launch(file.name)
          },
          modifier = Modifier.fillMaxWidth().height(48.dp),
          colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
          shape = RoundedCornerShape(8.dp)
        ) {
          Text("SAVE TO FOLDER…", fontFamily = PixelFont, color = theme.textOnPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
      }
    }

    // Bottom has ONLY the Save PDF button (Phase 4, point 10).
    Button(
      onClick = {
        vibrate()
        if (pages.isEmpty()) {
          Toast.makeText(ctx, "Capture or select a document first", Toast.LENGTH_SHORT).show()
          return@Button
        }
        // Ad BEFORE the file is made (Phase 1 AdsManager: capped, and
        // onDone is always called, so the file is never blocked).
        if (activity != null) AdsManager.showBeforeSave(activity) { buildPdf() }
        else buildPdf()
      },
      enabled = !isProcessing && pages.isNotEmpty(),
      modifier = Modifier.fillMaxWidth().height(56.dp),
      colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
      shape = RoundedCornerShape(8.dp)
    ) {
      Text("GENERATE SCANNED PDF (${pages.size} PAGES)", fontFamily = PixelFont, fontWeight = FontWeight.Black, fontSize = 14.sp, color = theme.textOnPrimary)
    }
  }
}


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

  fun vibrate() {
    if (tm.hapticOn()) {
      try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
    }
  }

  val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { selected ->
    pdfUri = selected
    extractedCount = 0
    pageOnePreview = null
    if (selected != null) {
      scope.launch(Dispatchers.IO) {
        val n = PdfEngine.pageCount(ctx, selected)
        val bmp = PdfReader.renderPageBitmap(ctx, selected, 0, 2.0f)
        withContext(Dispatchers.Main) {
          totalPages = n
          pageOnePreview = bmp
        }
      }
    }
  }

  ToolScreenContainer("PDF to Images", theme, onBack) {
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
      Text("Extracts every PDF page into sharp JPG files with preview.", fontFamily = PixelFont, fontSize = 12.sp, color = theme.text)
      Spacer(Modifier.height(14.dp))

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

      // PREVIEW SECTION BEFORE MAKING FILE
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
          Spacer(Modifier.height(6.dp))
          Text("Will extract all $totalPages pages as high-quality JPGs", fontFamily = PixelFont, fontSize = 10.sp, color = theme.text)
        }
      }

      Spacer(Modifier.height(16.dp))
      if (isProcessing) {
        LinearProgressIndicator(Modifier.fillMaxWidth(), color = theme.primary)
        Spacer(Modifier.height(8.dp))
        Text("Rendering pages to JPG...", fontFamily = PixelFont, fontSize = 11.sp, color = theme.text)
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
          Text("Extracted $extractedCount pages to JPG", fontFamily = PixelFont, color = theme.text, fontSize = 12.sp)
          Spacer(Modifier.height(12.dp))
          val firstImg = File(outDir, "page_1.jpg")
          if (firstImg.exists()) {
            Button(
              onClick = {
                vibrate()
                ShareHelper.openFile(ctx, firstImg, "image/jpeg")
              },
              modifier = Modifier.fillMaxWidth().height(48.dp),
              colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
              shape = RoundedCornerShape(8.dp)
            ) {
              Text("OPEN FIRST PAGE (JPG)", fontFamily = PixelFont, color = theme.textOnPrimary, fontSize = 12.sp)
            }
          }
        }
      }
    }

    Button(
      onClick = {
        vibrate()
        val u = pdfUri ?: return@Button
        isProcessing = true
        scope.launch {
          val dir = File(ctx.getExternalFilesDir(null), "Chami/Images_${System.currentTimeMillis()}")
          if (!dir.exists()) dir.mkdirs()
          val count = withContext(Dispatchers.IO) {
            PdfEngine.pdfToImages(ctx, u, dir) { _, _ -> }
          }
          isProcessing = false
          extractedCount = count
          outDir = dir
        }
      },
      enabled = !isProcessing && pdfUri != null,
      modifier = Modifier.fillMaxWidth().height(56.dp),
      colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
      shape = RoundedCornerShape(8.dp)
    ) {
      Text("EXTRACT TO IMAGES ($totalPages PAGES)", fontFamily = PixelFont, fontWeight = FontWeight.Black, fontSize = 14.sp, color = theme.textOnPrimary)
    }
  }
}

// 4. SPLIT PDF (WITH LIVE PAGE PREVIEW)
@Composable
fun SplitPdfScreen(theme: ChamiTheme, onBack: () -> Unit) {
  val ctx = LocalContext.current
  val activity = ctx as? Activity
  val haptic = LocalHapticFeedback.current
  val tm = remember { ThemeManager(ctx) }
  val scope = rememberCoroutineScope()
  var pdfUri by remember { mutableStateOf<Uri?>(null) }
  var totalPages by remember { mutableStateOf(0) }
  val selectedPages = remember { mutableStateListOf<Int>() }
  var previewPageIndex by remember { mutableStateOf(1) }
  var pagePreviewBitmap by remember { mutableStateOf<Bitmap?>(null) }
  var isProcessing by remember { mutableStateOf(false) }
  var resultFile by remember { mutableStateOf<File?>(null) }
  var pendingSaveFile by remember { mutableStateOf<File?>(null) }

  val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
    if (uri != null && pendingSaveFile != null) {
      SaveLocation.copyToUri(ctx, pendingSaveFile!!, uri)
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
    resultFile = null
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
        }
      }
    }
  }

  ToolScreenContainer("Split PDF", theme, onBack) {
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
      Text("Pick a PDF. Tap page chips to select pages and view live preview.", fontFamily = PixelFont, fontSize = 12.sp, color = theme.text)
      Spacer(Modifier.height(14.dp))

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

        // PREVIEW SECTION BEFORE MAKING FILE
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
            Spacer(Modifier.height(4.dp))
            Text("Selected to keep: ${selectedPages.contains(previewPageIndex)}", fontFamily = PixelFont, fontSize = 10.sp, color = theme.text)
          }
        }
      }

      Spacer(Modifier.height(16.dp))
      if (isProcessing) {
        LinearProgressIndicator(Modifier.fillMaxWidth(), color = theme.primary)
        Spacer(Modifier.height(8.dp))
        Text("Extracting pages at 2x crisp rendering...", fontFamily = PixelFont, fontSize = 11.sp, color = theme.text)
      }

      resultFile?.let { file ->
        Spacer(Modifier.height(12.dp))
        ResultCard(file, theme)
      }
      resultFile?.let { file ->
        Spacer(Modifier.height(10.dp))
        PrimaryButton(
          theme = theme,
          text = "SAVE TO FOLDER…",
          height = 48.dp,
          onClick = {
            vibrate()
            pendingSaveFile = file
            saveLauncher.launch(file.name)
          }
        )
      }
    }

    PrimaryButton(
      theme = theme,
      text = "SPLIT & SAVE ${selectedPages.size} PAGES",
      enabled = !isProcessing && pdfUri != null && selectedPages.isNotEmpty(),
      onClick = {
        vibrate()
        val u = pdfUri ?: return@PrimaryButton
        if (selectedPages.isEmpty()) {
          Toast.makeText(ctx, "Select at least 1 page to keep", Toast.LENGTH_SHORT).show()
          return@PrimaryButton
        }
        val runSave = {
          isProcessing = true
          scope.launch {
            val out = PdfEngine.outFile(ctx, "Split_${System.currentTimeMillis()}.pdf")
            val ok = withContext(Dispatchers.IO) {
              PdfEngine.copyPages(ctx, listOf(u to selectedPages.toSet()), out)
            }
            isProcessing = false
            if (ok) resultFile = out
            else Toast.makeText(ctx, "Failed to split PDF", Toast.LENGTH_SHORT).show()
          }
        }
        if (activity != null) AdsManager.showBeforeSave(activity) { runSave() } else runSave()
      }
    )
  }
}

// 5. MERGE PDF (WITH LIST & THUMBNAIL PREVIEW)
@Composable
fun MergePdfScreen(theme: ChamiTheme, onBack: () -> Unit) {
  val ctx = LocalContext.current
  val activity = ctx as? Activity
  val haptic = LocalHapticFeedback.current
  val tm = remember { ThemeManager(ctx) }
  val scope = rememberCoroutineScope()
  var pdfUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
  var pdfPreviews by remember { mutableStateOf<List<Pair<Int, Bitmap?>>>(emptyList()) }
  var isProcessing by remember { mutableStateOf(false) }
  var resultFile by remember { mutableStateOf<File?>(null) }
  var pendingSaveFile by remember { mutableStateOf<File?>(null) }

  val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
    if (uri != null && pendingSaveFile != null) {
      SaveLocation.copyToUri(ctx, pendingSaveFile!!, uri)
    }
  }

  fun vibrate() {
    if (tm.hapticOn()) {
      try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
    }
  }

  val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selected ->
    pdfUris = selected
    resultFile = null
    scope.launch(Dispatchers.IO) {
      val previews = selected.map { u ->
        val count = PdfEngine.pageCount(ctx, u)
        val bmp = PdfReader.renderPageBitmap(ctx, u, 0, 1.5f)
        Pair(count, bmp)
      }
      withContext(Dispatchers.Main) { pdfPreviews = previews }
    }
  }

  ToolScreenContainer("Merge PDF", theme, onBack) {
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
      Text("Select multiple PDF files to combine all pages in order.", fontFamily = PixelFont, fontSize = 12.sp, color = theme.text)
      Spacer(Modifier.height(14.dp))

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

      // PREVIEW SECTION BEFORE MAKING FILE
      if (pdfPreviews.isNotEmpty()) {
        Spacer(Modifier.height(14.dp))
        val totalCombinedPages = pdfPreviews.sumOf { it.first }
        Text("PREVIEW: $totalCombinedPages TOTAL PAGES COMBINED", fontFamily = PixelFont, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = theme.primaryDark)
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
          itemsIndexed(pdfPreviews) { index, pair ->
            Column(
              horizontalAlignment = Alignment.CenterHorizontally,
              modifier = Modifier
                .width(140.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(theme.card)
                .padding(8.dp)
            ) {
              pair.second?.let { bmp ->
                Image(
                  bitmap = bmp.asImageBitmap(),
                  contentDescription = "PDF ${index + 1}",
                  modifier = Modifier
                    .size(120.dp)
                    .clip(RoundedCornerShape(4.dp)),
                  contentScale = ContentScale.Fit
                )
              }
              Spacer(Modifier.height(4.dp))
              Text("Doc ${index + 1}", fontFamily = PixelFont, fontSize = 10.sp, color = theme.text, fontWeight = FontWeight.Bold)
              Text("${pair.first} pages", fontFamily = PixelFont, fontSize = 9.sp, color = theme.text.copy(alpha = 0.7f))
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

      resultFile?.let { file ->
        Spacer(Modifier.height(12.dp))
        ResultCard(file, theme)
      }
      resultFile?.let { file ->
        Spacer(Modifier.height(10.dp))
        PrimaryButton(
          theme = theme,
          text = "SAVE TO FOLDER…",
          height = 48.dp,
          onClick = {
            vibrate()
            pendingSaveFile = file
            saveLauncher.launch(file.name)
          }
        )
      }
    }

    PrimaryButton(
      theme = theme,
      text = "MERGE INTO ONE PDF",
      enabled = !isProcessing && pdfUris.size >= 2,
      onClick = {
        vibrate()
        if (pdfUris.size < 2) {
          Toast.makeText(ctx, "Select at least 2 PDFs to merge", Toast.LENGTH_SHORT).show()
          return@PrimaryButton
        }
        val runSave = {
          isProcessing = true
          scope.launch {
            val out = PdfEngine.outFile(ctx, "Merged_${System.currentTimeMillis()}.pdf")
            val sources = pdfUris.map { it to null }
            val ok = withContext(Dispatchers.IO) {
              PdfEngine.copyPages(ctx, sources, out)
            }
            isProcessing = false
            if (ok) resultFile = out
            else Toast.makeText(ctx, "Failed to merge PDFs", Toast.LENGTH_SHORT).show()
          }
        }
        if (activity != null) AdsManager.showBeforeSave(activity) { runSave() } else runSave()
      }
    )
  }
}

// 6. COMPRESS KB (DUAL TAB: IMAGE / PDF)
@Composable
fun CompressKbScreen(theme: ChamiTheme, onBack: () -> Unit) {
  val ctx = LocalContext.current
  val activity = ctx as? Activity
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
  var imageResultFile by remember { mutableStateOf<File?>(null) }

  // --- PDF TAB STATE ---
  var pdfUri by remember { mutableStateOf<Uri?>(null) }
  var pdfOrigBytes by remember { mutableStateOf(0L) }
  var pdfTotalPages by remember { mutableStateOf(0) }
  var pdfPreviewBmp by remember { mutableStateOf<Bitmap?>(null) }
  var pdfPreset by remember { mutableStateOf(PdfCompress.PRESETS[0]) }
  var isPdfProcessing by remember { mutableStateOf(false) }
  var pdfProgressText by remember { mutableStateOf("") }
  var pdfResultFile by remember { mutableStateOf<File?>(null) }

  // SAF save launcher
  var pendingSaveFile by remember { mutableStateOf<File?>(null) }
  val pdfSaveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
    if (uri != null && pendingSaveFile != null) {
      SaveLocation.copyToUri(ctx, pendingSaveFile!!, uri)
    }
  }
  val imgSaveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { uri ->
    if (uri != null && pendingSaveFile != null) {
      SaveLocation.copyToUri(ctx, pendingSaveFile!!, uri)
    }
  }

  fun vibrate() {
    if (tm.hapticOn()) {
      try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
    }
  }

  val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { selected ->
    imageUri = selected
    imageResultFile = null
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
    pdfResultFile = null
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
        Text("Compress image to exact target KB without exceeding original size.", fontFamily = PixelFont, fontSize = 12.sp, color = theme.text)
        Spacer(Modifier.height(12.dp))

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

        imageResultFile?.let { file ->
          Spacer(Modifier.height(12.dp))
          ResultCard(file, theme, "image/jpeg")
        }
        imageResultFile?.let { file ->
          Spacer(Modifier.height(10.dp))
          PrimaryButton(
            theme = theme,
            text = "SAVE TO FOLDER…",
            height = 48.dp,
            onClick = {
              vibrate()
              pendingSaveFile = file
              imgSaveLauncher.launch(file.name)
            }
          )
        }
      } else {
        // PDF TAB CONTENT
        Text("Re-encode and compress PDF pages to significantly reduce file size.", fontFamily = PixelFont, fontSize = 12.sp, color = theme.text)
        Spacer(Modifier.height(12.dp))

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
          Text("Compression Preset:", fontFamily = PixelFont, fontSize = 12.sp, color = theme.text)
          Spacer(Modifier.height(8.dp))

          val presets = listOf(
            Triple(PdfCompress.PRESETS[0], "HIGH QUALITY", "1.0x Scale • 70% Quality"),
            Triple(PdfCompress.PRESETS[1], "MEDIUM COMPACT", "0.75x Scale • 50% Quality"),
            Triple(PdfCompress.PRESETS[2], "LOW / MAXIMUM", "0.6x Scale • 40% Quality")
          )
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.forEach { (p, label, desc) ->
              val isSelected = pdfPreset == p
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .clip(RoundedCornerShape(8.dp))
                  .background(if (isSelected) theme.primary.copy(alpha = 0.15f) else theme.card)
                  .border(if (isSelected) 2.dp else 1.dp, if (isSelected) theme.primary else Color.Transparent, RoundedCornerShape(8.dp))
                  .clickable {
                    vibrate()
                    pdfPreset = p
                  }
                  .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
              ) {
                RadioButton(
                  selected = isSelected,
                  onClick = {
                    vibrate()
                    pdfPreset = p
                  },
                  colors = RadioButtonDefaults.colors(selectedColor = theme.primary)
                )
                Spacer(Modifier.width(8.dp))
                Column {
                  Text(label, fontFamily = PixelFont, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = if (isSelected) theme.primaryDark else theme.text)
                  Text(desc, fontFamily = PixelFont, fontSize = 10.sp, color = theme.textSecondary)
                }
              }
            }
          }
        }

        Spacer(Modifier.height(16.dp))
        if (isPdfProcessing) {
          LinearProgressIndicator(Modifier.fillMaxWidth(), color = theme.primary)
          Spacer(Modifier.height(8.dp))
          Text(if (pdfProgressText.isNotEmpty()) pdfProgressText else "Compressing PDF pages...", fontFamily = PixelFont, fontSize = 11.sp, color = theme.text)
        }

        pdfResultFile?.let { file ->
          Spacer(Modifier.height(12.dp))
          val newKb = file.length() / 1024L
          val oldKb = pdfOrigBytes / 1024L
          val savedPct = if (oldKb > 0) ((oldKb - newKb) * 100 / oldKb).coerceAtLeast(0) else 0
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(8.dp))
              .background(theme.card)
              .padding(12.dp)
          ) {
            Text("Compression Complete ✓", fontFamily = PixelFont, fontWeight = FontWeight.Bold, color = theme.primaryDark, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            Text("Reduced: ${oldKb}KB → ${newKb}KB (Saved ${savedPct}%)", fontFamily = PixelFont, fontSize = 11.sp, color = theme.text, fontWeight = FontWeight.Bold)
          }
          Spacer(Modifier.height(10.dp))
          ResultCard(file, theme, "application/pdf")
        }
        pdfResultFile?.let { file ->
          Spacer(Modifier.height(10.dp))
          PrimaryButton(
            theme = theme,
            text = "SAVE TO FOLDER…",
            height = 48.dp,
            onClick = {
              vibrate()
              pendingSaveFile = file
              pdfSaveLauncher.launch(file.name)
            }
          )
        }
      }
    }

    if (selectedTab == 0) {
      PrimaryButton(
        theme = theme,
        text = "COMPRESS IMAGE TO ${targetKb}KB",
        enabled = !isImageProcessing && imageUri != null,
        onClick = {
          vibrate()
          val u = imageUri ?: return@PrimaryButton
          val runSave = {
            isImageProcessing = true
            scope.launch {
              val out = PdfEngine.outFile(ctx, "Compress_${targetKb}KB_${System.currentTimeMillis()}.jpg")
              val ok = withContext(Dispatchers.IO) {
                PdfEngine.compressImageToKb(ctx, u, targetKb, out)
              }
              isImageProcessing = false
              if (ok) imageResultFile = out
              else Toast.makeText(ctx, "Compression failed", Toast.LENGTH_SHORT).show()
            }
          }
          if (activity != null) AdsManager.showBeforeSave(activity) { runSave() } else runSave()
        }
      )
    } else {
      PrimaryButton(
        theme = theme,
        text = "COMPRESS PDF",
        enabled = !isPdfProcessing && pdfUri != null,
        onClick = {
          vibrate()
          val u = pdfUri ?: return@PrimaryButton
          val runSave = {
            isPdfProcessing = true
            pdfProgressText = "Compressing PDF pages..."
            scope.launch {
              val out = PdfEngine.outFile(ctx, "Compressed_${System.currentTimeMillis()}.pdf")
              val res = withContext(Dispatchers.IO) {
                PdfCompress.compressPdf(
                  ctx = ctx,
                  pdfUri = u,
                  preset = pdfPreset,
                  outFile = out
                )
              }
              isPdfProcessing = false
              if (res.ok) pdfResultFile = out
              else Toast.makeText(ctx, "PDF compression failed", Toast.LENGTH_SHORT).show()
            }
          }
          if (activity != null) AdsManager.showBeforeSave(activity) { runSave() } else runSave()
        }
      )
    }
  }
}

// 7. PASSPORT PHOTO (WITH CROPPED PREVIEW)
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
  var resultFile by remember { mutableStateOf<File?>(null) }

  fun vibrate() {
    if (tm.hapticOn()) {
      try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
    }
  }

  val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { selected ->
    imageUri = selected
    resultFile = null
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

  ToolScreenContainer("Passport Photo", theme, onBack) {
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
      Text("Crops center square to 413x531 (35x45mm @ 300dpi) on white background.", fontFamily = PixelFont, fontSize = 12.sp, color = theme.text)
      Spacer(Modifier.height(14.dp))

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

      // PREVIEW SECTION BEFORE MAKING FILE
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

      resultFile?.let { file ->
        ResultCard(file, theme, if (is8UpSheet) "application/pdf" else "image/jpeg")
      }
    }

    Button(
      onClick = {
        vibrate()
        val u = imageUri ?: return@Button
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
              resultFile = sheetOut
            } else {
              resultFile = singleOut
            }
          } else {
            Toast.makeText(ctx, "Failed to create passport photo", Toast.LENGTH_SHORT).show()
          }
          isProcessing = false
        }
      },
      enabled = !isProcessing && imageUri != null,
      modifier = Modifier.fillMaxWidth().height(56.dp),
      colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
      shape = RoundedCornerShape(8.dp)
    ) {
      Text(if (is8UpSheet) "GENERATE 8-UP SHEET" else "GENERATE PASSPORT PHOTO", fontFamily = PixelFont, fontWeight = FontWeight.Black, fontSize = 14.sp, color = theme.textOnPrimary)
    }
  }
}

// 8. IMAGE RESIZER & CONVERT (WITH DIMENSIONS & IMAGE PREVIEW)
@Composable
fun ResizeConvertScreen(theme: ChamiTheme, title: String = "Image Resizer", onBack: () -> Unit) {
  val ctx = LocalContext.current
  val activity = ctx as? Activity
  val haptic = LocalHapticFeedback.current
  val tm = remember { ThemeManager(ctx) }
  val scope = rememberCoroutineScope()
  var imageUri by remember { mutableStateOf<Uri?>(null) }
  var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
  var origDimensions by remember { mutableStateOf<Pair<Int, Int>?>(null) }
  var widthStr by remember { mutableStateOf("800") }
  var heightStr by remember { mutableStateOf("800") }
  var formatChoice by remember { mutableStateOf("JPG") }
  var isProcessing by remember { mutableStateOf(false) }
  var resultFile by remember { mutableStateOf<File?>(null) }

  fun vibrate() {
    if (tm.hapticOn()) {
      try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
    }
  }

  val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { selected ->
    imageUri = selected
    resultFile = null
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

  ToolScreenContainer(title, theme, onBack) {
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
      Text("Resize pixel dimensions and convert to JPG, PNG, or WEBP with preview.", fontFamily = PixelFont, fontSize = 12.sp, color = theme.text)
      Spacer(Modifier.height(14.dp))

      PrimaryButton(
        theme = theme,
        text = if (imageUri != null) "IMAGE LOADED ✓" else "SELECT IMAGE",
        height = 48.dp,
        onClick = {
          vibrate()
          picker.launch("image/*")
        }
      )

      // PREVIEW SECTION BEFORE MAKING FILE
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

      Spacer(Modifier.height(16.dp))
      if (isProcessing) {
        LinearProgressIndicator(Modifier.fillMaxWidth(), color = theme.primary)
        Spacer(Modifier.height(8.dp))
      }

      resultFile?.let { file ->
        val mime = if (formatChoice == "PNG") "image/png" else if (formatChoice == "WEBP") "image/webp" else "image/jpeg"
        ResultCard(file, theme, mime)
      }
    }

    PrimaryButton(
      theme = theme,
      text = "RESIZE & CONVERT",
      enabled = !isProcessing && imageUri != null,
      onClick = {
        vibrate()
        val u = imageUri ?: return@PrimaryButton
        val w = widthStr.toIntOrNull() ?: 800
        val h = heightStr.toIntOrNull() ?: 800
        val format = when (formatChoice) {
          "PNG" -> Bitmap.CompressFormat.PNG
          "WEBP" -> Bitmap.CompressFormat.WEBP
          else -> Bitmap.CompressFormat.JPEG
        }
        val ext = formatChoice.lowercase()
        val runSave = {
          isProcessing = true
          scope.launch {
            val out = PdfEngine.outFile(ctx, "Resized_${w}x${h}_${System.currentTimeMillis()}.$ext")
            val ok = withContext(Dispatchers.IO) {
              PdfEngine.resizeConvert(ctx, u, w, h, format, out)
            }
            isProcessing = false
            if (ok) resultFile = out
            else Toast.makeText(ctx, "Failed to resize", Toast.LENGTH_SHORT).show()
          }
        }
        if (activity != null) AdsManager.showBeforeSave(activity) { runSave() } else runSave()
      }
    )
  }
}

// 9. OCR TEXT (WITH IMAGE PREVIEW)
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

  fun vibrate() {
    if (tm.hapticOn()) {
      try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
    }
  }

  val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { selected ->
    imageUri = selected
    extractedText = ""
    previewBitmap = null
    if (selected != null) {
      scope.launch(Dispatchers.IO) {
        val bmp = PdfEngine.loadBitmap(ctx, selected, 1200)
        withContext(Dispatchers.Main) { previewBitmap = bmp }
      }
    }
  }

  ToolScreenContainer("OCR Text", theme, onBack) {
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
      Text("Extract editable text from any photo or document using on-device ML Kit.", fontFamily = PixelFont, fontSize = 12.sp, color = theme.text)
      Spacer(Modifier.height(14.dp))

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

      // PREVIEW SECTION BEFORE MAKING FILE
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
        }
      }
    }

    Button(
      onClick = {
        vibrate()
        val u = imageUri ?: return@Button
        isProcessing = true
        scope.launch {
          val txt = withContext(Dispatchers.IO) {
            ocrImage(ctx, u)
          }
          isProcessing = false
          extractedText = if (txt.isBlank()) "[No readable text found in image]" else txt
        }
      },
      enabled = !isProcessing && imageUri != null,
      modifier = Modifier.fillMaxWidth().height(56.dp),
      colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
      shape = RoundedCornerShape(8.dp)
    ) {
      Text("EXTRACT TEXT (OCR)", fontFamily = PixelFont, fontWeight = FontWeight.Black, fontSize = 14.sp, color = theme.textOnPrimary)
    }
  }
}

// 10. PDF READER (TOOL 14)
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

  fun vibrate() {
    if (tm.hapticOn()) {
      try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
    }
  }

  val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { selected ->
    pdfUri = selected
    if (selected != null) {
      totalPages = PdfEngine.pageCount(ctx, selected)
      currentPage = 0
      pageInput = "1"
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
        Spacer(Modifier.height(8.dp))
        Text("Pinch to zoom, swipe through pages. One page at a time, so large files stay smooth.", fontFamily = PixelFont, fontSize = 11.sp, color = theme.primaryDark)
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
            Spacer(Modifier.width(6.dp))
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

// 11. PDF TO TEXT (WITH PAGE 1 PREVIEW)
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
  var savedFile by remember { mutableStateOf<File?>(null) }

  fun vibrate() {
    if (tm.hapticOn()) {
      try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
    }
  }

  val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { selected ->
    pdfUri = selected
    extractedText = ""
    savedFile = null
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

  ToolScreenContainer("PDF to Text", theme, onBack) {
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
      Text("Extracts text per page. Honest indicator if page is scanned or image.", fontFamily = PixelFont, fontSize = 12.sp, color = theme.text)
      Spacer(Modifier.height(14.dp))

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

      // PREVIEW SECTION BEFORE MAKING FILE
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
              savedFile = f
              Toast.makeText(ctx, "Saved to ${f.name} (${f.length() / 1024} KB)", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(8.dp)
          ) {
            Text("SAVE AS .TXT FILE", fontFamily = PixelFont, color = theme.primaryDark, fontSize = 12.sp)
          }
        }
      }

      savedFile?.let { file ->
        Spacer(Modifier.height(12.dp))
        ResultCard(file, theme, "text/plain")
      }
    }

    Button(
      onClick = {
        vibrate()
        val u = pdfUri ?: return@Button
        isProcessing = true
        scope.launch {
          val res = withContext(Dispatchers.IO) {
            pdfToText(ctx, u)
          }
          isProcessing = false
          extractedText = res
        }
      },
      enabled = !isProcessing && pdfUri != null,
      modifier = Modifier.fillMaxWidth().height(56.dp),
      colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
      shape = RoundedCornerShape(8.dp)
    ) {
      Text("EXTRACT ALL TEXT", fontFamily = PixelFont, fontWeight = FontWeight.Black, fontSize = 14.sp, color = theme.textOnPrimary)
    }
  }
}


// 12. PROTECT PDF WITH PASSWORD
@Composable
fun ProtectPdfScreen(theme: ChamiTheme, onBack: () -> Unit) {
  val ctx = LocalContext.current
  val activity = ctx as? Activity
  val haptic = LocalHapticFeedback.current
  val tm = remember { ThemeManager(ctx) }
  val scope = rememberCoroutineScope()

  var selectedUri by remember { mutableStateOf<Uri?>(null) }
  var pageCount by remember { mutableStateOf(0) }
  var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
  var password by remember { mutableStateOf("") }
  var confirmPassword by remember { mutableStateOf("") }
  var passwordVisible by remember { mutableStateOf(false) }

  var isProcessing by remember { mutableStateOf(false) }
  var progressStatus by remember { mutableStateOf("") }
  var resultFile by remember { mutableStateOf<File?>(null) }
  var pendingSaveFile by remember { mutableStateOf<File?>(null) }

  val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
    if (uri != null && pendingSaveFile != null) {
      SaveLocation.copyToUri(ctx, pendingSaveFile!!, uri)
    }
  }

  var showVerifyDialog by remember { mutableStateOf(false) }
  var testPasswordInput by remember { mutableStateOf("") }
  var verificationResult by remember { mutableStateOf<Boolean?>(null) }

  fun vibrate() {
    if (tm.hapticOn()) {
      try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
    }
  }

  val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
    if (uri != null) {
      vibrate()
      selectedUri = uri
      resultFile = null
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

  ToolScreenContainer("Protect PDF", theme, onBack) {
    Column(
      Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
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
              Text(
                "Previewing Page 1",
                fontFamily = PixelFont,
                fontSize = 10.sp,
                color = theme.primary
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

          // Password strength indicator
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
            Spacer(Modifier.height(8.dp))
          }

          Box(
            Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(8.dp))
              .background(theme.bg)
              .padding(10.dp)
          ) {
            Text(
              "🔒 Standard PDF 1.4 Encryption: Compatible with Adobe Acrobat, Google Drive, Chrome, and all PDF viewers. Recipients must enter this password to view the document.",
              fontFamily = PixelFont,
              fontSize = 11.sp,
              color = theme.textSecondary
            )
          }
        }
      }

      // Action Button
      if (selectedUri != null) {
        val canEncrypt = password.isNotBlank() && password == confirmPassword && !isProcessing
        PrimaryButton(
          theme = theme,
          text = if (isProcessing) (if (progressStatus.isNotEmpty()) progressStatus else "Encrypting...") else "ENCRYPT & PROTECT PDF",
          enabled = canEncrypt,
          onClick = {
            vibrate()
            val runEncrypt = {
              isProcessing = true
              progressStatus = "Encrypting document & writing crypt keys..."
              scope.launch(Dispatchers.IO) {
                val out = PdfEngine.outFile(ctx, "protected_${System.currentTimeMillis()}.pdf")
                val ok = PdfProtector.protectPdf(ctx, selectedUri!!, password, out) { current, total ->
                  progressStatus = "Encrypting page $current of $total..."
                }
                withContext(Dispatchers.Main) {
                  isProcessing = false
                  if (ok) {
                    resultFile = out
                    Toast.makeText(ctx, "PDF Protected Successfully! ✓", Toast.LENGTH_SHORT).show()
                  } else {
                    Toast.makeText(ctx, "Failed to encrypt PDF", Toast.LENGTH_SHORT).show()
                  }
                }
              }
            }
            if (activity != null) AdsManager.showBeforeSave(activity) { runEncrypt() } else runEncrypt()
          }
        )
      }

      // Success Result & Test Password Option
      if (resultFile != null) {
        ResultCard(resultFile!!, theme)

        Spacer(Modifier.height(8.dp))
        PrimaryButton(
          theme = theme,
          text = "SAVE TO FOLDER…",
          height = 48.dp,
          onClick = {
            vibrate()
            pendingSaveFile = resultFile
            saveLauncher.launch(resultFile!!.name)
          }
        )

        OutlinedButton(
          onClick = {
            vibrate()
            testPasswordInput = ""
            verificationResult = null
            showVerifyDialog = true
          },
          modifier = Modifier.fillMaxWidth().height(44.dp),
          shape = RoundedCornerShape(10.dp),
          border = androidx.compose.foundation.BorderStroke(1.dp, theme.cardBorder),
          colors = ButtonDefaults.outlinedButtonColors(contentColor = theme.primary)
        ) {
          Text(
            "TEST UNLOCK / VERIFY PASSWORD",
            fontFamily = PixelFont,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp
          )
        }
      }

      // Dialog: Test Password
      if (showVerifyDialog && resultFile != null) {
        AlertDialog(
          onDismissRequest = { showVerifyDialog = false },
          confirmButton = {
            Button(
              onClick = {
                vibrate()
                verificationResult = PdfProtector.verifyPassword(resultFile!!, testPasswordInput)
              },
              colors = ButtonDefaults.buttonColors(containerColor = theme.primary)
            ) {
              Text("TEST PASSWORD", fontFamily = PixelFont, color = theme.textOnPrimary, fontSize = 11.sp)
            }
          },
          dismissButton = {
            TextButton(onClick = { showVerifyDialog = false }) {
              Text("CLOSE", fontFamily = PixelFont, color = theme.text)
            }
          },
          title = {
            Text("Verify Protected PDF", fontFamily = PixelFont, fontWeight = FontWeight.Bold, fontSize = 15.sp)
          },
          text = {
            Column {
              Text(
                "Enter the password to test unlocking \"${resultFile?.name}\":",
                fontFamily = PixelFont,
                fontSize = 12.sp,
                color = theme.textSecondary
              )
              Spacer(Modifier.height(10.dp))
              OutlinedTextField(
                value = testPasswordInput,
                onValueChange = {
                  testPasswordInput = it
                  verificationResult = null
                },
                placeholder = { Text("Type password...", fontFamily = PixelFont, fontSize = 11.sp) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
              )
              if (verificationResult != null) {
                Spacer(Modifier.height(10.dp))
                if (verificationResult == true) {
                  Text(
                    "✓ Password Correct! Document decrypted successfully.",
                    fontFamily = PixelFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = theme.primary
                  )
                } else {
                  Text(
                    "✗ Incorrect Password. Document remains locked.",
                    fontFamily = PixelFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error
                  )
                }
              }
            }
          }
        )
      }
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
