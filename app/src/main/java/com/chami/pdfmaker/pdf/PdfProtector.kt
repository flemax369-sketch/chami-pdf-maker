package com.chami.pdfmaker.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import java.io.*
import java.security.MessageDigest
import java.util.UUID

object PdfProtector {

  private val PADDING = byteArrayOf(
    0x28.toByte(), 0xBF.toByte(), 0x4E.toByte(), 0x5E.toByte(), 0x4E.toByte(), 0x75.toByte(), 0x8A.toByte(), 0x41.toByte(),
    0x64.toByte(), 0x00.toByte(), 0x4E.toByte(), 0x56.toByte(), 0xFF.toByte(), 0xFA.toByte(), 0x01.toByte(), 0x08.toByte(),
    0x2E.toByte(), 0x2E.toByte(), 0x00.toByte(), 0xB6.toByte(), 0xD0.toByte(), 0x68.toByte(), 0x3E.toByte(), 0x80.toByte(),
    0x2F.toByte(), 0x0C.toByte(), 0xA9.toByte(), 0xFE.toByte(), 0x64.toByte(), 0x53.toByte(), 0x69.toByte(), 0x7A.toByte()
  )

  class RC4(key: ByteArray) {
    private val s = IntArray(256) { it }
    init {
      var k = 0
      for (i in 0 until 256) {
        k = (k + s[i] + (key[i % key.size].toInt() and 0xFF)) and 0xFF
        val t = s[i]; s[i] = s[k]; s[k] = t
      }
    }
    fun process(data: ByteArray): ByteArray {
      val out = ByteArray(data.size)
      var i = 0
      var j = 0
      val st = s.clone()
      for (idx in data.indices) {
        i = (i + 1) and 0xFF
        j = (j + st[i]) and 0xFF
        val t = st[i]; st[i] = st[j]; st[j] = t
        val k = (st[i] + st[j]) and 0xFF
        out[idx] = (data[idx].toInt() xor st[k]).toByte()
      }
      return out
    }
  }

  private fun md5(vararg chunks: ByteArray): ByteArray {
    val md = MessageDigest.getInstance("MD5")
    for (chunk in chunks) {
      md.update(chunk)
    }
    return md.digest()
  }

  private fun padPassword(pw: String): ByteArray {
    val b = pw.toByteArray(Charsets.ISO_8859_1)
    val out = ByteArray(32)
    val copyLen = minOf(b.size, 32)
    System.arraycopy(b, 0, out, 0, copyLen)
    if (copyLen < 32) {
      System.arraycopy(PADDING, 0, out, copyLen, 32 - copyLen)
    }
    return out
  }

  private fun toHex(bytes: ByteArray): String {
    val sb = StringBuilder()
    for (b in bytes) {
      sb.append(String.format("%02x", b.toInt() and 0xFF))
    }
    return sb.toString()
  }

  /**
   * Computes Standard PDF 1.4 encryption parameters (Algorithm 3.2 / 3.3 / 3.4).
   * Generates standard /Encrypt dictionary values compatible with Adobe Reader, Chrome, Google Drive.
   */
  fun computeEncryptionParams(userPassword: String, ownerPassword: String = "", documentId: ByteArray): EncryptionParams {
    val paddedUser = padPassword(userPassword)
    val paddedOwner = padPassword(ownerPassword.ifEmpty { userPassword })
    val pVal = -4 // Permissions: no restrictions other than open password
    val pBytes = byteArrayOf(
      (pVal and 0xFF).toByte(),
      ((pVal shr 8) and 0xFF).toByte(),
      ((pVal shr 16) and 0xFF).toByte(),
      ((pVal shr 24) and 0xFF).toByte()
    )

    // Algorithm 3.3: O entry
    val ownerHash = md5(paddedOwner)
    val ownerKey = ownerHash.copyOfRange(0, 5)
    val oEntry = RC4(ownerKey).process(paddedUser)

    // Algorithm 3.2: Encryption key
    val hash = md5(paddedUser, oEntry, pBytes, documentId)
    val encryptionKey = hash.copyOfRange(0, 5)

    // Algorithm 3.4: U entry
    val uEntry = RC4(encryptionKey).process(PADDING)

    return EncryptionParams(
      encryptionKey = encryptionKey,
      oHex = toHex(oEntry),
      uHex = toHex(uEntry),
      pVal = pVal,
      documentIdHex = toHex(documentId)
    )
  }

  data class EncryptionParams(
    val encryptionKey: ByteArray,
    val oHex: String,
    val uHex: String,
    val pVal: Int,
    val documentIdHex: String
  )

  fun objectKey(encKey: ByteArray, objNum: Int, genNum: Int = 0): ByteArray {
    val objBytes = byteArrayOf(
      (objNum and 0xFF).toByte(),
      ((objNum shr 8) and 0xFF).toByte(),
      ((objNum shr 16) and 0xFF).toByte(),
      (genNum and 0xFF).toByte(),
      ((genNum shr 8) and 0xFF).toByte()
    )
    val hash = md5(encKey, objBytes)
    return hash.copyOfRange(0, minOf(encKey.size + 5, 16))
  }

  /**
   * Encrypts and protects any PDF file with the specified password.
   * Compatible with all standard PDF viewers.
   */
  fun protectPdf(ctx: Context, inputUri: Uri, password: String, outFile: File, progressCallback: (Int, Int) -> Unit = { _, _ -> }): Boolean {
    val pfd = ctx.contentResolver.openFileDescriptor(inputUri, "r") ?: return false
    return try {
      val renderer = PdfRenderer(pfd)
      val pageCount = renderer.pageCount
      if (pageCount == 0) {
        renderer.close()
        pfd.close()
        return false
      }

      val docId = md5(UUID.randomUUID().toString().toByteArray())
      val enc = computeEncryptionParams(password, "", docId)

      val pageBitmaps = mutableListOf<ByteArray>()
      val pageDimensions = mutableListOf<Pair<Int, Int>>()

      for (i in 0 until pageCount) {
        val page = renderer.openPage(i)
        val w = page.width
        val h = page.height
        // Scale appropriately for crisp resolution (max side 1600)
        val maxSide = maxOf(w, h)
        val scale = if (maxSide > 1600) 1600f / maxSide else if (maxSide < 800) 1.5f else 1.0f
        val bw = (w * scale).toInt()
        val bh = (h * scale).toInt()

        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        page.close()

        val baos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, baos)
        bmp.recycle()
        pageBitmaps.add(baos.toByteArray())
        pageDimensions.add(Pair(w, h))
        progressCallback(i + 1, pageCount)
      }
      renderer.close()
      pfd.close()

      // Now build encrypted PDF
      writeEncryptedPdf(pageBitmaps, pageDimensions, enc, docId, outFile)
      true
    } catch (e: Exception) {
      e.printStackTrace()
      try { pfd.close() } catch (_: Exception) {}
      false
    }
  }

  /**
   * Creates an encrypted PDF directly from image URIs.
   */
  fun imagesToProtectedPdf(ctx: Context, uris: List<Uri>, password: String, outFile: File): Boolean {
    if (uris.isEmpty()) return false
    return try {
      val docId = md5(UUID.randomUUID().toString().toByteArray())
      val enc = computeEncryptionParams(password, "", docId)

      val pageBitmaps = mutableListOf<ByteArray>()
      val pageDimensions = mutableListOf<Pair<Int, Int>>()

      for (uri in uris) {
        val bmp = PdfEngine.loadBitmap(ctx, uri, 1800) ?: continue
        val baos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, baos)
        val w = bmp.width
        val h = bmp.height
        bmp.recycle()

        // Standard A4 dimensions 595x842 or proportional
        val a4w = 595
        val a4h = 842
        pageBitmaps.add(baos.toByteArray())
        pageDimensions.add(Pair(a4w, a4h))
      }

      if (pageBitmaps.isEmpty()) return false
      writeEncryptedPdf(pageBitmaps, pageDimensions, enc, docId, outFile)
      true
    } catch (e: Exception) {
      e.printStackTrace()
      false
    }
  }

  private fun writeEncryptedPdf(
    pageImages: List<ByteArray>,
    dimensions: List<Pair<Int, Int>>,
    enc: EncryptionParams,
    docId: ByteArray,
    outFile: File
  ) {
    val offsets = mutableListOf<Long>()
    var currentOffset = 0L

    val fos = FileOutputStream(outFile)
    val out = CountingOutputStream(fos)

    fun writeString(s: String) {
      val b = s.toByteArray(Charsets.ISO_8859_1)
      out.write(b)
    }

    fun startObj(objNum: Int) {
      offsets.add(out.count)
      writeString("$objNum 0 obj\n")
    }

    fun endObj() {
      writeString("endobj\n")
    }

    // Header
    writeString("%PDF-1.4\n%\u00e2\u00e3\u00cf\u00d3\n")

    val numPages = pageImages.size
    // Object numbering:
    // 1: Catalog
    // 2: Pages
    // 3..(2+numPages): Page objects
    // (3+numPages)..(2+2*numPages): Content streams
    // (3+2*numPages)..(2+3*numPages): Image XObjects
    // (3+3*numPages): Encrypt dictionary

    val catalogObj = 1
    val pagesObj = 2
    val firstPageObj = 3
    val firstContentObj = firstPageObj + numPages
    val firstImageObj = firstContentObj + numPages
    val encryptObj = firstImageObj + numPages

    // 1: Catalog
    startObj(catalogObj)
    writeString("<< /Type /Catalog /Pages $pagesObj 0 R >>\n")
    endObj()

    // 2: Pages
    startObj(pagesObj)
    val kidsStr = (0 until numPages).joinToString(" ") { "${firstPageObj + it} 0 R" }
    writeString("<< /Type /Pages /Kids [$kidsStr] /Count $numPages >>\n")
    endObj()

    // Page objects
    for (i in 0 until numPages) {
      val pObj = firstPageObj + i
      val cObj = firstContentObj + i
      val imgObj = firstImageObj + i
      val (pw, ph) = dimensions[i]

      startObj(pObj)
      writeString(
        "<< /Type /Page /Parent $pagesObj 0 R /MediaBox [0 0 $pw $ph] " +
          "/Contents $cObj 0 R /Resources << /ProcSet [/PDF /ImageC] /XObject << /Im1 $imgObj 0 R >> >> >>\n"
      )
      endObj()
    }

    // Content streams (draw image to fit page)
    for (i in 0 until numPages) {
      val cObj = firstContentObj + i
      val (pw, ph) = dimensions[i]
      val rawContent = "q\n$pw 0 0 $ph 0 0 cm\n/Im1 Do\nQ\n".toByteArray(Charsets.ISO_8859_1)

      // Encrypt content stream
      val key = objectKey(enc.encryptionKey, cObj)
      val encContent = RC4(key).process(rawContent)

      startObj(cObj)
      writeString("<< /Length ${encContent.size} >>\nstream\n")
      out.write(encContent)
      writeString("\nendstream\n")
      endObj()
    }

    // Image XObjects
    for (i in 0 until numPages) {
      val imgObj = firstImageObj + i
      val rawJpg = pageImages[i]

      val key = objectKey(enc.encryptionKey, imgObj)
      val encJpg = RC4(key).process(rawJpg)

      val (pw, ph) = dimensions[i]
      startObj(imgObj)
      writeString(
        "<< /Type /XObject /Subtype /Image /Width $pw /Height $ph " +
          "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${encJpg.size} >>\nstream\n"
      )
      out.write(encJpg)
      writeString("\nendstream\n")
      endObj()
    }

    // Encrypt dictionary
    startObj(encryptObj)
    writeString(
      "<< /Filter /Standard /V 1 /R 2 /O <${enc.oHex}> /U <${enc.uHex}> /P ${enc.pVal} >>\n"
    )
    endObj()

    // Cross-reference table
    val startXref = out.count
    val totalObjs = encryptObj + 1
    writeString("xref\n0 $totalObjs\n")
    writeString("0000000000 65535 f \n")
    for (offset in offsets) {
      writeString(String.format("%010d 00000 n \n", offset))
    }

    // Trailer
    val idHex = toHex(docId)
    writeString("trailer\n<< /Size $totalObjs /Root $catalogObj 0 R /Encrypt $encryptObj 0 R /ID [<$idHex> <$idHex>] >>\n")
    writeString("startxref\n$startXref\n%%EOF\n")

    out.flush()
    fos.close()
  }

  private class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
    var count = 0L
      private set

    override fun write(b: Int) {
      super.write(b)
      count++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
      out.write(b, off, len)
      count += len
    }
  }

  /**
   * Verifies if a user password unlocks an encrypted PDF file.
   */
  fun verifyPassword(pdfFile: File, candidatePassword: String): Boolean {
    return try {
      val text = pdfFile.inputStream().bufferedReader(Charsets.ISO_8859_1).use { it.readText() }
      val oMatch = Regex("""/O\s*<([0-9a-fA-F]{64})>""").find(text)
      val uMatch = Regex("""/U\s*<([0-9a-fA-F]{64})>""").find(text)
      val pMatch = Regex("""/P\s*(-?[0-9]+)""").find(text)
      val idMatch = Regex("""/ID\s*\[\s*<([0-9a-fA-F]+)>""").find(text)

      if (oMatch == null || uMatch == null || pMatch == null || idMatch == null) {
        return false
      }

      val oHex = oMatch.groupValues[1]
      val uHex = uMatch.groupValues[1]
      val pVal = pMatch.groupValues[1].toInt()
      val idHex = idMatch.groupValues[1]

      val oBytes = hexToBytes(oHex)
      val idBytes = hexToBytes(idHex)
      val paddedUser = padPassword(candidatePassword)
      val pBytes = byteArrayOf(
        (pVal and 0xFF).toByte(),
        ((pVal shr 8) and 0xFF).toByte(),
        ((pVal shr 16) and 0xFF).toByte(),
        ((pVal shr 24) and 0xFF).toByte()
      )

      val hash = md5(paddedUser, oBytes, pBytes, idBytes)
      val key = hash.copyOfRange(0, 5)
      val computedU = RC4(key).process(PADDING)

      toHex(computedU).equals(uHex, ignoreCase = true)
    } catch (e: Exception) {
      false
    }
  }

  private fun hexToBytes(hex: String): ByteArray {
    val len = hex.length
    val data = ByteArray(len / 2)
    var i = 0
    while (i < len) {
      data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
      i += 2
    }
    return data
  }
}
