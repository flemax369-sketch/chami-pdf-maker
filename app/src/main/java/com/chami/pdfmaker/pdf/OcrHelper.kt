package com.chami.pdfmaker.pdf
import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

suspend fun ocrImage(ctx:Context, uri:Uri): String = suspendCancellableCoroutine{ cont ->
  try {
    val image = InputImage.fromFilePath(ctx, uri)
    TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(image)
      .addOnSuccessListener{ cont.resume(it.text) }
      .addOnFailureListener{ cont.resume("") }
  } catch(e:Exception){ cont.resume("") }
}
