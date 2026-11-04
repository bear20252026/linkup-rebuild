package com.youloft.watcher.pages.user.dialog

import android.content.Context
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.inputmethod.InputMethodManager
import com.mc.fastkit.dialog.BindingDialog
import com.youloft.watcher.R
import com.youloft.watcher.databinding.DialogUpdateUsernameBinding
import com.youloft.watcher.ext.onIntervalClick

/**
 * Recovered from `pages.user.dialog.UpdateUsernameDialog`: a length-capped single-line editor with a
 * `%d/%d` counter, confirm disabled while empty (unless [alwaysEnabled]), and the keyboard raised
 * 300ms after the dialog appears.
 */
class UpdateUsernameDialog(
    context: Context,
    private val title: String,
    private val preset: String,
    private val hint: String,
    private val maxLength: Int = DEFAULT_MAX_LENGTH,
    private val alwaysEnabled: Boolean = false,
    private val onConfirm: (String) -> Unit,
) : BindingDialog<DialogUpdateUsernameBinding>(context) {

    override fun onInit(binding: DialogUpdateUsernameBinding) {
        binding.tvTitle.text = title
        binding.etUsername.hint = hint
        binding.etUsername.filters = arrayOf(InputFilter.LengthFilter(maxLength))
        if (alwaysEnabled) {
            binding.tvConfirm.isEnabled = true
        }
        if (preset.isNotEmpty()) {
            binding.etUsername.setText(preset)
            binding.tvUsernameCount.text = context.getString(R.string.input_length_hint, preset.length, maxLength)
            binding.etUsername.setSelection(preset.length)
        }
        binding.etUsername.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(editable: Editable?) {
                val length = editable?.length ?: 0
                binding.tvUsernameCount.text = context.getString(R.string.input_length_hint, length, maxLength)
                if (!alwaysEnabled) {
                    binding.tvConfirm.isEnabled = length > 0
                }
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })
        binding.tvCancel.onIntervalClick { dismissDelayed() }
        binding.tvConfirm.onIntervalClick {
            onConfirm(binding.etUsername.text.toString().trim())
            dismissDelayed()
        }
        binding.root.postDelayed({
            binding.etUsername.requestFocus()
            context.getSystemService(InputMethodManager::class.java)
                ?.showSoftInput(binding.etUsername, 0)
        }, KEYBOARD_DELAY_MILLIS)
    }

    private companion object {
        const val DEFAULT_MAX_LENGTH = 10
        const val KEYBOARD_DELAY_MILLIS = 300L
    }
}
