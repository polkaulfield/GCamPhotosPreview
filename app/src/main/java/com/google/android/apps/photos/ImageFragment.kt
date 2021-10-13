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

import android.app.Activity.RESULT_OK
import android.app.KeyguardManager
import android.app.KeyguardManager.KeyguardDismissCallback
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.View.INVISIBLE
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ProgressBar
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult
import androidx.core.content.ContextCompat.getSystemService
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.ORIENTATION_USE_EXIF

class ImageFragment : Fragment() {

    companion object {
        fun newInstance(id: Long) = ImageFragment().apply {
            arguments = Bundle().apply {
                putLong("id", id)
            }
        }
    }

    private val viewModel: MainViewModel by activityViewModels()

    private lateinit var imageView: SubsamplingScaleImageView
    private lateinit var progressBar: ProgressBar
    private lateinit var actionBar: ViewGroup
    private lateinit var deleteButton: ImageButton

    private var currentItem: PagerItem.UriItem? = null

    private val deletionLauncher = registerForActivityResult(StartIntentSenderForResult()) {
        if (it.resultCode == RESULT_OK) viewModel.onItemDeleted(currentItem!!)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_image, container, false).apply {
        val id = requireArguments().getLong("id")
        imageView = findViewById(R.id.imageView)
        progressBar = findViewById(R.id.progressBar)
        actionBar = findViewById(R.id.actionBar)
        deleteButton = findViewById(R.id.deleteButton)

        // our fragments don't get recreated for changes, so listen to changes here
        viewModel.items.observe(viewLifecycleOwner, { items ->
            val item = items.find { it.id == id } as PagerItem.UriItem?
            if (item != null) setItem(item)
        })
    }

    private fun setItem(item: PagerItem.UriItem) {
        if (currentItem == item) return
        currentItem = item
        if (item.ready) {
            progressBar.visibility = INVISIBLE
            actionBar.visibility = VISIBLE
            try {
                imageView.orientation = ORIENTATION_USE_EXIF
                imageView.setImage(ImageSource.uri(item.uri))
            } catch (e: Exception) {
                Log.e(TAG, "Error setting image", e)
            }
        } else {
            progressBar.visibility = VISIBLE
            actionBar.visibility = INVISIBLE
        }
        deleteButton.setOnClickListener {
            onDeleteButtonClicked(item)
        }
    }

    private fun onDeleteButtonClicked(item: PagerItem.UriItem) {
        val context = requireActivity()
        val km = getSystemService(context, KeyguardManager::class.java)!!
        if (km.isDeviceLocked) {
            // If the device is locked, the deletion request won't work.
            // We are not allowed to delete the file ourselves,
            // so the only other option would be getting MANAGE_EXTERNAL_STORAGE permission.
            // Here we just ask for unlocking instead for now.
            km.requestDismissKeyguard(context, object : KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    requestDeletion(context, item.uri)
                }
            })
        } else {
            requestDeletion(context, item.uri)
        }
    }

    private fun requestDeletion(context: Context, uri: Uri) {
        val contentResolver = context.contentResolver
        val intent = MediaStore.createDeleteRequest(contentResolver, listOf(uri))
        val request = IntentSenderRequest.Builder(intent).build()
        deletionLauncher.launch(request)
    }

}
