package com.youloft.watcher.pages.track.dialog

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.WindowManager
import com.youloft.watcher.databinding.DialogNoTrackReasonBinding

class NoTrackReasonDialog(context: Context) : Dialog(context) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = DialogNoTrackReasonBinding.inflate(LayoutInflater.from(context))
        setContentView(binding.root)
        binding.tvOk.setOnClickListener { dismiss() }
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
    }
}
