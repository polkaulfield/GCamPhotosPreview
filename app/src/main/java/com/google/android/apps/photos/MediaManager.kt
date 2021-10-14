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

import android.content.ContentResolver.*
import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.provider.MediaStore
import android.provider.MediaStore.MediaColumns
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private val FILES_EXTERNAL_CONTENT_URI = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)

class MediaManager(context: Context) {

    private val contentResolver = context.contentResolver
    private val handler = Handler(context.mainLooper)

    internal fun getIdFromUri(uri: Uri): Long {
        return uri.lastPathSegment?.toLong() ?: error("Can't get ID from $uri")
    }

    internal fun isUriReady(uri: Uri): Boolean {
        return getReadyCursor(uri)?.use { c ->
            isCursorReady(c)
        } ?: return false
    }

    private fun isCursorReady(c: Cursor): Boolean {
        c.moveToNext()
        return c.getInt(0) == 0
    }

    private fun getReadyCursor(uri: Uri): Cursor? {
        return contentResolver.query(uri, arrayOf(MediaColumns.IS_PENDING), null, null, null)
    }

    internal suspend fun <T> whenReady(uri: Uri, isReady: Boolean, block: suspend () -> T): T {
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

    internal fun getUriItemsFromSecureIds(secureIds: List<Long>): List<PagerItem.UriItem> {
        val items = ArrayList<PagerItem.UriItem>()
        secureIds.forEach { id ->
            val queryArgs = Bundle().apply {
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, 1)
                putString(QUERY_ARG_SQL_SELECTION,
                    "${MediaColumns._ID} = ?")
                putStringArray(QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(id.toString()))
            }
            contentResolver.query(
                FILES_EXTERNAL_CONTENT_URI,
                arrayOf(MediaColumns.IS_PENDING, MediaColumns.MIME_TYPE),
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

    internal fun getUriItemsFromFirstUri(uri: Uri, mimeType: String?): List<PagerItem.UriItem> {
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
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, 1)
            putString(QUERY_ARG_SQL_SELECTION, "${MediaColumns.BUCKET_ID} = ?")
            putStringArray(QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(bucketId.toString()))
            putStringArray(QUERY_ARG_SORT_COLUMNS, arrayOf(MediaColumns._ID))
            putInt(QUERY_ARG_SORT_DIRECTION, QUERY_SORT_DIRECTION_DESCENDING)
        }
        contentResolver.query(
            FILES_EXTERNAL_CONTENT_URI,
            arrayOf(MediaColumns._ID, MediaColumns.IS_PENDING, MediaColumns.MIME_TYPE),
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
}
