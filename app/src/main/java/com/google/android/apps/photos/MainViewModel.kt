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

import android.app.Application
import android.content.Intent
import androidx.annotation.UiThread
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentSkipListSet

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val intentHandler = IntentHandler(app)

    private val _items = MutableLiveData<List<PagerItem>>()
    val items: LiveData<List<PagerItem>> = _items
    private val _showBottomBar = MutableLiveData(false)
    val showBottomBar: LiveData<Boolean> = _showBottomBar

    private val deletedIds = ConcurrentSkipListSet<Long>()

    fun isSecure(intent: Intent) = intentHandler.isSecure(intent)

    fun onNewIntent(intent: Intent) {
        viewModelScope.launch(Dispatchers.IO) {
            intentHandler.handleIntent(intent).collect { newItems ->
                _items.postValue(newItems.filter {
                    val isDeleted = deletedIds.contains(it.id)
                    !isDeleted
                })
            }
        }
    }

    @UiThread
    fun toggleBottomBar() {
        _showBottomBar.value = !_showBottomBar.value!!
    }

    @UiThread
    fun onItemDeleted(item: PagerItem.UriItem) {
        deletedIds.add(item.id)
        val oldItems = items.value!!
        _items.value = oldItems.filter { it.id != item.id }
    }

}
