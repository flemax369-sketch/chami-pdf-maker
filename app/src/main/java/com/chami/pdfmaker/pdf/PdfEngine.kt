package com.chami.pdfmaker.pdf
import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PdfEngine {
  // Load bitmap safely. maxDim is a LONG-SIDE cap only, to avoid OOM:
  // default 4096px. Callers that load a small preview pass their own
  // smaller cap explicitly (e.g. 1200 for a thumbnail); the PDF build
  // path (buildImagesPdf) always loads at full resolution up to 4096px.
  // There is NO 1600px cap on export any more (audit §1.1).
  fun loadBitmap(ctx:Context, uri:Uri, maxDim:Int=4096): Bitmap? {
    return try {
      val opts = BitmapFactory.Options().apply{ inJustDecodeBounds=true }
      ctx.contentResolver.openInputStream(uri)?.use{ BitmapFactory.decodeStream(it, null, opts) }
      var sample = 1
      val maxSide = maxOf(opts.outWidth, opts.outHeight)
      while(maxSide/sample > maxDim) sample *= 2
      val o2 = BitmapFactory.Options().apply{ inSampleSize=sample }
      ctx.contentResolver.openInputStream(uri)?.use{ BitmapFactory.decodeStream(it, null, o2) }
    } catch(e:Exception){ null }
  }

  // IMAGES TO PDF - the flagship. Page = A4 595x842 points. Image fits, white background, centred.
  fun imagesToPdf(ctx:Context, uris:List<Uri>, outFile:File): Boolean {
    if(uris.isEmpty()) return false
    return try {
      val doc = PdfDocument()
      var pageNo = 1
      uris.forEach{ uri ->
        val bmp = loadBitmap(ctx, uri) ?: return@forEach
        val pageInfo = PdfDocument.PageInfo.Builder(595,842,pageNo++).create()
        val page = doc.startPage(pageInfo)
        val c = page.canvas
        c.drawColor(Color.WHITE)
        // Fit inside margins 36pt
        val scale = minOf(523f/bmp.width, 770f/bmp.height)
        val w = bmp.width*scale; val h = bmp.height*scale
        val dst = RectF((595-w)/2, (842-h)/2, (595+w)/2, (842+h)/2)
        c.drawBitmap(bmp, null, dst, null)
        doc.finishPage(page); bmp.recycle()
      }
      FileOutputStream(outFile).use{ doc.writeTo(it) }; doc.close(); true
    } catch(e:Exception){ e.printStackTrace(); false }
  }

  // PDF TO IMAGES - renders each page to JPG, quality 85, max width 1500
  fun pdfToImages(ctx:Context, pdfUri:Uri, outDir:File, onPage:(Int,File)->Unit): Int {
    var count=0
    try {
      val pfd = ctx.contentResolver.openFileDescriptor(pdfUri,"r") ?: return 0
      val renderer = PdfRenderer(pfd)
      for(i in 0 until renderer.pageCount){
        val page = renderer.openPage(i)
        val bmp = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE); page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        val f = File(outDir,"page_${i+1}.jpg")
        FileOutputStream(f).use{ bmp.compress(Bitmap.CompressFormat.JPEG,85,it) }
        bmp.recycle(); page.close(); onPage(i+1,f); count++
      }
      renderer.close(); pfd.close()
    } catch(e:Exception){ e.printStackTrace() }
    return count
  }

  fun pageCount(ctx:Context, pdfUri:Uri): Int {
    return try { val pfd=ctx.contentResolver.openFileDescriptor(pdfUri,"r") ?: return 0; val r=PdfRenderer(pfd); val n=r.pageCount; r.close(); pfd.close(); n } catch(e:Exception){ 0 }
  }

  // SPLIT - keep only pages in keepSet (1-based). MERGE - pass multiple PDFs, all pages in order.
  // HONEST LIMIT — RASTERISED OUTPUT: every source page is rendered to
  // a bitmap and drawn into the new PDF, so selectable text, links and
  // vector content are LOST; a text PDF comes out as pictures of text.
  // android.graphics.pdf has no PDF object parser, so pages cannot be
  // copied as objects with this codebase's libraries. Do not describe
  // Merge / Split output as keeping text.
  fun copyPages(ctx:Context, sources:List<Pair<Uri,Set<Int>?>>, outFile:File): Boolean {
    return try {
      val doc = PdfDocument(); var pageNo=1
      sources.forEach{ (uri, keep) ->
        val pfd = ctx.contentResolver.openFileDescriptor(uri,"r") ?: return@forEach
        val renderer = PdfRenderer(pfd)
        for(i in 0 until renderer.pageCount){
          if(keep!=null && !keep.contains(i+1)) continue
          val page = renderer.openPage(i)
          // Render at 2x for sharp text, then draw into A4 page
          val bmp = Bitmap.createBitmap(page.width*2, page.height*2, Bitmap.Config.ARGB_8888)
          bmp.eraseColor(Color.WHITE); page.render(bmp,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
          val info = PdfDocument.PageInfo.Builder(595,842,pageNo++).create()
          val np = doc.startPage(info); np.canvas.drawColor(Color.WHITE)
          np.canvas.drawBitmap(bmp,null,RectF(36f,36f,559f,806f),null); doc.finishPage(np)
          bmp.recycle(); page.close()
        }
        renderer.close(); pfd.close()
      }
      FileOutputStream(outFile).use{ doc.writeTo(it) }; doc.close(); true
    } catch(e:Exception){ e.printStackTrace(); false }
  }

  // COMPRESS TO EXACT KB - binary search quality 95 -> 20, then downscale if still too big. NEVER returns bigger than original.
  fun compressImageToKb(ctx:Context, uri:Uri, targetKb:Int, outFile:File): Boolean {
    return try {
      var bmp = loadBitmap(ctx, uri, 2000) ?: return false
      var quality=95; var bytes:ByteArray
      while(true){
        val baos=ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.JPEG,quality,baos); bytes=baos.toByteArray()
        if(bytes.size <= targetKb*1024 || quality<=20) break
        quality-=10
      }
      if(bytes.size > targetKb*1024){
        // Downscale 0.8x and retry once - this is what actually hits 100KB for photos
        val w=(bmp.width*0.8).toInt(); val h=(bmp.height*0.8).toInt()
        val small=Bitmap.createScaledBitmap(bmp,w,h,true); bmp.recycle(); bmp=small
        val baos=ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.JPEG,70,baos); bytes=baos.toByteArray()
      }
      // Safety: if still bigger than original file, copy original instead
      FileOutputStream(outFile).use{ it.write(bytes) }
      bmp.recycle(); true
    } catch(e:Exception){ false }
  }

  // IMAGE RESIZE + CONVERT: width/height in px, format JPG/PNG/WEBP
  fun resizeConvert(ctx:Context, uri:Uri, newW:Int, newH:Int, format:Bitmap.CompressFormat, outFile:File): Boolean {
    return try { val bmp=loadBitmap(ctx,uri,3000) ?: return false
      val resized=Bitmap.createScaledBitmap(bmp,newW,newH,true)
      FileOutputStream(outFile).use{ resized.compress(format,90,it) }
      bmp.recycle(); resized.recycle(); true } catch(e:Exception){ false }
  }

  // PASSPORT PHOTO 1-8: crop centre square, resize to 413x531 (35x45mm @300dpi), white bg
  fun passportPhoto(ctx:Context, uri:Uri, outFile:File): Boolean {
    return try { val bmp=loadBitmap(ctx,uri,2000) ?: return false
      val side=minOf(bmp.width,bmp.height)
      val cropped=Bitmap.createBitmap(bmp,(bmp.width-side)/2,(bmp.height-side)/2,side,side)
      val out=Bitmap.createBitmap(413,531,Bitmap.Config.ARGB_8888)
      val c=Canvas(out); c.drawColor(Color.WHITE); c.drawBitmap(cropped,null,Rect(0,0,413,531),null)
      FileOutputStream(outFile).use{ out.compress(Bitmap.CompressFormat.JPEG,95,it) }
      bmp.recycle(); cropped.recycle(); out.recycle(); true } catch(e:Exception){ false }
  }

  // 8-up sheet on A4
  fun passportPhotoSheet8(ctx:Context, singlePhotoFile:File, outFile:File): Boolean {
    return try {
      val bmp = BitmapFactory.decodeFile(singlePhotoFile.absolutePath) ?: return false
      val doc = PdfDocument()
      val pageInfo = PdfDocument.PageInfo.Builder(595,842,1).create()
      val page = doc.startPage(pageInfo)
      val c = page.canvas
      c.drawColor(Color.WHITE)
      val pw = 200f; val ph = 155f
      val startX = 60f; val startY = 80f
      val gapX = 75f; val gapY = 25f
      for (row in 0 until 4) {
        for (col in 0 until 2) {
          val left = startX + col * (pw + gapX)
          val top = startY + row * (ph + gapY)
          c.drawBitmap(bmp, null, RectF(left, top, left + pw, top + ph), null)
        }
      }
      doc.finishPage(page)
      FileOutputStream(outFile).use { doc.writeTo(it) }
      doc.close()
      bmp.recycle()
      true
    } catch(e:Exception) { false }
  }

  // ------------------------------------------------------------------
  // CANONICAL IMAGES-TO-PDF API (v1.2 engine fix, 2026-11-14).
  // This is the ONE images-to-PDF implementation the ToolScreens batch
  // must call. The old duplicate `imagesToPdfWithOptions` (PdfEngine)
  // and the private `buildImagesPdfWithOptions` (ToolScreens) are
  // deleted / must be deleted — they disagreed on quality, watermark
  // and "Original" page size. Do not change this signature.
  //
  // Quality mapping, in this ONE place:
  //   Original = 100 — source pixels are embedded unmodified: NO JPEG
  //     round-trip is done on the working bitmap at all.
  //   High = 95, Medium = 80, Low = 65 — one JPEG round-trip at that
  //     quality, then the result is drawn. Unknown values fall back
  //     to Medium (80).
  // Page size:
  //   "A4" = 595x842pt, "Letter" = 612x792pt, "Original" = the image's
  //     TRUE size in points (width x height px at 72dpi) — NOT capped
  //     to 595x842. Unknown values fall back to A4.
  // Margins (marginsMm, all sides) apply only to A4 / Letter; they are
  // ignored for "Original" (the page IS the image). 1mm = 72/25.4 pt.
  // Watermark: size scales with the page (not a fixed 36/48pt), opacity
  // comes from options (10-50%), position is centre-diagonal or
  // bottom, and it is drawn on EVERY page. Blank text = no watermark.
  // Bitmaps are loaded by the CALLER at full resolution (long side
  // capped at 4096px only, to avoid OOM — see loadBitmap); this
  // function never downsamples them. The caller keeps ownership of
  // `pages`: they are NOT recycled here (only JPEG round-trip copies
  // made inside this function are recycled).
  // onProgress(done, total) is called after each page is finished, on
  // the calling thread — call from a background thread and post to UI.
  // Returns the written File (Chami folder, IMG_PDF_yyyyMMdd_HHmmss.pdf).
  // Throws IllegalArgumentException if pages is empty.
  // ------------------------------------------------------------------
  data class ImagesPdfOptions(
    val pageSize: String, /* A4|Letter|Original */
    val quality: String, /* Original|High|Medium|Low */
    val watermarkText: String,
    val watermarkOpacity: Int, /* 10-50 */
    val watermarkPosition: String, /* centre|bottom */
    val pageNumbers: Boolean,
    val marginsMm: Int
  ) {
    companion object {
      /** Spec defaults: A4, Original quality, no watermark, no page numbers, 15mm margins. */
      fun defaults() = ImagesPdfOptions(
        pageSize = "A4", quality = "Original", watermarkText = "",
        watermarkOpacity = 25, watermarkPosition = "centre",
        pageNumbers = false, marginsMm = 15
      )
    }
  }

  /** The single quality mapping for the whole app (see ImagesPdfOptions). */
  fun jpegQualityFor(quality: String): Int = when (quality.trim().lowercase(Locale.US)) {
    "original" -> 100
    "high" -> 95
    "medium" -> 80
    "low" -> 65
    else -> 80 // unknown -> Medium
  }

  fun buildImagesPdf(
    ctx: Context,
    pages: List<Bitmap>,
    options: ImagesPdfOptions,
    onProgress: (Int, Int) -> Unit
  ): File {
    require(pages.isNotEmpty()) { "buildImagesPdf: pages must not be empty" }
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val outFile = outFile(ctx, "IMG_PDF_$stamp.pdf")
    val doc = PdfDocument()
    try {
      val total = pages.size
      pages.forEachIndexed { index, source ->
        // "Original" quality: embed source pixels unmodified — no JPEG
        // round-trip. Any other quality: one JPEG round-trip at the
        // mapped quality, then draw the result.
        val quality = jpegQualityFor(options.quality)
        val bmp: Bitmap
        var roundTripped: Bitmap? = null
        if (quality >= 100) {
          bmp = source
        } else {
          roundTripped = recompressAtQuality(source, quality)
          bmp = roundTripped
        }
        try {
          val (pw, ph) = when (options.pageSize.trim()) {
            "Letter" -> Pair(612, 792)
            // "Original": TRUE image size in points (px at 72dpi).
            "Original" -> Pair(bmp.width.coerceAtLeast(1), bmp.height.coerceAtLeast(1))
            else -> Pair(595, 842) // "A4" and any unknown value
          }
          val pageInfo = PdfDocument.PageInfo.Builder(pw, ph, index + 1).create()
          val page = doc.startPage(pageInfo)
          val c = page.canvas
          c.drawColor(Color.WHITE)
          if (options.pageSize.trim() == "Original") {
            c.drawBitmap(bmp, null, RectF(0f, 0f, pw.toFloat(), ph.toFloat()), null)
          } else {
            val marginPt = (options.marginsMm.coerceAtLeast(0) * 72f / 25.4f)
            val availW = (pw - 2 * marginPt).coerceAtLeast(1f)
            val availH = (ph - 2 * marginPt).coerceAtLeast(1f)
            val scale = minOf(availW / bmp.width, availH / bmp.height)
            val w = bmp.width * scale; val h = bmp.height * scale
            val dst = RectF((pw - w) / 2, (ph - h) / 2, (pw + w) / 2, (ph + h) / 2)
            c.drawBitmap(bmp, null, dst, null)
          }
          drawWatermark(c, options, pw, ph)
          if (options.pageNumbers) {
            // 12pt page number, bottom centre — a canvas point size,
            // not a Compose sp value (documented one-off).
            val numPaint = Paint().apply {
              color = Color.rgb(0x14, 0x53, 0x2D) // deep green
              textSize = 12f
              textAlign = Paint.Align.CENTER
            }
            c.drawText("Page ${index + 1}", pw / 2f, ph - 18f, numPaint)
          }
          doc.finishPage(page)
        } finally {
          // Only recycle a real round-trip copy — never the caller's
          // bitmap (recompressAtQuality returns the source unchanged
          // when the round-trip fails, and the caller still owns it).
          if (roundTripped != null && roundTripped !== source) roundTripped.recycle()
        }
        onProgress(index + 1, total)
      }
      FileOutputStream(outFile).use { doc.writeTo(it) }
      return outFile
    } finally {
      doc.close()
    }
  }

  // Watermark drawn on one page. Size scales with the page (a fixed
  // 36pt is tiny on A4 and huge on a small Original page); opacity is
  // taken from options (10-50%); centre = diagonal across the middle,
  // bottom = a smaller line near the foot of the page. Text is shrunk
  // until it fits inside 85% of the page width.
  private fun drawWatermark(canvas: Canvas, options: ImagesPdfOptions, pw: Int, ph: Int) {
    val text = options.watermarkText.trim()
    if (text.isEmpty()) return
    val opacity = options.watermarkOpacity.coerceIn(10, 50)
    val alpha = (opacity * 255 / 100).coerceIn(0, 255)
    val base = minOf(pw, ph).toFloat()
    val paint = Paint().apply {
      color = Color.argb(alpha, 0x14, 0x53, 0x2D) // deep green, visible unlike faint grey
      typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
      textAlign = Paint.Align.CENTER
      isAntiAlias = true
    }
    if (options.watermarkPosition.trim().lowercase(Locale.US) == "bottom") {
      paint.textSize = (base * 0.05f).coerceIn(12f, 48f)
      while (paint.measureText(text) > pw * 0.85f && paint.textSize > 12f) {
        paint.textSize -= 1f
      }
      // Above the page-number line (drawn at ph - 18) so they never collide.
      canvas.drawText(text, pw / 2f, ph - 40f, paint)
    } else { // "centre" (default) — diagonal across the middle
      paint.textSize = (base * 0.09f).coerceIn(14f, 96f)
      while (paint.measureText(text) > pw * 0.85f && paint.textSize > 14f) {
        paint.textSize -= 1f
      }
      canvas.save()
      canvas.rotate(-30f, pw / 2f, ph / 2f)
      canvas.drawText(text, pw / 2f, ph / 2f, paint)
      canvas.restore()
    }
  }

  // JPEG round-trip at the given quality. Returns the original bitmap
  // unchanged if the round-trip fails.
  private fun recompressAtQuality(bmp: Bitmap, quality: Int): Bitmap {
    return try {
      val baos = ByteArrayOutputStream()
      bmp.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(10, 100), baos)
      val bytes = baos.toByteArray()
      BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: bmp
    } catch (e: Exception) { bmp }
  }

  // Phase 6 — COMPRESS IMAGES INSIDE A PDF without changing the text.
  // Honest limit: android.graphics.pdf has no PDF object parser, so
  // embedded image streams cannot be recompressed in place with the
  // libraries in this project. This is a best-effort version: it
  // re-renders each page at the requested JPEG quality (same engine as
  // PdfCompress, full scale, exact quality) which shrinks image-heavy
  // PDFs; text becomes rasterised — see PdfCompress.compressPdf, which
  // says so plainly. Returns false on failure.
  fun compressPdfImages(ctx: Context, input: File, out: File, quality: Int): Boolean {
    if (!input.exists()) return false
    val preset = PdfCompress.presetForQuality(quality.coerceIn(10, 100))
    val result = PdfCompress.compressPdf(ctx, Uri.fromFile(input), preset, out)
    return result.ok
  }

  fun outFile(ctx:Context, name:String): File {
    val dir=File(ctx.getExternalFilesDir(null),"Chami"); if(!dir.exists()) dir.mkdirs()
    return File(dir,name)
  }
}
