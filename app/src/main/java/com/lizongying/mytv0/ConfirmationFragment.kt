package com.lizongying.mytv0

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment

class ConfirmationFragment(
    private val listener: ConfirmationListener,
    private val message: String,
    private val update: Boolean
) : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return activity?.let {
            val builder = AlertDialog.Builder(it)
            if (update) {
                builder.setTitle("发现新版本")
                    .setMessage(message)
                    .setPositiveButton(
                        "立即更新"
                    ) { _, _ ->
                        listener.onConfirm()
                    }
                    .setNegativeButton(
                        "暂不更新"
                    ) { _, _ ->
                        listener.onCancel()
                    }
            } else {
                builder.setTitle(message)
                    .setMessage("")
                    .setNegativeButton(
                        "确定"
                    ) { _, _ ->
                    }
            }
            builder.create()
        } ?: throw IllegalStateException("Activity cannot be null")
    }

    interface ConfirmationListener {
        fun onConfirm()
        fun onCancel()
    }
}

