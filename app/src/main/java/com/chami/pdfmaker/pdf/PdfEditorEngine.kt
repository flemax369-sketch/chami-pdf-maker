package com.chami.pdfmaker.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

// One page in the edited output. Rotation is stored ON the page, so
// reorder / delete / rotate never lose it.
data class PageRef(
  val sourcePage: Int = -1, // >= 0: page index in the source PDF.
  val rotation: Int = 0, // 0, 90, 180, 270
  val imageUri: Uri? = null, // set: this output page is a full-page image.
  val blank: Boolean = false // set: this output page is blank white.
)

// All coordinates are in PDF points on a 595 x 842 A4 page.
// The UI converts screen taps to PDF points before creating these.
sealed class Annotation {
  abstract val page: Int // output page index (0-based)
  data class Highlight(override val page: Int, val rect: RectF, val colour: Int) : Annotation()
  data class Underline(override val page: Int, val rect: RectF, val colour: Int) : Annotation()
  data class Strike(override val page: Int, val rect: RectF, val colour: Int) : Annotation()
  data class TextBox(override val page: Int, val x: Float, val y: Float, val text: String, val size: Float, val colour: Int) : Annotation()
  data class PenPath(override val page: Int, val points: List<PointF>, val colour: Int, val width: Float) : Annotation()
  data class Signature(override val page: Int, val bitmap: Bitmap, val x: Float, val y: Float, val w: Float, val h: Float) : Annotation()
  data class ImageStamp(override val page: Int, val bitmap: Bitmap, val x: Float, val y: Float, val w: Float, val h: Float) : Annotation()
  data class Redact(override val page: Int, val rect: RectF) : Annotation()
}

object PdfEditorEngine {
  const val PAGE_W = 595f
  const val PAGE_H = 842f

  // HONEST LIMIT: the edited copy is built from rendered page images,
  // with annotations drawn on top. The original selectable text is NOT
  // preserved in the edited copy. The original file is never changed —
  // the caller always writes to a new file (Save as Copy).
  fun saveAnnotatedPdf(
    ctx: Context,
    pdfUri: Uri,
    pages: List<PageRef>,
    annotationsByPage: Map<Int, List<Annotation>>,
    watermarkText: String,
    showPageNumbers: Boolean,
    outFile: File
  ): Boolean {
    if (pages.isEmpty()) return false
    return try {
      val doc = PdfDocument()
      val smallPaint = Paint().apply {
        color = Color.GRAY
        textSize = 10f
        textAlign = Paint.Align.CENTER
      }
      val watermarkPaint = Paint().apply {
        color = Color.argb(60, 120, 120, 120)
        textSize = 48f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
      }
      pages.forEachIndexed { outIndex, ref ->
        val info = PdfDocument.PageInfo.Builder(595, 842, outIndex + 1).create()
        val page = doc.startPage(info)
        val c = page.canvas
        c.drawColor(Color.WHITE)
        var base: Bitmap? = null
        if (ref.blank) {
          // White page — nothing to draw underneath.
        } else if (ref.imageUri != null) {
          base = PdfEngine.loadBitmap(ctx, ref.imageUri, 2000)
          if (base != null) fitBitmap(c, base)
        } else if (ref.sourcePage >= 0) {
          base = PdfReader.renderPageBitmap(ctx, pdfUri, ref.sourcePage, 1.5f)
          if (base != null) {
            val drawn = if (ref.rotation != 0) rotatedBitmap(base, ref.rotation) else base
            fitBitmap(c, drawn)
            if (drawn !== base) drawn.recycle()
          }
        }
        base?.recycle()
        annotationsByPage[outIndex]?.forEach { drawAnnotation(c, it) }
        if (watermarkText.isNotBlank()) {
          c.save()
          c.translate(297.5f, 421f)
          c.rotate(-30f)
          c.drawText(watermarkText, 0f, 0f, watermarkPaint)
          c.restore()
        }
        if (showPageNumbers) {
          c.drawText("Page ${outIndex + 1}", 297.5f, 820f, smallPaint)
        }
        doc.finishPage(page)
      }
      FileOutputStream(outFile).use { doc.writeTo(it) }
      doc.close()
      true
    } catch (_: Exception) {
      false
    }
  }

  // Draw a bitmap inside the 36pt margins, keeping its aspect ratio.
  private fun fitBitmap(c: Canvas, bmp: Bitmap) {
    val scale = minOf(523f / bmp.width, 770f / bmp.height)
    val w = bmp.width * scale
    val h = bmp.height * scale
    c.drawBitmap(bmp, null, RectF((595f - w) / 2f, (842f - h) / 2f, (595f + w) / 2f, (842f + h) / 2f), null)
  }

  private fun rotatedBitmap(bmp: Bitmap, rotation: Int): Bitmap {
    val m = Matrix().apply { postRotate(rotation.toFloat()) }
    return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
  }

  private fun drawAnnotation(c: Canvas, a: Annotation) {
    when (a) {
      is Annotation.Highlight -> {
        val p = Paint().apply { color = a.colour; alpha = 90; style = Paint.Style.FILL }
        c.drawRect(a.rect, p)
      }
      is Annotation.Underline -> {
        val p = Paint().apply { color = a.colour; strokeWidth = 2f; style = Paint.Style.STROKE }
        c.drawLine(a.rect.left, a.rect.bottom, a.rect.right, a.rect.bottom, p)
      }
      is Annotation.Strike -> {
        val p = Paint().apply { color = a.colour; strokeWidth = 2f; style = Paint.Style.STROKE }
        val mid = (a.rect.top + a.rect.bottom) / 2f
        c.drawLine(a.rect.left, mid, a.rect.right, mid, p)
      }
      is Annotation.TextBox -> {
        val p = Paint().apply { color = a.colour; textSize = a.size; isAntiAlias = true }
        var y = a.y
        a.text.split("\n").forEach { line ->
          c.drawText(line, a.x, y, p)
          y += a.size + 4f
        }
      }
      is Annotation.PenPath -> {
        if (a.points.size < 2) return
        val p = Paint().apply {
          color = a.colour
          strokeWidth = a.width
          style = Paint.Style.STROKE
          strokeCap = Paint.Cap.ROUND
          strokeJoin = Paint.Join.ROUND
          isAntiAlias = true
        }
        val path = Path().apply {
          moveTo(a.points[0].x, a.points[0].y)
          for (i in 1 until a.points.size) lineTo(a.points[i].x, a.points[i].y)
        }
        c.drawPath(path, p)
      }
      is Annotation.Signature -> {
        c.drawBitmap(a.bitmap, null, RectF(a.x, a.y, a.x + a.w, a.y + a.h), null)
      }
      is Annotation.ImageStamp -> {
        c.drawBitmap(a.bitmap, null, RectF(a.x, a.y, a.x + a.w, a.y + a.h), null)
      }
      is Annotation.Redact -> {
        val p = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
        c.drawRect(a.rect, p)
      }
    }
  }

  // Bounding box of an annotation, for the ERASE tap test.
  fun annotationBounds(a: Annotation): RectF = when (a) {
    is Annotation.Highlight -> a.rect
    is Annotation.Underline -> a.rect
    is Annotation.Strike -> a.rect
    is Annotation.Redact -> a.rect
    is Annotation.TextBox -> RectF(a.x, a.y - a.size, a.x + a.text.length * a.size * 0.6f, a.y + 6f)
    is Annotation.PenPath -> {
      if (a.points.isEmpty()) RectF(0f, 0f, 0f, 0f)
      else {
        var l = Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var r = Float.MIN_VALUE
        var b = Float.MIN_VALUE
        for (pt in a.points) {
          if (pt.x < l) l = pt.x
          if (pt.y < t) t = pt.y
          if (pt.x > r) r = pt.x
          if (pt.y > b) b = pt.y
        }
        RectF(l, t, r, b)
      }
    }
    is Annotation.Signature -> RectF(a.x, a.y, a.x + a.w, a.y + a.h)
    is Annotation.ImageStamp -> RectF(a.x, a.y, a.x + a.w, a.y + a.h)
  }

  // Signature saved as a transparent PNG, so it sits on the page
  // without a white box. Latest saved signature is reused.
  fun saveSignatureBitmap(ctx: Context, bitmap: Bitmap): File? {
    return try {
      val dir = File(ctx.filesDir, "signatures")
      if (!dir.exists()) dir.mkdirs()
      val f = File(dir, "signature_${System.currentTimeMillis()}.png")
      FileOutputStream(f).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
      f
    } catch (_: Exception) {
      null
    }
  }

  fun loadSavedSignature(ctx: Context): Bitmap? {
    return try {
      val dir = File(ctx.filesDir, "signatures")
      if (!dir.exists()) return null
      val latest = dir.listFiles()
        ?.filter { it.name.endsWith(".png") }
        ?.maxByOrNull { it.lastModified() } ?: return null
      BitmapFactory.decodeFile(latest.absolutePath)
    } catch (_: Exception) {
      null
    }
  }
}
