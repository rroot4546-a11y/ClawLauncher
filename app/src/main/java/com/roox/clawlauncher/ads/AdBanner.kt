package com.roox.clawlauncher.ads

import android.util.Log
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView

@Composable
fun AdBanner(modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp),
        factory = { ctx ->
            AdView(ctx).apply {
                this.adUnitId = "ca-app-pub-2662987126681282/4456987632"
                setAdSize(AdSize.BANNER)
                adListener = object : com.google.android.gms.ads.AdListener() {
                    override fun onAdFailedToLoad(error: com.google.android.gms.ads.LoadAdError) {
                        Log.w("AdBanner", "Banner failed: ${error.message}")
                    }
                }
                loadAd(AdRequest.Builder().build())
            }
        }
    )
}
