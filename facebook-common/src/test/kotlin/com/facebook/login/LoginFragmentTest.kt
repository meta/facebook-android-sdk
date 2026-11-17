/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.login

import android.app.Activity
import android.os.Bundle
import android.os.Parcel
import android.view.View
import androidx.fragment.app.FragmentActivity
import com.facebook.FacebookTestCase
import com.facebook.common.R
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController

class LoginFragmentTest : FacebookTestCase() {
  // test activity: it's the host for the login fragment during robolectric test
  open class TestActivity : FragmentActivity() {
    lateinit var fragment: LoginFragment
    override fun onCreate(savedInstanceState: Bundle?) {
      super.onCreate(savedInstanceState)
      fragment = LoginFragment()
      supportFragmentManager.beginTransaction().add(fragment, "FRAGMENT_TAG").commit()
    }
  }

  // Hosts the login fragment the way FacebookActivity does: retained, so a configuration change
  // recreates the activity without calling the fragment's onCreate again.
  class RetainedFragmentActivity : FragmentActivity() {
    lateinit var fragment: LoginFragment
    override fun onCreate(savedInstanceState: Bundle?) {
      super.onCreate(savedInstanceState)
      fragment =
          supportFragmentManager.findFragmentByTag("FRAGMENT_TAG") as LoginFragment?
              ?: LoginFragment().also {
                @Suppress("DEPRECATION")
                it.retainInstance = true
                supportFragmentManager.beginTransaction().add(it, "FRAGMENT_TAG").commit()
              }
    }
  }

  private lateinit var activityController: ActivityController<TestActivity>

  override fun setUp() {
    super.setUp()
    activityController = Robolectric.buildActivity(TestActivity::class.java)
  }

  @Test
  fun `test creating the fragment will create a login client`() {
    activityController.create()
    activityController.start()

    val fragment = activityController.get().fragment
    assertThat(fragment.loginClient).isNotNull
    assertThat(fragment.loginClient.onCompletedListener).isNotNull
  }

  @Test
  fun `test finishing the activity when the login client complete `() {
    val request =
        LoginClient.Request(
            LoginBehavior.NATIVE_WITH_FALLBACK,
            null,
            DefaultAudience.EVERYONE,
            "testAuthType",
            "123456789",
            UUID.randomUUID().toString(),
            LoginTargetApp.FACEBOOK)
    val result = LoginClient.Result(request, LoginClient.Result.Code.CANCEL, null, null, null)

    activityController.create()
    activityController.start()

    val fragment = activityController.get().fragment
    val loginClient = fragment.loginClient
    loginClient.complete(result)
    val shadowActivity = Shadows.shadowOf(activityController.get())
    assertThat(shadowActivity.resultCode).isEqualTo(Activity.RESULT_CANCELED)
    assertThat(activityController.get().isFinishing).isTrue()
  }

  @Test
  fun `recreation sets an Android SDK LVA launch's provisional result again`() {
    activityController.create()
    activityController.start()
    activityController.get().fragment.loginClient.pendingRequest = androidLvaRequest()

    recreateThroughParcel()
    assertProvisionalResult(activityController.get())
  }

  @Test
  fun `recreating the host of a retained fragment sets an Android SDK LVA launch's provisional result again`() {
    val controller =
        Robolectric.buildActivity(RetainedFragmentActivity::class.java).create().start()
    val fragment = controller.get().fragment
    fragment.loginClient.pendingRequest = androidLvaRequest()

    controller.recreate()
    assertThat(controller.get().fragment).isSameAs(fragment)
    assertProvisionalResult(controller.get())
  }

  private fun androidLvaRequest(): LoginClient.Request =
      LoginClient.Request(
              LoginBehavior.NATIVE_WITH_FALLBACK,
              null,
              DefaultAudience.EVERYONE,
              "rerequest",
              "123456789",
              UUID.randomUUID().toString(),
              LoginTargetApp.FACEBOOK)
          .apply {
            androidLvaLaunchState = "{\"lva\":1}"
            androidLvaLaunchNonce = "nonce1"
          }

  private fun assertProvisionalResult(activity: Activity) {
    val shadowActivity = Shadows.shadowOf(activity)
    assertThat(shadowActivity.resultCode).isEqualTo(Activity.RESULT_CANCELED)
    val result = checkNotNull(shadowActivity.resultIntent)
    assertThat(result.getStringExtra(AndroidLvaAttemptRecord.RESULT_EXTRA_STATE))
        .isEqualTo("{\"lva\":1}")
    assertThat(result.getStringExtra(AndroidLvaAttemptRecord.RESULT_EXTRA_LAUNCH_NONCE))
        .isEqualTo("nonce1")
  }

  @Test
  fun `recreation of an ordinary attempt sets no result`() {
    activityController.create()
    activityController.start()
    recreateThroughParcel()
    assertThat(Shadows.shadowOf(activityController.get()).resultIntent).isNull()
  }

  // Robolectric's recreate() hands back the saved Bundle without parceling it, so the restored
  // LoginClient would be the same instance, whose fragment is already set. Parcel the state the way
  // the system does when it recreates the activity.
  private fun recreateThroughParcel() {
    val outState = Bundle()
    activityController.saveInstanceState(outState)
    val parcel = Parcel.obtain()
    try {
      outState.writeToParcel(parcel, 0)
      parcel.setDataPosition(0)
      val restoredState = Bundle.CREATOR.createFromParcel(parcel)
      restoredState.classLoader = LoginClient::class.java.classLoader
      activityController =
          Robolectric.buildActivity(TestActivity::class.java).create(restoredState).start()
    } finally {
      parcel.recycle()
    }
  }

  @Test
  fun `test fragment display progress bar when login client processing `() {
    activityController.create()
    activityController.start()
    val fragment = activityController.get().fragment
    val loginClient = fragment.loginClient
    loginClient.notifyBackgroundProcessingStart()
    val progressBar =
        fragment.view?.findViewById<View>(R.id.com_facebook_login_fragment_progress_bar)
    assertThat(progressBar?.visibility).isEqualTo(View.VISIBLE)
  }
}
