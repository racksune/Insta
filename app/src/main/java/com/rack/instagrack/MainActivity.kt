package com.rack.instagrack

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast

/** Icon dabate hi: overlay box dikhao aur seedha Instagram kholo. */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val svc = UnfollowService.instance
        if (svc == null) {
            Toast.makeText(
                this,
                "Accessibility mein Instagrack ON karo, phir app dobara kholo",
                Toast.LENGTH_LONG
            ).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } else {
            svc.showOverlay()
            val ig = packageManager.getLaunchIntentForPackage(UnfollowService.IG)
            if (ig != null) startActivity(ig)
            else Toast.makeText(this, "Instagram install nahi hai", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
