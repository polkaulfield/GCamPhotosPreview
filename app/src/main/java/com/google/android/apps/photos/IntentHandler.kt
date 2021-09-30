/*
 * Copyright 2021 The Calyx Institute
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.apps.photos

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA
import android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE
import android.util.Log
import android.view.View
import android.widget.ProgressBar

private const val PACKAGE_GOOGLE_CAM = "com.google.android.GoogleCamera"
private const val SECURE_MODE = "com.google.android.apps.photos.api.secure_mode"
private const val INTENT_CAM = "CAMERA_RELAUNCH_INTENT_EXTRA"
private const val INTENT_CAM_SECURE = "CAMERA_RELAUNCH_SECURE_INTENT_EXTRA"
private const val EXTRA_PROCESSING = "processing_uri_intent_extra"
private const val EXTRA_SECURE_IDS = "com.google.android.apps.photos.api.secure_mode_ids"

class IntentHandler(
    private val contentResolver: ContentResolver,
    private val mainLooper: Looper,
    private val progressBar: ProgressBar,
    private val setShowWhenLocked: () -> Unit,
) {

    fun handleIntent(intent: Intent, showUri: (Uri, List<Long>?) -> Unit) {
        if (BuildConfig.DEBUG) log(intent)

        val isSecure = intent.getBooleanExtra(SECURE_MODE, false)
        if (isSecure) setShowWhenLocked()

        val secureIds = if (isSecure) {
            (intent.extras?.getSerializable(EXTRA_SECURE_IDS) as LongArray).toList()
        } else null

        val uri = intent.data!!
        val processingUri = intent.getParcelableExtra<Uri>(EXTRA_PROCESSING)
        if (processingUri == null) showUri(uri, secureIds)
        else observeUri(uri, secureIds, showUri)
    }

    private fun observeUri(uri: Uri, secureIds: List<Long>?, showUri: (Uri, List<Long>?) -> Unit) {
        progressBar.visibility = View.VISIBLE
        val handler = Handler(mainLooper)
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                Log.e(TAG, "uri changed: (selfChange: $selfChange) $uri")
                if (isUriReady(uri)) {
                    showUri(uri, secureIds)
                    contentResolver.unregisterContentObserver(this)
                }
            }
        }
        contentResolver.registerContentObserver(uri, false, observer)
    }

    private fun isUriReady(uri: Uri): Boolean {
        return contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.IS_PENDING),
            null,
            null,
            null
        )?.use { c ->
            while (c.moveToNext()) {
                return@use (c.getInt(0) == 0).apply {
                    Log.e(TAG, "is ready: $this")
                }
            }
            return@use false
        } ?: return false
    }

    fun getUris(uri: Uri): List<Uri> {
        val bucketIdProjection = arrayOf(MediaStore.MediaColumns.BUCKET_ID)
        val bucketId = contentResolver.query(uri, bucketIdProjection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                Log.e(TAG, "bucketId: ${c.getInt(0)}")
                return@use c.getInt(0)
            }
            return@use null
        } ?: return listOf(uri)
        val ids = ArrayList<Long>()
        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.MIME_TYPE),
            "${MediaStore.MediaColumns.BUCKET_ID} = ?",
            arrayOf(bucketId.toString()),
            "${MediaStore.MediaColumns._ID} DESC"
        )?.use { c ->
            while (c.moveToNext()) {
                ids.add(c.getLong(0))
            }
        }
        if (ids.isEmpty()) {
            Log.e(TAG, "Warning query returned no results")
            return listOf(uri)
        }
        return getUrisFromIds(ids)
    }

    fun getUrisFromIds(ids: List<Long>): List<Uri> = ids.map {
        ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, it)
    }

    fun getCamPendingIntent(context: Context, intent: Intent, isSecure: Boolean): PendingIntent {
        val camIntentKey = if (isSecure) INTENT_CAM_SECURE else INTENT_CAM
        return intent.getParcelableExtra(camIntentKey) ?: PendingIntent.getActivity(
            context, 0, getCamIntent(isSecure), PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun getCamIntent(isSecure: Boolean = false) = Intent(
        if (isSecure) INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE
        else INTENT_ACTION_STILL_IMAGE_CAMERA
    ).apply {
        `package` = PACKAGE_GOOGLE_CAM
    }

    private fun log(intent: Intent?) {
        Log.e(TAG, "intent: $intent")
        val extras = intent?.extras ?: return
        extras.keySet().forEach { key ->
            val value = extras.get(key)
            Log.e(TAG, "$key: $value")
        }
    }

}
