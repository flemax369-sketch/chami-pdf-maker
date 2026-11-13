package com.chami.pdfmaker.pdf

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileInputStream

object SaveLocation {
  fun copyToUri(ctx: Context, file: File, uri: Uri): Boolean {
    return try {
      val out = ctx.contentResolver.openOutputStream(uri) ?: return false
      out.use { o ->
        FileInputStream(file).use { it.copyTo(o) }
      }
      true
    } catch (_: Exception) { false }
  }
}
