package com.red.alert.ui.dialogs.custom

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.widget.Toast

import com.red.alert.R
import com.red.alert.ui.localization.rtl.RTLSupport
import com.red.alert.utils.marketing.GooglePlay

object UpdateDialogs {
    @JvmStatic
    fun showUpdateDialog(context: Context, newVersion: String?) {
        // Use builder to create dialog
        val builder = AlertDialog.Builder(context)

        // Insert version into message
        val message = String.format(context.getString(R.string.updateDesc), newVersion)

        // Use builder to create dialog
        builder.setTitle(context.getString(R.string.update)).setMessage(message)

        // Set positive button
        builder.setPositiveButton(R.string.updatePositive, DialogInterface.OnClickListener { _, _ ->
            // Open app page
            GooglePlay.openAppListingPage(context)
        })

        // Set negative button
        builder.setNegativeButton(R.string.notNow, DialogInterface.OnClickListener { dialogInterface, _ ->
            // Close dialog
            dialogInterface.dismiss()
        })

        try {
            // Create the dialog
            val dialog = builder.create()

            // Show dialog
            dialog.show()

            // Support for RTL languages
            RTLSupport.mirrorDialog(dialog, context)
        }
        catch (exc: Exception) {
            // Show toast instead
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
}
