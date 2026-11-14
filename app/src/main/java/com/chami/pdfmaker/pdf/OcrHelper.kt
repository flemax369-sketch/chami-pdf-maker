package com.chami.pdfmaker.pdf
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

// Languages the OCR screen offers. Only Hindi changes the recogniser:
// ML Kit has one recogniser per SCRIPT, not per language. Hindi (plus
// Marathi / Nepali) reads Devanagari; English, Spanish, French and
// German all read the Latin script, so they share the Latin recogniser.
// There is no Spanish-only recogniser — Spanish works because it is
// written in Latin script, not because a Spanish model is loaded.
val SUPPORTED_OCR_LANGUAGES = listOf("English", "Hindi", "Spanish")

// True when the language needs the Devanagari recogniser.
fun isDevanagariLanguage(language: String): Boolean = when (language.trim().lowercase()) {
  "hindi", "हिन्दी", "devanagari", "marathi", "nepali" -> true
  else -> false
}

// The recogniser options for a language name. "Hindi" (and Marathi /
// Nepali) -> Devanagari recogniser; everything else -> Latin.
// Unknown names fall back to Latin, never to a silent empty result.
fun recognizerOptionsFor(language: String): TextRecognizerOptions =
  if (isDevanagariLanguage(language)) {
    DevanagariTextRecognizerOptions.Builder().build()
  } else {
    TextRecognizerOptions.DEFAULT_OPTIONS
  }

// OCR a Bitmap with the recogniser for `language`. Returns "" on any
// failure (same contract as before — callers treat blank as no text).
suspend fun ocrBitmap(bmp: Bitmap, language: String = "English"): String =
  suspendCancellableCoroutine { cont ->
    val client = TextRecognition.getClient(recognizerOptionsFor(language))
    try {
      val image = InputImage.fromBitmap(bmp, 0)
      client.process(image)
        .addOnSuccessListener { result ->
          try { client.close() } catch (_: Exception) {}
          if (cont.isActive) cont.resume(result.text)
        }
        .addOnFailureListener {
          try { client.close() } catch (_: Exception) {}
          if (cont.isActive) cont.resume("")
        }
    } catch (e: Exception) {
      try { client.close() } catch (_: Exception) {}
      if (cont.isActive) cont.resume("")
    }
  }

// OCR an image Uri. Language-aware overload — this is the one the OCR
// screen must call with the user's picked language.
suspend fun ocrImage(ctx: Context, uri: Uri, language: String): String =
  suspendCancellableCoroutine { cont ->
    val client = TextRecognition.getClient(recognizerOptionsFor(language))
    try {
      val image = InputImage.fromFilePath(ctx, uri)
      client.process(image)
        .addOnSuccessListener { result ->
          try { client.close() } catch (_: Exception) {}
          if (cont.isActive) cont.resume(result.text)
        }
        .addOnFailureListener {
          try { client.close() } catch (_: Exception) {}
          if (cont.isActive) cont.resume("")
        }
    } catch (e: Exception) {
      try { client.close() } catch (_: Exception) {}
      if (cont.isActive) cont.resume("")
    }
  }

// Backwards-compatible overload: Latin recogniser (English default),
// as before. Existing callers (Reader search) keep compiling.
suspend fun ocrImage(ctx: Context, uri: Uri): String = ocrImage(ctx, uri, "English")
