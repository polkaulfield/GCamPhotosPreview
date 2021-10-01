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

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.View.INVISIBLE
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.widget.ProgressBar
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.ORIENTATION_USE_EXIF

class ImageFragment : Fragment() {

    companion object {
        fun newInstance(index: Int) = ImageFragment().apply {
            arguments = Bundle().apply {
                putInt("index", index)
            }
        }
    }

    private val viewModel: MainViewModel by activityViewModels()

    private lateinit var imageView: SubsamplingScaleImageView
    private lateinit var progressBar: ProgressBar

    private var currentItem: PagerItem.UriItem? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_image, container, false).apply {
        val index = requireArguments().getInt("index")
        imageView = findViewById(R.id.imageView)
        progressBar = findViewById(R.id.progressBar)

        // our fragments don't get recreated for changes, so listen to changes here
        viewModel.items.observe(viewLifecycleOwner, { items ->
            setItem(items[index] as PagerItem.UriItem)
        })
    }

    private fun setItem(item: PagerItem.UriItem) {
        if (currentItem == item) return
        currentItem = item
        if (item.ready) {
            progressBar.visibility = INVISIBLE
            try {
                imageView.setImage(ImageSource.uri(item.uri))
                imageView.orientation = ORIENTATION_USE_EXIF
            } catch (e: Exception) {
                Log.e(TAG, "Error getting EXIF orientation", e)
            }
        } else {
            progressBar.visibility = VISIBLE
        }
    }

}
