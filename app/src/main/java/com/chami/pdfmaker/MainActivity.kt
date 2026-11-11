package com.chami.pdfmaker

import android.os.Bundle
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.chami.pdfmaker.ads.AdsManager
import com.google.android.gms.ads.MobileAds
import com.chami.pdfmaker.theme.*
import com.chami.pdfmaker.ui.*

data class ToolItem(val id: String, val title: String, val iconRes: Int, val category: String = "CORE")

class MainActivity : ComponentActivity() {
  override fun onCreate(b: Bundle?) {
    super.onCreate(b)
    MobileAds.initialize(this)
    AdsManager.load(this)
    val tm = ThemeManager(this)
    setContent {
      var theme by remember { mutableStateOf(tm.current()) }
      var activeTool by remember { mutableStateOf<String?>(null) }
      var currentTab by remember { mutableStateOf("Home") }
      val haptic = LocalHapticFeedback.current

      fun vibrate() {
        if (tm.hapticOn()) {
          try {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
          } catch (_: Exception) {}
        }
      }

      val tools = listOf(
        ToolItem("img_pdf", "Images to PDF", R.drawable.tool_ic_01_images_to_pdf, "CREATE"),
        ToolItem("scan", "Scan to PDF", R.drawable.tool_ic_02_scan_to_pdf, "CREATE"),
        ToolItem("protect", "Protect PDF", R.drawable.tool_ic_03_protect_pdf, "SECURITY"),
        ToolItem("compress", "Compress KB", R.drawable.tool_ic_04_compress_kb, "OPTIMIZE"),
        ToolItem("merge", "Merge PDF", R.drawable.tool_ic_05_merge_pdf, "EDIT"),
        ToolItem("split", "Split PDF", R.drawable.tool_ic_06_split_pdf, "EDIT"),
        ToolItem("pdf_img", "PDF to Images", R.drawable.tool_ic_07_pdf_to_images, "CONVERT"),
        ToolItem("passport", "Passport Photo", R.drawable.tool_ic_08_passport_photo, "PHOTO"),
        ToolItem("resize", "Image Resizer", R.drawable.tool_ic_09_image_resizer, "PHOTO"),
        ToolItem("reader", "PDF Reader", R.drawable.tool_ic_10_pdf_reader, "VIEW"),
        ToolItem("ocr", "OCR Text", R.drawable.tool_ic_11_ocr_text, "EXTRACT"),
        ToolItem("pdf_text", "PDF to Text", R.drawable.tool_ic_12_pdf_to_text, "CONVERT"),
        ToolItem("text_pdf", "Text to PDF", R.drawable.tool_ic_13_text_to_pdf, "CREATE"),
        ToolItem("convert", "Format Convert", R.drawable.tool_ic_14_format_convert, "PHOTO"),
        ToolItem("editor", "PDF Editor", R.drawable.tool_ic_15_pdf_editor, "EDIT")
      )

      Surface(modifier = Modifier.fillMaxSize(), color = theme.bg) {
        if (activeTool != null) {
          when (activeTool) {
            "img_pdf" -> ImagesToPdfScreen(theme) { activeTool = null }
            "scan" -> ScanToPdfScreen(theme) { activeTool = null }
            "protect" -> ProtectPdfScreen(theme) { activeTool = null }
            "pdf_img" -> PdfToImagesScreen(theme) { activeTool = null }
            "split" -> SplitPdfScreen(theme) { activeTool = null }
            "merge" -> MergePdfScreen(theme) { activeTool = null }
            "compress" -> CompressKbScreen(theme) { activeTool = null }
            "passport" -> PassportPhotoScreen(theme) { activeTool = null }
            "resize" -> ResizeConvertScreen(theme, title = "Image Resizer") { activeTool = null }
            "convert" -> ResizeConvertScreen(theme, title = "Format Convert") { activeTool = null }
            "ocr" -> OcrScreen(theme) { activeTool = null }
            "reader" -> PdfReaderScreen(theme) { activeTool = null }
            "pdf_text" -> PdfToTextScreen(theme) { activeTool = null }
            "text_pdf" -> WordTextEditorScreen(theme) { activeTool = null }
            "editor" -> PdfEditorScreen(theme) { activeTool = null }
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
              .background(theme.bg)
              .statusBarsPadding()
              .padding(horizontal = 16.dp, vertical = 12.dp)
          ) {
            // Main Header
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
                "History" -> HistoryScreen(theme)
                "Settings" -> SettingsScreen(theme, tm) { theme = it }
                else -> {
                  Column(Modifier.fillMaxSize()) {
                    // Quick Action Hero Row
                    Row(
                      Modifier.fillMaxWidth(),
                      horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                      // Quick Action 1: Images to PDF
                      Row(
                        Modifier
                          .weight(1f)
                          .clip(RoundedCornerShape(14.dp))
                          .background(theme.card)
                          .border(1.dp, theme.cardBorder, RoundedCornerShape(14.dp))
                          .clickable {
                            vibrate()
                            activeTool = "img_pdf"
                          }
                          .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                      ) {
                        Box(
                          Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(theme.primary.copy(alpha = 0.12f)),
                          contentAlignment = Alignment.Center
                        ) {
                          Image(
                            painter = painterResource(id = R.drawable.tool_ic_01_images_to_pdf),
                            contentDescription = "Photos to PDF",
                            modifier = Modifier.size(28.dp),
                            contentScale = ContentScale.Fit
                          )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                          Text(
                            "Photos to PDF",
                            fontFamily = PixelFont,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = theme.text
                          )
                          Text(
                            "Batch Convert",
                            fontFamily = PixelFont,
                            fontSize = 10.sp,
                            color = theme.textSecondary
                          )
                        }
                      }

                      // Quick Action 2: Protect PDF
                      Row(
                        Modifier
                          .weight(1f)
                          .clip(RoundedCornerShape(14.dp))
                          .background(theme.card)
                          .border(1.dp, theme.cardBorder, RoundedCornerShape(14.dp))
                          .clickable {
                            vibrate()
                            activeTool = "protect"
                          }
                          .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                      ) {
                        Box(
                          Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(theme.primary.copy(alpha = 0.12f)),
                          contentAlignment = Alignment.Center
                        ) {
                          Image(
                            painter = painterResource(id = R.drawable.tool_ic_03_protect_pdf),
                            contentDescription = "Protect PDF",
                            modifier = Modifier.size(28.dp),
                            contentScale = ContentScale.Fit
                          )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                          Text(
                            "Protect PDF",
                            fontFamily = PixelFont,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = theme.text
                          )
                          Text(
                            "Password Lock",
                            fontFamily = PixelFont,
                            fontSize = 10.sp,
                            color = theme.textSecondary
                          )
                        }
                      }
                    }

                    Spacer(Modifier.height(14.dp))

                    Row(
                      Modifier.fillMaxWidth(),
                      horizontalArrangement = Arrangement.SpaceBetween,
                      verticalAlignment = Alignment.CenterVertically
                    ) {
                      Text(
                        "ALL TOOLS",
                        fontFamily = PixelFont,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 13.sp,
                        color = theme.text
                      )
                      Text(
                        "${tools.size} features",
                        fontFamily = PixelFont,
                        fontSize = 11.sp,
                        color = theme.textSecondary
                      )
                    }

                    Spacer(Modifier.height(8.dp))

                    LazyVerticalGrid(
                      columns = GridCells.Fixed(3),
                      horizontalArrangement = Arrangement.spacedBy(10.dp),
                      verticalArrangement = Arrangement.spacedBy(10.dp),
                      modifier = Modifier.fillMaxSize()
                    ) {
                      items(tools) { t ->
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
                    }
                  }
                }
              }
            }

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
                Triple("Home", "⚡", "Home"),
                Triple("History", "📁", "History"),
                Triple("Settings", "⚙️", "Settings")
              )

              navItems.forEach { (tab, icon, label) ->
                val isSelected = currentTab == tab
                Row(
                  Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isSelected) theme.primary.copy(alpha = 0.15f) else androidx.compose.ui.graphics.Color.Transparent)
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
