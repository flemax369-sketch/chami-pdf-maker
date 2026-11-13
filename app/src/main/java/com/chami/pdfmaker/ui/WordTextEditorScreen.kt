package com.chami.pdfmaker.ui

import android.app.Activity
import android.content.Context
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chami.pdfmaker.pdf.DocAlign
import com.chami.pdfmaker.pdf.DocBlock
import com.chami.pdfmaker.pdf.DocBlockType
import com.chami.pdfmaker.pdf.PdfEngine
import com.chami.pdfmaker.pdf.RichTextPdf
import com.chami.pdfmaker.pdf.SaveLocation
import com.chami.pdfmaker.theme.ChamiTheme
import com.chami.pdfmaker.theme.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val DRAFT_PREFS = "chami_text_draft"
private const val DRAFT_KEY = "chami_text_draft"

private fun loadDraft(ctx: Context): List<DocBlock> {
  val saved = ctx.getSharedPreferences(DRAFT_PREFS, Context.MODE_PRIVATE)
    .getString(DRAFT_KEY, null)
  return RichTextPdf.decodeBlocks(saved)
}

private fun saveDraft(ctx: Context, blocks: List<DocBlock>) {
  ctx.getSharedPreferences(DRAFT_PREFS, Context.MODE_PRIVATE).edit()
    .putString(DRAFT_KEY, RichTextPdf.encodeBlocks(blocks)).apply()
}

@Composable
fun ToolChip(label: String, active: Boolean, theme: ChamiTheme, onClick: () -> Unit) {
  Box(
    Modifier
      .clip(RoundedCornerShape(6.dp))
      .background(if (active) theme.primary else theme.card)
      .border(1.dp, theme.cardBorder, RoundedCornerShape(6.dp))
      .clickable { onClick() }
      .padding(horizontal = 10.dp, vertical = 7.dp),
    contentAlignment = Alignment.Center
  ) {
    Text(
      label,
      fontFamily = PixelFont,
      fontSize = 12.sp,
      fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
      color = if (active) theme.textOnPrimary else theme.text
    )
  }
}

@Composable
fun WordTextEditorScreen(theme: ChamiTheme, onBack: () -> Unit) {
  val ctx = LocalContext.current
  val activity = ctx as Activity
  val haptic = LocalHapticFeedback.current
  val tm = remember { ThemeManager(ctx) }
  val scope = rememberCoroutineScope()

  var blocks by remember { mutableStateOf(loadDraft(ctx)) }
  var selected by remember { mutableStateOf(0) }
  var undoStack by remember { mutableStateOf<List<List<DocBlock>>>(emptyList()) }
  var redoStack by remember { mutableStateOf<List<List<DocBlock>>>(emptyList()) }
  var findText by remember { mutableStateOf("") }
  var replaceText by remember { mutableStateOf("") }
  var matchInfo by remember { mutableStateOf("") }
  var showPageNumbers by remember { mutableStateOf(true) }
  var showHeader by remember { mutableStateOf(false) }
  var headerText by remember { mutableStateOf("") }
  var footerText by remember { mutableStateOf("") }
  var isProcessing by remember { mutableStateOf(false) }
  var resultFile by remember { mutableStateOf<File?>(null) }

  fun vibrate() {
    if (tm.hapticOn()) {
      try {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
      } catch (_: Exception) {}
    }
  }

  // Autosave draft whenever the document changes.
  LaunchedEffect(blocks) { saveDraft(ctx, blocks) }

  // Structural change: push the OLD document onto undo, clear redo.
  // Typing does NOT call commit — typing edits the block in place so
  // undo does not fill up with one entry per keystroke.
  fun commit(newBlocks: List<DocBlock>, newSelected: Int = selected) {
    undoStack = (undoStack + listOf(blocks)).takeLast(50)
    redoStack = emptyList()
    blocks = newBlocks
    selected = newSelected.coerceIn(0, (newBlocks.size - 1).coerceAtLeast(0))
  }

  fun updateSelected(transform: (DocBlock) -> DocBlock) {
    if (selected !in blocks.indices) return
    blocks = blocks.toMutableList().also { it[selected] = transform(it[selected]) }
  }

  fun undo() {
    if (undoStack.isEmpty()) return
    redoStack = redoStack + listOf(blocks)
    blocks = undoStack.last()
    undoStack = undoStack.dropLast(1)
    selected = selected.coerceIn(0, (blocks.size - 1).coerceAtLeast(0))
  }

  fun redo() {
    if (redoStack.isEmpty()) return
    undoStack = undoStack + listOf(blocks)
    blocks = redoStack.last()
    redoStack = redoStack.dropLast(1)
    selected = selected.coerceIn(0, (blocks.size - 1).coerceAtLeast(0))
  }

  fun findNext() {
    if (findText.isBlank()) {
      matchInfo = "Type a word to find"
      return
    }
    val idx = blocks.indexOfFirst { it.text.contains(findText, ignoreCase = true) }
    if (idx >= 0) {
      selected = idx
      matchInfo = "Found in block ${idx + 1} of ${blocks.size}"
    } else {
      matchInfo = "Not found"
    }
  }

  fun replaceAll() {
    if (findText.isBlank()) return
    var count = 0
    val replaced = blocks.map { b ->
      var t = b.text
      var start = 0
      while (true) {
        val i = t.indexOf(findText, start, ignoreCase = true)
        if (i < 0) break
        count++
        t = t.substring(0, i) + replaceText + t.substring(i + findText.length)
        start = i + replaceText.length
      }
      b.copy(text = t)
    }
    commit(replaced)
    matchInfo = "Replaced $count time(s)"
  }

  val importLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.GetContent()
  ) { uri: Uri? ->
    if (uri == null) return@rememberLauncherForActivityResult
    scope.launch(Dispatchers.IO) {
      val text = try {
        ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
      } catch (_: Exception) {
        ""
      }
      if (text.isBlank()) {
        withContext(Dispatchers.Main) {
          Toast.makeText(ctx, "Empty file or could not read it", Toast.LENGTH_SHORT).show()
        }
        return@launch
      }
      val imported = text.split("\n\n").map { it.trim() }.filter { it.isNotEmpty() }
        .map { DocBlock(type = DocBlockType.BODY, text = it, fontSize = 12f) }
      withContext(Dispatchers.Main) {
        if (imported.isNotEmpty()) commit(imported, 0)
      }
    }
  }

  val saveLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.CreateDocument("application/pdf")
  ) { uri: Uri? ->
    if (uri != null && resultFile != null) {
      if (SaveLocation.copyToUri(ctx, resultFile!!, uri)) {
        Toast.makeText(ctx, "Saved to chosen folder", Toast.LENGTH_SHORT).show()
      } else {
        Toast.makeText(ctx, "Could not save there", Toast.LENGTH_SHORT).show()
      }
    }
  }

  val sel = blocks.getOrNull(selected)
  val wordCount = blocks.sumOf { it.text.split("\\s+".toRegex()).count { w -> w.isNotBlank() } }
  val charCount = blocks.sumOf { it.text.length }
  val previewPages = remember(blocks) { RichTextPdf.paginate(blocks) }
  val colours = listOf(
    "Black" to AndroidColor.BLACK,
    "Green" to AndroidColor.rgb(0x14, 0x53, 0x2D),
    "Red" to AndroidColor.RED,
    "Blue" to AndroidColor.BLUE,
    "Grey" to AndroidColor.GRAY
  )

  ToolScreenContainer("Text to PDF", theme, onBack) {
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
      Text(
        "Word-like document editor. Each paragraph is one block — tap a block, then style it. Export is a formatted PDF.",
        fontFamily = PixelFont, fontSize = 12.sp, color = theme.text
      )
      Spacer(Modifier.height(8.dp))
      Text(
        "$wordCount words  •  $charCount characters  •  ${blocks.size} blocks  •  ${previewPages.size} pages",
        fontFamily = PixelFont, fontSize = 11.sp, color = theme.primaryDark
      )
      Spacer(Modifier.height(12.dp))

      // ---- Formatting toolbar (applies to the selected block) ----
      Text("STYLE — BLOCK ${selected + 1}", fontFamily = PixelFont, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = theme.primaryDark)
      Spacer(Modifier.height(6.dp))
      Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ToolChip("B", sel?.bold == true, theme) { updateSelected { it.copy(bold = !it.bold) } }
        ToolChip("I", sel?.italic == true, theme) { updateSelected { it.copy(italic = !it.italic) } }
        ToolChip("U", sel?.underline == true, theme) { updateSelected { it.copy(underline = !it.underline) } }
        ToolChip("LEFT", sel?.align == DocAlign.LEFT, theme) { updateSelected { it.copy(align = DocAlign.LEFT) } }
        ToolChip("CENTRE", sel?.align == DocAlign.CENTER, theme) { updateSelected { it.copy(align = DocAlign.CENTER) } }
        ToolChip("RIGHT", sel?.align == DocAlign.RIGHT, theme) { updateSelected { it.copy(align = DocAlign.RIGHT) } }
      }
      Spacer(Modifier.height(6.dp))
      Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ToolChip("Title", sel?.type == DocBlockType.TITLE, theme) {
          updateSelected { it.copy(type = DocBlockType.TITLE, fontSize = RichTextPdf.defaultSizeFor(DocBlockType.TITLE, it.fontSize), bold = true) }
        }
        ToolChip("H1", sel?.type == DocBlockType.H1, theme) {
          updateSelected { it.copy(type = DocBlockType.H1, fontSize = RichTextPdf.defaultSizeFor(DocBlockType.H1, it.fontSize), bold = true) }
        }
        ToolChip("H2", sel?.type == DocBlockType.H2, theme) {
          updateSelected { it.copy(type = DocBlockType.H2, fontSize = RichTextPdf.defaultSizeFor(DocBlockType.H2, it.fontSize), bold = true) }
        }
        ToolChip("H3", sel?.type == DocBlockType.H3, theme) {
          updateSelected { it.copy(type = DocBlockType.H3, fontSize = RichTextPdf.defaultSizeFor(DocBlockType.H3, it.fontSize), bold = true) }
        }
        ToolChip("Body", sel?.type == DocBlockType.BODY, theme) {
          updateSelected { it.copy(type = DocBlockType.BODY) }
        }
        ToolChip("• Bullet", sel?.type == DocBlockType.BULLET, theme) {
          updateSelected { it.copy(type = DocBlockType.BULLET) }
        }
        ToolChip("1. Number", sel?.type == DocBlockType.NUMBER, theme) {
          updateSelected { it.copy(type = DocBlockType.NUMBER) }
        }
      }
      Spacer(Modifier.height(6.dp))
      Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(10f, 12f, 14f, 16f, 18f).forEach { sz ->
          ToolChip("${sz.toInt()}pt", sel?.fontSize == sz, theme) {
            updateSelected { it.copy(fontSize = sz) }
          }
        }
        colours.forEach { (name, value) ->
          Box(
            Modifier
              .size(30.dp)
              .clip(RoundedCornerShape(15.dp))
              .background(Color(value))
              .border(
                if (sel?.colour == value) 3.dp else 1.dp,
                if (sel?.colour == value) theme.primary else theme.cardBorder,
                RoundedCornerShape(15.dp)
              )
              .clickable { updateSelected { it.copy(colour = value) } },
            contentAlignment = Alignment.Center
          ) {
            if (sel?.colour == value) Text("✓", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
          }
        }
        Text("Colour: ${colours.firstOrNull { it.second == sel?.colour }?.first ?: ""}", fontFamily = PixelFont, fontSize = 10.sp, color = theme.textSecondary)
      }

      Spacer(Modifier.height(14.dp))

      // ---- Blocks ----
      blocks.forEachIndexed { index, block ->
        val isSel = index == selected
        Column(
          Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(theme.card)
            .border(
              if (isSel) 2.dp else 1.dp,
              if (isSel) theme.primary else theme.cardBorder,
              RoundedCornerShape(10.dp)
            )
            .clickable { selected = index }
            .padding(10.dp)
        ) {
          Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
              "BLOCK ${index + 1} — ${block.type.name}",
              fontFamily = PixelFont, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = theme.primaryDark,
              modifier = Modifier.weight(1f)
            )
            if (blocks.size > 1) {
              Text(
                "✕ DELETE",
                fontFamily = PixelFont, fontSize = 11.sp, color = theme.textSecondary,
                modifier = Modifier.clickable {
                  vibrate()
                  val next = blocks.toMutableList().apply { removeAt(index) }
                  commit(next, (index - 1).coerceAtLeast(0))
                }
              )
            }
          }
          Spacer(Modifier.height(6.dp))
          OutlinedTextField(
            value = block.text,
            onValueChange = { v ->
              selected = index
              blocks = blocks.toMutableList().also { it[index] = it[index].copy(text = v) }
            },
            placeholder = { Text("Type here…", fontFamily = PixelFont, fontSize = 11.sp) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 8
          )
        }
        Spacer(Modifier.height(8.dp))
      }

      Button(
        onClick = {
          vibrate()
          val next = blocks.toMutableList().apply {
            add(selected + 1, DocBlock(type = DocBlockType.BODY, fontSize = 12f))
          }
          commit(next, selected + 1)
        },
        modifier = Modifier.fillMaxWidth().height(44.dp),
        colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
        shape = RoundedCornerShape(8.dp)
      ) {
        Text("+ ADD BLOCK", fontFamily = PixelFont, color = theme.textOnPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
      }

      Spacer(Modifier.height(14.dp))

      // ---- Find & Replace ----
      Text("FIND & REPLACE", fontFamily = PixelFont, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = theme.primaryDark)
      Spacer(Modifier.height(6.dp))
      OutlinedTextField(
        value = findText,
        onValueChange = { findText = it },
        label = { Text("Find…", fontFamily = PixelFont, fontSize = 10.sp) },
        modifier = Modifier.fillMaxWidth(), singleLine = true
      )
      Spacer(Modifier.height(6.dp))
      OutlinedTextField(
        value = replaceText,
        onValueChange = { replaceText = it },
        label = { Text("Replace with…", fontFamily = PixelFont, fontSize = 10.sp) },
        modifier = Modifier.fillMaxWidth(), singleLine = true
      )
      Spacer(Modifier.height(6.dp))
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
          onClick = { vibrate(); findNext() },
          modifier = Modifier.weight(1f).height(42.dp),
          shape = RoundedCornerShape(8.dp)
        ) {
          Text("FIND", fontFamily = PixelFont, color = theme.text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        OutlinedButton(
          onClick = { vibrate(); replaceAll() },
          modifier = Modifier.weight(1f).height(42.dp),
          shape = RoundedCornerShape(8.dp)
        ) {
          Text("REPLACE ALL", fontFamily = PixelFont, color = theme.text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
      }
      if (matchInfo.isNotBlank()) {
        Spacer(Modifier.height(4.dp))
        Text(matchInfo, fontFamily = PixelFont, fontSize = 11.sp, color = theme.primaryDark)
      }

      Spacer(Modifier.height(14.dp))

      // ---- Undo / Redo / Import / Clear draft ----
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
          onClick = { vibrate(); undo() },
          enabled = undoStack.isNotEmpty(),
          modifier = Modifier.weight(1f).height(42.dp),
          shape = RoundedCornerShape(8.dp)
        ) {
          Text("↩ UNDO", fontFamily = PixelFont, color = theme.text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        OutlinedButton(
          onClick = { vibrate(); redo() },
          enabled = redoStack.isNotEmpty(),
          modifier = Modifier.weight(1f).height(42.dp),
          shape = RoundedCornerShape(8.dp)
        ) {
          Text("REDO ↪", fontFamily = PixelFont, color = theme.text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
      }
      Spacer(Modifier.height(6.dp))
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
          onClick = { vibrate(); importLauncher.launch("text/plain") },
          modifier = Modifier.weight(1f).height(42.dp),
          shape = RoundedCornerShape(8.dp)
        ) {
          Text("IMPORT .TXT", fontFamily = PixelFont, color = theme.text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        OutlinedButton(
          onClick = {
            vibrate()
            commit(listOf(DocBlock()), 0)
            Toast.makeText(ctx, "Draft cleared", Toast.LENGTH_SHORT).show()
          },
          modifier = Modifier.weight(1f).height(42.dp),
          shape = RoundedCornerShape(8.dp)
        ) {
          Text("CLEAR DRAFT", fontFamily = PixelFont, color = theme.text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
      }
      Spacer(Modifier.height(4.dp))
      Text(
        "Draft saves itself — closing the app does not lose your text. Import replaces the current document.",
        fontFamily = PixelFont, fontSize = 10.sp, color = theme.textSecondary
      )

      Spacer(Modifier.height(14.dp))

      // ---- Header / footer / page numbers ----
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Page numbers", fontFamily = PixelFont, fontSize = 12.sp, color = theme.text, modifier = Modifier.weight(1f))
        Switch(checked = showPageNumbers, onCheckedChange = { showPageNumbers = it })
      }
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Header / footer", fontFamily = PixelFont, fontSize = 12.sp, color = theme.text, modifier = Modifier.weight(1f))
        Switch(checked = showHeader, onCheckedChange = { showHeader = it })
      }
      if (showHeader) {
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
          value = headerText,
          onValueChange = { headerText = it },
          label = { Text("Header text (top of every page)", fontFamily = PixelFont, fontSize = 10.sp) },
          modifier = Modifier.fillMaxWidth(), singleLine = true
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
          value = footerText,
          onValueChange = { footerText = it },
          label = { Text("Footer text (bottom of every page)", fontFamily = PixelFont, fontSize = 10.sp) },
          modifier = Modifier.fillMaxWidth(), singleLine = true
        )
      }

      Spacer(Modifier.height(14.dp))

      // ---- A4 preview — ALL pages, same layout as the PDF ----
      Text(
        "A4 PREVIEW — ALL PAGES (${previewPages.size}):",
        fontFamily = PixelFont, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = theme.primaryDark
      )
      Spacer(Modifier.height(6.dp))
      previewPages.forEachIndexed { pageIndex, lines ->
        Column(
          Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White)
            .border(1.dp, theme.cardBorder, RoundedCornerShape(8.dp))
            .padding(14.dp)
        ) {
          for (rich in lines) {
            Text(
              text = rich.text.ifBlank { " " },
              fontSize = rich.block.fontSize.sp,
              fontWeight = if (rich.block.bold) FontWeight.Bold else FontWeight.Normal,
              fontStyle = if (rich.block.italic) FontStyle.Italic else FontStyle.Normal,
              textDecoration = if (rich.block.underline) TextDecoration.Underline else TextDecoration.None,
              color = Color(rich.block.colour),
              textAlign = when (rich.block.align) {
                DocAlign.LEFT -> TextAlign.Left
                DocAlign.CENTER -> TextAlign.Center
                DocAlign.RIGHT -> TextAlign.Right
              },
              modifier = Modifier.fillMaxWidth()
            )
          }
          Spacer(Modifier.height(8.dp))
          Text("Page ${pageIndex + 1}", fontFamily = PixelFont, fontSize = 9.sp, color = Color.Gray)
        }
        Spacer(Modifier.height(8.dp))
      }

      Spacer(Modifier.height(8.dp))
      if (isProcessing) {
        LinearProgressIndicator(Modifier.fillMaxWidth(), color = theme.primary)
        Spacer(Modifier.height(8.dp))
      }

      resultFile?.let { file ->
        ResultCard(file, theme)
        Spacer(Modifier.height(8.dp))
        Button(
          onClick = { saveLauncher.launch(file.name) },
          modifier = Modifier.fillMaxWidth().height(48.dp),
          colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
          shape = RoundedCornerShape(8.dp)
        ) {
          Text("SAVE TO FOLDER…", fontFamily = PixelFont, color = theme.textOnPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
      }
    }

    Button(
      onClick = {
        vibrate()
        if (blocks.none { it.text.isNotBlank() }) {
          Toast.makeText(ctx, "Please enter some text", Toast.LENGTH_SHORT).show()
          return@Button
        }
        // Phase 1 ad hook: the PDF is built AFTER the ad closes (or
        // immediately if no ad is ready). The save is never blocked.
        // Phase 6: ads removed — build directly, no interstitial.
        run {
          isProcessing = true
          scope.launch {
            val out = PdfEngine.outFile(ctx, "Doc_${System.currentTimeMillis()}.pdf")
            val ok = withContext(Dispatchers.IO) {
              RichTextPdf.renderDocToPdf(
                blocks, out, showPageNumbers,
                if (showHeader) headerText else "",
                if (showHeader) footerText else ""
              )
            }
            isProcessing = false
            if (ok) resultFile = out
            else Toast.makeText(ctx, "Failed to create PDF", Toast.LENGTH_SHORT).show()
          }
        }
      },
      enabled = !isProcessing && blocks.any { it.text.isNotBlank() },
      modifier = Modifier.fillMaxWidth().height(56.dp),
      colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
      shape = RoundedCornerShape(8.dp)
    ) {
      Text("EXPORT PDF", fontFamily = PixelFont, fontWeight = FontWeight.Black, fontSize = 14.sp, color = theme.textOnPrimary)
    }
  }
}
