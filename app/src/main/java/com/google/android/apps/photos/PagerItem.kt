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
import android.net.Uri
import androidx.recyclerview.widget.DiffUtil

sealed class PagerItem(open val id: Long) {
    data class CamItem(override val id: Long = -1, val pendingIntent: PendingIntent) : PagerItem(id)
    data class UriItem(
        override val id: Long,
        val uri: Uri,
        val mimeType: String?,
        val ready: Boolean,
    ) : PagerItem(id)
}

class PagerItemCallback : DiffUtil.ItemCallback<PagerItem>() {
    override fun areItemsTheSame(oldItem: PagerItem, newItem: PagerItem): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsTheSame(oldItem: PagerItem, newItem: PagerItem): Boolean {
        return when {
            (newItem is PagerItem.UriItem && oldItem is PagerItem.UriItem) -> {
                newItem.ready == oldItem.ready
            }
            else -> oldItem.id == newItem.id
        }
    }

}
