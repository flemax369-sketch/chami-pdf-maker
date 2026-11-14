package com.chami.pdfmaker.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaActionSound
import android.net.Uri
import android.util.Size
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.chami.pdfmaker.pdf.PdfEngine
import com.chami.pdfmaker.theme.ChamiTheme
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Live in-app camera for Scan to PDF (CameraX, NOT the system camera
 * intent). Opens straight onto the viewfinder like Adobe Scan. After a
 * capture it STAYS on the camera so the user can batch-scan more pages;
 * the caller adds each Bitmap to the page list.
 *
 * CAPTURE PROOF (audit P0 #1): every successful capture gives four
 * signals at once — white flash overlay, shutter sound
 * ([MediaActionSound.SHUTTER_CLICK]), vibration (haptic LongPress),
 * and a "Page N captured" banner plus the captured-pages stack
 * (thumbnail + count badge) on the camera screen.
 *
 * EDGE FRAME — HONEST LIMIT: there is no OpenCV / ML edge detection
 * in this build. [ImageAnalysis] runs on downscaled Y-plane frames
 * (16x12 luminance grid) and reuses the approach of
 * [ImagePageEditor.detectDocumentQuad]: adaptive threshold from mean
 * luminance (mean x 0.9) plus a luminance-gradient edge estimate and
 * frame-to-frame stability. When a quad is found it is drawn in green;
 * otherwise a centred GUIDE rectangle is drawn in white and the status
 * pill says so ("GUIDE — ALIGN PAGE INSIDE"). The frame never pulses
 * and is never presented as live tracking when it is only the guide.
 * Thresholds below (stability < 3, edge score > 6) are estimates for a
 * 16x12 grid — tune on-phone if auto-capture fires too early / late.
 *
 * AUTO-CAPTURE: the old blind 2.5s timer is gone. Auto-capture fires
 * only when the toggle is ON, the frame is steady AND edges are found
 * continuously for 1 second. After any capture it must re-arm: the
 * scene has to become unsteady first, so one steady page can never
 * auto-fire twice. Manual shutter always works regardless.
 */
private data class FrameStats(
  val stability: Float, // mean |luminance change| vs previous frame (0-255 scale)
  val edgeScore: Float, // mean luminance gradient across the grid
  val quad: List<Offset>?, // normalised document quad, null when not found
)

private const val GRID_W = 16
private const val GRID_H = 12
private const val STABLE_MAX_DIFF = 3f
private const val EDGE_MIN_SCORE = 6f
private const val AUTO_CAPTURE_STEADY_MS = 1000L

@Composable
fun ScanCameraScreen(
  theme: ChamiTheme,
  autoCapture: Boolean,
  onCaptured: (android.graphics.Bitmap) -> Unit,
  onGallery: () -> Unit,
  onClose: () -> Unit,
  /** Pages already captured before this camera open (ToolScreens can pass pages.size). */
  capturedCountOffset: Int = 0,
) {
  val ctx = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val haptic = LocalHapticFeedback.current
  val scope = rememberCoroutineScope()

  var hasPermission by remember {
    mutableStateOf(
      ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
    )
  }
  val permissionLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission()
  ) { granted -> hasPermission = granted }

  // PreviewView is created once with remember (never assigned inside
  // the AndroidView factory — writing state during composition is
  // not allowed) and handed to AndroidView below.
  val previewView = remember {
    PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
  }
  var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
  var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
  var cameraReady by remember { mutableStateOf(false) }
  var flashOn by remember { mutableStateOf(false) }
  // On-camera auto-capture toggle: initialised from the caller's value,
  // toggled here for this camera session (the toggle lives on the
  // camera screen, per spec §7 Tool 2).
  var autoCaptureOn by remember { mutableStateOf(autoCapture) }
  var capturing by remember { mutableStateOf(false) }
  var needRearm by remember { mutableStateOf(false) }
  var steadySince by remember { mutableStateOf<Long?>(null) }

  // Capture proof state.
  var sessionCaptures by remember { mutableStateOf(0) }
  var lastThumb by remember { mutableStateOf<Bitmap?>(null) }
  var showFlash by remember { mutableStateOf(false) }
  var captureMessage by remember { mutableStateOf<String?>(null) }
  var bannerJob by remember { mutableStateOf<Job?>(null) }
  val capturedCount = capturedCountOffset + sessionCaptures

  // Live frame analysis (background thread, never the main thread).
  val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
  val statsRef = remember { AtomicReference<FrameStats?>(null) }
  val prevGridRef = remember { AtomicReference<FloatArray?>(null) }
  var detectedQuad by remember { mutableStateOf<List<Offset>?>(null) }
  var edgesFound by remember { mutableStateOf(false) }

  // Shutter sound: the system camera shutter click. Created once and
  // released on dispose. MediaActionSound is the API that actually
  // plays the camera shutter sound on-device (RingtoneManager has no
  // shutter sound); if the device is muted there is no click, which is
  // why flash + vibration + banner fire at the same time.
  val shutterSound = remember {
    try {
      MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) }
    } catch (_: Exception) {
      null
    }
  }

  fun playCaptureProof(pageNumber: Int) {
    // (a) White flash overlay on the viewfinder.
    showFlash = true
    scope.launch { delay(150); showFlash = false }
    // (b) Shutter sound.
    try { shutterSound?.play(MediaActionSound.SHUTTER_CLICK) } catch (_: Exception) {}
    // (c) Vibration (haptic success).
    try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
    // (d) "Page N captured" banner + stack counter (state below).
    bannerJob?.cancel()
    captureMessage = "Page $pageNumber captured"
    bannerJob = scope.launch { delay(1500); captureMessage = null }
  }

  // Local funs are only visible AFTER their declaration point, so
  // takePhoto / bindCamera are declared here, before the effects below.
  fun takePhoto() {
    val capture = imageCapture ?: return
    if (capturing) return
    capturing = true
    val tmp = File(ctx.cacheDir, "scan_${System.currentTimeMillis()}.jpg")
    val options = ImageCapture.OutputFileOptions.Builder(tmp).build()
    capture.takePicture(
      options,
      ContextCompat.getMainExecutor(ctx),
      object : ImageCapture.OnImageSavedCallback {
        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
          capturing = false
          // Downsample to ~1600 px max side: a full 12 MP capture is
          // ~48 MB per page and batch scanning would OOM (H7).
          val bmp = PdfEngine.loadBitmap(ctx, Uri.fromFile(tmp), 1600)
          if (bmp != null) {
            // Capture proof FIRST, then hand the page to the caller.
            // The camera stays open for the next page.
            sessionCaptures += 1
            val pageNumber = capturedCountOffset + sessionCaptures
            val oldThumb = lastThumb
            lastThumb = try {
              Bitmap.createScaledBitmap(
                bmp, 120, (120f * bmp.height / bmp.width).toInt().coerceAtLeast(1), true
              )
            } catch (_: Exception) {
              null
            }
            try { oldThumb?.recycle() } catch (_: Exception) {}
            playCaptureProof(pageNumber)
            // One steady page must never auto-fire twice: re-arm only
            // after the scene becomes unsteady again.
            needRearm = true
            steadySince = null
            onCaptured(bmp)
          } else {
            Toast.makeText(ctx, "Capture failed — try again", Toast.LENGTH_SHORT).show()
          }
          try { tmp.delete() } catch (_: Exception) {}
        }

        override fun onError(e: ImageCaptureException) {
          capturing = false
          Toast.makeText(ctx, "Capture failed — try again", Toast.LENGTH_SHORT).show()
        }
      }
    )
  }

  /** Luminance analysis of one downscaled frame. Runs off the main thread. */
  fun analyseFrame(image: ImageProxy) {
    try {
      val w = image.width
      val h = image.height
      val yPlane = image.planes[0]
      val buffer = yPlane.buffer
      val rowStride = yPlane.rowStride
      val grid = FloatArray(GRID_W * GRID_H)
      var lumSum = 0f
      for (gy in 0 until GRID_H) {
        for (gx in 0 until GRID_W) {
          val x = (gx * w / GRID_W).coerceIn(0, w - 1)
          val y = (gy * h / GRID_H).coerceIn(0, h - 1)
          val lum = (buffer.get(y * rowStride + x).toInt() and 0xFF).toFloat()
          grid[gy * GRID_W + gx] = lum
          lumSum += lum
        }
      }
      val meanLum = lumSum / grid.size
      // Edge estimate: mean absolute luminance gradient between neighbours.
      var gradSum = 0f
      var gradCount = 0
      for (gy in 0 until GRID_H) {
        for (gx in 0 until GRID_W) {
          if (gx + 1 < GRID_W) { gradSum += abs(grid[gy * GRID_W + gx + 1] - grid[gy * GRID_W + gx]); gradCount++ }
          if (gy + 1 < GRID_H) { gradSum += abs(grid[(gy + 1) * GRID_W + gx] - grid[gy * GRID_W + gx]); gradCount++ }
        }
      }
      val edgeScore = if (gradCount > 0) gradSum / gradCount else 0f
      // Stability: mean absolute change vs the previous frame's grid.
      val prev = prevGridRef.get()
      var stability = Float.MAX_VALUE
      if (prev != null) {
        var diffSum = 0f
        for (i in grid.indices) diffSum += abs(grid[i] - prev[i])
        stability = diffSum / grid.size
      }
      prevGridRef.set(grid)
      // Quad estimate, same approach as ImagePageEditor.detectDocumentQuad:
      // bounding box of cells darker than the adaptive threshold
      // (mean luminance x 0.9). Full-frame bbox = not found.
      val threshold = (meanLum * 0.9f).coerceIn(60f, 235f)
      var minX = GRID_W; var minY = GRID_H; var maxX = -1; var maxY = -1; var dark = 0
      for (gy in 0 until GRID_H) {
        for (gx in 0 until GRID_W) {
          if (grid[gy * GRID_W + gx] < threshold) {
            dark++
            if (gx < minX) minX = gx
            if (gx > maxX) maxX = gx
            if (gy < minY) minY = gy
            if (gy > maxY) maxY = gy
          }
        }
      }
      val quad: List<Offset>? =
        if (dark < 3 || maxX < 0) null
        else if (minX == 0 && minY == 0 && maxX == GRID_W - 1 && maxY == GRID_H - 1) null
        else {
          val l = minX.toFloat() / GRID_W
          val t = minY.toFloat() / GRID_H
          val r = (maxX + 1).toFloat() / GRID_W
          val b = (maxY + 1).toFloat() / GRID_H
          if (r - l < 0.1f || b - t < 0.1f) null
          else listOf(Offset(l, t), Offset(r, t), Offset(r, b), Offset(l, b))
        }
      statsRef.set(FrameStats(stability, edgeScore, quad))
    } catch (_: Exception) {
      // A bad frame must never kill the camera: skip it.
    } finally {
      image.close()
    }
  }

  fun bindCamera(view: PreviewView) {
    val providerFuture = ProcessCameraProvider.getInstance(ctx)
    providerFuture.addListener(
      {
        try {
          val provider = providerFuture.get()
          cameraProvider = provider
          val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(view.surfaceProvider)
          }
          val capture = ImageCapture.Builder()
            .setFlashMode(
              if (flashOn) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
            )
            .build()
          imageCapture = capture
          val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetResolution(Size(320, 240))
            .build()
            .also { it.setAnalyzer(analysisExecutor) { image -> analyseFrame(image) } }
          provider.unbindAll()
          provider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            capture,
            analysis
          )
          cameraReady = true
        } catch (_: Exception) {
          cameraReady = false
        }
      },
      ContextCompat.getMainExecutor(ctx)
    )
  }

  // Bind once the permission is granted (previewView already exists).
  LaunchedEffect(hasPermission) {
    if (hasPermission) bindCamera(previewView)
  }

  // Auto-capture: NO blind timer. Polls the analysed frames every
  // 150 ms; fires only when auto-capture is ON, the frame is steady
  // AND edges are found continuously for 1 second, and the capture
  // has re-armed since the last shot. Manual shutter always works.
  LaunchedEffect(cameraReady, autoCaptureOn) {
    if (!cameraReady) return@LaunchedEffect
    while (true) {
      delay(150)
      val stats = statsRef.get() ?: continue
      val stable = stats.stability < STABLE_MAX_DIFF
      val edges = stats.edgeScore > EDGE_MIN_SCORE && stats.quad != null
      // Publish detection state for the frame overlay (only on change,
      // so the screen is not recomposed every 150 ms for nothing).
      if (detectedQuad != stats.quad) detectedQuad = stats.quad
      if (edgesFound != edges) edgesFound = edges
      if (needRearm) {
        if (!stable) { needRearm = false; steadySince = null }
        continue
      }
      if (autoCaptureOn && stable && edges) {
        val now = System.currentTimeMillis()
        val since = steadySince
        if (since == null) {
          steadySince = now
        } else if (now - since >= AUTO_CAPTURE_STEADY_MS && !capturing) {
          steadySince = null
          takePhoto()
        }
      } else {
        steadySince = null
      }
    }
  }

  // Flash toggle applies live to the bound ImageCapture (no rebind).
  LaunchedEffect(flashOn) {
    imageCapture?.flashMode =
      if (flashOn) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
  }

  // Release the camera when leaving this screen, or it stays held
  // (battery drain, blocks other apps) until the Activity dies (H2).
  DisposableEffect(Unit) {
    onDispose {
      try { cameraProvider?.unbindAll() } catch (_: Exception) {}
      try { analysisExecutor.shutdown() } catch (_: Exception) {}
      try { shutterSound?.release() } catch (_: Exception) {}
      try { lastThumb?.recycle() } catch (_: Exception) {}
    }
  }

  if (!hasPermission) {
    // Camera permission needed — simple screen with a request button.
    Column(
      modifier = Modifier
        .fillMaxSize()
        .background(theme.bg)
        .statusBarsPadding()
        .padding(horizontal = Ds.s16, vertical = Ds.s32),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center
    ) {
      Icon(
        Icons.Filled.CameraAlt,
        contentDescription = "Camera",
        tint = theme.primary,
        modifier = Modifier.size(Ds.s32 + Ds.s16)
      )
      Spacer(Modifier.height(Ds.s16))
      Text(
        "Camera permission needed",
        fontFamily = PixelFontFamily,
        fontSize = Ds.TITLE_SP.sp,
        fontWeight = FontWeight.Bold,
        color = theme.text,
        textAlign = TextAlign.Center
      )
      Spacer(Modifier.height(Ds.s8))
      Text(
        "Allow camera access to scan pages into a PDF.",
        fontSize = Ds.BODY_SP.sp,
        lineHeight = (Ds.BODY_SP * 1.4).sp,
        color = theme.textSecondary,
        textAlign = TextAlign.Center
      )
      Spacer(Modifier.height(Ds.s24))
      ChamiPrimaryButton(
        text = "ALLOW CAMERA",
        onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
        modifier = Modifier.fillMaxWidth()
      )
      Spacer(Modifier.height(Ds.s12))
      ChamiSecondaryButton(
        text = "CLOSE",
        onClick = onClose,
        modifier = Modifier.fillMaxWidth()
      )
      Spacer(Modifier.height(Ds.s12))
      ChamiSecondaryButton(
        text = "USE GALLERY INSTEAD",
        onClick = onGallery,
        modifier = Modifier.fillMaxWidth()
      )
    }
    return
  }

  // Status pill text: honest about what the frame is.
  val statusText = when {
    !cameraReady -> "STARTING CAMERA…"
    captureMessage != null -> captureMessage!!
    edgesFound -> "PAGE FOUND — HOLD STEADY"
    else -> "GUIDE — ALIGN PAGE INSIDE"
  }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(Color.Black)
  ) {
    AndroidView(
      factory = { previewView },
      modifier = Modifier.fillMaxSize()
    )

    // Document frame: the detected quad when one is found (green),
    // otherwise the centred guide rectangle (white). Never pulsing,
    // never presented as live detection when it is only the guide.
    Canvas(Modifier.fillMaxSize()) {
      val w = size.width
      val h = size.height
      val quad = detectedQuad
      if (quad != null && quad.size == 4) {
        val pts = quad.map { Offset(it.x * w, it.y * h) }
        val frameColor = Color(0xFF22C55E)
        val path = Path().apply {
          moveTo(pts[0].x, pts[0].y)
          for (i in 1 until 4) lineTo(pts[i].x, pts[i].y)
          close()
        }
        drawPath(path, frameColor, style = Stroke(width = 5f))
        val cornerLen = 56f
        val handle = Stroke(width = 9f)
        for (i in pts.indices) {
          val p = pts[i]
          val prev = pts[(i + 3) % 4]
          val next = pts[(i + 1) % 4]
          val dPrev = Offset(prev.x - p.x, prev.y - p.y)
          val dNext = Offset(next.x - p.x, next.y - p.y)
          val lenPrev = kotlin.math.hypot(dPrev.x, dPrev.y).coerceAtLeast(1f)
          val lenNext = kotlin.math.hypot(dNext.x, dNext.y).coerceAtLeast(1f)
          val cornerPath = Path().apply {
            moveTo(p.x + dPrev.x / lenPrev * cornerLen, p.y + dPrev.y / lenPrev * cornerLen)
            lineTo(p.x, p.y)
            lineTo(p.x + dNext.x / lenNext * cornerLen, p.y + dNext.y / lenNext * cornerLen)
          }
          drawPath(cornerPath, frameColor, style = handle)
        }
      } else {
        val left = w * 0.08f
        val right = w * 0.92f
        val top = h * 0.14f
        val bottom = h * 0.68f
        val guideColor = Color.White.copy(alpha = 0.6f)
        val cornerLen = 56f
        drawRoundRect(
          color = guideColor,
          topLeft = Offset(left, top),
          size = androidx.compose.ui.geometry.Size(right - left, bottom - top),
          cornerRadius = CornerRadius(24f, 24f),
          style = Stroke(width = 3f)
        )
        val handle = Stroke(width = 7f)
        fun corner(cx: Float, cy: Float, dx: Float, dy: Float) {
          val path = Path().apply {
            moveTo(cx + dx * cornerLen, cy)
            lineTo(cx, cy)
            lineTo(cx, cy + dy * cornerLen)
          }
          drawPath(path, guideColor, style = handle)
        }
        corner(left, top, 1f, 1f)
        corner(right, top, -1f, 1f)
        corner(left, bottom, 1f, -1f)
        corner(right, bottom, -1f, -1f)
      }
    }

    // Top bar: close (left), status (centre), flash toggle (right).
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .statusBarsPadding()
        .padding(Ds.s16),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Row(
        modifier = Modifier
          .height(Ds.minTouch)
          .clip(RoundedCornerShape(Ds.s16))
          .background(Color.Black.copy(alpha = 0.45f))
          .clickable { onClose() }
          .padding(horizontal = Ds.s12),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Icon(Icons.Filled.Close, contentDescription = "Close camera", tint = Color.White, modifier = Modifier.size(24.dp))
        Spacer(Modifier.size(Ds.s4))
        Text("CLOSE", color = Color.White, fontSize = Ds.HINT_SP.sp, fontWeight = FontWeight.Bold)
      }
      Box(
        modifier = Modifier
          .height(Ds.minTouch)
          .clip(RoundedCornerShape(Ds.s16))
          .background(Color.Black.copy(alpha = 0.45f))
          .padding(horizontal = Ds.s12),
        contentAlignment = Alignment.Center
      ) {
        Text(
          statusText,
          color = Color.White,
          fontSize = Ds.NAV_SP.sp,
          fontWeight = FontWeight.Bold,
          textAlign = TextAlign.Center
        )
      }
      Row(
        modifier = Modifier
          .height(Ds.minTouch)
          .clip(RoundedCornerShape(Ds.s16))
          .background(Color.Black.copy(alpha = 0.45f))
          .clickable { flashOn = !flashOn }
          .padding(horizontal = Ds.s12),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Icon(
          if (flashOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
          contentDescription = if (flashOn) "Flash on" else "Flash off",
          tint = Color.White,
          modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.size(Ds.s4))
        Text(
          if (flashOn) "FLASH ON" else "FLASH",
          color = Color.White,
          fontSize = Ds.HINT_SP.sp,
          fontWeight = FontWeight.Bold
        )
      }
    }

    // Bottom controls: capture banner, auto-capture toggle, then
    // gallery | shutter | pages stack. The banner is the first child
    // of this bottom-aligned column, so it grows upwards and never
    // moves the shutter button when it appears.
    Column(
      modifier = Modifier
        .align(Alignment.BottomCenter)
        .fillMaxWidth()
        .padding(horizontal = Ds.s16)
        .padding(bottom = Ds.s32),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      // "Page N captured" banner — appears on every capture, then
      // fades after 1.5s. The camera stays open.
      AnimatedVisibility(
        visible = captureMessage != null,
        enter = fadeIn(),
        exit = fadeOut()
      ) {
        Row(
          modifier = Modifier
            .clip(RoundedCornerShape(Ds.s16))
            .background(ChamiColors.brightGreen)
            .padding(horizontal = Ds.s16, vertical = Ds.s8),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
          Spacer(Modifier.size(Ds.s8))
          Text(
            captureMessage ?: "",
            color = Color.White,
            fontSize = Ds.BODY_SP.sp,
            fontWeight = FontWeight.Bold
          )
        }
      }
      if (captureMessage != null) Spacer(Modifier.height(Ds.s12))
      Row(
        modifier = Modifier
          .clip(RoundedCornerShape(Ds.s16))
          .background(Color.Black.copy(alpha = 0.45f))
          .padding(horizontal = Ds.s12),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          "Auto-capture",
          color = Color.White,
          fontSize = Ds.HINT_SP.sp,
          fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.size(Ds.s8))
        Switch(
          checked = autoCaptureOn,
          onCheckedChange = { autoCaptureOn = it },
          colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = ChamiColors.brightGreen,
            uncheckedThumbColor = Color.White,
            uncheckedTrackColor = Color.White.copy(alpha = 0.4f)
          )
        )
      }
      Spacer(Modifier.height(Ds.s12))
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        // Gallery — labelled icon button, 48dp target (was a lone emoji).
        Column(
          modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable { onGallery() },
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center
        ) {
          Icon(Icons.Filled.PhotoLibrary, contentDescription = "Gallery", tint = Color.White, modifier = Modifier.size(24.dp))
          Text("Gallery", color = Color.White, fontSize = Ds.NAV_SP.sp)
        }
        // Large round shutter button — 72dp, centred. Plain shape
        // (a shutter is a shape, not an icon); tap = take photo.
        Box(
          modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(ChamiColors.brightGreen)
            .border(4.dp, Color.White, CircleShape)
            .clickable(enabled = cameraReady && !capturing, onClickLabel = "Take photo") { takePhoto() },
          contentAlignment = Alignment.Center
        ) {
          Box(
            Modifier
              .size(Ds.minTouch)
              .clip(CircleShape)
              .background(Color.White.copy(alpha = 0.25f))
          )
        }
        // Captured-pages stack: last captured thumbnail + count badge.
        // Tapping it closes the camera to the review screen.
        Box(
          modifier = Modifier
            .size(72.dp)
            .clip(RoundedCornerShape(Ds.s12))
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(onClickLabel = "View captured pages") { onClose() },
          contentAlignment = Alignment.Center
        ) {
          val thumb = lastThumb
          if (thumb != null) {
            androidx.compose.foundation.Image(
              bitmap = thumb.asImageBitmap(),
              contentDescription = "Captured pages",
              modifier = Modifier
                .size(Ds.minTouch)
                .clip(RoundedCornerShape(Ds.s8))
            )
          } else {
            Icon(Icons.Filled.Image, contentDescription = "No pages captured yet", tint = Color.White, modifier = Modifier.size(24.dp))
          }
          Box(
            modifier = Modifier
              .align(Alignment.TopEnd)
              .offset(x = 4.dp, y = (-4).dp)
              .size(24.dp)
              .clip(CircleShape)
              .background(ChamiColors.brightGreen),
            contentAlignment = Alignment.Center
          ) {
            Text(
              capturedCount.toString(),
              color = Color.White,
              fontSize = Ds.NAV_SP.sp,
              fontWeight = FontWeight.Bold
            )
          }
        }
      }
    }

    // (a) White flash overlay — shown for ~150 ms on every capture.
    if (showFlash) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(Color.White.copy(alpha = 0.9f))
      )
    }
  }
}
