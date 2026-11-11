package com.chami.pdfmaker.pdf

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileInputStream

object SaveLocation {
  fun copyToUri(ctx: Context, file: File, uri: Uri): Boolean {
    return try {
      ctx.contentResolver.openOutputStream(uri)?.use { out ->
        FileInputStream(file).use { it.copyTo(out) }
      }
      true
    } catch (_: Exception) { false }
  }
}
