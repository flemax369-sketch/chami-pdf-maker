package com.chami.pdfmaker.pdf

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.util.Base64
import java.io.File
import java.io.FileOutputStream

// HONEST NOTE: this is a block editor, not full Word .docx. Each block
// is one paragraph with one style. Export is formatted PDF only —
// there is no .docx export and no .docx import.
enum class DocAlign { LEFT, CENTER, RIGHT }

enum class DocBlockType { TITLE, H1, H2, H3, BODY, BULLET, NUMBER }

data class DocBlock(
  val type: DocBlockType = DocBlockType.BODY,
  val text: String = "",
  val bold: Boolean = false,
  val italic: Boolean = false,
  val underline: Boolean = false,
  val align: DocAlign = DocAlign.LEFT,
  val fontSize: Float = 12f,
  val colour: Int = Color.BLACK
)

// One laid-out line. `block` is the same object for every line of one
// block, so the renderer can tell where a block ends.
data class RichLine(val text: String, val block: DocBlock)

object RichTextPdf {
  const val PAGE_W = 595
  const val PAGE_H = 842
  const val MARGIN = 40f
  const val TEXT_W = 515f
  const val TOP_Y = 64f
  const val BOTTOM_Y = 780f

  fun defaultSizeFor(type: DocBlockType, current: Float): Float = when (type) {
    DocBlockType.TITLE -> 22f
    DocBlockType.H1 -> 20f
    DocBlockType.H2 -> 17f
    DocBlockType.H3 -> 15f
    else -> current
  }

  private fun paintFor(block: DocBlock): Paint {
    val style = when {
      block.bold && block.italic -> Typeface.BOLD_ITALIC
      block.bold -> Typeface.BOLD
      block.italic -> Typeface.ITALIC
      else -> Typeface.NORMAL
    }
    return Paint().apply {
      color = block.colour
      textSize = block.fontSize
      typeface = Typeface.create(Typeface.DEFAULT, style)
      isUnderlineText = block.underline
      isAntiAlias = true
    }
  }

  private fun blockSpacing(block: DocBlock): Float = when (block.type) {
    DocBlockType.TITLE -> 16f
    DocBlockType.H1 -> 12f
    DocBlockType.H2, DocBlockType.H3 -> 10f
    else -> 6f
  }

  private fun wrapText(text: String, paint: Paint): List<String> {
    val out = mutableListOf<String>()
    if (text.isEmpty()) {
      out.add("")
      return out
    }
    for (para in text.split("\n")) {
      var line = ""
      for (word in para.split(" ")) {
        val trial = if (line.isEmpty()) word else "$line $word"
        if (paint.measureText(trial) > TEXT_W && line.isNotEmpty()) {
          out.add(line)
          line = word
        } else {
          line = trial
        }
      }
      out.add(line)
    }
    return out
  }

  // Split blocks into pages of lines. This ONE function feeds both the
  // PDF export and the on-screen preview, so page breaks match.
  fun paginate(blocks: List<DocBlock>): List<List<RichLine>> {
    val pages = mutableListOf<MutableList<RichLine>>()
    var page = mutableListOf<RichLine>()
    var y = TOP_Y
    var number = 0
    val safe = if (blocks.isEmpty()) listOf(DocBlock()) else blocks
    for (block in safe) {
      if (block.type == DocBlockType.NUMBER) number++ else number = 0
      val paint = paintFor(block)
      val prefix = when (block.type) {
        DocBlockType.BULLET -> "\u2022  "
        DocBlockType.NUMBER -> "$number.  "
        else -> ""
      }
      val lines = wrapText(prefix + block.text, paint)
      for (line in lines) {
        val lineH = block.fontSize + 8f
        if (y + lineH > BOTTOM_Y) {
          pages.add(page)
          page = mutableListOf()
          y = TOP_Y
        }
        page.add(RichLine(line, block))
        y += lineH
      }
      y += blockSpacing(block)
      if (y > BOTTOM_Y && page.isNotEmpty()) {
        pages.add(page)
        page = mutableListOf()
        y = TOP_Y
      }
    }
    if (page.isNotEmpty() || pages.isEmpty()) pages.add(page)
    return pages
  }

  fun countPages(blocks: List<DocBlock>): Int = paginate(blocks).size

  fun renderDocToPdf(
    blocks: List<DocBlock>,
    outFile: File,
    showPageNumbers: Boolean = true,
    headerText: String = "",
    footerText: String = ""
  ): Boolean {
    return try {
      val pages = paginate(blocks)
      val doc = PdfDocument()
      val smallPaint = Paint().apply {
        color = Color.GRAY
        textSize = 10f
        textAlign = Paint.Align.CENTER
      }
      pages.forEachIndexed { index, lines ->
        val info = PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, index + 1).create()
        val page = doc.startPage(info)
        val c = page.canvas
        c.drawColor(Color.WHITE)
        if (headerText.isNotBlank()) {
          c.drawText(headerText, 297.5f, 36f, smallPaint)
        }
        var y = TOP_Y
        for ((li, rich) in lines.withIndex()) {
          val paint = paintFor(rich.block)
          val x = when (rich.block.align) {
            DocAlign.LEFT -> MARGIN
            DocAlign.CENTER -> (PAGE_W - paint.measureText(rich.text)) / 2f
            DocAlign.RIGHT -> PAGE_W - MARGIN - paint.measureText(rich.text)
          }
          c.drawText(rich.text, x, y, paint)
          y += rich.block.fontSize + 8f
          val next = lines.getOrNull(li + 1)
          if (next == null || next.block !== rich.block) y += blockSpacing(rich.block)
        }
        if (footerText.isNotBlank()) {
          c.drawText(footerText, 297.5f, 806f, smallPaint)
        }
        if (showPageNumbers) {
          c.drawText("Page ${index + 1}", 297.5f, 820f, smallPaint)
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

  // Draft save / load. Text is Base64 so any characters survive.
  // One record per block, fields joined by \u0001, records by \u0002.
  fun encodeBlocks(blocks: List<DocBlock>): String {
    return blocks.joinToString("\u0002") { b ->
      listOf(
        b.type.name,
        b.text.encodeBase64(),
        b.bold.toString(),
        b.italic.toString(),
        b.underline.toString(),
        b.align.name,
        b.fontSize.toString(),
        b.colour.toString()
      ).joinToString("\u0001")
    }
  }

  fun decodeBlocks(saved: String?): List<DocBlock> {
    if (saved.isNullOrBlank()) return listOf(DocBlock())
    return try {
      saved.split("\u0002").mapNotNull { rec ->
        val f = rec.split("\u0001")
        if (f.size != 8) return@mapNotNull null
        DocBlock(
          type = DocBlockType.valueOf(f[0]),
          text = f[1].decodeBase64(),
          bold = f[2].toBoolean(),
          italic = f[3].toBoolean(),
          underline = f[4].toBoolean(),
          align = DocAlign.valueOf(f[5]),
          fontSize = f[6].toFloat(),
          colour = f[7].toInt()
        )
      }.ifEmpty { listOf(DocBlock()) }
    } catch (_: Exception) {
      listOf(DocBlock())
    }
  }

  private fun String.encodeBase64(): String =
    Base64.encodeToString(toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

  private fun String.decodeBase64(): String =
    String(Base64.decode(this, Base64.DEFAULT), Charsets.UTF_8)
}
