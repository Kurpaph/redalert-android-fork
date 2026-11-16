package com.red.alert.ui.dialogs

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.widget.Toast

import com.red.alert.ui.localization.rtl.RTLSupport
import com.red.alert.utils.formatting.StringUtils

object AlertDialogBuilder {
    @JvmStatic
    fun showGenericDialog(title: String?, message: String?, positiveButton: String?, negativeButton: String?, cancelable: Boolean, context: Context, clickListener: DialogInterface.OnClickListener?) {
        // Use builder to create dialog
        val builder = AlertDialog.Builder(context)

        // Use builder to create dialog
        builder.setTitle(title).setMessage(message).setPositiveButton(positiveButton, clickListener)

        // Negative button defined?
        if (!StringUtils.stringIsNullOrEmpty(negativeButton)) {
            builder.setNegativeButton(negativeButton, clickListener)
        }

        // Set cancelable flag
        builder.setCancelable(cancelable)

        try {
            // Build it
            val dialog = builder.create()

            // Show it
            dialog.show()

            // Support for RTL languages
            RTLSupport.mirrorDialog(dialog, context)
        }
        catch (exc: Exception) {
            // Show toast instead
            Toast.makeText(context, "$title - $message", Toast.LENGTH_LONG).show()
        }
    }
}
