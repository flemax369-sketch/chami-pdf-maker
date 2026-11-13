package com.chami.pdfmaker.pdf
import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import java.io.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

// TOOL 14 - INBUILT PDF READER: render ONE page at a time, never load all pages.
// In Compose: LazyColumn, for each page index call renderPageBitmap(ctx, uri, index, zoom)
// zoom 1.0 = normal, 2.0 = zoomed. Cache bitmaps in memory only for visible pages, recycle on scroll.
object PdfReader {
  fun renderPageBitmap(ctx:Context, pdfUri:Uri, pageIndex:Int, scale:Float=1.5f): Bitmap? {
    return try {
      val pfd = ctx.contentResolver.openFileDescriptor(pdfUri,"r") ?: return null
      val renderer = PdfRenderer(pfd)
      if(pageIndex !in 0 until renderer.pageCount){ renderer.close(); pfd.close(); return null }
      val page = renderer.openPage(pageIndex)
      val w = (page.width*scale).toInt(); val h = (page.height*scale).toInt()
      val bmp = Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
      bmp.eraseColor(Color.WHITE); page.render(bmp,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
      page.close(); renderer.close(); pfd.close(); bmp
    } catch(e:Exception){ null }
  }
  // Reader UI must have: Go to page input, Zoom +/- buttons, Night mode = invert with ColorMatrix (do NOT re-render), Search = use Tool 15 text + find page containing query.
}

// TOOL 15 - PDF TO TEXT: honest version.
// Android PdfRenderer has NO text extraction. So: first try OCR per page (works for scanned + digital PDFs), return text per page.
// If OCR returns empty for a page, return "[Page N: No text found - try OCR]" - never fake blank as success.
suspend fun pdfToText(ctx:Context, pdfUri:Uri, pages:Set<Int>?=null): String {
  val sb = StringBuilder()
  try {
    val count = PdfEngine.pageCount(ctx, pdfUri)
    // FIX: render ALL pages ONCE before the loop, not once per page.
    val tmpDir = File(ctx.cacheDir,"tmppdf"); if(!tmpDir.exists()) tmpDir.mkdirs()
    PdfEngine.pdfToImages(ctx, pdfUri, tmpDir){ _,_ -> }
    for(i in 0 until count){
      if(pages!=null && !pages.contains(i+1)) continue
      val imgFile = File(tmpDir,"page_${i+1}.jpg")
      val text = if(imgFile.exists()) ocrImage(ctx, Uri.fromFile(imgFile)) else ""
      sb.append("--- Page ${i+1} ---\n")
      sb.append(if(text.isBlank()) "[No text found on this page - try OCR / this page may be an image]\n" else text.trim()+"\n")
      sb.append("\n")
    }
  } catch(e:Exception){ sb.append("Error: ${e.message}") }
  return sb.toString()
}
fun saveTextToFile(ctx:Context, text:String, name:String): File {
  val f = PdfEngine.outFile(ctx, name); f.writeText(text); return f
}

// TOOL 16 - TEXT TO PDF: real selectable text, multi-page, never cuts off.
fun textToPdf(ctx:Context, title:String, body:String, fontSize:Float=12f, outFile:File): Boolean {
  return try {
    val doc = PdfDocument()
    val titlePaint = Paint().apply{ color=Color.rgb(0x14,0x53,0x2D); textSize=20f; typeface=Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    val bodyPaint = Paint().apply{ color=Color.BLACK; textSize=fontSize }
    val pagePaint = Paint().apply{ color=Color.GRAY; textSize=10f }
    // Word-wrap body into lines, then paginate: 40 lines per A4 page max
    val lines = mutableListOf<String>()
    body.split("\n").forEach{ para ->
      var line=""; para.split(" ").forEach{ w -> val t=if(line.isEmpty()) w else "$line $w"
        if(bodyPaint.measureText(t) > 515f){ lines.add(line); line=w } else line=t }
      if(line.isNotEmpty()) lines.add(line)
      lines.add("") // paragraph break
    }
    var idx=0; var pageNo=1
    while(idx < lines.size || pageNo==1){
      val info = PdfDocument.PageInfo.Builder(595,842,pageNo).create()
      val page = doc.startPage(info); val c = page.canvas; c.drawColor(Color.WHITE)
      var y=60f
      if(pageNo==1 && title.isNotBlank()){ c.drawText(title,40f,y,titlePaint); y+=32f }
      while(idx < lines.size && y < 780f){ c.drawText(lines[idx],40f,y,bodyPaint); y+= (fontSize+6f); idx++ }
      c.drawText("Page $pageNo", 270f, 820f, pagePaint)
      doc.finishPage(page); pageNo++
      if(idx>=lines.size) break
    }
    FileOutputStream(outFile).use{ doc.writeTo(it) }; doc.close(); true
  } catch(e:Exception){ e.printStackTrace(); false }
}

// Phase 6 — SEARCH INSIDE A PDF (Reader).
// NOTE: suspend, like pdfToText above — Android PdfRenderer has no
// text extraction, so each page is rendered and read with the same
// ML Kit OCR used by pdfToText. Returns 1-based page numbers whose
// OCR text contains the query (case-insensitive). The caller owns the
// PdfRenderer: it is NOT closed here. Slow on big PDFs — call from a
// background coroutine and show progress.
suspend fun searchInPdf(reader: PdfRenderer, query: String): List<Int> {
  if (query.isBlank()) return emptyList()
  val hits = mutableListOf<Int>()
  for (i in 0 until reader.pageCount) {
    try {
      val page = reader.openPage(i)
      val w = page.width
      val h = page.height
      val maxSide = maxOf(w, h)
      val scale = if (maxSide > 1600) 1600f / maxSide else 1.5f
      val bmp = Bitmap.createBitmap((w * scale).toInt(), (h * scale).toInt(), Bitmap.Config.ARGB_8888)
      bmp.eraseColor(Color.WHITE)
      page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
      page.close()
      val text = ocrBitmap(bmp)
      bmp.recycle()
      if (text.contains(query, ignoreCase = true)) hits.add(i + 1)
    } catch (e: Exception) {
      e.printStackTrace()
    }
  }
  return hits
}

// OCR for a Bitmap (same ML Kit client as ocrImage, which takes a Uri).
private suspend fun ocrBitmap(bmp: Bitmap): String = suspendCancellableCoroutine { cont ->
  try {
    val image = com.google.mlkit.vision.common.InputImage.fromBitmap(bmp, 0)
    com.google.mlkit.vision.text.TextRecognition
      .getClient(com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS)
      .process(image)
      .addOnSuccessListener { cont.resume(it.text) }
      .addOnFailureListener { cont.resume("") }
  } catch (e: Exception) {
    cont.resume("")
  }
}

// Phase 6 — BOOKMARKS (Reader). Tiny SharedPreferences store:
// one entry per file, a set of 1-based page numbers.
private fun bookmarkKey(fileName: String): String =
  "bm_" + fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")

fun saveBookmark(context: Context, fileName: String, page: Int) {
  val prefs = context.getSharedPreferences("chami_bookmarks", Context.MODE_PRIVATE)
  val key = bookmarkKey(fileName)
  val set = prefs.getStringSet(key, emptySet())?.toMutableSet() ?: mutableSetOf()
  set.add(page.toString())
  prefs.edit().putStringSet(key, set).apply()
}

fun getBookmarks(context: Context, fileName: String): List<Int> {
  val prefs = context.getSharedPreferences("chami_bookmarks", Context.MODE_PRIVATE)
  return prefs.getStringSet(bookmarkKey(fileName), emptySet())
    ?.mapNotNull { it.toIntOrNull() }
    ?.sorted()
    ?: emptyList()
}
