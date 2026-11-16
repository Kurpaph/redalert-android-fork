package com.red.alert.ui.elements

/*
 * Copyright 2012 Jay Weisskopf
 *
 * Licensed under the MIT License (see LICENSE.txt)
 */

import android.content.Context
import android.content.res.TypedArray
import android.preference.DialogPreference
import android.util.AttributeSet
import android.view.View
import android.widget.SeekBar
import android.widget.TextView

import com.red.alert.R
import com.red.alert.utils.localization.Localization

/**
 * @author Jay Weisskopf
 */
open class SliderPreference : DialogPreference {
    companion object {
        internal const val SEEKBAR_RESOLUTION = 10000
    }

    protected var mValue: Float = 0f
    protected var minValue: Float = 0f
    protected var mSeekBarValue: Int = 0
    protected var mSummaries: Array<out CharSequence>? = null
    private var seekBarChangedListener: onSeekBarChangedListener? = null

    /**
     * @param context
     * @param attrs
     */
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        setup(context, attrs)
    }

    /**
     * @param context
     * @param attrs
     * @param defStyle
     */
    constructor(context: Context, attrs: AttributeSet?, defStyle: Int) : super(context, attrs, defStyle) {
        setup(context, attrs)
    }

    private fun setup(context: Context, attrs: AttributeSet?) {
        setDialogLayoutResource(R.layout.slider_preference_dialog)
        val a: TypedArray = context.obtainStyledAttributes(attrs, R.styleable.SliderPreference)
        try {
            setSummary(a.getTextArray(R.styleable.SliderPreference_android_summary))
        }
        catch (e: Exception) {
            // Do nothing
        }

        try {
            minValue = a.getFloat(R.styleable.SliderPreference_android_min, 0f)
        }
        catch (e: Exception) {
            // Do nothing
        }

        a.recycle()
    }

    override fun onGetDefaultValue(a: TypedArray, index: Int): Any? {
        return a.getFloat(index, 0f)
    }

    override fun onSetInitialValue(restoreValue: Boolean, defaultValue: Any?) {
        setValue(if (restoreValue) getPersistedFloat(mValue) else defaultValue as Float)
    }

    override fun getSummary(): CharSequence? {
        val summaries = mSummaries

        if (summaries != null && summaries.size > 0) {
            var index = (mValue * summaries.size).toInt()
            index = Math.min(index, summaries.size - 1)
            return summaries[index]
        }
        else {
            return super.getSummary()
        }
    }

    @Suppress("ResourceType")
    override fun setSummary(summaryResId: Int) {
        try {
            setSummary(context.resources.getStringArray(summaryResId))
        }
        catch (e: Exception) {
            super.setSummary(summaryResId)
        }
    }

    fun setSummary(summaries: Array<out CharSequence>?) {
        mSummaries = summaries
    }

    override fun setSummary(summary: CharSequence?) {
        super.setSummary(summary)
        mSummaries = null
    }

    fun getValue(): Float {
        return mValue
    }

    fun setValue(value: Float) {
        val clamped = Math.max(0f, Math.min(value, 1f)) // clamp to [0, 1]
        if (shouldPersist()) {
            persistFloat(clamped)
        }
        if (clamped != mValue) {
            mValue = clamped
            notifyChanged()
        }
    }

    internal fun getSeekBarChangedListener(): onSeekBarChangedListener? {
        return seekBarChangedListener
    }

    fun setSeekBarChangedListener(listener: onSeekBarChangedListener?) {
        seekBarChangedListener = listener
    }

    fun getDialogMessage(value: Float): String {
        val listener = getSeekBarChangedListener()

        if (listener == null) {
            return "?"
        }

        return Localization.localizeDigits(listener.getDialogMessage(value), context)
    }

    override fun onCreateDialogView(): View {
        mSeekBarValue = (mValue * SEEKBAR_RESOLUTION).toInt()
        val view = super.onCreateDialogView()

        val message = view.findViewById<TextView>(R.id.message)
        val seekbar = view.findViewById<SeekBar>(R.id.slider_preference_seekbar)

        seekbar.max = SEEKBAR_RESOLUTION
        seekbar.progress = mSeekBarValue
        seekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStopTrackingTouch(seekBar: SeekBar) {
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
            }

            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                var newProgress = progress

                // Check if minimum allowed value is set for this SliderPreference
                if (minValue > 0) {
                    // Calculate min value allowed
                    val minAllowed = (minValue * SEEKBAR_RESOLUTION).toInt()

                    // Progress bar set too low?
                    if (newProgress < minAllowed) {
                        // Increase progress to minimum value allowed
                        newProgress = minAllowed
                        seekBar.progress = newProgress
                    }
                }

                // Update progress value and refresh dialog summary text
                this@SliderPreference.mSeekBarValue = newProgress
                reloadDialogSummary(message)
            }
        })

        // Set initial summary
        reloadDialogSummary(message)

        return view
    }

    private fun reloadDialogSummary(message: TextView) {
        val newValue = mSeekBarValue.toFloat() / SEEKBAR_RESOLUTION

        val newMessage = getDialogMessage(newValue)

        message.text = newMessage
        setDialogMessage(newMessage)
    }

    override fun onDialogClosed(positiveResult: Boolean) {
        val newValue = mSeekBarValue.toFloat() / SEEKBAR_RESOLUTION
        if (positiveResult && callChangeListener(newValue)) {
            setValue(newValue)
        }
        super.onDialogClosed(positiveResult)
    }

    fun interface onSeekBarChangedListener {
        fun getDialogMessage(value: Float): String
    }

    // TODO: Save and restore preference state.
}
