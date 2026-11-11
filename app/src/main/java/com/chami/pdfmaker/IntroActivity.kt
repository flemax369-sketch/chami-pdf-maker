package com.chami.pdfmaker

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chami.pdfmaker.theme.ThemeManager
import com.chami.pdfmaker.ui.ChamiLogo
import kotlinx.coroutines.delay

class IntroActivity : ComponentActivity() {
  override fun onCreate(b: Bundle?) {
    super.onCreate(b)
    val tm = ThemeManager(this)
    setContent {
      val theme = tm.current()
      var fill by remember { mutableStateOf(0f) }

      LaunchedEffect(Unit) {
        // Fast, smooth, responsive 1-second sequence
        repeat(20) { i ->
          fill = (i + 1) / 20f
          delay(50)
        }
        delay(150)
        startActivity(Intent(this@IntroActivity, MainActivity::class.java))
        finish()
      }

      Box(
        Modifier
          .fillMaxSize()
          .background(theme.bg)
          .padding(24.dp),
        contentAlignment = Alignment.Center
      ) {
        ChamiLogo(
          theme = theme,
          fill = fill,
          modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 440.dp)
        )
      }
    }
  }
}
