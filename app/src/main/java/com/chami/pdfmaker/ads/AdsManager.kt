package com.chami.pdfmaker.ads

import android.app.Activity
import android.content.Context

/**
 * Phase 6 — ADS REMOVED (user decision, 2026-11-13).
 *
 * No ad is ever loaded or shown in this build: no interstitial before
 * a save, no banner anywhere. The AdMob IDs are deliberately NOT in
 * this file (they are held in memory for later) and no AdMob class is
 * referenced here, so nothing in this object can show an ad.
 *
 * load() and showBeforeSave() are kept with their original signatures
 * so every existing call site (MainActivity, ToolScreens,
 * PdfEditorScreen, WordTextEditorScreen) still compiles unchanged —
 * they are now harmless no-ops and showBeforeSave() always runs
 * onDone() immediately, so a save is never delayed.
 *
 * When the user says ads are wanted back, restore the interstitial
 * (every 3rd save + 60s min, never blocks save) and the Home banner
 * from the pre-Phase-6 version in /tmp/chami-v12-phase6-backup/.
 */
object AdsManager {

  fun load(ctx: Context) {
    // No-op (Phase 6): ads removed, nothing is loaded.
  }

  // No-op (Phase 6): ads removed. onDone() runs immediately, always.
  fun showBeforeSave(activity: Activity, onDone: () -> Unit) {
    onDone()
  }
}
