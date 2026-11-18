package com.red.alert.ui.localization.rtl

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

import com.red.alert.config.Logging
import com.red.alert.utils.localization.Localization

object RTLSupport {
    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
    @JvmStatic
    fun mirrorActionBar(activity: Activity) {
        // Must be Jellybean or newer
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            // Set RTL layout direction
            activity.window.decorView.layoutDirection = if (Localization.isRTLLocale(activity)) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        }
    }

    @SuppressLint("NewApi")
    @JvmStatic
    fun mirrorDialog(dialog: Dialog, context: Context) {
        // RTL locale only
        if (!Localization.isRTLLocale(context)) {
            return
        }

        try {
            // Get message text view
            val message = dialog.findViewById<TextView>(android.R.id.message)

            // Defy gravity
            if (message != null) {
                message.gravity = Gravity.RIGHT
            }

            // Get the title of text view
            val title = dialog.findViewById<TextView>(context.resources.getIdentifier("alertTitle", "id", "android"))

            // Defy gravity
            title.gravity = Gravity.RIGHT

            // Get list view (may not exist)
            val listView = dialog.findViewById<ListView>(context.resources.getIdentifier("select_dialog_listview", "id", "android"))

            // Check if list & set RTL mode
            if (listView != null) {
                listView.layoutDirection = View.LAYOUT_DIRECTION_RTL
            }

            // Get title's parent layout
            val parent = title.parent as LinearLayout

            // Get layout params
            val originalParams = parent.layoutParams as LinearLayout.LayoutParams

            // Set width to WRAP_CONTENT
            originalParams.width = LinearLayout.LayoutParams.WRAP_CONTENT

            // Defy gravity
            originalParams.gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL

            // Set layout params
            parent.layoutParams = originalParams
        }
        catch (exc: Exception) {
            // Log failure to logcat
            Log.d(Logging.TAG, "RTL failed", exc)
        }
    }
}
