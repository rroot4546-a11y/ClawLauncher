package com.roox.clawlauncher.ads

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback

object AdManager {
    private const val TAG = "AdManager"
    // Real AdMob Ad Unit ID
    private const val INTERSTITIAL_AD_UNIT_ID = "ca-app-pub-2662987126681282/3749832498"

    private var interstitialAd: InterstitialAd? = null
    private var isLoading = false

    fun initialize(context: Context) {
        MobileAds.initialize(context) {
            Log.d(TAG, "AdMob initialized")
            loadInterstitial(context)
        }
    }

    fun loadInterstitial(context: Context) {
        if (isLoading || interstitialAd != null) return
        isLoading = true
        val adRequest = AdRequest.Builder().build()
        InterstitialAd.load(context, INTERSTITIAL_AD_UNIT_ID, adRequest,
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitialAd = ad
                    isLoading = false
                    Log.d(TAG, "Interstitial ad loaded")
                }
                override fun onAdFailedToLoad(error: LoadAdError) {
                    interstitialAd = null
                    isLoading = false
                    Log.w(TAG, "Ad failed to load: ${error.message}")
                }
            })
    }

    fun showInterstitial(activity: Activity, onDismiss: () -> Unit = {}) {
        val ad = interstitialAd
        if (ad != null) {
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    interstitialAd = null
                    loadInterstitial(activity)
                    onDismiss()
                }
            }
            ad.show(activity)
        } else {
            loadInterstitial(activity)
            onDismiss()
        }
    }

    fun isReady(): Boolean = interstitialAd != null
}
