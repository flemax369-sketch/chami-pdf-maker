package com.chami.pdfmaker.pdf

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

object ShareHelper {
  fun shareFile(ctx: Context, file: File, mimeType: String = "application/pdf") {
    try {
      val uri = FileProvider.getUriForFile(ctx, "com.chami.pdfmaker.fileprovider", file)
      val intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
      ctx.startActivity(Intent.createChooser(intent, "Share via"))
    } catch (e: Exception) {
      e.printStackTrace()
    }
  }

  fun openFile(ctx: Context, file: File, mimeType: String = "application/pdf") {
    try {
      val uri = FileProvider.getUriForFile(ctx, "com.chami.pdfmaker.fileprovider", file)
      val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
      ctx.startActivity(intent)
    } catch (e: Exception) {
      e.printStackTrace()
    }
  }
}

fun sharePdf(ctx: Context, file: File) {
  ShareHelper.shareFile(ctx, file, "application/pdf")
}
