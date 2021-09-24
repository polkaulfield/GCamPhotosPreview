package com.google.android.apps.photos

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.Intent.CATEGORY_BROWSABLE
import android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
import android.content.Intent.FLAG_ACTIVITY_NO_HISTORY
import android.os.Bundle
import android.util.Log
import android.widget.TextView

private const val TAG = "FakePhotos"

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        if (intent.`package` == packageName) {
            if (BuildConfig.DEBUG) log(intent)
            val newIntent = Intent(intent).apply {
                // allow other apps to handle the intent
                `package` = null
                component = null
                // prevent ourselves from handling the intent
                addCategory(CATEGORY_BROWSABLE)
                // clear flags that break Gallery2
                flags = FLAG_ACTIVITY_NO_HISTORY and FLAG_ACTIVITY_CLEAR_TOP
            }
            Log.i(TAG, "re-firing intent: $intent")
            Log.i(TAG, "as $newIntent")
            startActivity(newIntent)
            finish()
        } else {
            @SuppressLint("SetTextI18n")
            findViewById<TextView>(R.id.textView).text = "Unknown intent:\n\n$intent"
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        Log.e(TAG, "onNewIntent: $intent")
    }

    private fun log(intent: Intent?) {
        val extras = intent?.extras ?: return
        extras.keySet().forEach { key ->
            val value = extras.get(key)
            Log.e(TAG, "$key: $value")
        }
    }

}
