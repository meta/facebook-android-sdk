/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.window.OnBackInvokedDispatcher
import androidx.annotation.RequiresApi
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.facebook.internal.CustomTab
import com.facebook.internal.InstagramCustomTab
import com.facebook.internal.NativeProtocol.createProtocolResultIntent
import com.facebook.internal.ServerProtocol
import com.facebook.internal.Utility.parseUrlQueryString
import com.facebook.login.AndroidLvaAttemptRecord
import com.facebook.login.AndroidLvaPendingScreen
import com.facebook.login.LoginTargetApp
import com.facebook.login.LoginTargetApp.Companion.fromString

class CustomTabMainActivity : Activity() {
  private var shouldCloseCustomTab = true
  private var redirectReceiver: BroadcastReceiver? = null

  // Android SDK Login Via App (LVA): the state of an attempt in scope, whose record is in
  // AndroidLvaAttemptRecord. Null for every other attempt, which behaves exactly as before.
  private var androidLvaState: String? = null
  // The record version the shown pending screen is for; null once one of its actions ran, so a
  // repeated or stale action is ignored.
  private var androidLvaPendingVersion: Int? = null
  // The shown pending screen, and the delayed reveal of its actions, which onPause removes.
  private var androidLvaPendingView: View? = null
  private val androidLvaHandler = Handler(Looper.getMainLooper())
  private val showAndroidLvaPendingActions = Runnable {
    androidLvaPendingView?.let { AndroidLvaPendingScreen.showActions(it) }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // A launch without an action never opens a tab, so it is not treated as an LVA attempt.
    androidLvaState =
        intent.getStringExtra(EXTRA_ANDROID_LVA_STATE)?.takeIf {
          intent.getStringExtra(EXTRA_ACTION) != null
        }

    // Custom Tab Redirects should not be creating a new instance of this activity
    if (CustomTabActivity.CUSTOM_TAB_REDIRECT_ACTION == intent.action) {
      setResult(RESULT_CANCELED)
      finish()
      return
    }

    // Hosts using predictive back (opted in, or targeting API 36) never call onBackPressed.
    if (androidLvaState != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      Api33.registerBackCallback(this) { onAndroidLvaBack() }
    }

    if (savedInstanceState == null) {
      val action = intent.getStringExtra(EXTRA_ACTION) ?: return
      val couldOpenCustomTab = openCustomTab(action, intent.getBundleExtra(EXTRA_PARAMS))
      // The resume that immediately follows creation is not a return from the custom tab.
      shouldCloseCustomTab = false
      if (!couldOpenCustomTab) {
        return
      }
    }

    // A recreated Android SDK LVA activity waits on the pending screen instead of cancelling, so it
    // listens again; otherwise a callback that falls back to the broadcast (for example after
    // process death) is lost. Every other recreated activity cancels on resume, as before.
    if (savedInstanceState == null || androidLvaState != null) {
      // This activity will receive a broadcast if it can't be opened from the back stack
      val redirectReceiver =
          object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
              // Remove the custom tab on top of this activity.
              val newIntent = Intent(this@CustomTabMainActivity, CustomTabMainActivity::class.java)
              newIntent.action = REFRESH_ACTION
              newIntent.putExtra(EXTRA_URL, intent.getStringExtra(EXTRA_URL))
              newIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
              startActivity(newIntent)
            }
          }
      this.redirectReceiver = redirectReceiver
      LocalBroadcastManager.getInstance(this)
          .registerReceiver(
              redirectReceiver, IntentFilter(CustomTabActivity.CUSTOM_TAB_REDIRECT_ACTION))
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    if (REFRESH_ACTION == intent.action) {
      // The custom tab is now destroyed so we can finish the redirect activity
      val broadcast = Intent(CustomTabActivity.DESTROY_ACTION)
      LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast)
      sendResult(RESULT_OK, intent)
    } else if (CustomTabActivity.CUSTOM_TAB_REDIRECT_ACTION == intent.action) {
      // We have successfully redirected back to this activity. Return the result and close.
      sendResult(RESULT_OK, intent)
    }
  }

  override fun onResume() {
    super.onResume()
    if (shouldCloseCustomTab) {
      // The custom tab was closed without getting a result.
      val state = androidLvaState
      if (state != null) {
        // A callback or cancel already finished the activity; Android still resumes it once.
        if (!isFinishing) {
          onAndroidLvaResumeWithoutResult(state)
        }
      } else {
        sendResult(RESULT_CANCELED, null)
      }
    }
    shouldCloseCustomTab = true
  }

  override fun onPause() {
    super.onPause()
    // Covered before the pending screen's actions showed, as by full Chrome in an LVA hand-off:
    // they stay hidden, and the next resume (a return to a pending screen) shows them at once.
    androidLvaHandler.removeCallbacks(showAndroidLvaPendingActions)
  }

  override fun onDestroy() {
    // A recreated LVA activity registers its own receiver, so the destroyed one's must not linger.
    if (androidLvaState != null) {
      unregisterRedirectReceiver()
    }
    super.onDestroy()
  }

  @Deprecated("Deprecated in Java")
  override fun onBackPressed() {
    if (androidLvaState != null) {
      onAndroidLvaBack()
      return
    }
    @Suppress("DEPRECATION") super.onBackPressed()
  }

  /**
   * Back for an Android SDK LVA attempt, from onBackPressed or the predictive-back callback. It
   * cancels only while the pending screen can still act; after Continue (or while finishing) it does
   * nothing, so it can't cancel the launch just opened.
   */
  private fun onAndroidLvaBack() {
    if (consumeAndroidLvaPendingAction(androidLvaPendingVersion)) {
      sendResult(RESULT_CANCELED, null)
    }
  }

  /** Opens the custom tab; on failure, sets the no-activity result and finishes. */
  private fun openCustomTab(action: String, parameters: Bundle?): Boolean {
    val chromePackage = intent.getStringExtra(EXTRA_CHROME_PACKAGE)
    val targetApp = fromString(intent.getStringExtra(EXTRA_TARGET_APP))
    val customTab: CustomTab =
        when (targetApp) {
          LoginTargetApp.INSTAGRAM -> InstagramCustomTab(action, parameters)
          else -> CustomTab(action, parameters)
        }
    val couldOpenCustomTab = customTab.openCustomTab(this, chromePackage)
    if (!couldOpenCustomTab) {
      // Continue can fail after the first launch registered the redirect receiver.
      unregisterRedirectReceiver()
      setResult(RESULT_CANCELED, intent.putExtra(NO_ACTIVITY_EXCEPTION, true))
      finish()
    }
    return couldOpenCustomTab
  }

  /**
   * Whether the pending screen for [version] may act now. It may act once: this consumes it, so a
   * repeated tap, or a tap on a screen shown for an older version, does nothing.
   */
  private fun consumeAndroidLvaPendingAction(version: Int?): Boolean {
    if (version == null || version != androidLvaPendingVersion || isFinishing) {
      return false
    }
    androidLvaPendingVersion = null
    return true
  }

  /**
   * The SDK activity resumed while the login may still be finishing in Instagram or the browser, so
   * a resume isn't evidence of a user close. A live attempt enters PENDING and shows the pending
   * screen, also after recreation. A missing, expired or unwritable record cancels, as before.
   *
   * The first return of a launch may be the LVA hand-off, after which full Chrome covers this
   * activity at once, so the screen waits [ANDROID_LVA_ACTIONS_DELAY_MS] before showing its
   * actions. A return to a screen already pending, or after Continue (never routed), shows them at
   * once.
   */
  private fun onAndroidLvaResumeWithoutResult(state: String) {
    val record =
        AndroidLvaAttemptRecord.read()?.takeIf { it.state == state }
            ?: return sendResult(RESULT_CANCELED, null)
    val pending =
        AndroidLvaAttemptRecord.enterPending(record.state, record.version)
            ?: return sendResult(RESULT_CANCELED, null)
    val version = pending.version
    androidLvaPendingVersion = version
    val view =
        AndroidLvaPendingScreen.create(
            this,
            onContinue = {
              if (consumeAndroidLvaPendingAction(version)) {
                onAndroidLvaContinue(state, version)
              }
            },
            onCancel = {
              if (consumeAndroidLvaPendingAction(version)) {
                sendResult(RESULT_CANCELED, null)
              }
            })
    androidLvaPendingView = view
    setContentView(view)
    AndroidLvaPendingScreen.fitSystemBars(window, view)
    // Only Continue (AndroidLvaAttemptRecord.continueLaunch) raises the version, so a version above
    // 1 means this return follows a Continue, whose tab is never routed to LVA.
    val returnAfterContinue = version > 1
    if (record.phase == AndroidLvaAttemptRecord.Phase.PENDING || returnAfterContinue) {
      AndroidLvaPendingScreen.showActions(view)
    } else {
      androidLvaHandler.postDelayed(showAndroidLvaPendingActions, ANDROID_LVA_ACTIONS_DELAY_MS)
    }
  }

  /**
   * Continue reopens the same request, with the same state and logger ID, in a fresh launch window
   * and as chrome_custom_tab_pkg, so the server never routes it to LVA.
   */
  private fun onAndroidLvaContinue(state: String, version: Int) {
    val action = intent.getStringExtra(EXTRA_ACTION)
    if (action == null || AndroidLvaAttemptRecord.continueLaunch(state, version) == null) {
      sendResult(RESULT_CANCELED, null)
      return
    }
    val parameters = Bundle(intent.getBundleExtra(EXTRA_PARAMS) ?: Bundle())
    parameters.putString(ServerProtocol.DIALOG_PARAM_SSO_DEVICE, ANDROID_LVA_PACKAGE_ONLY_SSO)
    // The activity is already resumed, so the next resume is the return from this custom tab and
    // must count as a resume without a result (shouldCloseCustomTab stays true).
    openCustomTab(action, parameters)
  }

  private fun unregisterRedirectReceiver() {
    redirectReceiver?.let {
      LocalBroadcastManager.getInstance(this@CustomTabMainActivity).unregisterReceiver(it)
    }
    redirectReceiver = null
  }

  private fun sendResult(resultCode: Int, resultIntent: Intent?) {
    unregisterRedirectReceiver()
    if (resultIntent != null) {
      val responseURL = resultIntent.getStringExtra(EXTRA_URL)
      val results = if (responseURL != null) parseResponseUri(responseURL) else Bundle()
      val nativeProtocolResultIntent = createProtocolResultIntent(intent, results, null)
      setResult(resultCode, nativeProtocolResultIntent ?: resultIntent)
    } else {
      setResult(resultCode, createProtocolResultIntent(intent, null, null))
    }
    finish()
  }

  @RequiresApi(Build.VERSION_CODES.TIRAMISU)
  private object Api33 {
    fun registerBackCallback(activity: Activity, onBack: () -> Unit) {
      activity.onBackInvokedDispatcher.registerOnBackInvokedCallback(
          OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
            onBack()
          }
    }
  }

  companion object {
    @JvmField val EXTRA_ACTION = CustomTabMainActivity::class.java.simpleName + ".extra_action"
    @JvmField val EXTRA_PARAMS = CustomTabMainActivity::class.java.simpleName + ".extra_params"
    @JvmField
    val EXTRA_CHROME_PACKAGE = CustomTabMainActivity::class.java.simpleName + ".extra_chromePackage"
    @JvmField val EXTRA_URL = CustomTabMainActivity::class.java.simpleName + ".extra_url"
    @JvmField
    val EXTRA_TARGET_APP = CustomTabMainActivity::class.java.simpleName + ".extra_targetApp"
    @JvmField val REFRESH_ACTION = CustomTabMainActivity::class.java.simpleName + ".action_refresh"
    @JvmField
    val NO_ACTIVITY_EXCEPTION =
        CustomTabMainActivity::class.java.simpleName + ".no_activity_exception"
    // Set by the Custom Tab handler for an Android SDK LVA attempt in scope.
    internal val EXTRA_ANDROID_LVA_STATE =
        CustomTabMainActivity::class.java.simpleName + ".extra_android_lva_state"
    // The Android SDK LVA sso values: an attempt in scope sends ANDROID_LVA_SSO (may be routed to
    // LVA); Continue sends ANDROID_LVA_PACKAGE_ONLY_SSO (never routed). Both are closed strictly.
    internal const val ANDROID_LVA_SSO = "chrome_custom_tab_lva"
    internal const val ANDROID_LVA_PACKAGE_ONLY_SSO = "chrome_custom_tab_pkg"
    // How long the pending screen waits on the first return of a launch before showing its
    // actions. A placeholder until devices measure the gap between the Custom Tab closing and full
    // Chrome covering this activity (design 5.1.4); a longer gap shows the actions briefly.
    internal const val ANDROID_LVA_ACTIONS_DELAY_MS = 2_000L

    private fun parseResponseUri(urlString: String): Bundle {
      val uri = Uri.parse(urlString)
      val bundle = parseUrlQueryString(uri.query)
      bundle.putAll(parseUrlQueryString(uri.fragment))
      return bundle
    }
  }
}
