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
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.provider.MediaStore
import android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA
import android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE
import android.provider.MediaStore.MediaColumns.IS_PENDING
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private const val PACKAGE_GOOGLE_CAM = "com.google.android.GoogleCamera"
private const val SECURE_MODE = "com.google.android.apps.photos.api.secure_mode"
private const val INTENT_CAM = "CAMERA_RELAUNCH_INTENT_EXTRA"
private const val INTENT_CAM_SECURE = "CAMERA_RELAUNCH_SECURE_INTENT_EXTRA"
private const val EXTRA_PROCESSING = "processing_uri_intent_extra"
private const val EXTRA_SECURE_IDS = "com.google.android.apps.photos.api.secure_mode_ids"

class IntentHandler(private val context: Context) {

    private val contentResolver = context.contentResolver
    private val handler = Handler(context.mainLooper)

    fun isSecure(intent: Intent) = intent.getBooleanExtra(SECURE_MODE, false)

    fun handleIntent(intent: Intent): Flow<List<PagerItem>> = flow {
        if (BuildConfig.DEBUG) log(intent)

        val isSecure = isSecure(intent)
        val uri = intent.data!!
        // TODO see if we can get a processing preview somehow
        val processingUri = intent.getParcelableExtra<Uri>(EXTRA_PROCESSING)
        val uriIsReady = isUriReady(uri)
        val items = ArrayList<PagerItem>().apply {
            add(PagerItem.CamItem(pendingIntent = getCamPendingIntent(intent, isSecure)))
            add(PagerItem.UriItem(getIdFromUri(uri), uri, uriIsReady))
        }
        emit(items)

        // create PagerItems for other Uris
        val newItems = ArrayList(items)
        val extraUris: List<Uri> = if (isSecure) {
            val secureIds = (intent.extras?.getSerializable(EXTRA_SECURE_IDS) as LongArray).toList()
            getUrisFromIds(secureIds)
        } else whenReady(uri, uriIsReady) {
            newItems.removeLast() // will be returned again in next call
            // TODO emit update as soon as first URI is ready
            getUris(uri)
        }
        var allReady = true
        extraUris.forEach { nextUri ->
            val item = PagerItem.UriItem(
                id = getIdFromUri(nextUri),
                uri = nextUri,
                ready = isUriReady(nextUri),
            )
            newItems.add(item)
            allReady = allReady && item.ready
        }
        emit(newItems)

        // return early of all are ready
        if (allReady) return@flow

        // create final PagerItems (we could emit more frequently, but don't bother for now)
        Log.d(TAG, "Creating final pager items")
        val finalItems = ArrayList<PagerItem>(newItems.size)
        newItems.forEach { item ->
            when (item) {
                is PagerItem.CamItem -> finalItems.add(item)
                is PagerItem.UriItem -> {
                    whenReady(item.uri, item.ready) {
                        finalItems.add(item.copy(ready = true))
                    }
                }
            }
        }
        emit(finalItems)
    }

    private fun getCamPendingIntent(intent: Intent, isSecure: Boolean): PendingIntent {
        val camIntentKey = if (isSecure) INTENT_CAM_SECURE else INTENT_CAM
        return intent.getParcelableExtra(camIntentKey) ?: PendingIntent.getActivity(
            context, 0, getCamIntent(isSecure), PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun getCamIntent(isSecure: Boolean = false) = Intent(
        if (isSecure) INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE
        else INTENT_ACTION_STILL_IMAGE_CAMERA
    ).apply {
        `package` = PACKAGE_GOOGLE_CAM
    }

    private fun getIdFromUri(uri: Uri): Long {
        return uri.lastPathSegment?.toLong() ?: error("Can't get ID from $uri")
    }

    private fun isUriReady(uri: Uri): Boolean {
        return getReadyCursor(uri)?.use { c ->
            isCursorReady(c)
        } ?: return false
    }

    private fun isCursorReady(c: Cursor): Boolean {
        c.moveToNext()
        return c.getInt(0) == 0
    }

    private fun getReadyCursor(uri: Uri): Cursor? {
        return contentResolver.query(uri, arrayOf(IS_PENDING), null, null, null)
    }

    private suspend fun <T> whenReady(uri: Uri, isReady: Boolean, block: () -> T): T {
        if (!isReady) waitForUriToBecomeReady(uri)
        return block()
    }

    private suspend fun waitForUriToBecomeReady(uri: Uri) =
        suspendCancellableCoroutine<Unit> { continuation ->
            Log.e(TAG, "waitForUriToBecomeReady $uri")
            val cursor = getReadyCursor(uri) ?: error("ready cursor null for $uri")
            if (isCursorReady(cursor)) {
                Log.d(TAG, "cursor was ready early for $uri")
                continuation.resume(Unit)
                return@suspendCancellableCoroutine
            }
            val observer = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) {
                    Log.e(TAG, "uri changed while waiting: $uri")
                    if (isUriReady(uri)) {
                        // need to check if still active, because fires several times
                        if (continuation.isActive) {
                            cursor.unregisterContentObserver(this)
                            continuation.resume(Unit)
                        }
                    }
                }
            }
            cursor.registerContentObserver(observer)
            continuation.invokeOnCancellation { cursor.unregisterContentObserver(observer) }
        }

    private fun getUris(uri: Uri): List<Uri> {
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

    private fun getUrisFromIds(ids: List<Long>): List<Uri> = ids.map {
        ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, it)
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
