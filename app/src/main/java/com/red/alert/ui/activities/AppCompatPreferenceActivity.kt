package com.red.alert.ui.activities

/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.preference.PreferenceActivity
import android.view.MenuInflater
import android.view.View
import android.view.ViewGroup

import androidx.appcompat.app.ActionBar
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.Toolbar

/**
 * A [android.preference.PreferenceActivity] which implements and proxies the necessary calls
 * to be used with AppCompat.
 *
 * This technique can be used with an [android.app.Activity] class, not just
 * [android.preference.PreferenceActivity].
 */
abstract class AppCompatPreferenceActivity : PreferenceActivity() {
    private var mThemeId = 0
    private var mDelegate: AppCompatDelegate? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        getDelegate().installViewFactory()
        getDelegate().onCreate(savedInstanceState)
        getDelegate().applyDayNight()

        // Re-apply theme
        if (Build.VERSION.SDK_INT >= 23) {
            onApplyThemeResource(theme, mThemeId, false)
        } else {
            setTheme(mThemeId)
        }

        super.onCreate(savedInstanceState)
    }

    override fun setTheme(themeId: Int) {
        super.setTheme(themeId)

        // Store theme ID for later
        mThemeId = themeId
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        getDelegate().onPostCreate(savedInstanceState)
    }

    val supportActionBar: ActionBar?
        get() = getDelegate().supportActionBar

    fun setSupportActionBar(toolbar: Toolbar?) {
        getDelegate().setSupportActionBar(toolbar)
    }

    override fun getMenuInflater(): MenuInflater {
        return getDelegate().menuInflater
    }

    override fun setContentView(layoutResID: Int) {
        getDelegate().setContentView(layoutResID)
    }

    override fun setContentView(view: View?) {
        getDelegate().setContentView(view)
    }

    override fun setContentView(view: View?, params: ViewGroup.LayoutParams?) {
        getDelegate().setContentView(view, params)
    }

    override fun addContentView(view: View?, params: ViewGroup.LayoutParams?) {
        getDelegate().addContentView(view, params)
    }

    override fun onPostResume() {
        super.onPostResume()
        getDelegate().onPostResume()
    }

    override fun onTitleChanged(title: CharSequence?, color: Int) {
        super.onTitleChanged(title, color)
        getDelegate().setTitle(title)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        getDelegate().onConfigurationChanged(newConfig)
    }

    override fun onStop() {
        super.onStop()
        getDelegate().onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        getDelegate().onDestroy()
    }

    override fun invalidateOptionsMenu() {
        getDelegate().invalidateOptionsMenu()
    }

    private fun getDelegate(): AppCompatDelegate {
        if (mDelegate == null) {
            mDelegate = AppCompatDelegate.create(this, null)
        }
        return mDelegate!!
    }
}
