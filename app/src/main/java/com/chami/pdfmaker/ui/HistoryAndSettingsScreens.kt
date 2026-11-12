package com.chami.pdfmaker.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.chami.pdfmaker.pdf.PdfEngine
import com.chami.pdfmaker.pdf.PdfReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.webkit.WebView
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.chami.pdfmaker.pdf.ShareHelper
import com.chami.pdfmaker.theme.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Phase 4, point 6: History tab — search, filter by tool, sort, first-page
// thumbnails, date groups, multi-select, rename, favourites, storage used.
// Tool is inferred from the file-name prefix the app itself writes
// (Chami_ = Images to PDF, Scan_ = Scan, Split_ = Split, Merged_ = Merge,
// Compress_/Compressed_ = Compress, Resized_ = Resizer, protected_ = Protect).
// There is no per-file tool record in v1.2, so files with an unknown prefix
// only appear under "All". Before→after KB is NOT shown: no record of the
// original size exists for a saved file, so only the current size is shown.

private sealed class HistoryEntry {
  data class Header(val title: String) : HistoryEntry()
  data class FileRow(val file: File) : HistoryEntry()
}

private fun historyToolOf(file: File): String {
  val n = file.name
  return when {
    n.startsWith("Chami_") -> "Images to PDF"
    n.startsWith("Scan_") -> "Scan"
    n.startsWith("Split_") -> "Split"
    n.startsWith("Merged_") -> "Merge"
    n.startsWith("Compress_") || n.startsWith("Compressed_") -> "Compress"
    n.startsWith("Resized_") -> "Resizer"
    n.startsWith("protected_") -> "Protect"
    else -> "Other"
  }
}

private fun historyDateBucket(lastModified: Long): String {
  val cal = java.util.Calendar.getInstance()
  val today = java.util.Calendar.getInstance().apply {
    set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
    set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
  }.timeInMillis
  val day = 24L * 60 * 60 * 1000
  return when {
    lastModified >= today -> "Today"
    lastModified >= today - day -> "Yesterday"
    lastModified >= today - 7 * day -> "This week"
    else -> "Older"
  }
}

private fun formatKb(bytes: Long): String {
  return if (bytes >= 1024 * 1024) "%.1f MB".format(bytes / (1024f * 1024f))
  else "${bytes / 1024} KB"
}

@Composable
private fun HistoryThumb(file: File, theme: ChamiTheme) {
  val ctx = LocalContext.current
  var bmp by remember(file.absolutePath) { mutableStateOf<Bitmap?>(null) }
  LaunchedEffect(file.absolutePath) {
    withContext(Dispatchers.IO) {
      val uri = Uri.fromFile(file)
      val b = if (file.name.endsWith(".pdf", ignoreCase = true)) {
        PdfReader.renderPageBitmap(ctx, uri, 0, 0.35f)
      } else {
        PdfEngine.loadBitmap(ctx, uri, 200)
      }
      withContext(Dispatchers.Main) { bmp = b }
    }
  }
  Box(
    Modifier
      .size(72.dp)
      .clip(RoundedCornerShape(8.dp))
      .background(theme.bg)
      .border(1.dp, theme.cardBorder, RoundedCornerShape(8.dp)),
    contentAlignment = Alignment.Center
  ) {
    val b = bmp
    if (b != null) {
      Image(
        bitmap = b.asImageBitmap(),
        contentDescription = "First page of ${file.name}",
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop
      )
    } else {
      Text(
        if (file.name.endsWith(".pdf", ignoreCase = true)) "📄" else "🖼️",
        fontSize = 20.sp
      )
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(theme: ChamiTheme, onOpenInReader: ((File) -> Unit)? = null) {
  val ctx = LocalContext.current
  val haptic = LocalHapticFeedback.current
  val tm = remember { ThemeManager(ctx) }
  val favPrefs = remember { ctx.getSharedPreferences("chami_history_favs", Context.MODE_PRIVATE) }
  // Phase 5 FLICKER FIX: the file list is loaded SYNCHRONOUSLY here,
  // before the first composition, instead of starting empty and filling
  // in from LaunchedEffect. The screen therefore never shows the
  // "No Documents Found" empty state for a millisecond when files exist
  // — the first frame already has the real list (or the true empty state).
  // `loaded` stays as a guard: until the first refresh completes, show
  // a blank background, never the empty state.
  fun loadFilesNow(): List<File> {
    val dir = File(ctx.getExternalFilesDir(null), "Chami")
    return if (dir.exists()) dir.listFiles()?.filter { it.isFile } ?: emptyList() else emptyList()
  }
  var fileList by remember { mutableStateOf(loadFilesNow()) }
  var storageUsed by remember { mutableStateOf(fileList.sumOf { it.length() }) }
  var storageTotal by remember { mutableStateOf(storageUsed + (File(ctx.getExternalFilesDir(null), "Chami").usableSpace)) }
  var loaded by remember { mutableStateOf(false) }
  var query by remember { mutableStateOf("") }
  var filter by remember { mutableStateOf("All") }
  var sortMode by remember { mutableStateOf("Newest") }
  var sortMenuOpen by remember { mutableStateOf(false) }
  var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
  var favourites by remember {
    mutableStateOf(favPrefs.getStringSet("favs", emptySet())?.toSet() ?: emptySet())
  }
  var fileToDelete by remember { mutableStateOf<File?>(null) }
  var filesToDelete by remember { mutableStateOf<List<File>>(emptyList()) }
  var fileToRename by remember { mutableStateOf<File?>(null) }
  var renameText by remember { mutableStateOf("") }
  var menuFor by remember { mutableStateOf<String?>(null) }
  var pageCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }

  fun vibrate() {
    if (tm.hapticOn()) {
      try {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
      } catch (_: Exception) {}
    }
  }

  fun refresh() {
    val dir = File(ctx.getExternalFilesDir(null), "Chami")
    if (dir.exists()) {
      val files = dir.listFiles()?.filter { it.isFile } ?: emptyList()
      fileList = files
      storageUsed = files.sumOf { it.length() }
      storageTotal = storageUsed + dir.usableSpace
    } else {
      fileList = emptyList()
      storageUsed = 0L
      storageTotal = 0L
    }
    loaded = true
  }

  LaunchedEffect(Unit) { refresh() }

  // Page counts are loaded off the main thread, one file at a time.
  LaunchedEffect(fileList) {
    withContext(Dispatchers.IO) {
      val counts = mutableMapOf<String, Int>()
      fileList.filter { it.name.endsWith(".pdf", ignoreCase = true) }.forEach { f ->
        val n = PdfEngine.pageCount(ctx, Uri.fromFile(f))
        if (n > 0) counts[f.absolutePath] = n
      }
      withContext(Dispatchers.Main) { pageCounts = counts }
    }
  }

  fun toggleFavourite(file: File) {
    val next = if (favourites.contains(file.absolutePath)) favourites - file.absolutePath
    else favourites + file.absolutePath
    favourites = next
    favPrefs.edit().putStringSet("favs", next).apply()
  }

  fun deleteFiles(files: List<File>) {
    files.forEach { it.delete() }
    selected = emptySet()
    fileToDelete = null
    filesToDelete = emptyList()
    refresh()
    Toast.makeText(ctx, "File deleted", Toast.LENGTH_SHORT).show()
  }

  val sdf = remember { SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()) }

  // Search + filter + favourite-first + sort. Tool filter uses the
  // file-name prefix (see historyToolOf); "PDF to Images", "Reader" and
  // "OCR" write no files into the Chami folder, so those chips are
  // honestly empty unless such a file exists.
  val visible = remember(fileList, query, filter, sortMode, favourites) {
    var list = fileList.filter { f ->
      (query.isBlank() || f.name.contains(query, ignoreCase = true)) &&
        (filter == "All" || historyToolOf(f) == filter)
    }
    list = when (sortMode) {
      "Oldest" -> list.sortedBy { it.lastModified() }
      "Biggest" -> list.sortedByDescending { it.length() }
      "Name" -> list.sortedBy { it.name.lowercase(Locale.getDefault()) }
      else -> list.sortedByDescending { it.lastModified() }
    }
    // Favourites / pinned files always come first.
    list.sortedByDescending { favourites.contains(it.absolutePath) }
  }

  // Group headers only for date sorts; Name/Biggest stay a flat list.
  val entries: List<HistoryEntry> = remember(visible, sortMode) {
    if (sortMode == "Name" || sortMode == "Biggest") {
      visible.map { HistoryEntry.FileRow(it) }
    } else {
      val out = mutableListOf<HistoryEntry>()
      var lastBucket: String? = null
      visible.forEach { f ->
        val bucket = historyDateBucket(f.lastModified())
        if (bucket != lastBucket) {
          out.add(HistoryEntry.Header(bucket))
          lastBucket = bucket
        }
        out.add(HistoryEntry.FileRow(f))
      }
      out
    }
  }

  val filterChips = listOf(
    "All", "Images to PDF", "Scan", "Merge", "Split", "Protect",
    "Compress", "PDF to Images", "Resizer", "Reader", "OCR"
  )
  val multiSelect = selected.isNotEmpty()

  Column(
    Modifier
      .fillMaxSize()
      .padding(16.dp)
  ) {
    // Title — smaller, consistent hierarchy (Phase 4, point 7).
    Text(
      text = "History",
      fontFamily = PixelFont,
      fontWeight = FontWeight.ExtraBold,
      fontSize = 20.sp,
      color = theme.text
    )
    Text(
      text = "${fileList.size} documents saved",
      fontFamily = PixelFont,
      fontSize = 12.sp,
      color = theme.textSecondary
    )

    Spacer(Modifier.height(10.dp))

    // Storage used (Phase 4, point 6) — real folder size, bar is the
    // folder size against folder size + free space on that storage.
    Row(
      Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween
    ) {
      Text(
        "Storage used: ${formatKb(storageUsed)}",
        fontFamily = PixelFont,
        fontSize = 12.sp,
        color = theme.textSecondary
      )
    }
    LinearProgressIndicator(
      progress = if (storageTotal > 0) storageUsed.toFloat() / storageTotal else 0f,
      modifier = Modifier.fillMaxWidth(),
      color = theme.primary,
      trackColor = theme.cardBorder
    )

    Spacer(Modifier.height(10.dp))

    // Search by file name.
    OutlinedTextField(
      value = query,
      onValueChange = { query = it },
      placeholder = {
        Text("Search files…", fontFamily = PixelFont, fontSize = 12.sp, color = theme.textSecondary)
      },
      singleLine = true,
      modifier = Modifier.fillMaxWidth().height(48.dp),
      shape = RoundedCornerShape(10.dp),
      colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = theme.primary,
        unfocusedBorderColor = theme.cardBorder,
        focusedContainerColor = theme.card,
        unfocusedContainerColor = theme.card
      )
    )

    Spacer(Modifier.height(8.dp))

    // Filter by tool (horizontal chip row).
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      items(filterChips) { chip ->
        val isSel = filter == chip
        Box(
          Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (isSel) theme.primary else theme.card)
            .border(1.dp, theme.cardBorder, RoundedCornerShape(16.dp))
            .clickable { vibrate(); filter = chip }
            .padding(horizontal = 10.dp, vertical = 6.dp),
          contentAlignment = Alignment.Center
        ) {
          Text(
            chip,
            fontFamily = PixelFont,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = if (isSel) theme.textOnPrimary else theme.text
          )
        }
      }
    }

    Spacer(Modifier.height(8.dp))

    // Sort + refresh row.
    Row(
      Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Box {
        OutlinedButton(
          onClick = { vibrate(); sortMenuOpen = true },
          shape = RoundedCornerShape(10.dp),
          border = androidx.compose.foundation.BorderStroke(1.dp, theme.cardBorder),
          colors = ButtonDefaults.outlinedButtonColors(contentColor = theme.primary),
          contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
        ) {
          Text("SORT: $sortMode", fontFamily = PixelFont, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        DropdownMenu(
          expanded = sortMenuOpen,
          onDismissRequest = { sortMenuOpen = false }
        ) {
          listOf("Newest", "Oldest", "Biggest", "Name").forEach { mode ->
            DropdownMenuItem(
              text = {
                Text(mode, fontFamily = PixelFont, fontSize = 12.sp, color = theme.text)
              },
              onClick = { sortMode = mode; sortMenuOpen = false }
            )
          }
        }
      }
      OutlinedButton(
        onClick = { vibrate(); refresh() },
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, theme.cardBorder),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = theme.primary),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
      ) {
        Text("REFRESH", fontFamily = PixelFont, fontSize = 11.sp, fontWeight = FontWeight.Bold)
      }
    }

    // Multi-select action bar.
    if (multiSelect) {
      Spacer(Modifier.height(8.dp))
      Row(
        Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(10.dp))
          .background(theme.primary.copy(alpha = 0.12f))
          .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          "${selected.size} selected",
          fontFamily = PixelFont,
          fontSize = 12.sp,
          fontWeight = FontWeight.Bold,
          color = theme.text,
          modifier = Modifier.weight(1f)
        )
        Button(
          onClick = {
            vibrate()
            val files = fileList.filter { selected.contains(it.absolutePath) }
            files.forEach { f ->
              val mime = if (f.name.endsWith(".pdf", ignoreCase = true)) "application/pdf" else "image/jpeg"
              ShareHelper.shareFile(ctx, f, mime)
            }
          },
          colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
          shape = RoundedCornerShape(8.dp),
          contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
        ) {
          Text("SHARE", fontFamily = PixelFont, color = theme.textOnPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Button(
          onClick = {
            vibrate()
            filesToDelete = fileList.filter { selected.contains(it.absolutePath) }
          },
          colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
          shape = RoundedCornerShape(8.dp),
          contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
        ) {
          Text("DELETE", fontFamily = PixelFont, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        TextButton(onClick = { selected = emptySet() }) {
          Text("CANCEL", fontFamily = PixelFont, fontSize = 11.sp, color = theme.text)
        }
      }
    }

    Spacer(Modifier.height(10.dp))

    if (!loaded) {
      // Loading: blank background, NOT the empty state (flicker fix).
      Box(Modifier.fillMaxSize().background(theme.bg))
    } else if (visible.isEmpty()) {
      Box(
        Modifier
          .fillMaxSize()
          .clip(RoundedCornerShape(16.dp))
          .background(theme.card)
          .border(1.dp, theme.cardBorder, RoundedCornerShape(16.dp))
          .padding(24.dp),
        contentAlignment = Alignment.Center
      ) {
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center
        ) {
          Box(
            Modifier
              .size(64.dp)
              .clip(CircleShape)
              .background(theme.primary.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
          ) {
            Text("📁", fontSize = 32.sp)
          }
          Spacer(Modifier.height(14.dp))
          Text(
            "No Documents Found",
            fontFamily = PixelFont,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            color = theme.text
          )
          Spacer(Modifier.height(6.dp))
          Text(
            if (query.isNotBlank() || filter != "All") "No files match this search or filter."
            else "Created PDFs, scans, and converted files will appear here.",
            fontFamily = PixelFont,
            fontSize = 12.sp,
            color = theme.textSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
          )
        }
      }
    } else {
      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        items(entries, key = { entry ->
          when (entry) {
            is HistoryEntry.Header -> "header:${entry.title}"
            is HistoryEntry.FileRow -> entry.file.absolutePath
          }
        }) { entry ->
          when (entry) {
            is HistoryEntry.Header -> {
              Text(
                entry.title,
                fontFamily = PixelFont,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = theme.primary,
                modifier = Modifier.padding(top = 4.dp)
              )
            }
            is HistoryEntry.FileRow -> {
              val file = entry.file
              val isPdf = file.name.endsWith(".pdf", ignoreCase = true)
              val mime = if (isPdf) "application/pdf" else "image/jpeg"
              val isSel = selected.contains(file.absolutePath)
              val isFav = favourites.contains(file.absolutePath)
              val pages = pageCounts[file.absolutePath]
              Column(
                Modifier
                  .fillMaxWidth()
                  .clip(RoundedCornerShape(14.dp))
                  .background(if (isSel) theme.primary.copy(alpha = 0.12f) else theme.card)
                  .border(
                    1.dp,
                    if (isSel) theme.primary else theme.cardBorder,
                    RoundedCornerShape(14.dp)
                  )
                  .combinedClickable(
                    onClick = {
                      vibrate()
                      if (multiSelect) {
                        selected = if (isSel) selected - file.absolutePath
                        else selected + file.absolutePath
                      } else if (isPdf && onOpenInReader != null) {
                        // Tap opens the file in the inbuilt PDF Reader.
                        onOpenInReader(file)
                      } else {
                        ShareHelper.openFile(ctx, file, mime)
                      }
                    },
                    onLongClick = {
                      vibrate()
                      selected = if (isSel) selected - file.absolutePath
                      else selected + file.absolutePath
                    }
                  )
                  .padding(12.dp)
              ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  if (multiSelect) {
                    Checkbox(
                      checked = isSel,
                      onCheckedChange = {
                        selected = if (isSel) selected - file.absolutePath
                        else selected + file.absolutePath
                      },
                      colors = CheckboxDefaults.colors(checkedColor = theme.primary)
                    )
                    Spacer(Modifier.width(6.dp))
                  }
                  HistoryThumb(file, theme)
                  Spacer(Modifier.width(12.dp))
                  Column(Modifier.weight(1f)) {
                    Text(
                      file.name,
                      fontFamily = PixelFont,
                      fontWeight = FontWeight.Bold,
                      fontSize = 15.sp,
                      color = theme.text,
                      maxLines = 1
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                      buildString {
                        if (pages != null) append("$pages pages • ")
                        append(formatKb(file.length()))
                        append(" • ${sdf.format(Date(file.lastModified()))}")
                      },
                      fontFamily = PixelFont,
                      fontSize = 12.sp,
                      color = theme.textSecondary
                    )
                  }
                  // Favourite / pin (star, persisted in SharedPreferences).
                  IconButton(onClick = { vibrate(); toggleFavourite(file) }) {
                    Text(
                      if (isFav) "⭐" else "☆",
                      fontSize = 18.sp,
                      color = if (isFav) theme.primary else theme.textSecondary
                    )
                  }
                  // Row menu: rename / share / delete.
                  Box {
                    IconButton(onClick = { menuFor = file.absolutePath }) {
                      Text("⋮", fontSize = 18.sp, color = theme.text)
                    }
                    DropdownMenu(
                      expanded = menuFor == file.absolutePath,
                      onDismissRequest = { menuFor = null }
                    ) {
                      DropdownMenuItem(
                        text = { Text("Rename", fontFamily = PixelFont, fontSize = 12.sp, color = theme.text) },
                        onClick = {
                          menuFor = null
                          renameText = file.nameWithoutExtension
                          fileToRename = file
                        }
                      )
                      DropdownMenuItem(
                        text = { Text("Share", fontFamily = PixelFont, fontSize = 12.sp, color = theme.text) },
                        onClick = {
                          menuFor = null
                          ShareHelper.shareFile(ctx, file, mime)
                        }
                      )
                      DropdownMenuItem(
                        text = { Text("Open", fontFamily = PixelFont, fontSize = 12.sp, color = theme.text) },
                        onClick = {
                          menuFor = null
                          if (isPdf && onOpenInReader != null) onOpenInReader(file)
                          else ShareHelper.openFile(ctx, file, mime)
                        }
                      )
                      DropdownMenuItem(
                        text = { Text("Delete", fontFamily = PixelFont, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) },
                        onClick = { menuFor = null; fileToDelete = file }
                      )
                    }
                  }
                }
              }
            }
          }
        }
      }
    }

    // Delete one file (from the row menu).
    if (fileToDelete != null) {
      AlertDialog(
        onDismissRequest = { fileToDelete = null },
        confirmButton = {
          Button(
            onClick = {
              vibrate()
              fileToDelete?.let { deleteFiles(listOf(it)) }
            },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
          ) {
            Text("DELETE", fontFamily = PixelFont, fontSize = 12.sp, fontWeight = FontWeight.Bold)
          }
        },
        dismissButton = {
          TextButton(onClick = { fileToDelete = null }) {
            Text("CANCEL", fontFamily = PixelFont, color = theme.text)
          }
        },
        title = {
          Text("Delete Document?", fontFamily = PixelFont, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        },
        text = {
          Text(
            "Permanently delete \"${fileToDelete?.name}\"? This action cannot be undone.",
            fontFamily = PixelFont,
            fontSize = 13.sp,
            color = theme.textSecondary
          )
        }
      )
    }

    // Delete many files (multi-select bar).
    if (filesToDelete.isNotEmpty()) {
      AlertDialog(
        onDismissRequest = { filesToDelete = emptyList() },
        confirmButton = {
          Button(
            onClick = { vibrate(); deleteFiles(filesToDelete) },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
          ) {
            Text("DELETE ${filesToDelete.size}", fontFamily = PixelFont, fontSize = 12.sp, fontWeight = FontWeight.Bold)
          }
        },
        dismissButton = {
          TextButton(onClick = { filesToDelete = emptyList() }) {
            Text("CANCEL", fontFamily = PixelFont, color = theme.text)
          }
        },
        title = {
          Text("Delete ${filesToDelete.size} files?", fontFamily = PixelFont, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        },
        text = {
          Text(
            "These files will be permanently deleted. This action cannot be undone.",
            fontFamily = PixelFont,
            fontSize = 13.sp,
            color = theme.textSecondary
          )
        }
      )
    }

    // Rename a file (same folder, same extension).
    if (fileToRename != null) {
      AlertDialog(
        onDismissRequest = { fileToRename = null },
        confirmButton = {
          Button(
            onClick = {
              vibrate()
              val f = fileToRename
              if (f != null && renameText.isNotBlank()) {
                val newName = renameText.trim() + "." + f.extension
                val newFile = File(f.parentFile, newName)
                if (newFile.exists() && newFile.absolutePath != f.absolutePath) {
                  Toast.makeText(ctx, "A file with that name already exists", Toast.LENGTH_SHORT).show()
                } else if (f.renameTo(newFile)) {
                  Toast.makeText(ctx, "Renamed", Toast.LENGTH_SHORT).show()
                  fileToRename = null
                  refresh()
                } else {
                  Toast.makeText(ctx, "Could not rename", Toast.LENGTH_SHORT).show()
                }
              }
            },
            colors = ButtonDefaults.buttonColors(containerColor = theme.primary)
          ) {
            Text("RENAME", fontFamily = PixelFont, fontSize = 12.sp, fontWeight = FontWeight.Bold)
          }
        },
        dismissButton = {
          TextButton(onClick = { fileToRename = null }) {
            Text("CANCEL", fontFamily = PixelFont, color = theme.text)
          }
        },
        title = {
          Text("Rename file", fontFamily = PixelFont, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        },
        text = {
          OutlinedTextField(
            value = renameText,
            onValueChange = { renameText = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
          )
        }
      )
    }

  }
}


@Composable
fun SettingsScreen(
  theme: ChamiTheme,
  tm: ThemeManager,
  onThemeChanged: (ChamiTheme) -> Unit
) {
  val ctx = LocalContext.current
  val haptic = LocalHapticFeedback.current
  // Phase 5: no haptic toggle any more — haptic is always on
  // (ThemeManager.hapticOn() now always returns true).

  var showClearCacheDialog by remember { mutableStateOf(false) }
  var showPrivacyPolicy by remember { mutableStateOf(false) }

  fun vibrate() {
    // Haptic is always on (no toggle) — always give the tap feedback.
    try {
      haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    } catch (_: Exception) {}
  }

  Column(
    Modifier
      .fillMaxSize()
      .padding(16.dp)
      .verticalScroll(rememberScrollState())
  ) {
    Text(
      text = "Settings",
      fontFamily = PixelFont,
      fontWeight = FontWeight.ExtraBold,
      fontSize = 16.sp,
      color = theme.text
    )
    Text(
      text = "Appearance and security",
      fontFamily = PixelFont,
      fontSize = 12.sp,
      color = theme.textSecondary
    )

    Spacer(Modifier.height(18.dp))

    // SECTION 1: THEME & COLOR PALETTE
    Text(
      "THEME",
      fontFamily = PixelFont,
      fontWeight = FontWeight.Bold,
      fontSize = 12.sp,
      color = theme.primary
    )
    Spacer(Modifier.height(8.dp))

    Column(
      Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(16.dp))
        .background(theme.card)
        .border(1.dp, theme.cardBorder, RoundedCornerShape(16.dp))
        .padding(8.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
      ChamiThemes.ALL.forEach { th ->
        val isSelected = th.id == theme.id
        Row(
          Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) th.primary.copy(alpha = 0.1f) else Color.Transparent)
            .border(
              width = if (isSelected) 1.5.dp else 0.dp,
              color = if (isSelected) th.primary else Color.Transparent,
              shape = RoundedCornerShape(12.dp)
            )
            .clickable {
              vibrate()
              tm.set(th.id)
              onThemeChanged(th)
            }
            .padding(12.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          // Color Preview Swatch
          Row(
            Modifier
              .size(36.dp)
              .clip(RoundedCornerShape(8.dp))
              .background(th.bg)
              .border(1.dp, th.cardBorder, RoundedCornerShape(8.dp)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
          ) {
            Box(
              Modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(th.primary)
            )
          }

          Spacer(Modifier.width(12.dp))

          Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Text(
                th.name,
                fontFamily = PixelFont,
                fontSize = 13.sp,
                color = theme.text,
                fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold
              )
              if (th.isDark) {
                Spacer(Modifier.width(6.dp))
                Box(
                  Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(theme.cardBorder)
                    .padding(horizontal = 4.dp, vertical = 1.dp)
                ) {
                  Text("DARK", fontFamily = PixelFont, fontSize = 9.sp, color = theme.textSecondary)
                }
              }
            }
            Spacer(Modifier.height(2.dp))
            Text(
              th.subtitle,
              fontFamily = PixelFont,
              fontSize = 11.sp,
              color = theme.textSecondary,
              maxLines = 1
            )
          }

          if (isSelected) {
            Box(
              Modifier
                .clip(CircleShape)
                .background(th.primary)
                .size(22.dp),
              contentAlignment = Alignment.Center
            ) {
              Text("✓", color = th.textOnPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
          }
        }
      }
    }

    Spacer(Modifier.height(20.dp))

    Spacer(Modifier.height(20.dp))

    Spacer(Modifier.height(16.dp))

    // SECTION 4: STORAGE & PRIVACY
    Row(
      Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      OutlinedButton(
        onClick = {
          vibrate()
          showClearCacheDialog = true
        },
        modifier = Modifier.weight(1f).height(46.dp),
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, theme.cardBorder),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = theme.text)
      ) {
        Text(
          "CLEAR CACHE",
          fontFamily = PixelFont,
          color = theme.text,
          fontSize = 11.sp,
          fontWeight = FontWeight.Bold
        )
      }

      OutlinedButton(
        onClick = {
          vibrate()
          showPrivacyPolicy = true
        },
        modifier = Modifier.weight(1f).height(46.dp),
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, theme.cardBorder),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = theme.text)
      ) {
        Text(
          "PRIVACY POLICY",
          fontFamily = PixelFont,
          color = theme.text,
          fontSize = 11.sp,
          fontWeight = FontWeight.Bold
        )
      }
    }

    Spacer(Modifier.height(24.dp))

    // Clean version card (Phase 5): just the version and the tagline —
    // exactly "Easy. Faster. Safe.", nothing else.
    Column(
      Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(16.dp))
        .background(theme.card)
        .border(1.dp, theme.cardBorder, RoundedCornerShape(16.dp))
        .padding(16.dp),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      Text(
        "Version 1.2",
        fontFamily = PixelFont,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        color = theme.text
      )
      Spacer(Modifier.height(3.dp))
      Text(
        "Easy. Faster. Safe.",
        fontFamily = PixelFont,
        fontSize = 12.sp,
        color = theme.textSecondary
      )
    }

    Spacer(Modifier.height(24.dp))

    // Dialog: Clear Cache
    if (showClearCacheDialog) {
      AlertDialog(
        onDismissRequest = { showClearCacheDialog = false },
        confirmButton = {
          Button(
            onClick = {
              vibrate()
              try {
                ctx.cacheDir.deleteRecursively()
                val chamiTmp = File(ctx.getExternalFilesDir(null), "Chami/tmppdf")
                if (chamiTmp.exists()) chamiTmp.deleteRecursively()
                Toast.makeText(ctx, "Cache cleared successfully ✓", Toast.LENGTH_SHORT).show()
              } catch (e: Exception) {
                e.printStackTrace()
              }
              showClearCacheDialog = false
            },
            colors = ButtonDefaults.buttonColors(containerColor = theme.primary)
          ) {
            Text("CLEAR", fontFamily = PixelFont, color = theme.textOnPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
          }
        },
        dismissButton = {
          TextButton(onClick = { showClearCacheDialog = false }) {
            Text("CANCEL", fontFamily = PixelFont, color = theme.text)
          }
        },
        title = {
          Text("Clear Temporary Cache?", fontFamily = PixelFont, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        },
        text = {
          Text(
            "This will clean temporary image buffers. Your saved documents will not be affected.",
            fontFamily = PixelFont,
            fontSize = 13.sp,
            color = theme.textSecondary
          )
        }
      )
    }

    // Modal: Privacy Policy
    if (showPrivacyPolicy) {
      AlertDialog(
        onDismissRequest = { showPrivacyPolicy = false },
        confirmButton = {
          Button(
            onClick = { showPrivacyPolicy = false },
            colors = ButtonDefaults.buttonColors(containerColor = theme.primary)
          ) {
            Text("CLOSE", fontFamily = PixelFont, color = theme.textOnPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
          }
        },
        title = {
          Text("Privacy Policy", fontFamily = PixelFont, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        },
        text = {
          Box(Modifier.height(300.dp)) {
            AndroidView(
              factory = { context ->
                WebView(context).apply {
                  loadUrl("file:///android_asset/privacy_policy.html")
                }
              },
              modifier = Modifier.fillMaxSize()
            )
          }
        }
      )
    }
  }
}
