package com.chami.pdfmaker

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chami.pdfmaker.pdf.PdfEngine
import com.chami.pdfmaker.theme.*
import com.chami.pdfmaker.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ToolItem(val id: String, val title: String, val iconRes: Int, val category: String = "CORE")

class MainActivity : ComponentActivity() {
  override fun onCreate(b: Bundle?) {
    super.onCreate(b)
    // Screenshots / screen recording are always blocked. No toggle, no
    // option in the app — FLAG_SECURE is always on.
    window.setFlags(
      WindowManager.LayoutParams.FLAG_SECURE,
      WindowManager.LayoutParams.FLAG_SECURE
    )
    // ALL ADS REMOVED (user decision 2026-11-13) — no interstitial, no
    // banner, no AdMob at all in this build.
    // THEME LOCK: Executive Emerald (emerald_pro) is the only theme —
    // there is no theme picker any more. Force it here, before
    // ThemeManager is read anywhere else.
    val tm = ThemeManager(this)
    if (tm.current().id != "emerald_pro") tm.set("emerald_pro")
    LocaleHelper.applyLocale(this)
    val lockPrefs = getSharedPreferences(LocaleHelper.PREFS, Context.MODE_PRIVATE)
    val appLockOn = lockPrefs.getBoolean("app_lock", false)
    val savedPinStored = lockPrefs.getString("app_pin", "") ?: ""
    setContent {
      val ctx = LocalContext.current
      var theme by remember { mutableStateOf(tm.current()) }
      var activeTool by remember { mutableStateOf<String?>(null) }
      var currentTab by remember { mutableStateOf("Home") }
      var readerFile by remember { mutableStateOf<java.io.File?>(null) }
      val haptic = LocalHapticFeedback.current
      val scope = rememberCoroutineScope()
      val homeGridState = rememberLazyGridState()
      // History keeps its scroll position across tab switches: the list
      // state lives here, not inside HistoryScreen.
      val historyListState = remember { androidx.compose.foundation.lazy.LazyListState() }
      // App Lock: if a PIN is set, Home stays locked until it is entered.
      // A stored PIN is a "sha256:…" hash (see hashPin); a legacy plain
      // 4-digit PIN is still accepted once and then re-hashed on unlock.
      var unlocked by remember { mutableStateOf(!appLockOn || savedPinStored.isBlank()) }
      var pinInput by remember { mutableStateOf("") }
      var pinError by remember { mutableStateOf(false) }
      var showForgotPin by remember { mutableStateOf(false) }

      fun vibrate() {
        if (tm.hapticOn()) {
          try {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
          } catch (_: Exception) {}
        }
      }

      // Home grouping — Option A (user decision 2026-11-14), replacing
      // the rejected Simple Tools (7) / Smart Tools (4) split.
      val makePdfTools = listOf(
        ToolItem("img_pdf", "Images to PDF", R.drawable.tool_ic_01_images_to_pdf, "CREATE"),
        ToolItem("scan", "Scan to PDF", R.drawable.tool_ic_02_scan_to_pdf, "CREATE"),
      )
      val fixPdfTools = listOf(
        ToolItem("merge", "Merge PDF", R.drawable.tool_ic_05_merge_pdf, "EDIT"),
        ToolItem("split", "Split PDF", R.drawable.tool_ic_06_split_pdf, "EDIT"),
        ToolItem("protect", "Protect PDF", R.drawable.tool_ic_03_protect_pdf, "SECURITY"),
        ToolItem("compress", "Compress KB", R.drawable.tool_ic_04_compress_kb, "OPTIMIZE"),
      )
      val convertTools = listOf(
        ToolItem("pdf_img", "PDF to Images", R.drawable.tool_ic_07_pdf_to_images, "CONVERT"),
        ToolItem("convert", "Format Convert", R.drawable.tool_ic_14_format_convert, "PHOTO"),
        ToolItem("ocr", "OCR Text", R.drawable.tool_ic_11_ocr_text, "EXTRACT"),
      )
      val imagesReadingTools = listOf(
        ToolItem("resize", "Image Resizer", R.drawable.tool_ic_09_image_resizer, "PHOTO"),
        ToolItem("reader", "PDF Reader", R.drawable.tool_ic_10_pdf_reader, "VIEW"),
      )
      val toolGroups = listOf(
        "Make a PDF" to makePdfTools,
        "Fix a PDF" to fixPdfTools,
        "Convert" to convertTools,
        "Images & Reading" to imagesReadingTools,
      )

      // Home background from Settings ("home_bg" in chami_prefs).
      // No pixel-art background assets exist in this build, so the
      // picker stores colour tints (labelled as such in Settings) or a
      // custom photo URI; opacity 20–100%, default 60%, is a white
      // overlay over the tint/photo and applies to Home only.
      val bgPrefs = ctx.getSharedPreferences(LocaleHelper.PREFS, Context.MODE_PRIVATE)
      val homeBgKey = bgPrefs.getString("home_bg", "default") ?: "default"
      val homeBgOpacity = bgPrefs.getFloat("home_bg_opacity", 0.6f).coerceIn(0.2f, 1.0f)
      val homeBgTint: Color = when (homeBgKey) {
        "grass" -> Color(0xFFE8F5E9)
        "sky" -> Color(0xFFE3F2FD)
        "night" -> Color(0xFF111827)
        "sunset" -> Color(0xFFFFF3E0)
        else -> theme.bg
      }
      val customBgUriStr = bgPrefs.getString("home_bg_custom_uri", null)
      var customBgBmp by remember(customBgUriStr, homeBgKey) { mutableStateOf<Bitmap?>(null) }
      LaunchedEffect(customBgUriStr, homeBgKey) {
        customBgBmp = null
        if (homeBgKey == "custom" && !customBgUriStr.isNullOrBlank()) {
          withContext(Dispatchers.IO) {
            try {
              PdfEngine.loadBitmap(ctx, Uri.parse(customBgUriStr), 1200)
            } catch (_: Exception) { null }
          }?.let { customBgBmp = it }
        }
      }

      @Composable
      fun ToolTile(t: ToolItem) {
        Column(
          Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(Ds.tileRadius))
            .background(theme.card)
            .border(1.dp, theme.cardBorder, RoundedCornerShape(Ds.tileRadius))
            .clickable {
              vibrate()
              activeTool = t.id
            }
            .padding(Ds.s8),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center
        ) {
          Image(
            painter = painterResource(id = t.iconRes),
            contentDescription = t.title,
            modifier = Modifier.size(40.dp),
            contentScale = ContentScale.Fit
          )
          Spacer(Modifier.height(Ds.s8))
          Text(
            t.title,
            fontSize = Ds.TILE_SP.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = (Ds.TILE_SP * 1.4).sp,
            color = theme.text,
            maxLines = 2,
            textAlign = TextAlign.Center
          )
        }
      }

      Surface(modifier = Modifier.fillMaxSize(), color = theme.bg) {
        if (!unlocked) {
          // App Lock PIN overlay — shown before any Home content.
          Column(
            Modifier
              .fillMaxSize()
              .background(theme.bg)
              .padding(Ds.s24),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
          ) {
            Icon(
              Icons.Filled.Lock,
              contentDescription = "Locked",
              tint = theme.primary,
              modifier = Modifier.size(40.dp)
            )
            Spacer(Modifier.height(Ds.s16))
            Text(
              LocaleHelper.t(ctx, "app_lock"),
              fontFamily = PixelFont,
              fontWeight = FontWeight.ExtraBold,
              fontSize = Ds.TITLE_SP.sp,
              lineHeight = (Ds.TITLE_SP * 1.4).sp,
              color = theme.text
            )
            Spacer(Modifier.height(Ds.s8))
            Text(
              "Enter your 4-digit PIN",
              fontSize = Ds.HINT_SP.sp,
              lineHeight = (Ds.HINT_SP * 1.4).sp,
              color = theme.textSecondary
            )
            Spacer(Modifier.height(Ds.s20))
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
              shape = RoundedCornerShape(Ds.buttonRadius)
            )
            if (pinError) {
              Spacer(Modifier.height(Ds.s8))
              Text(
                "Wrong PIN — try again",
                fontSize = Ds.HINT_SP.sp,
                color = MaterialTheme.colorScheme.error
              )
            }
            Spacer(Modifier.height(Ds.s16))
            Button(
              onClick = {
                if (pinMatches(savedPinStored, pinInput)) {
                  // Migrate a legacy plain-text PIN to its SHA-256 hash.
                  if (!savedPinStored.startsWith("sha256:")) {
                    lockPrefs.edit().putString("app_pin", hashPin(pinInput)).apply()
                  }
                  unlocked = true
                } else {
                  pinError = true
                  pinInput = ""
                  Toast.makeText(ctx, "Wrong PIN", Toast.LENGTH_SHORT).show()
                }
              },
              enabled = pinInput.length == 4,
              colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
              shape = RoundedCornerShape(Ds.buttonRadius),
              modifier = Modifier.fillMaxWidth().height(Ds.secondaryButtonHeight)
            ) {
              Text(
                "UNLOCK",
                fontFamily = PixelFont,
                color = theme.textOnPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
              )
            }
            Spacer(Modifier.height(Ds.s8))
            TextButton(
              onClick = { showForgotPin = true },
              modifier = Modifier.defaultMinSize(minHeight = Ds.minTouch)
            ) {
              Text(
                "Forgot PIN?",
                fontSize = Ds.HINT_SP.sp,
                color = theme.textSecondary
              )
            }
            // Forgot PIN: there is NO one-tap unlock. Resetting a
            // forgotten PIN requires reinstalling the app (stated at
            // setup in Settings). This dialog only says so.
            if (showForgotPin) {
              AlertDialog(
                onDismissRequest = { showForgotPin = false },
                title = {
                  Text(
                    "Forgot PIN?",
                    fontFamily = PixelFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = Ds.SECTION_SP.sp
                  )
                },
                text = {
                  Text(
                    "Resetting a forgotten PIN requires reinstalling the app. " +
                      "There is no other way to remove App Lock.",
                    fontSize = Ds.BODY_SP.sp,
                    lineHeight = (Ds.BODY_SP * 1.4).sp
                  )
                },
                confirmButton = {
                  TextButton(onClick = { showForgotPin = false }) {
                    Text("CLOSE", fontFamily = PixelFont, fontSize = Ds.HINT_SP.sp)
                  }
                }
              )
            }
          }
        } else if (activeTool != null) {
          // 11 tools only. Dead routes are NOT wired: text_pdf (Word
          // editor), editor (PDF Editor), Passport Photo and PDF to Text
          // screens exist as dead code elsewhere but are never opened
          // from here — an unknown id just returns to Home.
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

          Box(
            Modifier
              .fillMaxSize()
              .background(theme.bg)
          ) {
            // Home background layer (Home only): custom photo or tint
            // at the saved opacity over white. Other tabs stay plain.
            if (currentTab == "Home") {
              val customBmp = customBgBmp
              if (homeBgKey == "custom" && customBmp != null) {
                Image(
                  bitmap = customBmp.asImageBitmap(),
                  contentDescription = null,
                  modifier = Modifier.fillMaxSize(),
                  contentScale = ContentScale.Crop,
                  alpha = homeBgOpacity
                )
              } else {
                Box(
                  Modifier
                    .fillMaxSize()
                    .background(lerp(Color.White, homeBgTint, homeBgOpacity))
                )
              }
            }
            Column(
              Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = Ds.s16, vertical = Ds.s12)
            ) {
              // Header — CHAMI PRO kept (user confirmed). Nothing else:
              // no OFFLINE badge, no feature count, no subtext.
              Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
              ) {
                ChamiAppIcon(theme)
                Spacer(Modifier.width(Ds.s12))
                Column {
                  Text(
                    "CHAMI PRO",
                    fontFamily = PixelFont,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = Ds.TITLE_SP.sp,
                    lineHeight = (Ds.TITLE_SP * 1.4).sp,
                    color = theme.text
                  )
                  Text(
                    "Professional PDF Toolkit",
                    fontSize = Ds.HINT_SP.sp,
                    lineHeight = (Ds.HINT_SP * 1.4).sp,
                    color = theme.textSecondary
                  )
                }
              }

              // Body based on selected bottom tab
              Box(Modifier.weight(1f)) {
                when (currentTab) {
                  "History" -> HistoryScreen(
                    theme,
                    onOpenInReader = { file -> readerFile = file; activeTool = "reader" },
                    listState = historyListState
                  )
                  "Settings" -> SettingsScreen(theme, tm) { theme = it }
                  else -> {
                    LazyVerticalGrid(
                      columns = GridCells.Fixed(3),
                      state = homeGridState,
                      horizontalArrangement = Arrangement.spacedBy(Ds.s12),
                      verticalArrangement = Arrangement.spacedBy(Ds.s12),
                      contentPadding = PaddingValues(bottom = Ds.s24),
                      modifier = Modifier.fillMaxSize()
                    ) {
                      toolGroups.forEach { (heading, tools) ->
                        item(span = { GridItemSpan(3) }) {
                          Text(
                            heading,
                            fontFamily = PixelFont,
                            fontWeight = FontWeight.Bold,
                            fontSize = Ds.SECTION_SP.sp,
                            lineHeight = (Ds.SECTION_SP * 1.4).sp,
                            color = theme.primaryDark,
                            modifier = Modifier.padding(top = Ds.s24, bottom = Ds.s12)
                          )
                        }
                        items(tools) { t -> ToolTile(t) }
                      }
                    }
                  }
                }
              }

              Spacer(Modifier.height(Ds.s12))

              // Bottom navigation — real Material vector icons, never
              // emoji. Home is a proper house, NOT a lightning bolt.
              // Bar 64dp + system inset, labels always visible (11sp),
              // selected dark green / unselected grey.
              Row(
                Modifier
                  .fillMaxWidth()
                  .height(Ds.bottomNavHeight)
                  .navigationBarsPadding()
                  .clip(RoundedCornerShape(Ds.s16))
                  .background(theme.card)
                  .border(1.dp, theme.cardBorder, RoundedCornerShape(Ds.s16)),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
              ) {
                val navItems = listOf(
                  Triple("Home", Icons.Filled.Home, LocaleHelper.t(ctx, "home")),
                  Triple("History", Icons.Filled.History, LocaleHelper.t(ctx, "history")),
                  Triple("Settings", Icons.Filled.Settings, LocaleHelper.t(ctx, "settings"))
                )
                navItems.forEach { (tab, icon, label) ->
                  val isSelected = currentTab == tab
                  Column(
                    Modifier
                      .weight(1f)
                      .fillMaxHeight()
                      .clip(RoundedCornerShape(Ds.s12))
                      .background(if (isSelected) theme.primary.copy(alpha = 0.15f) else Color.Transparent)
                      .clickable {
                        vibrate()
                        if (tab == "Home" && currentTab == "Home") {
                          scope.launch { homeGridState.scrollToItem(0) }
                        } else {
                          currentTab = tab
                        }
                      }
                      .padding(vertical = Ds.s8),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                  ) {
                    Icon(
                      icon,
                      contentDescription = label,
                      tint = if (isSelected) theme.primary else theme.textSecondary,
                      modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.height(Ds.s4))
                    Text(
                      text = label,
                      color = if (isSelected) theme.primary else theme.textSecondary,
                      fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
                      fontSize = Ds.NAV_SP.sp,
                      lineHeight = (Ds.NAV_SP * 1.4).sp,
                      maxLines = 1
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
}
