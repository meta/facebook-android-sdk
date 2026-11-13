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
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.facebook.login.AndroidLvaAttemptRecord
import com.facebook.login.AndroidLvaCallbackCheck

/*
 * Login from a custom tab redirects here. Pass the url on to CustomTabMainActivity so it can return
 * the result.
 */
class CustomTabActivity : Activity() {
  private var closeReceiver: BroadcastReceiver? = null
  // Android SDK Login Via App: the live attempt this callback belongs to, if it is marked.
  private var androidLvaRecord: AndroidLvaAttemptRecord.Record? = null
  private val handler = Handler(Looper.getMainLooper())
  private var androidLvaOrphanTimeout: Runnable? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    when (val check = AndroidLvaCallbackCheck.check(getIntent().dataString)) {
      // A marked callback without a live attempt: finish only this activity, without forwarding
      // and without touching the open custom tab or the active attempt.
      is AndroidLvaCallbackCheck.Result.Rejected -> {
        finish()
        return
      }
      is AndroidLvaCallbackCheck.Result.Accepted -> androidLvaRecord = check.record
      is AndroidLvaCallbackCheck.Result.Unmarked -> Unit
    }
    val intent = Intent(this, CustomTabMainActivity::class.java)
    intent.action = CUSTOM_TAB_REDIRECT_ACTION
    intent.putExtra(CustomTabMainActivity.EXTRA_URL, getIntent().dataString)

    // these flags will open CustomTabMainActivity from the back stack as well as closing this
    // activity and the custom tab opened by CustomTabMainActivity.
    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    startActivityForResult(intent, CUSTOM_TAB_REDIRECT_REQUEST_CODE)
  }

  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    if (resultCode == RESULT_CANCELED) {
      // We weren't able to open CustomTabMainActivity from the back stack. Send a broadcast
      // instead.
      val broadcast = Intent(CUSTOM_TAB_REDIRECT_ACTION)
      broadcast.putExtra(CustomTabMainActivity.EXTRA_URL, intent.dataString)
      LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast)

      // Wait for the custom tab to be removed from the back stack before finishing.
      val closeReceiver =
          object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
              // Acknowledged: this callback isn't orphaned, so its record must not be cleared.
              cancelAndroidLvaOrphanTimeout()
              finish()
            }
          }
      LocalBroadcastManager.getInstance(this)
          .registerReceiver(closeReceiver, IntentFilter(DESTROY_ACTION))
      this.closeReceiver = closeReceiver

      // For a live Android SDK LVA attempt with no restorable task, don't wait forever: if no
      // receiver acknowledges in time, clear this attempt's record (a newer attempt keeps its own)
      // and finish. This bounds the fallback; it is not an attempt timer.
      androidLvaRecord?.let { record ->
        val timeout = Runnable {
          this.closeReceiver?.let { LocalBroadcastManager.getInstance(this).unregisterReceiver(it) }
          this.closeReceiver = null
          AndroidLvaAttemptRecord.clearIf(record.state, record.version)
          finish()
        }
        androidLvaOrphanTimeout = timeout
        handler.postDelayed(timeout, ANDROID_LVA_ORPHAN_TIMEOUT_MS)
      }
    }
  }

  private fun cancelAndroidLvaOrphanTimeout() {
    androidLvaOrphanTimeout?.let { handler.removeCallbacks(it) }
    androidLvaOrphanTimeout = null
  }

  override fun onDestroy() {
    cancelAndroidLvaOrphanTimeout()
    closeReceiver?.let { LocalBroadcastManager.getInstance(this).unregisterReceiver(it) }
    super.onDestroy()
  }

  companion object {
    private const val CUSTOM_TAB_REDIRECT_REQUEST_CODE = 2

    @JvmField
    val CUSTOM_TAB_REDIRECT_ACTION =
        CustomTabActivity::class.java.simpleName + ".action_customTabRedirect"

    @JvmField val DESTROY_ACTION = CustomTabActivity::class.java.simpleName + ".action_destroy"

    internal const val ANDROID_LVA_ORPHAN_TIMEOUT_MS = 5_000L
  }
}
