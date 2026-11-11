package com.chami.pdfmaker.ads

import android.app.Activity
import android.content.Context
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback

object AdsManager {
  // Google TEST interstitial unit ID. Replace with your real unit ID later.
  private const val UNIT_ID = "ca-app-pub-3940256099942544/1033173712"
  private var ad: InterstitialAd? = null
  private var lastShown = 0L
  private var savesSinceAd = 0

  fun load(ctx: Context) {
    InterstitialAd.load(ctx, UNIT_ID, AdRequest.Builder().build(),
      object : InterstitialAdLoadCallback() {
        override fun onAdLoaded(a: InterstitialAd) { ad = a }
        override fun onAdFailedToLoad(e: LoadAdError) { ad = null }
      })
  }

  // Show at most once every 3 saves AND at least 60 seconds apart.
  // onDone() is ALWAYS called — even if no ad is ready — so the file
  // is NEVER blocked by an ad.
  fun showBeforeSave(activity: Activity, onDone: () -> Unit) {
    savesSinceAd++
    val now = System.currentTimeMillis()
    val ready = ad != null
    val due = savesSinceAd >= 3 && now - lastShown >= 60_000
    if (!ready || !due) { onDone(); return }
    val toShow = ad; ad = null
    savesSinceAd = 0; lastShown = now
    toShow?.fullScreenContentCallback =
      object : com.google.android.gms.ads.FullScreenContentCallback() {
        override fun onAdDismissedFullScreenContent() { load(activity); onDone() }
        override fun onAdFailedToShowFullScreenContent(e: com.google.android.gms.ads.AdError) { load(activity); onDone() }
      }
    toShow?.show(activity) ?: onDone()
  }
}
