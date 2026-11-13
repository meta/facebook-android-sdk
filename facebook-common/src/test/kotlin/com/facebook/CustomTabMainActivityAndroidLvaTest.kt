/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.view.View
import androidx.browser.customtabs.CustomTabsIntent
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.test.core.app.ApplicationProvider
import com.facebook.common.R
import com.facebook.login.AndroidLvaAttemptRecord
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.powermock.api.mockito.PowerMockito
import org.powermock.core.classloader.annotations.PrepareForTest
import org.powermock.reflect.Whitebox
import org.robolectric.Robolectric
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController

/** The Android SDK Login Via App pending lifecycle in CustomTabMainActivity. */
@PrepareForTest(LocalBroadcastManager::class, CustomTabsIntent::class, FacebookSdk::class)
class CustomTabMainActivityAndroidLvaTest : FacebookPowerMockTestCase() {
  companion object {
    const val STATE = "{\"lva\":1}"
    val MINUTE = TimeUnit.MINUTES.toMillis(1)
  }

  private lateinit var context: Context
  private lateinit var activityController: ActivityController<CustomTabMainActivity>
  private lateinit var launchIntent: Intent
  private lateinit var mockCustomTabsIntent: CustomTabsIntent
  private lateinit var mockLocalBroadcastManager: LocalBroadcastManager

  override fun setup() {
    super.setup()
    context = ApplicationProvider.getApplicationContext()
    mockLocalBroadcastManager = mock()
    PowerMockito.mockStatic(LocalBroadcastManager::class.java)
    PowerMockito.`when`(LocalBroadcastManager.getInstance(any()))
        .thenReturn(mockLocalBroadcastManager)
    mockCustomTabsIntent = mock()
    Whitebox.setInternalState(mockCustomTabsIntent, "intent", Intent(Intent.ACTION_VIEW))
    PowerMockito.whenNew(CustomTabsIntent::class.java)
        .withAnyArguments()
        .thenReturn(mockCustomTabsIntent)
    PowerMockito.mockStatic(FacebookSdk::class.java)
    whenever(FacebookSdk.getApplicationContext()).thenReturn(context)
    whenever(FacebookSdk.getFacebookDomain()).thenReturn("facebook.com")
    whenever(FacebookSdk.getGraphApiVersion()).thenReturn("v19.0")
    context
        .getSharedPreferences(AndroidLvaAttemptRecord.PREFS_STORE, Context.MODE_PRIVATE)
        .edit()
        .clear()
        .commit()

    launchIntent = Intent(Intent.ACTION_VIEW)
    launchIntent.putExtra(CustomTabMainActivity.EXTRA_ACTION, "oauth")
    launchIntent.putExtra(CustomTabMainActivity.EXTRA_CHROME_PACKAGE, "com.android.chrome")
    launchIntent.putExtra(
        CustomTabMainActivity.EXTRA_PARAMS,
        Bundle().apply { putString("sso", CustomTabMainActivity.ANDROID_LVA_SSO) })
    launchIntent.putExtra(CustomTabMainActivity.EXTRA_ANDROID_LVA_STATE, STATE)
  }

  private fun launchRecord(at: Long = System.currentTimeMillis()) {
    AndroidLvaAttemptRecord.launch(STATE, "logger1", "treatment", at)
  }

  /** Creates the activity, which opens the custom tab, and returns from it without a result. */
  private fun returnWithoutResult(): CustomTabMainActivity {
    activityController = Robolectric.buildActivity(CustomTabMainActivity::class.java, launchIntent)
    activityController.create(null).resume() // the resume right after creation
    activityController.pause().resume() // the return from the custom tab
    return activityController.get()
  }

  /** Lets the first return's delayed reveal of the pending screen's actions run. */
  private fun waitForActions() {
    Shadows.shadowOf(Looper.getMainLooper())
        .idleFor(CustomTabMainActivity.ANDROID_LVA_ACTIONS_DELAY_MS, TimeUnit.MILLISECONDS)
  }

  private fun returnAndShowActions(): CustomTabMainActivity =
      returnWithoutResult().also { waitForActions() }

  private fun CustomTabMainActivity.actionsShown(): Boolean =
      checkNotNull(findViewById<View>(R.id.com_facebook_android_lva_pending_actions)).visibility ==
          View.VISIBLE

  private fun CustomTabMainActivity.pendingButton(id: Int): View {
    check(actionsShown()) { "the pending screen's actions are hidden" }
    return checkNotNull(findViewById<View>(id))
  }

  private fun launches() = argumentCaptor<Uri>().also {
    verify(mockCustomTabsIntent, atLeastOnce())
        .launchUrl(eq(activityController.get()), it.capture())
  }.allValues.size

  /** Creates the activity and returns the redirect receiver it registered. */
  private fun registeredReceiverAfterCreate(): BroadcastReceiver {
    activityController = Robolectric.buildActivity(CustomTabMainActivity::class.java, launchIntent)
    activityController.create(null)
    val receivers = argumentCaptor<BroadcastReceiver>()
    verify(mockLocalBroadcastManager).registerReceiver(receivers.capture(), any())
    return receivers.firstValue
  }

  private fun markedCallback(state: String) =
      Intent(CustomTabActivity.CUSTOM_TAB_REDIRECT_ACTION)
          .putExtra(
              CustomTabMainActivity.EXTRA_URL,
              "fbconnect://cct.com.example.app#state=${Uri.encode(state)}")

  @Test
  fun `a return without a result shows the pending screen and enters PENDING`() {
    launchRecord()
    val activity = returnWithoutResult()

    assertThat(activity.isFinishing).isFalse
    assertThat(activity.findViewById<View>(R.id.com_facebook_android_lva_pending_title)).isNotNull
    assertThat(AndroidLvaAttemptRecord.read()?.phase)
        .isEqualTo(AndroidLvaAttemptRecord.Phase.PENDING)
    // The first return may be the hand-off to full Chrome, so the actions wait.
    assertThat(activity.actionsShown()).isFalse
    waitForActions()
    assertThat(activity.actionsShown()).isTrue
  }

  @Test
  fun `a pause before the delay keeps the actions hidden, and the next return shows them at once`() {
    launchRecord()
    val activity = returnWithoutResult()

    activityController.pause() // full Chrome covers the activity
    waitForActions()
    assertThat(activity.actionsShown()).isFalse

    activityController.resume() // the user comes back before the login finishes
    assertThat(activity.actionsShown()).isTrue
    assertThat(AndroidLvaAttemptRecord.read()?.version).isEqualTo(1)
  }

  @Test
  fun `Continue reopens the request as package-only, and the next return is pending again`() {
    launchRecord()
    val activity = returnAndShowActions()

    activity.pendingButton(R.id.com_facebook_android_lva_pending_continue).performClick()
    val uris = argumentCaptor<Uri>()
    verify(mockCustomTabsIntent, times(2)).launchUrl(eq(activity), uris.capture())
    assertThat(uris.secondValue.getQueryParameter("sso")).isEqualTo("chrome_custom_tab_pkg")
    val continued = checkNotNull(AndroidLvaAttemptRecord.read())
    assertThat(continued.version).isEqualTo(2)
    assertThat(continued.phase).isEqualTo(AndroidLvaAttemptRecord.Phase.LAUNCHED)

    activityController.pause().resume()
    assertThat(activity.isFinishing).isFalse
    assertThat(AndroidLvaAttemptRecord.read()?.phase)
        .isEqualTo(AndroidLvaAttemptRecord.Phase.PENDING)
    // Continue is never routed, so a return from it is the user's: the actions show at once.
    assertThat(activity.actionsShown()).isTrue
  }

  @Test
  fun `a successful callback returns the result without the pending screen`() {
    launchRecord()
    activityController = Robolectric.buildActivity(CustomTabMainActivity::class.java, launchIntent)
    activityController.create(null).resume().pause()
    // The callback arrives, then Android resumes the finishing activity once.
    activityController.newIntent(Intent(CustomTabActivity.CUSTOM_TAB_REDIRECT_ACTION)).resume()
    val activity = activityController.get()

    assertThat(activity.isFinishing).isTrue
    assertThat(Shadows.shadowOf(activity).resultCode).isEqualTo(Activity.RESULT_OK)
    assertThat(activity.findViewById<View>(R.id.com_facebook_android_lva_pending_title)).isNull()
    assertThat(AndroidLvaAttemptRecord.read()?.phase)
        .isEqualTo(AndroidLvaAttemptRecord.Phase.LAUNCHED)
  }

  @Test
  fun `Cancel on the pending screen cancels`() {
    launchRecord()
    val activity = returnAndShowActions()

    activity.pendingButton(R.id.com_facebook_android_lva_pending_cancel).performClick()
    assertThat(activity.isFinishing).isTrue
    assertThat(Shadows.shadowOf(activity).resultCode).isEqualTo(Activity.RESULT_CANCELED)
  }

  @Test
  fun `Back on the pending screen cancels, also before its actions show`() {
    launchRecord()
    val activity = returnWithoutResult()
    assertThat(activity.actionsShown()).isFalse

    @Suppress("DEPRECATION") activity.onBackPressed()
    assertThat(activity.isFinishing).isTrue
    assertThat(Shadows.shadowOf(activity).resultCode).isEqualTo(Activity.RESULT_CANCELED)
  }

  @Test
  fun `a return after the deadline cancels`() {
    launchRecord(at = System.currentTimeMillis() - 31 * MINUTE)
    val activity = returnWithoutResult()

    assertThat(activity.isFinishing).isTrue
    assertThat(Shadows.shadowOf(activity).resultCode).isEqualTo(Activity.RESULT_CANCELED)
  }

  @Test
  fun `a return without a record for this attempt cancels`() {
    AndroidLvaAttemptRecord.launch("another state", "logger1", "treatment")
    val activity = returnWithoutResult()

    assertThat(activity.isFinishing).isTrue
    assertThat(Shadows.shadowOf(activity).resultCode).isEqualTo(Activity.RESULT_CANCELED)
  }

  @Test
  fun `recreation while pending shows the pending screen again`() {
    launchRecord()
    returnWithoutResult()

    activityController.recreate()
    val recreated = activityController.get()
    assertThat(recreated.isFinishing).isFalse
    assertThat(recreated.findViewById<View>(R.id.com_facebook_android_lva_pending_title))
        .isNotNull
    assertThat(recreated.actionsShown()).isTrue
    assertThat(AndroidLvaAttemptRecord.read()?.version).isEqualTo(1)
  }

  @Test
  fun `after recreation and Continue a broadcast callback still reaches the activity`() {
    launchRecord()
    returnWithoutResult()
    activityController.recreate()
    val recreated = activityController.get()
    val receivers = argumentCaptor<BroadcastReceiver>()
    verify(mockLocalBroadcastManager, times(2)).registerReceiver(receivers.capture(), any())
    verify(mockLocalBroadcastManager).unregisterReceiver(receivers.firstValue)

    recreated.pendingButton(R.id.com_facebook_android_lva_pending_continue).performClick()
    val callback =
        Intent(CustomTabActivity.CUSTOM_TAB_REDIRECT_ACTION)
            .putExtra(CustomTabMainActivity.EXTRA_URL, "fbconnect://cct.com.example?code=1")
    receivers.secondValue.onReceive(context, callback)
    val refresh = checkNotNull(Shadows.shadowOf(recreated).nextStartedActivity)
    assertThat(refresh.action).isEqualTo(CustomTabMainActivity.REFRESH_ACTION)

    activityController.newIntent(refresh)
    assertThat(recreated.isFinishing).isTrue
    assertThat(Shadows.shadowOf(recreated).resultCode).isEqualTo(Activity.RESULT_OK)
  }

  @Test
  fun `an attempt not in scope still cancels on return`() {
    launchIntent.removeExtra(CustomTabMainActivity.EXTRA_ANDROID_LVA_STATE)
    launchRecord()
    val activity = returnWithoutResult()

    assertThat(activity.isFinishing).isTrue
    assertThat(Shadows.shadowOf(activity).resultCode).isEqualTo(Activity.RESULT_CANCELED)
    assertThat(AndroidLvaAttemptRecord.read()?.phase)
        .isEqualTo(AndroidLvaAttemptRecord.Phase.LAUNCHED)
  }

  @Test
  fun `a launch without an action is not treated as an LVA attempt`() {
    launchIntent.removeExtra(CustomTabMainActivity.EXTRA_ACTION)
    launchRecord()
    activityController = Robolectric.buildActivity(CustomTabMainActivity::class.java, launchIntent)
    val activity = activityController.create(null).resume().get()

    assertThat(activity.isFinishing).isTrue
    assertThat(Shadows.shadowOf(activity).resultCode).isEqualTo(Activity.RESULT_CANCELED)
    assertThat(AndroidLvaAttemptRecord.read()?.phase)
        .isEqualTo(AndroidLvaAttemptRecord.Phase.LAUNCHED)
  }

  @Test
  fun `a second Continue tap before the custom tab opens does nothing`() {
    launchRecord()
    val activity = returnAndShowActions()
    val continueButton = activity.pendingButton(R.id.com_facebook_android_lva_pending_continue)

    continueButton.performClick()
    continueButton.performClick()
    assertThat(launches()).isEqualTo(2)
    assertThat(activity.isFinishing).isFalse
    assertThat(AndroidLvaAttemptRecord.read()?.version).isEqualTo(2)
  }

  @Test
  fun `Continue after Cancel does nothing`() {
    launchRecord()
    val activity = returnAndShowActions()

    activity.pendingButton(R.id.com_facebook_android_lva_pending_cancel).performClick()
    activity.pendingButton(R.id.com_facebook_android_lva_pending_continue).performClick()
    assertThat(Shadows.shadowOf(activity).resultCode).isEqualTo(Activity.RESULT_CANCELED)
    assertThat(launches()).isEqualTo(1)
    assertThat(AndroidLvaAttemptRecord.read()?.version).isEqualTo(1)
  }

  @Test
  fun `the previous pending screen's actions do nothing after Continue`() {
    launchRecord()
    val activity = returnAndShowActions()
    val oldContinue = activity.pendingButton(R.id.com_facebook_android_lva_pending_continue)
    val oldCancel = activity.pendingButton(R.id.com_facebook_android_lva_pending_cancel)
    oldContinue.performClick()
    activityController.pause().resume() // pending again, for version 2

    oldContinue.performClick()
    oldCancel.performClick()
    assertThat(activity.isFinishing).isFalse
    assertThat(launches()).isEqualTo(2)
    val record = checkNotNull(AndroidLvaAttemptRecord.read())
    assertThat(record.version).isEqualTo(2)
    assertThat(record.phase).isEqualTo(AndroidLvaAttemptRecord.Phase.PENDING)
  }

  @Test
  fun `a Continue whose browser launch fails cancels and unregisters the redirect receiver`() {
    launchRecord()
    doNothing()
        .doThrow(ActivityNotFoundException())
        .whenever(mockCustomTabsIntent)
        .launchUrl(any(), any())
    val activity = returnAndShowActions()
    val receivers = argumentCaptor<BroadcastReceiver>()
    verify(mockLocalBroadcastManager).registerReceiver(receivers.capture(), any())

    activity.pendingButton(R.id.com_facebook_android_lva_pending_continue).performClick()
    assertThat(activity.isFinishing).isTrue
    val shadow = Shadows.shadowOf(activity)
    assertThat(shadow.resultCode).isEqualTo(Activity.RESULT_CANCELED)
    assertThat(
            shadow.resultIntent.getBooleanExtra(CustomTabMainActivity.NO_ACTIVITY_EXCEPTION, false))
        .isTrue
    verify(mockLocalBroadcastManager).unregisterReceiver(receivers.firstValue)
  }

  /** Reads still see the stored record, but every write fails. */
  private fun failRecordWrites() {
    val stored =
        context
            .getSharedPreferences(AndroidLvaAttemptRecord.PREFS_STORE, Context.MODE_PRIVATE)
            .getString(AndroidLvaAttemptRecord.ACTIVE_ATTEMPT, null)
    val editor = mock<SharedPreferences.Editor>()
    whenever(editor.putString(any(), anyOrNull())).thenReturn(editor)
    whenever(editor.remove(any())).thenReturn(editor)
    whenever(editor.commit()).thenReturn(false)
    val prefs = mock<SharedPreferences>()
    whenever(prefs.getString(eq(AndroidLvaAttemptRecord.ACTIVE_ATTEMPT), anyOrNull()))
        .thenReturn(stored)
    whenever(prefs.edit()).thenReturn(editor)
    val failingContext = mock<Context>()
    whenever(failingContext.getSharedPreferences(any(), any())).thenReturn(prefs)
    whenever(FacebookSdk.getApplicationContext()).thenReturn(failingContext)
  }

  @Test
  fun `a return whose PENDING write fails cancels`() {
    launchRecord()
    failRecordWrites()
    val activity = returnWithoutResult()

    assertThat(activity.isFinishing).isTrue
    assertThat(Shadows.shadowOf(activity).resultCode).isEqualTo(Activity.RESULT_CANCELED)
  }

  @Test
  fun `a Continue whose record write fails cancels without opening another tab`() {
    launchRecord()
    val activity = returnAndShowActions()
    failRecordWrites()

    activity.pendingButton(R.id.com_facebook_android_lva_pending_continue).performClick()
    assertThat(activity.isFinishing).isTrue
    assertThat(Shadows.shadowOf(activity).resultCode).isEqualTo(Activity.RESULT_CANCELED)
    assertThat(launches()).isEqualTo(1)
  }

  @Test
  fun `the broadcast receiver ignores a marked callback without a live attempt`() {
    launchRecord()
    val receiver = registeredReceiverAfterCreate()
    val activity = activityController.get()

    receiver.onReceive(activity, markedCallback("{\"lva\":1,\"stale\":1}"))
    assertThat(Shadows.shadowOf(activity).nextStartedActivity).isNull()
    assertThat(activity.isFinishing).isFalse

    receiver.onReceive(activity, markedCallback(STATE))
    assertThat(Shadows.shadowOf(activity).nextStartedActivity.action)
        .isEqualTo(CustomTabMainActivity.REFRESH_ACTION)
  }

  @Test
  fun `a host for another attempt doesn't forward the live attempt's callback`() {
    val liveState = "{\"lva\":1,\"attempt\":\"B\"}"
    AndroidLvaAttemptRecord.launch(liveState, "logger2", "treatment")
    val receiver = registeredReceiverAfterCreate() // this host's state is STATE (attempt A)

    receiver.onReceive(activityController.get(), markedCallback(liveState))
    assertThat(Shadows.shadowOf(activityController.get()).nextStartedActivity).isNull()
    assertThat(activityController.get().isFinishing).isFalse
  }

  @Test
  fun `an ordinary host doesn't forward a marked callback`() {
    launchIntent.removeExtra(CustomTabMainActivity.EXTRA_ANDROID_LVA_STATE)
    launchRecord()
    val receiver = registeredReceiverAfterCreate()

    receiver.onReceive(activityController.get(), markedCallback(STATE))
    assertThat(Shadows.shadowOf(activityController.get()).nextStartedActivity).isNull()
  }

  @Test
  fun `Back after Continue does nothing`() {
    launchRecord()
    val activity = returnAndShowActions()

    activity.pendingButton(R.id.com_facebook_android_lva_pending_continue).performClick()
    @Suppress("DEPRECATION") activity.onBackPressed()
    assertThat(activity.isFinishing).isFalse
    assertThat(launches()).isEqualTo(2)
    val record = checkNotNull(AndroidLvaAttemptRecord.read())
    assertThat(record.version).isEqualTo(2)
    assertThat(record.phase).isEqualTo(AndroidLvaAttemptRecord.Phase.LAUNCHED)
  }
}
