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
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA
import android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

private const val PACKAGE_GOOGLE_CAM = "com.google.android.GoogleCamera"
private const val SECURE_MODE = "com.google.android.apps.photos.api.secure_mode"
private const val INTENT_CAM = "CAMERA_RELAUNCH_INTENT_EXTRA"
private const val INTENT_CAM_SECURE = "CAMERA_RELAUNCH_SECURE_INTENT_EXTRA"
private const val EXTRA_PROCESSING = "processing_uri_intent_extra"
private const val EXTRA_SECURE_IDS = "com.google.android.apps.photos.api.secure_mode_ids"

class IntentHandler(private val context: Context) {

    private val mediaManager = MediaManager(context)

    fun isSecure(intent: Intent) = intent.getBooleanExtra(SECURE_MODE, false)

    fun handleIntent(intent: Intent): Flow<List<PagerItem>> = flow {
        if (BuildConfig.DEBUG) log(intent)

        val isSecure = isSecure(intent)
        val uri = intent.data!!
        // TODO see if we can get a processing preview somehow
        val processingUri = intent.getParcelableExtra<Uri>(EXTRA_PROCESSING)
        val mimeType = intent.type
        val uriIsReady = mediaManager.isUriReady(uri)
        val items = ArrayList<PagerItem>().apply {
            add(PagerItem.CamItem(pendingIntent = getCamPendingIntent(intent, isSecure)))
            add(PagerItem.UriItem(mediaManager.getIdFromUri(uri), uri, mimeType, uriIsReady))
        }
        emit(items)

        // create PagerItems for other Uris
        val newItems = ArrayList(items)
        val extraItems: List<PagerItem.UriItem> = if (isSecure) {
            val secureIds = (intent.extras?.getSerializable(EXTRA_SECURE_IDS) as LongArray).toList()
            Log.d(TAG, "secureIds: $secureIds")
            mediaManager.getUriItemsFromSecureIds(secureIds)
        } else {
            mediaManager.getUriItemsFromFirstUri(uri, mimeType)
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
                    mediaManager.whenReady(item.uri, item.ready) {
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


    private fun log(intent: Intent?) {
        Log.e(TAG, "intent: $intent")
        val extras = intent?.extras ?: return
        extras.keySet().forEach { key ->
            val value = extras.get(key)
            Log.e(TAG, "$key: $value")
        }
    }

}
