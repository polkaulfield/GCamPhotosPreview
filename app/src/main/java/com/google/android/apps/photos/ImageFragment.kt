package com.google.android.apps.photos

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.exifinterface.media.ExifInterface
import androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION
import androidx.fragment.app.Fragment
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import java.io.InputStream

class ImageFragment : Fragment() {

    companion object {
        fun newInstance(uri: Uri) = ImageFragment().apply {
            arguments = Bundle().apply {
                putParcelable("uri", uri)
            }
        }
    }

    private lateinit var imageView: SubsamplingScaleImageView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_image, container, false).apply {
        val uri: Uri = requireArguments().getParcelable("uri")!!
        imageView = findViewById(R.id.imageView)

        try {
            imageView.setImage(uri)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting EXIF orientation", e)
        }
    }

    private fun SubsamplingScaleImageView.setImage(uri: Uri) {
        requireContext().contentResolver.openInputStream(uri).use {
            setExifOrientation(it!!)
        }
        setImage(ImageSource.uri(uri))
    }

    private fun SubsamplingScaleImageView.setExifOrientation(inputStream: InputStream) {
        val exifOrientation = ExifInterface(inputStream).getAttributeInt(TAG_ORIENTATION, 1)
        orientation = when (exifOrientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> {
                scaleX = -1f
                SubsamplingScaleImageView.ORIENTATION_0
            }
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                scaleY = -1f
                SubsamplingScaleImageView.ORIENTATION_0
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                scaleX = -1f
                SubsamplingScaleImageView.ORIENTATION_270
            }
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                scaleX = -1f
                SubsamplingScaleImageView.ORIENTATION_90
            }
            else -> SubsamplingScaleImageView.ORIENTATION_USE_EXIF
        }
    }

}
