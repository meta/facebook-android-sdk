/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.login

import android.app.Activity
import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.test.core.app.ApplicationProvider
import com.facebook.FacebookTestCase
import com.facebook.common.R
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.robolectric.Robolectric

class AndroidLvaPendingScreenTest : FacebookTestCase() {

  private val context: Context = ApplicationProvider.getApplicationContext()

  @Test
  fun `the screen starts waiting, with the title and progress but no actions`() {
    val view = AndroidLvaPendingScreen.create(context, {}, {})
    assertThat(view.findViewById<TextView>(R.id.com_facebook_android_lva_pending_title).text)
        .isEqualTo(context.getString(R.string.com_facebook_android_lva_pending_title))
    assertThat(
            ViewCompat.isAccessibilityHeading(
                view.findViewById(R.id.com_facebook_android_lva_pending_title)))
        .isTrue
    assertThat(view.findViewById<View>(R.id.com_facebook_android_lva_pending_progress).visibility)
        .isEqualTo(View.VISIBLE)
    val actions = view.findViewById<View>(R.id.com_facebook_android_lva_pending_actions)
    assertThat(actions.visibility).isEqualTo(View.GONE)
    assertThat(ViewCompat.getAccessibilityLiveRegion(actions))
        .isEqualTo(ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE)
  }

  @Test
  fun `showActions replaces the progress with the pending copy and both actions`() {
    val view = AndroidLvaPendingScreen.create(context, {}, {})
    AndroidLvaPendingScreen.showActions(view)

    assertThat(view.findViewById<View>(R.id.com_facebook_android_lva_pending_progress).visibility)
        .isEqualTo(View.GONE)
    assertThat(view.findViewById<View>(R.id.com_facebook_android_lva_pending_actions).visibility)
        .isEqualTo(View.VISIBLE)
    assertThat(view.findViewById<TextView>(R.id.com_facebook_android_lva_pending_body).text)
        .isEqualTo(context.getString(R.string.com_facebook_android_lva_pending_body))
    assertThat(view.findViewById<Button>(R.id.com_facebook_android_lva_pending_continue).text)
        .isEqualTo(context.getString(R.string.com_facebook_android_lva_pending_continue))
    assertThat(view.findViewById<Button>(R.id.com_facebook_android_lva_pending_cancel).text)
        .isEqualTo(context.getString(R.string.com_facebook_loginview_cancel_action))
  }

  @Test
  fun `Continue and Cancel each call only their own action`() {
    var continued = 0
    var cancelled = 0
    val view = AndroidLvaPendingScreen.create(context, { continued++ }, { cancelled++ })
    AndroidLvaPendingScreen.showActions(view)

    view.findViewById<Button>(R.id.com_facebook_android_lva_pending_continue).performClick()
    assertThat(continued).isEqualTo(1)
    assertThat(cancelled).isEqualTo(0)

    view.findViewById<Button>(R.id.com_facebook_android_lva_pending_cancel).performClick()
    assertThat(continued).isEqualTo(1)
    assertThat(cancelled).isEqualTo(1)
  }

  @Test
  fun `below API 35 fitSystemBars leaves the screen and window as they are`() {
    val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    val view = AndroidLvaPendingScreen.create(activity, {}, {})
    activity.setContentView(view)
    val flagsBefore = activity.window.decorView.systemUiVisibility

    // Runs at API 21: returning early is what keeps the API 30+ classes from loading.
    AndroidLvaPendingScreen.fitSystemBars(activity.window, view)
    assertThat(view.paddingTop).isEqualTo(0)
    assertThat(activity.window.decorView.systemUiVisibility).isEqualTo(flagsBefore)
  }

  @Test
  fun `on a short screen the content scrolls so both actions stay reachable`() {
    val view = AndroidLvaPendingScreen.create(context, {}, {}) as ScrollView
    AndroidLvaPendingScreen.showActions(view)
    val width = 1080
    val height = 150
    view.measure(
        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
    view.layout(0, 0, width, height)
    assertThat(view.canScrollVertically(1)).isTrue

    view.scrollTo(0, view.getChildAt(0).height)
    val cancel = view.findViewById<Button>(R.id.com_facebook_android_lva_pending_cancel)
    val actions = view.findViewById<View>(R.id.com_facebook_android_lva_pending_actions)
    assertThat(actions.top + cancel.bottom - view.scrollY).isLessThanOrEqualTo(height)
  }
}
