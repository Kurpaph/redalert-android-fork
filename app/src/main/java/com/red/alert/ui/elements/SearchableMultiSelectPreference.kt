package com.red.alert.ui.elements

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.res.TypedArray
import android.os.Handler
import android.preference.ListPreference
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.Filter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

import com.red.alert.R
import com.red.alert.logic.communication.broadcasts.LocationSelectionEvents
import com.red.alert.ui.dialogs.AlertDialogBuilder
import com.red.alert.utils.communication.Broadcasts
import com.red.alert.utils.formatting.StringUtils
import com.red.alert.utils.metadata.LocationData

import java.util.Collections

class SearchableMultiSelectPreference(context: Context, attrs: AttributeSet?) : ListPreference(context, attrs) {
    private val separator: String
    private var checkAllKey: String? = null
    private var mCurrentSearchText: String? = null
    private lateinit var mClickedDialogEntryIndices: BooleanArray

    // Constructor
    init {
        val a: TypedArray = context.obtainStyledAttributes(attrs, R.styleable.SearchableMultiSelectPreference)
        checkAllKey = a.getString(R.styleable.SearchableMultiSelectPreference_checkAll)
        val s = a.getString(R.styleable.SearchableMultiSelectPreference_separator)
        separator = s ?: DEFAULT_SEPARATOR

        val initialEntries = entries

        if (initialEntries != null) {
            // Initialize the array of boolean to the same size as number of entries
            mClickedDialogEntryIndices = BooleanArray(initialEntries.size)
        }
    }

    companion object {
        private const val DEFAULT_SEPARATOR = "OV=I=XseparatorX=I=VO"

        // Credits to kurellajunior on this post http://snippets.dzone.com/posts/show/91
        @JvmStatic
        internal fun join(pColl: Iterable<*>?, separator: String?): String {
            if (pColl == null) {
                return ""
            }

            val oIter = pColl.iterator()

            if (!oIter.hasNext()) {
                return ""
            }

            val oBuilder = StringBuilder(oIter.next().toString())

            while (oIter.hasNext()) {
                oBuilder.append(separator).append(oIter.next())
            }

            return oBuilder.toString()
        }

        /**
         * @param straw     String to be found
         * @param haystack  Raw string that can be read direct from preferences
         * @param separator Separator string. If null, static default separator will be used
         * @return boolean True if the straw was found in the haystack
         */
        @JvmStatic
        fun contains(straw: String, haystack: String, separator: String?): Boolean {
            val sep = separator ?: DEFAULT_SEPARATOR
            val vals = haystack.split(sep).dropLastWhile { it.isEmpty() }

            for (i in vals.indices) {
                if (vals[i] == straw) {
                    return true
                }
            }

            return false
        }
    }

    override fun setEntries(entries: Array<CharSequence>?) {
        super.setEntries(entries)

        // No entries?
        if (entries == null) {
            return
        }

        // Initialize the array of boolean to the same size as number of entries
        mClickedDialogEntryIndices = BooleanArray(entries.size)
    }

    override fun onPrepareDialogBuilder(builder: AlertDialog.Builder) {
        val entries = getEntries()
        val entryValues = getEntryValues()
        val zoneNames = LocationData.getAllCityZones(context)

        if (entries == null || entryValues == null || entries.size != entryValues.size) {
            throw IllegalStateException(
                    "ListPreference requires an entries array and an entryValues array which are both the same length")
        }

        restoreCheckedEntries()

        val allItems: MutableList<ListItemWithIndex> = ArrayList()
        val filteredItems: MutableList<ListItemWithIndex> = ArrayList()

        val isCitySelection = entryValues.size == zoneNames.size

        for (i in entries.indices) {
            val obj: Any = entries[i]

            val isDefault = i == 0
            val checked = mClickedDialogEntryIndices[i]

            var zone: String? = null

            if (isCitySelection) {
                zone = zoneNames[i]
            }

            var entryName = obj.toString()

            // Localize "select all" preference
            if (entryName == "Select All" || entryName == "בחר הכל") {
                entryName = context.resources.getStringArray(R.array.zoneNames)[0]
            }

            val listItemWithIndex = ListItemWithIndex(i, entryName, zone, checked, isDefault)
            allItems.add(listItemWithIndex)
            filteredItems.add(listItemWithIndex)
        }

        if (isCitySelection) {
            Collections.sort(filteredItems)
        }

        val objectsAdapter: ArrayAdapter<ListItemWithIndex> = object : ArrayAdapter<ListItemWithIndex>(context, R.layout.multiselect_checkable, filteredItems) {
            override fun getView(position: Int, v: View?, parent: ViewGroup): View {
                val viewHolder: ViewHolder
                val row: View

                if (v == null) {
                    val vi = context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
                    row = vi.inflate(R.layout.multiselect_checkable, null)

                    viewHolder = ViewHolder()

                    viewHolder.label = row.findViewById<TextView>(R.id.label)
                    viewHolder.subLabel = row.findViewById<TextView>(R.id.subLabel)
                    viewHolder.checkbox = row.findViewById<CheckBox>(R.id.checkbox)

                    row.tag = viewHolder
                }
                else {
                    row = v
                    viewHolder = v.tag as ViewHolder
                }

                val item = filteredItems[position]
                val name = item.value
                val code = item.zone

                viewHolder.label.text = name
                viewHolder.subLabel.text = code

                if (StringUtils.stringIsNullOrEmpty(code) || code == context.getString(R.string.all)) {
                    viewHolder.subLabel.visibility = View.GONE
                }
                else {
                    viewHolder.subLabel.visibility = View.VISIBLE
                }

                viewHolder.checkbox.setOnCheckedChangeListener(null)
                viewHolder.checkbox.isChecked = mClickedDialogEntryIndices[getRealPosition(name)]

                row.setOnClickListener {
                    viewHolder.checkbox.isChecked = !viewHolder.checkbox.isChecked
                }

                viewHolder.checkbox.setOnCheckedChangeListener(CompoundButton.OnCheckedChangeListener { _, checkedValue ->
                    // Checked all?

                    if (mClickedDialogEntryIndices[0]) {
                        (parent as ListView).setItemChecked(0, false)
                        mClickedDialogEntryIndices[0] = false
                    }

                    val realPosition = getRealPosition(name)

                    if (isCheckAllValue(realPosition)) {
                        checkAll(dialog, checkedValue)
                    }

                    mClickedDialogEntryIndices[realPosition] = checkedValue
                    viewHolder.checkbox.isChecked = checkedValue

                    item.checked = checkedValue

                    canCheckAll()

                    // Null safety check (in case dialog was closed)
                    if (dialog != null) {
                        // Invalidate list views (to update checkbox display)
                        val lv = dialog.findViewById<ListView>(R.id.searchListView)
                        lv.invalidateViews()
                    }
                })

                return row
            }

            private fun canCheckAll() {
                for (i in 1 until mClickedDialogEntryIndices.size) {
                    if (!mClickedDialogEntryIndices[i]) {
                        return
                    }
                }

                // Still here? Check all
                checkAll(dialog, true)
            }

            private fun getRealPosition(name: String): Int {
                for (i in entries.indices) {
                    if (entries[i] == name) {
                        return i
                    }
                }

                return 0
            }

            override fun getFilter(): Filter {
                return object : Filter() {
                    @Suppress("UNCHECKED_CAST")
                    override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                        filteredItems.clear()
                        filteredItems.addAll(results!!.values as List<ListItemWithIndex>)

                        if (isCitySelection) {
                            Collections.sort(filteredItems)
                        }

                        notifyDataSetChanged()
                    }

                    override fun performFiltering(constraint: CharSequence?): FilterResults {
                        val results = FilterResults()

                        val filterString = constraint!!.toString().lowercase()
                        val normalizedFilterString = normalizeSearchText(filterString)
                        val list = ArrayList<ListItemWithIndex>()

                        // Split search input into words for AND condition
                        val searchWords = normalizedFilterString.split(Regex("\\s+"))

                        for (obj in allItems) {
                            // Normalize items (remove punctuation and dashes)
                            val objStr = obj.toString().lowercase()
                            val normalizedObjStr = normalizeSearchText(objStr)
                            val objZone = obj.zone
                            val normalizedZone = if (objZone != null) normalizeSearchText(objZone.lowercase()) else ""

                            // Empty search field?
                            if (StringUtils.stringIsNullOrEmpty(filterString)) {
                                list.add(obj)
                            } else if (objStr.contains(filterString)) {
                                // Exact match takes priority
                                list.add(obj)
                            } else if (objZone != null && objZone.lowercase().contains(filterString)) {
                                // Exact zone match takes priority
                                list.add(obj)
                            } else {
                                // Check if all search words match (AND condition)
                                var allWordsMatch = true

                                // Traverse search keywords
                                for (word in searchWords) {
                                    // Word exists and present in current item?
                                    if (!word.isEmpty() &&
                                            !normalizedObjStr.contains(word) &&
                                            !normalizedZone.contains(word)) {
                                        allWordsMatch = false
                                        break
                                    }
                                }

                                // Ensure all words are present (AND condition)
                                if (allWordsMatch) {
                                    list.add(obj)
                                }
                            }
                        }

                        results.values = list
                        results.count = list.size
                        return results
                    }
                }
            }
        }

        val vi = context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater

        val parent = vi.inflate(R.layout.multiselect_search_box, null) as LinearLayout
        val searchEditText = parent.findViewById<EditText>(R.id.searchEditText)

        searchEditText.setOnEditorActionListener(TextView.OnEditorActionListener { textView, _, _ ->
            val inputManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

            inputManager.hideSoftInputFromWindow(textView.windowToken,
                    InputMethodManager.HIDE_NOT_ALWAYS)

            false
        })
        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun onTextChanged(arg0: CharSequence?, arg1: Int, arg2: Int, arg3: Int) {
            }

            override fun beforeTextChanged(arg0: CharSequence?, arg1: Int, arg2: Int, arg3: Int) {
            }

            override fun afterTextChanged(arg0: Editable?) {
                mCurrentSearchText = searchEditText.text.toString()
                objectsAdapter.getFilter().filter(searchEditText.text)
            }
        })

        val listView = parent.findViewById<ListView>(R.id.searchListView)
        listView.adapter = objectsAdapter

        builder.setView(parent)

        // Delay invocation by 300ms for dialog to be created
        Handler().postDelayed({
            // Dialog created?
            if (dialog != null) {
                // Get positive button
                val button: Button? = (dialog as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE)

                // Positive button created?
                if (button != null) {
                    // Override onClick listener to enforce max item selection limit
                    button.setOnClickListener {
                        // Get all possible values
                        val currentEntryValues = getEntryValues()

                        // ArrayList of selected values
                        val values = ArrayList<String>()

                        // Cities selected?
                        for (i in currentEntryValues.indices) {
                            if (mClickedDialogEntryIndices[i]) {
                                // Don't save the state of check all option - if any
                                val entryValue = currentEntryValues[i] as String
                                if (checkAllKey == null || entryValue != checkAllKey) {
                                    values.add(entryValue)
                                }
                            }
                        }

                        // Exceeded limit of 100 items?
                        if (values.size > 100) {
                            // Didn't check all?
                            if (!mClickedDialogEntryIndices[0]) {
                                // Show error dialog
                                AlertDialogBuilder.showGenericDialog(context.getString(R.string.error), context.getString(R.string.citySelectionLimitError), context.getString(R.string.okay), null, false, context, null)
                                return@setOnClickListener
                            }
                        }

                        // User entered a search term?
                        val searchText = mCurrentSearchText

                        if (searchText != null && !searchText.trim().isEmpty()) {
                            // No results
                            if (filteredItems.isEmpty()) {
                                // Show error dialog
                                AlertDialogBuilder.showGenericDialog(context.getString(R.string.error), context.getString(R.string.multiSelectionSearchNoResults), context.getString(R.string.okay), null, false, context, null)
                                return@setOnClickListener
                            }

                            // Check if at least one search result is ticked
                            var resultSelected = false

                            // Traverse filtered items
                            for (item in filteredItems) {
                                // Item checked?
                                if (item.checked) {
                                    resultSelected = true
                                    break
                                }
                            }

                            // No result selected?
                            if (!resultSelected) {
                                // Show error dialog
                                AlertDialogBuilder.showGenericDialog(context.getString(R.string.error), context.getString(R.string.multiSelectionSearchNoResultSelected), context.getString(R.string.okay), null, false, context, null)
                                return@setOnClickListener
                            }
                        }

                        // Less than or equal to 100 selected
                        // Set button clicked as positive
                        this@SearchableMultiSelectPreference.onClick(dialog, AlertDialog.BUTTON_POSITIVE)

                        // Dismiss dialog
                        dialog.dismiss()
                    }
                }
            }
        }, 300)

        //builder.setView()
        /*builder.setMultiChoiceItems(entries, mClickedDialogEntryIndices,
                new DialogInterface.OnMultiChoiceClickListener() {
                    public void onClick(DialogInterface dialog, int which, boolean val) {
                        if( isCheckAllValue( which ) == true ) {
                            checkAll( dialog, val );
                        }
                        mClickedDialogEntryIndices[which] = val;
                    }
                });  */
    }

    private fun isCheckAllValue(which: Int): Boolean {
        val entryValues = getEntryValues()
        if (checkAllKey != null) {
            return entryValues[which] == checkAllKey
        }
        return false
    }

    private fun normalizeSearchText(text: String?): String {
        // Normalizes a string by removing hyphens, dashes, and other common punctuation
        if (text == null) {
            return ""
        }

        // Remove hyphens, dashes, parentheses, and other common punctuation
        return text.replace(Regex("[\\-–—()\\[\\]{}]"), " ").trim()
    }

    private fun checkAll(dialog: DialogInterface?, checkedValue: Boolean) {
        if (dialog == null) {
            return
        }

        val lv = (dialog as AlertDialog).findViewById<ListView>(R.id.searchListView)
        val size = lv.count
        for (i in 0 until size) {
            lv.setItemChecked(i, checkedValue)
            mClickedDialogEntryIndices[i] = checkedValue
        }
    }

    fun parseStoredValue(storedValue: CharSequence?): Array<String>? {
        if ("" == storedValue) {
            return null
        }
        else {
            return (storedValue as String).split(separator).dropLastWhile { it.isEmpty() }.toTypedArray()
        }
    }

    private fun restoreCheckedEntries() {
        val entryValues = getEntryValues()

        var checkAll = false

        // Explode the string read in sharedpreferences
        var vals: Array<String>? = parseStoredValue(getValue())

        if (vals == null) {
            checkAll = true
            vals = arrayOf("")
        }

        val valuesList = vals.asList()

        if (valuesList.size == 1) {
            if (valuesList[0] == context.getString(R.string.all)) {
                checkAll = true
            }
        }

        for (i in entryValues.indices) {
            val entry = entryValues[i]
            if (valuesList.contains(entry.toString()) || checkAll) {
                mClickedDialogEntryIndices[i] = true
            }
            else {
                mClickedDialogEntryIndices[i] = false
            }
        }
    }

    override fun onDialogClosed(positiveResult: Boolean) {
        val values = ArrayList<String>()

        val entryValues = getEntryValues()

        if (positiveResult && entryValues != null) {
            // Check all selected?

            if (entryValues.size > 1) {
                if (entryValues[0] == context.getString(R.string.all)) {
                    if (mClickedDialogEntryIndices[0]) {
                        // Set empty value
                        setValue(join(values, separator))
                        Broadcasts.publish(context, LocationSelectionEvents.LOCATIONS_UPDATED)
                        return
                    }
                }
            }

            // Cities selected?
            for (i in entryValues.indices) {
                if (mClickedDialogEntryIndices[i]) {
                    // Don't save the state of check all option - if any
                    val entryValue = entryValues[i] as String
                    if (checkAllKey == null || entryValue != checkAllKey) {
                        values.add(entryValue)
                    }
                }
            }

            // Nothing selected
            if (values.size == 0) {
                values.add(context.getString(R.string.nullString))
            }

            //if (callChangeListener(value)) {
            setValue(join(values, separator))
            //}
        }

        if (positiveResult) {
            Broadcasts.publish(context, LocationSelectionEvents.LOCATIONS_UPDATED)
        }
    }

    fun showDialog() {
        super.showDialog(null)
    }

    class ViewHolder {
        lateinit var label: TextView
        lateinit var subLabel: TextView
        lateinit var checkbox: CheckBox
    }

    // TODO: Would like to keep this static but separator then needs to be put in by hand or use default separator "OV=I=XseparatorX=I=VO"...

    private class ListItemWithIndex(
            @JvmField var index: Int,
            @JvmField var value: String,
            @JvmField var zone: String?,
            @JvmField var checked: Boolean,
            @JvmField var isDefault: Boolean
    ) : Comparable<ListItemWithIndex> {
        override fun toString(): String {
            return value
        }

        override fun compareTo(other: ListItemWithIndex): Int {
            if (isDefault) {
                return -1
            }

            if (other.isDefault) {
                return 1
            }

            if (checked && !other.checked) {
                return -1
            }

            if (!checked && other.checked) {
                return 1
            }

            if (checked && other.checked) {
                return value.compareTo(other.value)
            }

            return value.compareTo(other.value)
        }
    }
}
