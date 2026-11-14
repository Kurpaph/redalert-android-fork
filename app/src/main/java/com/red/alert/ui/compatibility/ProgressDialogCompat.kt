package com.red.alert.ui.compatibility

import android.app.ProgressDialog
import android.content.Context
import android.os.Build

import com.red.alert.R

object ProgressDialogCompat {
    @JvmStatic
    fun getStyledProgressDialog(context: Context): ProgressDialog {
        // Only for pre-Honeycomb devices
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB) {
            return ProgressDialog(context, R.style.ProgressDialogCompat)
        }

        // Return a basic ProgressDialog
        return ProgressDialog(context)
    }
}
