package com.chami.pdfmaker.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

data class PdfCompressPreset(val label: String, val scale: Float, val jpegQuality: Int)

data class PdfCompressResult(
  val ok: Boolean,
  val outKb: Long,
  val keptOriginal: Boolean,
  val pageCount: Int,
  // Always false for this engine: output pages are rasterised images,
  // so selectable text is NOT preserved (see compressPdf). Kept on the
  // result so no caller can mistake a compressed PDF for a text-kept one.
  val textPreserved: Boolean = false
)

object PdfCompress {

  // Labelled presets — the ONLY honest UI for PDF compression in this
  // codebase (see compressPdf). They are also the named points on the
  // continuous scale below: High = quality 100, Medium = 70, Low = 40
  // on the 10-100 slider. Kept so existing callers keep compiling.
  val PRESETS = listOf(
    PdfCompressPreset("High", 1.0f, 95),
    PdfCompressPreset("Medium", 0.85f, 72),
    PdfCompressPreset("Low", 0.70f, 50)
  )

  fun presetByLabel(label: String): PdfCompressPreset {
    return PRESETS.firstOrNull { it.label.equals(label, ignoreCase = true) } ?: PRESETS[1]
  }

  // CONTINUOUS quality mapping (audit §6: the old slider was a lie —
  // 0-100 mapped onto 3 presets, so 71-99 and 41-69 changed nothing).
  // Now scale and JPEG quality follow the 10-100 value continuously:
  //   scale       = 0.50 + 0.50 * (quality / 100)   // 10 -> 0.55, 100 -> 1.00
  //   jpegQuality = 20 + quality * 0.75            // 10 -> 27,  100 -> 95
  // Every slider step changes the output. Use presetForQuality for the
  // estimate as well as the real run, so estimate and result agree.
  fun presetForQuality(quality: Int): PdfCompressPreset {
    val q = quality.coerceIn(10, 100)
    val scale = 0.50f + 0.50f * (q / 100f)
    val jpeg = (20 + q * 0.75f).toInt().coerceIn(10, 95)
    val label = when {
      q >= 85 -> "High"
      q >= 55 -> "Medium"
      else -> "Low"
    }
    return PdfCompressPreset(label, scale, jpeg)
  }

  // UI-facing entry point for the quality slider: one continuous value,
  // not a preset. For the labelled High / Medium / Low buttons use
  // compressPdfWithPresetLabel instead.
  fun compressPdfWithQuality(
    ctx: Context,
    pdfUri: Uri,
    quality: Int,
    outFile: File
  ): PdfCompressResult = compressPdf(ctx, pdfUri, presetForQuality(quality), outFile)

  // UI-facing entry point for the labelled preset buttons
  // (High / Medium / Low). The label is what the user picked; the
  // preset is the named point on the same continuous scale.
  fun compressPdfWithPresetLabel(
    ctx: Context,
    pdfUri: Uri,
    label: String,
    outFile: File
  ): PdfCompressResult = compressPdf(ctx, pdfUri, presetByLabel(label), outFile)

  // Size of the file behind a content Uri, in bytes, counted by
  // streaming (openInputStream().available() is unreliable).
  // Returns -1 if the size cannot be read.
  fun originalSizeBytes(ctx: Context, uri: Uri): Long {
    return try {
      var total = 0L
      val buf = ByteArray(8192)
      ctx.contentResolver.openInputStream(uri)?.use { input ->
        var n: Int
        while (input.read(buf).also { n = it } != -1) { total += n }
      }
      total
    } catch (_: Exception) { -1L }
  }

  fun fileSizeKb(ctx: Context, uri: Uri): Long {
    val bytes = originalSizeBytes(ctx, uri)
    return if (bytes < 0) 0L else bytes / 1024L
  }

  // Compress a PDF by re-rendering every page as a JPEG image.
  //
  // HONEST LIMIT — TEXT IS NOT PRESERVED. This re-renders each page to
  // a bitmap and draws it into a new PDF, so selectable text, links and
  // vector content are LOST; the output is pictures of pages. The spec
  // headline "compress images inside a PDF without changing text" is
  // NOT implemented here, because it cannot be: android.graphics.pdf
  // has no PDF object parser, and this project has no PDF library, so
  // embedded image streams cannot be recompressed in place. Do NOT
  // claim otherwise in UI copy, store listing or comments — the result
  // carries textPreserved = false for exactly this reason. If a future
  // build adds a real PDF library (e.g. PDFBox / iText), that version
  // must replace this function, not sit beside it.
  fun compressPdf(
    ctx: Context,
    pdfUri: Uri,
    preset: PdfCompressPreset,
    outFile: File
  ): PdfCompressResult {
    val origBytes = originalSizeBytes(ctx, pdfUri)
    var renderer: PdfRenderer? = null
    var pfd: android.os.ParcelFileDescriptor? = null
    try {
      pfd = ctx.contentResolver.openFileDescriptor(pdfUri, "r")
        ?: return PdfCompressResult(false, 0L, false, 0)
      renderer = PdfRenderer(pfd)
      val pageCount = renderer.pageCount
      if (pageCount == 0) return PdfCompressResult(false, 0L, false, 0)
      val doc = PdfDocument()
      try {
        for (i in 0 until pageCount) {
          val page = renderer.openPage(i)
          try {
            var w = (page.width * preset.scale).toInt().coerceAtLeast(1)
            var h = (page.height * preset.scale).toInt().coerceAtLeast(1)
            val maxSide = maxOf(w, h)
            if (maxSide > 2000) {
              val shrink = 2000f / maxSide
              w = (w * shrink).toInt().coerceAtLeast(1)
              h = (h * shrink).toInt().coerceAtLeast(1)
            }
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            try {
              bmp.eraseColor(Color.WHITE)
              page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
              val baos = ByteArrayOutputStream()
              bmp.compress(Bitmap.CompressFormat.JPEG, preset.jpegQuality, baos)
              val jpgBytes = baos.toByteArray()
              val jpg = BitmapFactory.decodeByteArray(jpgBytes, 0, jpgBytes.size)
              if (jpg != null) {
                try {
                  val info = PdfDocument.PageInfo.Builder(page.width, page.height, i + 1).create()
                  val newPage = doc.startPage(info)
                  newPage.canvas.drawColor(Color.WHITE)
                  newPage.canvas.drawBitmap(
                    jpg, null,
                    RectF(0f, 0f, page.width.toFloat(), page.height.toFloat()), null
                  )
                  doc.finishPage(newPage)
                } finally {
                  jpg.recycle()
                }
              }
            } finally {
              bmp.recycle()
            }
          } finally {
            page.close()
          }
        }
      } finally {
        FileOutputStream(outFile).use { doc.writeTo(it) }
        doc.close()
      }
      // Never output bigger than the original: keep the original.
      if (origBytes > 0 && outFile.length() >= origBytes) {
        outFile.delete()
        ctx.contentResolver.openInputStream(pdfUri)?.use { input ->
          FileOutputStream(outFile).use { output -> input.copyTo(output) }
        }
        return PdfCompressResult(true, outFile.length() / 1024L, true, pageCount, textPreserved = false)
      }
      return PdfCompressResult(true, outFile.length() / 1024L, false, pageCount, textPreserved = false)
    } catch (_: Exception) {
      try { outFile.delete() } catch (_: Exception) {}
      return PdfCompressResult(false, 0L, false, 0)
    } finally {
      try { renderer?.close() } catch (_: Exception) {}
      try { pfd?.close() } catch (_: Exception) {}
    }
  }
}
