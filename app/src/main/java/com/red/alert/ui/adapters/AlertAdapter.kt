package com.red.alert.ui.adapters

import android.content.Context
import android.text.Html
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView

import androidx.core.content.ContextCompat

import com.red.alert.R
import com.red.alert.config.ThreatTypes
import com.red.alert.model.Alert
import com.red.alert.utils.metadata.LocationData
import com.red.alert.utils.ui.TextViewUtil

class AlertAdapter(context: Context, alerts: List<Alert>) : ArrayAdapter<Alert>(context, R.layout.alert, alerts) {
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        // Prepare view holder
        val viewHolder: ViewHolder
        val rowView: View

        // Don't have a cached view?
        if (convertView == null) {
            // Get inflater service
            val layoutInflater = LayoutInflater.from(parent.context)

            // Inflate the alert layout (recycle views)
            rowView = layoutInflater.inflate(R.layout.alert, parent, false)

            // Create a new view holder
            viewHolder = ViewHolder()

            // Cache the view resources
            viewHolder.time = rowView.findViewById<TextView>(R.id.time)
            viewHolder.title = rowView.findViewById<TextView>(R.id.alertTitle)
            viewHolder.cities = rowView.findViewById<TextView>(R.id.alertCities)
            viewHolder.desc = rowView.findViewById<TextView>(R.id.alertDesc)
            viewHolder.image = rowView.findViewById<ImageView>(R.id.image)

            // Store it in tag
            rowView.tag = viewHolder
        }
        else {
            // Get cached convert view
            rowView = convertView
            viewHolder = convertView.tag as ViewHolder
        }

        // Retrieve the alert
        val alert = getItem(position)

        // Got an alert?
        if (alert != null) {
            // Set localized city name (HTML for using <b> tag for selected cities)
            viewHolder.cities.text = Html.fromHtml(alert.localizedCity)

            // Get alert desc
            var desc = alert.desc

            // System alert?
            if (ThreatTypes.SYSTEM == alert.threat) {
                // Set desc to "system message"
                desc = alert.localizedThreat

                // Hide time
                viewHolder.time.visibility = View.GONE
            }
            else {
                // Show time
                viewHolder.time.visibility = View.VISIBLE
            }

            // Set zone names
            viewHolder.desc.text = desc

            // Set title
            viewHolder.title.text = alert.localizedTitle

            // Show user-friendly time
            viewHolder.time.text = alert.dateString

            // Custom threat icons
            viewHolder.image.setImageResource(LocationData.getThreatDrawable(alert.threat))

            // Check if expanded
            if (alert.isExpanded) {
                // Disable ellipsis (show all)
                viewHolder.cities.maxLines = Int.MAX_VALUE
                viewHolder.cities.ellipsize = null
            }
            else {
                // Max 3 lines with ellipsis (...)
                viewHolder.cities.maxLines = 3
                viewHolder.cities.ellipsize = TextUtils.TruncateAt.END
            }

            // Title click event
            viewHolder.cities.setOnClickListener { view ->
                // Check if title is ellipsized (more than 3 lines of alert cities)
                if (TextViewUtil.isEllipsized(viewHolder.cities)) {
                    // Disable ellipsis (show all cities)
                    viewHolder.cities.animate().alpha(0f).setDuration(120).withEndAction {
                        viewHolder.cities.maxLines = Int.MAX_VALUE
                        viewHolder.cities.ellipsize = null

                        viewHolder.cities.animate().alpha(1f).setDuration(200).start()
                    }.start()

                    // Toggle expanded flag
                    alert.isExpanded = true
                } else {
                    // Already expanded?
                    if (alert.isExpanded) {
                        // Disable ellipsis (show all cities)
                        viewHolder.cities.animate().alpha(0f).setDuration(120).withEndAction {
                            // Max 3 lines with ellipsis (...)
                            viewHolder.cities.maxLines = 3
                            viewHolder.cities.ellipsize = TextUtils.TruncateAt.END

                            viewHolder.cities.animate().alpha(1f).setDuration(200).start()
                        }.start()

                        // Toggle expanded flag
                        alert.isExpanded = false
                    } else {
                        // Open map
                        (parent as ListView).performItemClick(view, position, getItemId(position))
                    }
                }
            }
        }

        // Return the view
        return rowView
    }

    class ViewHolder {
        lateinit var time: TextView
        lateinit var title: TextView
        lateinit var cities: TextView
        lateinit var desc: TextView
        lateinit var image: ImageView
    }
}
