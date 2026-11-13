package com.chami.pdfmaker.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
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

/**
 * Phase 6 — live in-app camera for Scan to PDF (CameraX, NOT the system
 * camera intent). Opens straight onto the viewfinder like Adobe Scan.
 * After a capture it STAYS on the camera so the user can batch-scan
 * more pages; the caller adds each Bitmap to the page list.
 *
 * NOTE: the green quadrilateral frame is a STATIC guide that gently
 * pulses. Real ML/OpenCV edge detection (finding the true page corners
 * in the live feed) is DEFERRED — OpenCV would add ~15 MB and is not
 * in the build yet — so the frame does not track the page yet.
 */
@Composable
fun ScanCameraScreen(
  theme: ChamiTheme,
  autoCapture: Boolean,
  onCaptured: (android.graphics.Bitmap) -> Unit,
  onGallery: () -> Unit,
  onClose: () -> Unit
) {
  val ctx = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current

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
  var autoFired by remember { mutableStateOf(false) }

  // Local funs are only visible AFTER their declaration point, so
  // takePhoto / bindCamera are declared here, before the effects below.
  fun takePhoto() {
    val capture = imageCapture ?: return
    // Mark fired here (not in the timer): a manual tap also cancels the
    // pending auto-capture, and a failed early return never consumes
    // the one auto shot (H6).
    autoFired = true
    val tmp = File(ctx.cacheDir, "scan_${System.currentTimeMillis()}.jpg")
    val options = ImageCapture.OutputFileOptions.Builder(tmp).build()
    capture.takePicture(
      options,
      ContextCompat.getMainExecutor(ctx),
      object : ImageCapture.OnImageSavedCallback {
        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
          // Downsample to ~1600 px max side: a full 12 MP capture is
          // ~48 MB per page and batch scanning would OOM (H7).
          val bmp = PdfEngine.loadBitmap(ctx, Uri.fromFile(tmp), 1600)
          if (bmp != null) onCaptured(bmp)
          else Toast.makeText(ctx, "Capture failed — try again", Toast.LENGTH_SHORT).show()
          try { tmp.delete() } catch (_: Exception) {}
        }

        override fun onError(e: ImageCaptureException) {
          Toast.makeText(ctx, "Capture failed — try again", Toast.LENGTH_SHORT).show()
        }
      }
    )
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
          provider.unbindAll()
          provider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            capture
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

  // Auto-capture: once the preview has been steady ~2.5s, capture once.
  // Guarded so it fires at most once per camera open. takePhoto() sets
  // autoFired, so a manual capture cancels the pending timer (H6).
  LaunchedEffect(cameraReady, autoCapture) {
    if (autoCapture && cameraReady && !autoFired) {
      kotlinx.coroutines.delay(2500)
      if (!autoFired) {
        takePhoto()
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
  androidx.compose.runtime.DisposableEffect(Unit) {
    onDispose { try { cameraProvider?.unbindAll() } catch (_: Exception) {} }
  }

  val pulse by rememberInfiniteTransition(label = "edgeFramePulse").animateFloat(
    initialValue = 0.55f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
    label = "edgeFrameAlpha"
  )

  if (!hasPermission) {
    // Camera permission needed — simple screen with a request button.
    Column(
      modifier = Modifier
        .fillMaxSize()
        .background(theme.bg)
        .statusBarsPadding()
        .padding(24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center
    ) {
      Text("📷", fontSize = 48.sp)
      Spacer(Modifier.height(16.dp))
      Text(
        "Camera permission needed",
        fontFamily = PixelFont,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        color = theme.text,
        textAlign = TextAlign.Center
      )
      Spacer(Modifier.height(8.dp))
      Text(
        "Allow camera access to scan pages into a PDF.",
        fontSize = 14.sp,
        color = theme.textSecondary,
        textAlign = TextAlign.Center
      )
      Spacer(Modifier.height(24.dp))
      Button(
        onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
        colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
        shape = RoundedCornerShape(12.dp)
      ) {
        Text("ALLOW CAMERA", fontFamily = PixelFont, color = theme.textOnPrimary)
      }
      Spacer(Modifier.height(12.dp))
      Button(
        onClick = onClose,
        colors = ButtonDefaults.buttonColors(containerColor = theme.card),
        shape = RoundedCornerShape(12.dp)
      ) {
        Text("CLOSE", fontFamily = PixelFont, color = theme.text)
      }
    }
    return
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

    // Green quadrilateral edge-detection frame (static guide + pulse).
    Canvas(Modifier.fillMaxSize()) {
      val w = size.width
      val h = size.height
      val left = w * 0.08f
      val right = w * 0.92f
      val top = h * 0.14f
      val bottom = h * 0.68f
      val frameColor = Color(0xFF22C55E).copy(alpha = pulse)
      val cornerLen = 56f
      val stroke = 5f
      // Rounded quad outline.
      drawRoundRect(
        color = frameColor,
        topLeft = Offset(left, top),
        size = Size(right - left, bottom - top),
        cornerRadius = CornerRadius(24f, 24f),
        style = Stroke(width = stroke)
      )
      // Corner handles (L-shaped, thicker than the outline).
      val handle = Stroke(width = 9f)
      fun corner(cx: Float, cy: Float, dx: Float, dy: Float) {
        val path = Path().apply {
          moveTo(cx + dx * cornerLen, cy)
          lineTo(cx, cy)
          lineTo(cx, cy + dy * cornerLen)
        }
        drawPath(path, frameColor, style = handle)
      }
      corner(left, top, 1f, 1f)
      corner(right, top, -1f, 1f)
      corner(left, bottom, 1f, -1f)
      corner(right, bottom, -1f, -1f)
      // Centre hint.
      drawCircle(frameColor, 4f, Offset(w / 2f, (top + bottom) / 2f))
    }

    // Top bar: close.
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .statusBarsPadding()
        .padding(16.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Box(
        modifier = Modifier
          .clip(CircleShape)
          .background(Color.Black.copy(alpha = 0.45f))
          .clickable { onClose() }
          .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
      ) {
        Text("✕  CLOSE", color = Color.White, fontFamily = PixelFont, fontSize = 13.sp)
      }
      Box(
        modifier = Modifier
          .clip(RoundedCornerShape(16.dp))
          .background(Color.Black.copy(alpha = 0.45f))
          .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
      ) {
        Text(
          if (cameraReady) "HOLD STEADY" else "STARTING CAMERA…",
          color = Color.White,
          fontSize = 11.sp,
          fontWeight = FontWeight.Bold
        )
      }
    }

    // Bottom controls: gallery | capture | flash.
    Row(
      modifier = Modifier
        .align(Alignment.BottomCenter)
        .fillMaxWidth()
        .padding(bottom = 36.dp, start = 28.dp, end = 28.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Box(
        modifier = Modifier
          .size(56.dp)
          .clip(CircleShape)
          .background(Color.Black.copy(alpha = 0.45f))
          .clickable { onGallery() },
        contentAlignment = Alignment.Center
      ) {
        Text("🖼", fontSize = 26.sp)
      }
      // Large round green capture button.
      Box(
        modifier = Modifier
          .size(84.dp)
          .clip(CircleShape)
          .background(theme.primary)
          .border(4.dp, Color.White, CircleShape)
          .clickable(enabled = cameraReady) { takePhoto() },
        contentAlignment = Alignment.Center
      ) {
        Box(
          Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.25f))
        )
      }
      Box(
        modifier = Modifier
          .size(56.dp)
          .clip(CircleShape)
          .background(Color.Black.copy(alpha = 0.45f))
          .clickable { flashOn = !flashOn },
        contentAlignment = Alignment.Center
      ) {
        Text(if (flashOn) "⚡" else "⚡̶", fontSize = 24.sp, color = Color.White)
      }
    }
  }
}
