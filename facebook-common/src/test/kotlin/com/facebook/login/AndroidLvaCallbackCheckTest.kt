/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.login

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.facebook.FacebookPowerMockTestCase
import com.facebook.FacebookSdk
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.mockito.kotlin.whenever
import org.powermock.api.mockito.PowerMockito.mockStatic
import org.powermock.core.classloader.annotations.PrepareForTest

@PrepareForTest(FacebookSdk::class)
class AndroidLvaCallbackCheckTest : FacebookPowerMockTestCase() {
  companion object {
    const val NOW = 1_800_000_000_000L
    const val STATE = "{\"0_auth_logger_id\":\"logger1\",\"lva\":1}"
    val MINUTE = TimeUnit.MINUTES.toMillis(1)
  }

  private lateinit var context: Context

  override fun setup() {
    super.setup()
    context = ApplicationProvider.getApplicationContext()
    mockStatic(FacebookSdk::class.java)
    whenever(FacebookSdk.getApplicationContext()).thenReturn(context)
    context
        .getSharedPreferences(AndroidLvaAttemptRecord.PREFS_STORE, Context.MODE_PRIVATE)
        .edit()
        .clear()
        .commit()
  }

  private fun callback(state: String?, inQuery: Boolean = false): String {
    val parameters = "access_token=token1" + (state?.let { "&state=${Uri.encode(it)}" } ?: "")
    return if (inQuery) "fbconnect://cct.com.example.app?$parameters"
    else "fbconnect://cct.com.example.app#$parameters"
  }

  @Test
  fun `callbacks without the discriminator are unmarked`() {
    AndroidLvaAttemptRecord.launch(STATE, "logger1", "treatment", NOW)
    assertThat(AndroidLvaCallbackCheck.check(null, NOW))
        .isEqualTo(AndroidLvaCallbackCheck.Result.Unmarked)
    assertThat(AndroidLvaCallbackCheck.check(callback(null), NOW))
        .isEqualTo(AndroidLvaCallbackCheck.Result.Unmarked)
    assertThat(AndroidLvaCallbackCheck.check(callback("{\"0_auth_logger_id\":\"x\"}"), NOW))
        .isEqualTo(AndroidLvaCallbackCheck.Result.Unmarked)
    assertThat(AndroidLvaCallbackCheck.check(callback("not json"), NOW))
        .isEqualTo(AndroidLvaCallbackCheck.Result.Unmarked)
  }

  @Test
  fun `the live attempt's callback is accepted, from the fragment or the query`() {
    val record = checkNotNull(AndroidLvaAttemptRecord.launch(STATE, "logger1", "treatment", NOW))
    assertThat(AndroidLvaCallbackCheck.check(callback(STATE), NOW + MINUTE))
        .isEqualTo(AndroidLvaCallbackCheck.Result.Accepted(record))
    assertThat(AndroidLvaCallbackCheck.check(callback(STATE, inQuery = true), NOW + MINUTE))
        .isEqualTo(AndroidLvaCallbackCheck.Result.Accepted(record))
  }

  @Test
  fun `a marked callback without its live attempt is rejected`() {
    assertThat(AndroidLvaCallbackCheck.check(callback(STATE), NOW))
        .isEqualTo(AndroidLvaCallbackCheck.Result.Rejected)

    AndroidLvaAttemptRecord.launch("{\"lva\":1,\"other\":1}", "logger2", "treatment", NOW)
    assertThat(AndroidLvaCallbackCheck.check(callback(STATE), NOW))
        .isEqualTo(AndroidLvaCallbackCheck.Result.Rejected)
    assertThat(AndroidLvaAttemptRecord.read()).isNotNull
  }

  @Test
  fun `an expired attempt's callback is rejected and its record cleared`() {
    AndroidLvaAttemptRecord.launch(STATE, "logger1", "treatment", NOW)
    assertThat(AndroidLvaCallbackCheck.check(callback(STATE), NOW + 30 * MINUTE))
        .isEqualTo(AndroidLvaCallbackCheck.Result.Rejected)
    assertThat(AndroidLvaAttemptRecord.read()).isNull()
  }

  @Test
  fun `a state with characters that need encoding round-trips exactly`() {
    for (loggerId in listOf("logger+1", "a%25b", "x&y=z", "sp ace")) {
      val state = "{\"0_auth_logger_id\":\"$loggerId\",\"lva\":1}"
      val record =
          checkNotNull(AndroidLvaAttemptRecord.launch(state, loggerId, "treatment", NOW))
      assertThat(AndroidLvaCallbackCheck.check(callback(state), NOW))
          .isEqualTo(AndroidLvaCallbackCheck.Result.Accepted(record))
      assertThat(AndroidLvaCallbackCheck.check(callback(state, inQuery = true), NOW))
          .isEqualTo(AndroidLvaCallbackCheck.Result.Accepted(record))
    }
  }
}
