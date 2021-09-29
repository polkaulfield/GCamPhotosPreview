package com.google.android.apps.photos

import android.Manifest.permission.READ_EXTERNAL_STORAGE
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View.INVISIBLE
import android.view.View.VISIBLE
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2

const val TAG = "FakePhotos"

class MainActivity : FragmentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(RequestPermission()) { ok ->
        if (ok) {
            intentHandler.handleIntent(intent, this::showUri)
        } else {
            Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    private lateinit var viewPager: ViewPager2
    private lateinit var progressBar: ProgressBar
    private lateinit var textView: TextView

    private lateinit var intentHandler: IntentHandler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        viewPager = findViewById(R.id.pager)
        progressBar = findViewById(R.id.progressBar)
        textView = findViewById(R.id.textView)

        intentHandler = IntentHandler(contentResolver, mainLooper, progressBar) {
            setShowWhenLocked(true)
        }

        if (intent.`package` == packageName) {
            if (checkSelfPermission(READ_EXTERNAL_STORAGE) == PERMISSION_GRANTED) {
                intentHandler.handleIntent(intent, this::showUri)
            } else {
                requestPermissionLauncher.launch(READ_EXTERNAL_STORAGE)
            }
        } else {
            @SuppressLint("SetTextI18n")
            textView.text = "Unknown intent:\n\n$intent"
            textView.visibility = VISIBLE
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        Log.e(TAG, "onNewIntent: $intent")
    }

    private fun showUri(uri: Uri, secureIds: List<Long>?) {
        progressBar.visibility = INVISIBLE
        val uris = if (secureIds == null) {
            intentHandler.getUris(uri)
        } else {
            intentHandler.getUrisFromIds(secureIds)
        }

        // the observer still fires after getting unregistered, so we add the adapter only once
        if (viewPager.adapter == null) {
            val camIntent = intentHandler.getCamPendingIntent(this, intent, secureIds != null)
            Log.d(TAG, "create ViewPagerAdapter")
            viewPager.adapter = ViewPagerAdapter(this, camIntent, uris)
            viewPager.setCurrentItem(1, false)
            viewPager.offscreenPageLimit = 1 // pre-load prev/next page automatically
        }
    }

    private inner class ViewPagerAdapter(
        fa: FragmentActivity,
        private val camIntent: PendingIntent,
        private val uris: List<Uri>
    ) : FragmentStateAdapter(fa) {

        override fun getItemCount(): Int = uris.size + 1

        override fun createFragment(position: Int): Fragment {
            Log.d(TAG, "createFragment $position")
            return if (position == 0) CamFragment.newInstance(camIntent)
            else ImageFragment.newInstance(uris[position - 1])
        }
    }

}
