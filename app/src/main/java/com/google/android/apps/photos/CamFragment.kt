package com.google.android.apps.photos

import android.app.PendingIntent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment

class CamFragment : Fragment() {

    companion object {
        fun newInstance(pendingIntent: PendingIntent) = CamFragment().apply {
            arguments = Bundle().apply {
                putParcelable("pendingIntent", pendingIntent)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_cam, container, false)

    override fun onResume() {
        super.onResume()
        Log.e(TAG, "Going back to camera")
        requireArguments().getParcelable<PendingIntent>("pendingIntent")!!.send()
        requireActivity().finish()
    }

}
