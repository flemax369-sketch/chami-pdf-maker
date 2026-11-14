package com.chami.pdfmaker.pdf
import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import java.io.*
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

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

// ------------------------------------------------------------------
// TOOL 15 - PDF TO TEXT, LAZY + CANCELLABLE (v1.2 engine fix).
// Android PdfRenderer has NO text extraction, so text still comes from
// per-page ML Kit OCR (works for scanned + digital PDFs) — there is no
// embedded-text path in this codebase. What changed:
//  * LAZY / PER-PAGE: pages are rendered + OCR'd one at a time, only
//    the pages asked for. Searching page 3 no longer OCRs all 50 pages.
//  * CANCELLABLE: every page checks ensureActive(); cancelling the
//    calling coroutine stops the pass at the next page boundary.
//  * PROGRESS: onProgress(done, total) after each page, so the UI can
//    show "Page 3 of 50" instead of a frozen screen.
//  * CACHED: extracted text is cached IN MEMORY per file (keyed by the
//    Uri string) in `textCache`, so a second search of the same file
//    reuses the text and is fast. The cache is memory-only — it is
//    gone when the process dies — and must be cleared with
//    clearTextCacheFor(uri) if the file behind a Uri is replaced.
//  * LANGUAGE: OCR uses OcrHelper's language-aware recogniser
//    ("Hindi" -> Devanagari, otherwise Latin); default English.
// If OCR returns empty for a page, that page reports "[No text found
// on this page - try OCR / this page may be an image]" — never fake
// blank as success.
// ------------------------------------------------------------------

// In-memory per-file text cache: Uri string -> (1-based page -> text).
// Synchronized map; values are plain strings, so no bitmap is held.
private val textCache: MutableMap<String, MutableMap<Int, String>> =
  java.util.Collections.synchronizedMap(mutableMapOf())

/** Cached text for one page, or null if that page has not been OCR'd yet. */
fun cachedPageText(pdfUri: Uri, page1Based: Int): String? =
  textCache[pdfUri.toString()]?.get(page1Based)

/** Drop the cached text for one file (call when the file is replaced). */
fun clearTextCacheFor(pdfUri: Uri) {
  textCache.remove(pdfUri.toString())
}

/** Drop all cached extracted text. */
fun clearTextCache() {
  textCache.clear()
}

// Render ONE page for OCR: up to 1600px on the long side, else 1.5x.
// Opens and closes its own PdfRenderer, so the caller never holds one.
private fun renderPageForOcr(ctx: Context, pdfUri: Uri, pageIndex0Based: Int): Bitmap? {
  return try {
    val pfd = ctx.contentResolver.openFileDescriptor(pdfUri, "r") ?: return null
    val renderer = PdfRenderer(pfd)
    try {
      if (pageIndex0Based !in 0 until renderer.pageCount) return null
      val page = renderer.openPage(pageIndex0Based)
      try {
        val w = page.width; val h = page.height
        val maxSide = maxOf(w, h)
        val scale = if (maxSide > 1600) 1600f / maxSide else 1.5f
        val bmp = Bitmap.createBitmap((w * scale).toInt(), (h * scale).toInt(), Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        bmp
      } finally {
        page.close()
      }
    } finally {
      renderer.close(); pfd.close()
    }
  } catch (_: Exception) { null }
}

/** Extract the text of ONE page (1-based), lazily, using the cache. */
suspend fun extractPageText(
  ctx: Context,
  pdfUri: Uri,
  page1Based: Int,
  language: String = "English"
): String {
  cachedPageText(pdfUri, page1Based)?.let { return it }
  coroutineContext.ensureActive()
  val bmp = renderPageForOcr(ctx, pdfUri, page1Based - 1) ?: return ""
  val text = try {
    ocrBitmap(bmp, language)
  } finally {
    bmp.recycle()
  }
  val trimmed = text.trim()
  textCache.getOrPut(pdfUri.toString()) { mutableMapOf() }[page1Based] = trimmed
  return trimmed
}

/**
 * Lazy per-page text extraction for a whole document (or a page subset).
 * Same "--- Page N ---" format as the old pdfToText, so existing callers
 * keep working. Renders + OCRs one page at a time, calls
 * onProgress(done, total) after each page, and throws
 * CancellationException promptly if the calling coroutine is cancelled.
 * A second call for the same file reuses the cache and is fast.
 */
suspend fun extractPdfTextLazy(
  ctx: Context,
  pdfUri: Uri,
  pages: Set<Int>? = null,
  language: String = "English",
  onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
): String {
  val sb = StringBuilder()
  try {
    val count = PdfEngine.pageCount(ctx, pdfUri)
    val wanted = (1..count).filter { pages == null || pages.contains(it) }
    var done = 0
    for (pageNo in wanted) {
      coroutineContext.ensureActive()
      val text = extractPageText(ctx, pdfUri, pageNo, language)
      sb.append("--- Page $pageNo ---\n")
      sb.append(if (text.isBlank()) "[No text found on this page - try OCR / this page may be an image]\n" else text + "\n")
      sb.append("\n")
      done++
      onProgress(done, wanted.size)
    }
  } catch (e: Exception) {
    // CancellationException is an Exception in Kotlin — rethrow it so
    // cancellation is never swallowed and reported as an error string.
    if (e is kotlinx.coroutines.CancellationException) throw e
    sb.append("Error: ${e.message}")
  }
  return sb.toString()
}

// TOOL 15 - PDF TO TEXT: kept signature. Now delegates to the lazy,
// cached, cancellable extractor above instead of rendering ALL pages
// to temp JPGs first and then OCRing them. Same output format.
suspend fun pdfToText(ctx:Context, pdfUri:Uri, pages:Set<Int>?=null): String =
  extractPdfTextLazy(ctx, pdfUri, pages)

/** Lazy, cached, cancellable search: returns 1-based pages whose OCR
 *  text contains the query (case-insensitive). Progress after each
 *  page; cancellation stops at the next page boundary. Prefer this over
 *  the PdfRenderer overload below — a repeat search hits the cache. */
suspend fun searchInPdfLazy(
  ctx: Context,
  pdfUri: Uri,
  query: String,
  language: String = "English",
  onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
): List<Int> {
  if (query.isBlank()) return emptyList()
  val count = PdfEngine.pageCount(ctx, pdfUri)
  val hits = mutableListOf<Int>()
  for (pageNo in 1..count) {
    coroutineContext.ensureActive()
    val text = extractPageText(ctx, pdfUri, pageNo, language)
    if (text.contains(query, ignoreCase = true)) hits.add(pageNo)
    onProgress(pageNo, count)
  }
  return hits
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

// Phase 6 — SEARCH INSIDE A PDF (Reader), kept signature for existing
// callers. The caller owns the PdfRenderer: it is NOT closed here.
// Now cancellable (checks ensureActive each page) and reports progress
// via onProgress(done, total). It cannot use the text cache — it has no
// Uri key — so prefer searchInPdfLazy(ctx, uri, query) for repeat
// searches of the same file. Slow on big PDFs: call from a background
// coroutine and show progress; do not call on the main thread.
suspend fun searchInPdf(
  reader: PdfRenderer,
  query: String,
  onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
): List<Int> {
  if (query.isBlank()) return emptyList()
  val hits = mutableListOf<Int>()
  for (i in 0 until reader.pageCount) {
    coroutineContext.ensureActive()
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
      if (e is kotlinx.coroutines.CancellationException) throw e
      e.printStackTrace()
    }
    onProgress(i + 1, reader.pageCount)
  }
  return hits
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
