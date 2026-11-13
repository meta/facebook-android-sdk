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
import android.net.Uri
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.test.core.app.ApplicationProvider
import com.facebook.login.AndroidLvaAttemptRecord
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.powermock.api.mockito.PowerMockito
import org.powermock.core.classloader.annotations.PrepareForTest
import org.robolectric.Robolectric
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowLooper

/** The Android SDK Login Via App checks in the Custom Tab callback receiver. */
@PrepareForTest(LocalBroadcastManager::class, FacebookSdk::class)
class CustomTabActivityAndroidLvaTest : FacebookPowerMockTestCase() {
  companion object {
    const val STATE = "{\"0_auth_logger_id\":\"logger1\",\"lva\":1}"
  }

  private lateinit var context: Context
  private lateinit var activityController: ActivityController<CustomTabActivity>
  private lateinit var mockLocalBroadcastManager: LocalBroadcastManager

  override fun setup() {
    super.setup()
    context = ApplicationProvider.getApplicationContext()
    mockLocalBroadcastManager = mock()
    PowerMockito.mockStatic(LocalBroadcastManager::class.java)
    whenever(LocalBroadcastManager.getInstance(any())).thenReturn(mockLocalBroadcastManager)
    PowerMockito.mockStatic(FacebookSdk::class.java)
    whenever(FacebookSdk.getApplicationContext()).thenReturn(context)
    context
        .getSharedPreferences(AndroidLvaAttemptRecord.PREFS_STORE, Context.MODE_PRIVATE)
        .edit()
        .clear()
        .commit()
  }

  private fun receiveCallback(state: String): CustomTabActivity {
    val url = "fbconnect://cct.com.example.app#access_token=token1&state=${Uri.encode(state)}"
    activityController =
        Robolectric.buildActivity(
            CustomTabActivity::class.java, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    activityController.create(null).start()
    return activityController.get()
  }

  @Test
  fun `a marked callback without a live attempt finishes without forwarding`() {
    val activity = receiveCallback(STATE)
    assertThat(activity.isFinishing).isTrue
    assertThat(Shadows.shadowOf(activity).nextStartedActivityForResult).isNull()
  }

  @Test
  fun `the live attempt's callback is forwarded`() {
    AndroidLvaAttemptRecord.launch(STATE, "logger1", "treatment")
    val activity = receiveCallback(STATE)
    assertThat(activity.isFinishing).isFalse
    assertThat(Shadows.shadowOf(activity).nextStartedActivityForResult.intent.action)
        .isEqualTo(CustomTabActivity.CUSTOM_TAB_REDIRECT_ACTION)
  }

  @Test
  fun `an orphaned callback stops waiting after the timeout and clears its own attempt`() {
    AndroidLvaAttemptRecord.launch(STATE, "logger1", "treatment")
    val activity = receiveCallback(STATE)
    val shadow = Shadows.shadowOf(activity)
    // No restorable task: the main activity couldn't be opened from the back stack.
    shadow.receiveResult(shadow.nextStartedActivityForResult.intent, Activity.RESULT_CANCELED, null)
    assertThat(activity.isFinishing).isFalse

    ShadowLooper.idleMainLooper(
        CustomTabActivity.ANDROID_LVA_ORPHAN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    assertThat(activity.isFinishing).isTrue
    assertThat(AndroidLvaAttemptRecord.read()).isNull()
  }

  @Test
  fun `the orphan timeout keeps a newer attempt`() {
    AndroidLvaAttemptRecord.launch(STATE, "logger1", "treatment")
    val activity = receiveCallback(STATE)
    val shadow = Shadows.shadowOf(activity)
    shadow.receiveResult(shadow.nextStartedActivityForResult.intent, Activity.RESULT_CANCELED, null)
    val newer = AndroidLvaAttemptRecord.launch("{\"lva\":1,\"n\":2}", "logger2", "treatment")

    ShadowLooper.idleMainLooper(
        CustomTabActivity.ANDROID_LVA_ORPHAN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    assertThat(activity.isFinishing).isTrue
    assertThat(AndroidLvaAttemptRecord.read()).isEqualTo(newer)
  }

  @Test
  fun `an unmarked callback keeps waiting for the main activity as before`() {
    AndroidLvaAttemptRecord.launch(STATE, "logger1", "treatment")
    val activity = receiveCallback("{\"0_auth_logger_id\":\"logger1\"}")
    val shadow = Shadows.shadowOf(activity)
    shadow.receiveResult(shadow.nextStartedActivityForResult.intent, Activity.RESULT_CANCELED, null)

    ShadowLooper.idleMainLooper(
        CustomTabActivity.ANDROID_LVA_ORPHAN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    assertThat(activity.isFinishing).isFalse
    assertThat(AndroidLvaAttemptRecord.read()).isNotNull
  }

  @Test
  fun `a destroy before the timeout cancels it and keeps the attempt's record`() {
    AndroidLvaAttemptRecord.launch(STATE, "logger1", "treatment")
    val activity = receiveCallback(STATE)
    val shadow = Shadows.shadowOf(activity)
    shadow.receiveResult(shadow.nextStartedActivityForResult.intent, Activity.RESULT_CANCELED, null)

    activityController.stop().destroy()
    verify(mockLocalBroadcastManager).unregisterReceiver(any())
    // A timeout still queued would clear the record after the activity is gone.
    ShadowLooper.idleMainLooper(
        CustomTabActivity.ANDROID_LVA_ORPHAN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    assertThat(AndroidLvaAttemptRecord.read()).isNotNull
  }

  @Test
  fun `an acknowledgement before the timeout keeps the attempt's record`() {
    AndroidLvaAttemptRecord.launch(STATE, "logger1", "treatment")
    val activity = receiveCallback(STATE)
    val shadow = Shadows.shadowOf(activity)
    shadow.receiveResult(shadow.nextStartedActivityForResult.intent, Activity.RESULT_CANCELED, null)
    val closeReceivers = argumentCaptor<BroadcastReceiver>()
    verify(mockLocalBroadcastManager).registerReceiver(closeReceivers.capture(), any())

    // The main activity acknowledges; the activity finishes but isn't destroyed yet.
    closeReceivers.firstValue.onReceive(activity, Intent(CustomTabActivity.DESTROY_ACTION))
    ShadowLooper.idleMainLooper(
        CustomTabActivity.ANDROID_LVA_ORPHAN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    assertThat(activity.isFinishing).isTrue
    assertThat(AndroidLvaAttemptRecord.read()).isNotNull
  }
}
