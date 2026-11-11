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
  val pageCount: Int
)

object PdfCompress {

  val PRESETS = listOf(
    PdfCompressPreset("High", 1.0f, 70),
    PdfCompressPreset("Medium", 0.75f, 50),
    PdfCompressPreset("Low", 0.6f, 40)
  )

  fun presetByLabel(label: String): PdfCompressPreset {
    return PRESETS.firstOrNull { it.label == label } ?: PRESETS[1]
  }

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
        return PdfCompressResult(true, outFile.length() / 1024L, true, pageCount)
      }
      return PdfCompressResult(true, outFile.length() / 1024L, false, pageCount)
    } catch (_: Exception) {
      try { outFile.delete() } catch (_: Exception) {}
      return PdfCompressResult(false, 0L, false, 0)
    } finally {
      try { renderer?.close() } catch (_: Exception) {}
      try { pfd?.close() } catch (_: Exception) {}
    }
  }
}
