package com.chami.pdfmaker

import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.chami.pdfmaker.theme.*
import com.chami.pdfmaker.ui.*

data class ToolItem(val id: String, val title: String, val iconRes: Int, val category: String = "CORE")

class MainActivity : ComponentActivity() {
  override fun onCreate(b: Bundle?) {
    super.onCreate(b)
    // Phase 4, point 4: screenshots / screen recording are always blocked.
    // No toggle, no option in the app — FLAG_SECURE is always on.
    window.setFlags(
      WindowManager.LayoutParams.FLAG_SECURE,
      WindowManager.LayoutParams.FLAG_SECURE
    )
    // Phase 6 (user decision 2026-11-13): ALL ADS REMOVED for now — no
    // interstitial, no banner, no AdMob at all in this build. Do NOT
    // re-add MobileAds.initialize() / AdsManager.load() here; the AdMob
    // IDs stay in memory only until the user says ads are back.
    // Phase 6 THEME LOCK: Executive Emerald (emerald_pro) is the only
    // theme — there is no theme picker any more. Force it here, before
    // ThemeManager is read anywhere else.
    val tm = ThemeManager(this)
    if (tm.current().id != "emerald_pro") tm.set("emerald_pro")
    // Phase 6: apply the saved app language before the first composition.
    LocaleHelper.applyLocale(this)
    val lockPrefs = getSharedPreferences(LocaleHelper.PREFS, Context.MODE_PRIVATE)
    val appLockOn = lockPrefs.getBoolean("app_lock", false)
    val savedPin = lockPrefs.getString("app_pin", "") ?: ""
    setContent {
      val ctx = LocalContext.current
      var theme by remember { mutableStateOf(tm.current()) }
      var activeTool by remember { mutableStateOf<String?>(null) }
      var currentTab by remember { mutableStateOf("Home") }
      var readerFile by remember { mutableStateOf<java.io.File?>(null) }
      val haptic = LocalHapticFeedback.current
      // Phase 6 App Lock: if a PIN is set, Home stays locked until it is entered.
      var unlocked by remember { mutableStateOf(!appLockOn || savedPin.length != 4) }
      var pinInput by remember { mutableStateOf("") }
      var pinError by remember { mutableStateOf(false) }

      fun vibrate() {
        if (tm.hapticOn()) {
          try {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
          } catch (_: Exception) {}
        }
      }

      // Phase 6: 11 tools only, grouped Simple (7) then Smart (4).
      val simpleTools = listOf(
        ToolItem("img_pdf", "Images to PDF", R.drawable.tool_ic_01_images_to_pdf, "CREATE"),
        ToolItem("merge", "Merge PDF", R.drawable.tool_ic_05_merge_pdf, "EDIT"),
        ToolItem("split", "Split PDF", R.drawable.tool_ic_06_split_pdf, "EDIT"),
        ToolItem("compress", "Compress KB", R.drawable.tool_ic_04_compress_kb, "OPTIMIZE"),
        ToolItem("pdf_img", "PDF to Images", R.drawable.tool_ic_07_pdf_to_images, "CONVERT"),
        ToolItem("resize", "Image Resizer", R.drawable.tool_ic_09_image_resizer, "PHOTO"),
        ToolItem("convert", "Format Convert", R.drawable.tool_ic_14_format_convert, "PHOTO"),
      )
      val smartTools = listOf(
        ToolItem("scan", "Scan to PDF", R.drawable.tool_ic_02_scan_to_pdf, "CREATE"),
        ToolItem("ocr", "OCR Text", R.drawable.tool_ic_11_ocr_text, "EXTRACT"),
        ToolItem("reader", "PDF Reader", R.drawable.tool_ic_10_pdf_reader, "VIEW"),
        ToolItem("protect", "Protect PDF", R.drawable.tool_ic_03_protect_pdf, "SECURITY"),
      )
      // Phase 6: Home background from Settings ("home_bg" in chami_prefs).
      // A tint/colour only — the chosen background colours the Home screen.
      val homeBgKey = ctx.getSharedPreferences(LocaleHelper.PREFS, Context.MODE_PRIVATE)
        .getString("home_bg", "default") ?: "default"
      val homeBg: Color = when (homeBgKey) {
        "grass" -> Color(0xFFE8F5E9)
        "sky" -> Color(0xFFE3F2FD)
        "night" -> Color(0xFF111827)
        "sunset" -> Color(0xFFFFF3E0)
        else -> theme.bg
      }

      @Composable
      fun ToolTile(t: ToolItem) {
        Column(
          Modifier
            .height(106.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(theme.card)
            .border(1.dp, theme.cardBorder, RoundedCornerShape(14.dp))
            .clickable {
              vibrate()
              activeTool = t.id
            }
            .padding(8.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center
        ) {
          Box(
            Modifier
              .size(40.dp)
              .clip(RoundedCornerShape(10.dp))
              .background(theme.bg)
              .border(1.dp, theme.cardBorder, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
          ) {
            Image(
              painter = painterResource(id = t.iconRes),
              contentDescription = t.title,
              modifier = Modifier.size(28.dp),
              contentScale = ContentScale.Fit
            )
          }
          Spacer(Modifier.height(7.dp))
          Text(
            t.title,
            fontFamily = PixelFont,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = theme.text,
            maxLines = 1,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
          )
        }
      }

      Surface(modifier = Modifier.fillMaxSize(), color = theme.bg) {
        if (!unlocked) {
          // Phase 6 App Lock PIN overlay — shown before any Home content.
          Column(
            Modifier
              .fillMaxSize()
              .background(theme.bg)
              .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
          ) {
            Text("🔒", fontSize = 40.sp)
            Spacer(Modifier.height(16.dp))
            Text(
              LocaleHelper.t(ctx, "app_lock"),
              fontFamily = PixelFont,
              fontWeight = FontWeight.ExtraBold,
              fontSize = 20.sp,
              color = theme.text
            )
            Spacer(Modifier.height(6.dp))
            Text(
              "Enter your 4-digit PIN",
              fontFamily = PixelFont,
              fontSize = 12.sp,
              color = theme.textSecondary
            )
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
              value = pinInput,
              onValueChange = {
                pinInput = it.filter { c -> c.isDigit() }.take(4)
                pinError = false
              },
              singleLine = true,
              visualTransformation = PasswordVisualTransformation(),
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
              isError = pinError,
              modifier = Modifier.fillMaxWidth(),
              shape = RoundedCornerShape(10.dp)
            )
            if (pinError) {
              Spacer(Modifier.height(8.dp))
              Text(
                "Wrong PIN — try again",
                fontFamily = PixelFont,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error
              )
            }
            Spacer(Modifier.height(16.dp))
            Button(
              onClick = {
                if (pinInput == savedPin) {
                  unlocked = true
                } else {
                  pinError = true
                  pinInput = ""
                  Toast.makeText(ctx, "Wrong PIN", Toast.LENGTH_SHORT).show()
                }
              },
              enabled = pinInput.length == 4,
              colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
              shape = RoundedCornerShape(10.dp),
              modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
              Text(
                "UNLOCK",
                fontFamily = PixelFont,
                color = theme.textOnPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
              )
            }
          }
        } else if (activeTool != null) {
          when (activeTool) {
            "img_pdf" -> ImagesToPdfScreen(theme) { activeTool = null }
            "scan" -> ScanToPdfScreen(theme) { activeTool = null }
            "protect" -> ProtectPdfScreen(theme) { activeTool = null }
            "pdf_img" -> PdfToImagesScreen(theme) { activeTool = null }
            "split" -> SplitPdfScreen(theme) { activeTool = null }
            "merge" -> MergePdfScreen(theme) { activeTool = null }
            "compress" -> CompressKbScreen(theme) { activeTool = null }
            "resize" -> ResizeConvertScreen(theme, title = "Image Resizer") { activeTool = null }
            "convert" -> ResizeConvertScreen(theme, title = "Format Convert") { activeTool = null }
            "ocr" -> OcrScreen(theme) { activeTool = null }
            "reader" -> PdfReaderScreen(theme, initialFile = readerFile, onOpenTool = { activeTool = it }) { activeTool = null; readerFile = null }
            else -> { activeTool = null }
          }
        } else {
          BackHandler(enabled = currentTab != "Home") {
            vibrate()
            currentTab = "Home"
          }

          Column(
            Modifier
              .fillMaxSize()
              .background(if (currentTab == "Home") homeBg else theme.bg)
              .statusBarsPadding()
              .padding(horizontal = 16.dp, vertical = 12.dp)
          ) {
            // Main Header — CHAMI PRO kept (user confirmed).
            Row(
              Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically
            ) {
              ChamiAppIcon(theme)
              Spacer(Modifier.width(12.dp))
              Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Text(
                    "CHAMI",
                    fontFamily = PixelFont,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 22.sp,
                    letterSpacing = 0.5.sp,
                    color = theme.text
                  )
                  Spacer(Modifier.width(6.dp))
                  Box(
                    Modifier
                      .clip(RoundedCornerShape(4.dp))
                      .background(theme.primary)
                      .padding(horizontal = 5.dp, vertical = 1.dp)
                  ) {
                    Text(
                      "PRO",
                      fontFamily = PixelFont,
                      fontWeight = FontWeight.Bold,
                      fontSize = 10.sp,
                      color = theme.textOnPrimary
                    )
                  }
                }
                Text(
                  "Professional PDF Toolkit",
                  fontFamily = PixelFont,
                  fontSize = 11.sp,
                  color = theme.textSecondary
                )
              }
              Spacer(Modifier.weight(1f))
              Box(
                Modifier
                  .clip(RoundedCornerShape(20.dp))
                  .background(theme.card)
                  .border(1.dp, theme.cardBorder, RoundedCornerShape(20.dp))
                  .padding(horizontal = 10.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
              ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Box(
                    Modifier
                      .size(6.dp)
                      .clip(CircleShape)
                      .background(theme.primary)
                  )
                  Spacer(Modifier.width(5.dp))
                  Text(
                    "OFFLINE",
                    fontFamily = PixelFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    color = theme.textSecondary
                  )
                }
              }
            }

            Spacer(Modifier.height(14.dp))

            // Body based on selected bottom tab
            Box(Modifier.weight(1f)) {
              when (currentTab) {
                "History" -> HistoryScreen(theme, onOpenInReader = { file -> readerFile = file; activeTool = "reader" })
                "Settings" -> SettingsScreen(theme, tm) { theme = it }
                else -> {
                  // Phase 6 Home: two grouped sections, tool names only —
                  // no subtext lines ("Batch Convert", "Password Lock",
                  // "11 features" all removed). The Quick Action hero row
                  // that carried the subtext is removed too.
                  LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                  ) {
                    item(span = { GridItemSpan(3) }) {
                      Text(
                        LocaleHelper.t(ctx, "simple_tools"),
                        fontFamily = PixelFont,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 13.sp,
                        color = theme.text,
                        modifier = Modifier.padding(top = 2.dp, bottom = 2.dp)
                      )
                    }
                    items(simpleTools) { t -> ToolTile(t) }
                    item(span = { GridItemSpan(3) }) {
                      Text(
                        LocaleHelper.t(ctx, "smart_tools"),
                        fontFamily = PixelFont,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 13.sp,
                        color = theme.text,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                      )
                    }
                    items(smartTools) { t -> ToolTile(t) }
                  }
                }
              }
            }

            // Phase 6 (user decision 2026-11-13): ALL ADS REMOVED — no
            // banner slot on Home (not even a test ID).
            Spacer(Modifier.height(10.dp))

            // Bottom Navigation Tabs
            Row(
              Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(theme.card)
                .border(1.dp, theme.cardBorder, RoundedCornerShape(16.dp))
                .padding(6.dp),
              horizontalArrangement = Arrangement.SpaceEvenly,
              verticalAlignment = Alignment.CenterVertically
            ) {
              val navItems = listOf(
                Triple("Home", "🏠", LocaleHelper.t(ctx, "home")),
                Triple("History", "📁", LocaleHelper.t(ctx, "history")),
                Triple("Settings", "⚙️", LocaleHelper.t(ctx, "settings"))
              )

              navItems.forEach { (tab, icon, label) ->
                val isSelected = currentTab == tab
                Row(
                  Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isSelected) theme.primary.copy(alpha = 0.15f) else Color.Transparent)
                    .clickable {
                      vibrate()
                      currentTab = tab
                    }
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Text(icon, fontSize = 14.sp)
                  Spacer(Modifier.width(6.dp))
                  Text(
                    text = label,
                    color = if (isSelected) theme.primary else theme.textSecondary,
                    fontFamily = PixelFont,
                    fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
                    fontSize = 12.sp
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
