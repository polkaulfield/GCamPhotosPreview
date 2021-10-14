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
import android.content.ContentResolver.*
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.provider.MediaStore
import android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA
import android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE
import android.provider.MediaStore.MediaColumns
import android.provider.MediaStore.MediaColumns.IS_PENDING
import android.provider.MediaStore.QUERY_ARG_MATCH_PENDING
import android.provider.MediaStore.VOLUME_EXTERNAL
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

private val FILES_EXTERNAL_CONTENT_URI = MediaStore.Files.getContentUri(VOLUME_EXTERNAL)

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
        val mimeType = intent.type
        val uriIsReady = isUriReady(uri)
        val items = ArrayList<PagerItem>().apply {
            add(PagerItem.CamItem(pendingIntent = getCamPendingIntent(intent, isSecure)))
            add(PagerItem.UriItem(getIdFromUri(uri), uri, mimeType, uriIsReady))
        }
        emit(items)

        // create PagerItems for other Uris
        val newItems = ArrayList(items)
        val extraItems: List<PagerItem.UriItem> = if (isSecure) {
            val secureIds = (intent.extras?.getSerializable(EXTRA_SECURE_IDS) as LongArray).toList()
            Log.d(TAG, "secureIds: $secureIds")
            getUriItemsFromSecureIds(secureIds)
        } else {
            getUriItemsFromFirstUri(uri, mimeType)
        }

        if (extraItems.isNotEmpty() && extraItems[0].id == items[1].id) {
            // The extra items include the first UriItem as well, so remove it from newItems first
            newItems.removeLast()
        }
        newItems.addAll(extraItems)
        emit(newItems)

        // return early if all are ready
        val allReady = !newItems.any { it is PagerItem.UriItem && !it.ready }
        if (allReady) return@flow

        // create final PagerItems
        Log.d(TAG, "Emitting final pager items")
        val finalItems = ArrayList<PagerItem>(newItems)
        // start waiting for items to become ready from the end
        newItems.reversed().forEachIndexed { index, item ->
            when (item) {
                is PagerItem.CamItem -> { // no-op
                }
                is PagerItem.UriItem -> if (!item.ready) {
                    // only wait-for and update items that aren't ready
                    whenReady(item.uri, item.ready) {
                        Log.i(TAG, "Item with id ${item.id} became ready")
                        val i = finalItems.size - 1 - index // account for reversed list
                        finalItems[i] = item.copy(ready = true)
                        emit(ArrayList(finalItems))
                    }
                }
            }
        }
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

    private suspend fun <T> whenReady(uri: Uri, isReady: Boolean, block: suspend () -> T): T {
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

    private fun getUriItemsFromSecureIds(secureIds: List<Long>): List<PagerItem.UriItem> {
        val items = ArrayList<PagerItem.UriItem>()
        secureIds.forEach { id ->
            val queryArgs = Bundle().apply {
                putInt(QUERY_ARG_MATCH_PENDING, 1)
                putString(QUERY_ARG_SQL_SELECTION, "${MediaColumns._ID} = ?")
                putStringArray(QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(id.toString()))
            }
            contentResolver.query(
                FILES_EXTERNAL_CONTENT_URI,
                arrayOf(IS_PENDING, MediaColumns.MIME_TYPE),
                queryArgs,
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val ready = c.getInt(0) == 0
                    val type = c.getString(1)
                    items.add(PagerItem.UriItem(id, getUriFromId(id, type), type, ready))
                }
            }
        }
        return items
    }

    private fun getUriItemsFromFirstUri(uri: Uri, mimeType: String?): List<PagerItem.UriItem> {
        val bucketIdProjection = arrayOf(MediaColumns.BUCKET_ID)
        val bucketId = contentResolver.query(uri, bucketIdProjection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                Log.d(TAG, "bucketId: ${c.getInt(0)}")
                return@use c.getInt(0)
            }
            return@use null
        } ?: return listOf(PagerItem.UriItem(getIdFromUri(uri), uri, mimeType, isUriReady(uri)))
        val items = ArrayList<PagerItem.UriItem>()
        val queryArgs = Bundle().apply {
            putInt(QUERY_ARG_LIMIT, 42)
            putInt(QUERY_ARG_MATCH_PENDING, 1)
            putString(QUERY_ARG_SQL_SELECTION, "${MediaColumns.BUCKET_ID} = ?")
            putStringArray(QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(bucketId.toString()))
            putStringArray(QUERY_ARG_SORT_COLUMNS, arrayOf(MediaColumns._ID))
            putInt(QUERY_ARG_SORT_DIRECTION, QUERY_SORT_DIRECTION_DESCENDING)
        }
        contentResolver.query(
            FILES_EXTERNAL_CONTENT_URI,
            arrayOf(MediaColumns._ID, IS_PENDING, MediaColumns.MIME_TYPE),
            queryArgs,
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val ready = c.getInt(1) == 0
                val type = c.getString(2)
                items.add(PagerItem.UriItem(id, getUriFromId(id, type), type, ready))
            }
        }
        if (items.isEmpty()) {
            Log.e(TAG, "Warning query returned no results")
            return listOf(PagerItem.UriItem(getIdFromUri(uri), uri, mimeType, isUriReady(uri)))
        }
        return items
    }

    private fun getUriFromId(id: Long, mimeType: String): Uri = when {
        mimeType.startsWith("image") -> {
            ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        }
        mimeType.startsWith("video") -> {
            ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
        }
        else -> {
            ContentUris.withAppendedId(FILES_EXTERNAL_CONTENT_URI, id)
        }
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
