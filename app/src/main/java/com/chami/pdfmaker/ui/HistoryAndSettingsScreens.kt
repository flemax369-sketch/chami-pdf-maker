package com.chami.pdfmaker.ui

import android.content.Context
import android.content.Intent
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(theme: ChamiTheme) {
  val ctx = LocalContext.current
  val haptic = LocalHapticFeedback.current
  val tm = remember { ThemeManager(ctx) }
  var fileList by remember { mutableStateOf<List<File>>(emptyList()) }
  var fileToDelete by remember { mutableStateOf<File?>(null) }

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
      val files = dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: emptyList()
      fileList = files
    } else {
      fileList = emptyList()
    }
  }

  LaunchedEffect(Unit) {
    refresh()
  }

  Column(
    Modifier
      .fillMaxSize()
      .padding(16.dp)
  ) {
    Row(
      Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Column {
        Text(
          text = "RECENT FILES",
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
      }

      OutlinedButton(
        onClick = {
          vibrate()
          refresh()
        },
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, theme.cardBorder),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = theme.primary),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
      ) {
        Text("REFRESH", fontFamily = PixelFont, fontSize = 11.sp, fontWeight = FontWeight.Bold)
      }
    }

    Spacer(Modifier.height(16.dp))

    if (fileList.isEmpty()) {
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
            fontSize = 17.sp,
            color = theme.text
          )
          Spacer(Modifier.height(6.dp))
          Text(
            "Created PDFs, scans, and converted files will appear here.",
            fontFamily = PixelFont,
            fontSize = 12.sp,
            color = theme.textSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
          )
        }
      }
    } else {
      val sdf = remember { SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()) }
      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        items(fileList) { file ->
          val mime = if (file.name.endsWith(".pdf", ignoreCase = true)) "application/pdf"
          else if (file.name.endsWith(".txt", ignoreCase = true)) "text/plain"
          else "image/jpeg"

          val isPdf = file.name.endsWith(".pdf", true)
          val isTxt = file.name.endsWith(".txt", true)

          Column(
            Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(14.dp))
              .background(theme.card)
              .border(1.dp, theme.cardBorder, RoundedCornerShape(14.dp))
              .combinedClickable(
                onClick = {
                  vibrate()
                  ShareHelper.openFile(ctx, file, mime)
                },
                onLongClick = {
                  vibrate()
                  fileToDelete = file
                }
              )
              .padding(14.dp)
          ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Box(
                Modifier
                  .size(42.dp)
                  .clip(RoundedCornerShape(10.dp))
                  .background(theme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
              ) {
                Text(
                  if (isPdf) "📄" else if (isTxt) "📝" else "🖼️",
                  fontSize = 20.sp
                )
              }
              Spacer(Modifier.width(12.dp))
              Column(Modifier.weight(1f)) {
                Text(
                  file.name,
                  fontFamily = PixelFont,
                  fontWeight = FontWeight.Bold,
                  fontSize = 13.sp,
                  color = theme.text,
                  maxLines = 1
                )
                Spacer(Modifier.height(3.dp))
                Text(
                  "${file.length() / 1024} KB • ${sdf.format(Date(file.lastModified()))}",
                  fontFamily = PixelFont,
                  fontSize = 11.sp,
                  color = theme.textSecondary
                )
              }
              IconButton(onClick = {
                vibrate()
                fileToDelete = file
              }) {
                Text("🗑️", fontSize = 16.sp)
              }
            }

            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              Button(
                onClick = {
                  vibrate()
                  ShareHelper.openFile(ctx, file, mime)
                },
                modifier = Modifier.weight(1f).height(40.dp),
                colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
                shape = RoundedCornerShape(8.dp)
              ) {
                Text(
                  "OPEN",
                  fontFamily = PixelFont,
                  color = theme.textOnPrimary,
                  fontSize = 12.sp,
                  fontWeight = FontWeight.Bold
                )
              }
              OutlinedButton(
                onClick = {
                  vibrate()
                  ShareHelper.shareFile(ctx, file, mime)
                },
                modifier = Modifier.weight(1f).height(40.dp),
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, theme.cardBorder),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = theme.text)
              ) {
                Text(
                  "SHARE",
                  fontFamily = PixelFont,
                  color = theme.text,
                  fontSize = 12.sp,
                  fontWeight = FontWeight.Bold
                )
              }
            }
          }
        }
      }
    }

    if (fileToDelete != null) {
      AlertDialog(
        onDismissRequest = { fileToDelete = null },
        confirmButton = {
          Button(
            onClick = {
              vibrate()
              fileToDelete?.delete()
              fileToDelete = null
              refresh()
              Toast.makeText(ctx, "File deleted", Toast.LENGTH_SHORT).show()
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
  var askSave by remember { mutableStateOf(tm.askSaveLocation()) }
  var hapticOn by remember { mutableStateOf(tm.hapticOn()) }

  var showClearCacheDialog by remember { mutableStateOf(false) }
  var showPrivacyPolicy by remember { mutableStateOf(false) }

  fun vibrate() {
    if (hapticOn) {
      try {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
      } catch (_: Exception) {}
    }
  }

  fun shareWithFriends() {
    vibrate()
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
      type = "text/plain"
      putExtra(
        Intent.EXTRA_TEXT,
        "Try Chami - Professional PDF & Scanner Suite! Convert photos to PDF, scan documents, protect PDFs with password, compress to KB, split/merge & more. Fast, 100% offline with zero lag!"
      )
    }
    ctx.startActivity(Intent.createChooser(shareIntent, "Share Chami with colleagues & friends"))
  }

  Column(
    Modifier
      .fillMaxSize()
      .padding(16.dp)
      .verticalScroll(rememberScrollState())
  ) {
    Text(
      text = "SETTINGS & PREFERENCES",
      fontFamily = PixelFont,
      fontWeight = FontWeight.ExtraBold,
      fontSize = 20.sp,
      color = theme.text
    )
    Text(
      text = "Customize appearance, haptics and security",
      fontFamily = PixelFont,
      fontSize = 12.sp,
      color = theme.textSecondary
    )

    Spacer(Modifier.height(18.dp))

    // SECTION 1: THEME & COLOR PALETTE
    Text(
      "THEME & PALETTE",
      fontFamily = PixelFont,
      fontWeight = FontWeight.Bold,
      fontSize = 13.sp,
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
                fontSize = 14.sp,
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

    // SECTION 2: HARDWARE & INTERACTION
    Text(
      "CONTROLS & HAPTICS",
      fontFamily = PixelFont,
      fontWeight = FontWeight.Bold,
      fontSize = 13.sp,
      color = theme.primary
    )
    Spacer(Modifier.height(8.dp))

    Column(
      Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(16.dp))
        .background(theme.card)
        .border(1.dp, theme.cardBorder, RoundedCornerShape(16.dp))
        .padding(14.dp)
    ) {
      Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column(Modifier.weight(1f)) {
          Text(
            "HAPTIC FEEDBACK",
            fontFamily = PixelFont,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = theme.text
          )
          Spacer(Modifier.height(2.dp))
          Text(
            "Tactile click vibration on buttons and chips",
            fontFamily = PixelFont,
            fontSize = 11.sp,
            color = theme.textSecondary
          )
        }
        Switch(
          checked = hapticOn,
          onCheckedChange = {
            hapticOn = it
            tm.setHaptic(it)
            if (it) {
              try {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
              } catch (_: Exception) {}
            }
          },
          colors = SwitchDefaults.colors(
            checkedThumbColor = theme.primary,
            checkedTrackColor = theme.primary.copy(alpha = 0.3f)
          )
        )
      }

      HorizontalDivider(
        Modifier.padding(vertical = 12.dp),
        thickness = 1.dp,
        color = theme.cardBorder
      )

      Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column(Modifier.weight(1f)) {
          Text(
            "ASK WHERE TO SAVE",
            fontFamily = PixelFont,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = theme.text
          )
          Spacer(Modifier.height(2.dp))
          Text(
            "Prompt to choose download/storage folder after creating each file",
            fontFamily = PixelFont,
            fontSize = 11.sp,
            color = theme.textSecondary
          )
        }
        Switch(
          checked = askSave,
          onCheckedChange = {
            askSave = it
            tm.setAskSaveLocation(it)
          },
          colors = SwitchDefaults.colors(
            checkedThumbColor = theme.primary,
            checkedTrackColor = theme.primary.copy(alpha = 0.3f)
          )
        )
      }
    }

    Spacer(Modifier.height(20.dp))

    // SECTION 3: SHARE WITH FRIENDS
    Column(
      Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(16.dp))
        .background(theme.card)
        .border(1.dp, theme.cardBorder, RoundedCornerShape(16.dp))
        .padding(16.dp)
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text("👥", fontSize = 20.sp)
        Spacer(Modifier.width(10.dp))
        Text(
          "SHARE WITH FRIENDS & COLLEAGUES",
          fontFamily = PixelFont,
          fontWeight = FontWeight.ExtraBold,
          fontSize = 13.sp,
          color = theme.text
        )
      }
      Spacer(Modifier.height(6.dp))
      Text(
        "Recommend this fast, private, 100% offline PDF utility that respects user privacy with no subscriptions.",
        fontFamily = PixelFont,
        fontSize = 12.sp,
        color = theme.textSecondary
      )
      Spacer(Modifier.height(14.dp))
      Button(
        onClick = { shareWithFriends() },
        modifier = Modifier.fillMaxWidth().height(44.dp),
        colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
        shape = RoundedCornerShape(10.dp)
      ) {
        Text(
          "SHARE CHAMI APP",
          fontFamily = PixelFont,
          color = theme.textOnPrimary,
          fontSize = 12.sp,
          fontWeight = FontWeight.Bold
        )
      }
    }

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

    // BRAND FOOTER
    Column(
      Modifier.fillMaxWidth(),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      Text(
        "CHAMI PRO • PDF & SCANNER SUITE",
        fontFamily = PixelFont,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        color = theme.textSecondary
      )
      Spacer(Modifier.height(3.dp))
      Text(
        "Version 1.2 • 100% Offline • Zero Lag • Secure",
        fontFamily = PixelFont,
        fontSize = 11.sp,
        color = theme.textSecondary.copy(alpha = 0.7f)
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
          Text("Clear Temporary Cache?", fontFamily = PixelFont, fontWeight = FontWeight.Bold, fontSize = 16.sp)
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
          Text("Privacy Policy", fontFamily = PixelFont, fontWeight = FontWeight.Bold, fontSize = 16.sp)
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
