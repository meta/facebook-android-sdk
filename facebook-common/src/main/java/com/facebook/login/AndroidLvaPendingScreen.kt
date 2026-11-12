/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.login

import android.content.Context
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import androidx.annotation.RequiresApi
import androidx.core.view.ViewCompat
import com.facebook.common.R

/**
 * The Android SDK Login Via App (LVA) pending screen. It is shown when the SDK activity resumes
 * while the login may still be finishing in Instagram or the browser. It starts as a waiting state
 * with no actions, because in a successful hand-off another window covers it at once; [showActions]
 * then adds the body, Continue (reopens the login) and Cancel (ends the attempt).
 */
internal object AndroidLvaPendingScreen {
  fun create(context: Context, onContinue: () -> Unit, onCancel: () -> Unit): View {
    val view = LayoutInflater.from(context).inflate(R.layout.com_facebook_android_lva_pending, null)
    // A heading for screen readers on every supported API level, not only API 28+.
    ViewCompat.setAccessibilityHeading(
        view.findViewById(R.id.com_facebook_android_lva_pending_title), true)
    // Screen readers announce the actions when they appear.
    ViewCompat.setAccessibilityLiveRegion(
        view.findViewById(R.id.com_facebook_android_lva_pending_actions),
        ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE)
    view.findViewById<Button>(R.id.com_facebook_android_lva_pending_continue).setOnClickListener {
      onContinue()
    }
    view.findViewById<Button>(R.id.com_facebook_android_lva_pending_cancel).setOnClickListener {
      onCancel()
    }
    return view
  }

  /** Replaces the waiting state of a screen from [create] with the body and both actions. */
  fun showActions(view: View) {
    view.findViewById<View>(R.id.com_facebook_android_lva_pending_progress).visibility = View.GONE
    view.findViewById<View>(R.id.com_facebook_android_lva_pending_actions).visibility = View.VISIBLE
  }

  /**
   * Keeps a screen from [create], shown as [window]'s content, clear of the system bars and display
   * cutout, with dark system-bar icons on its white background. Only needed where Android forces
   * edge-to-edge: API 35+ for a host app targeting API 35+. Elsewhere the window already sits
   * between the bars.
   */
  fun fitSystemBars(window: Window, view: View) {
    // 35 is VANILLA_ICE_CREAM, newer than the SDK's compileSdk.
    // ponytail: ignores the API 35 windowOptOutEdgeToEdgeEnforcement opt-out (removed in API 36);
    // read it from the theme if a host that opts out reports doubled padding.
    if (Build.VERSION.SDK_INT < 35 || view.context.applicationInfo.targetSdkVersion < 35) {
      return
    }
    Api30.fitSystemBars(window, view)
  }

  @RequiresApi(Build.VERSION_CODES.R)
  private object Api30 {
    fun fitSystemBars(window: Window, view: View) {
      val lightBars =
          WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
              WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
      window.insetsController?.setSystemBarsAppearance(lightBars, lightBars)
      view.setOnApplyWindowInsetsListener { v, insets ->
        val bars =
            insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        insets
      }
    }
  }
}
