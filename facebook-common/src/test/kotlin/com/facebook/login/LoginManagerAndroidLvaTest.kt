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
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.facebook.FacebookCallback
import com.facebook.FacebookPowerMockTestCase
import com.facebook.FacebookSdk
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.powermock.api.mockito.PowerMockito.mockStatic
import org.powermock.core.classloader.annotations.PrepareForTest
import org.powermock.reflect.Whitebox

/** LoginManager's mapping of results that identify an Android SDK LVA launch. */
@PrepareForTest(FacebookSdk::class)
class LoginManagerAndroidLvaTest : FacebookPowerMockTestCase() {
  companion object {
    const val STATE = "{\"0_auth_logger_id\":\"logger1\",\"lva\":1}"
  }

  private lateinit var context: Context
  private lateinit var mockLogger: LoginLogger
  private lateinit var mockCallback: FacebookCallback<LoginResult>

  @Before
  fun init() {
    context = ApplicationProvider.getApplicationContext()
    mockLogger = mock()
    mockCallback = mock()
    mockStatic(FacebookSdk::class.java)
    whenever(FacebookSdk.isInitialized()).thenReturn(true)
    whenever(FacebookSdk.getApplicationContext()).thenReturn(context)
    whenever(FacebookSdk.getApplicationId()).thenReturn("123")
    context
        .getSharedPreferences("com.facebook.login.AndroidLvaAttemptRecord", Context.MODE_PRIVATE)
        .edit()
        .clear()
        .commit()
    Whitebox.setInternalState(
        Class.forName("com.facebook.login.LoginManager\$LoginLoggerHolder"), "logger", mockLogger)
  }

  @After
  fun tearDown() {
    Whitebox.setInternalState(
        Class.forName("com.facebook.login.LoginManager\$LoginLoggerHolder"),
        "logger",
        null as LoginLogger?)
  }

  private fun launch(state: String = STATE, loggerId: String = "logger1") =
      checkNotNull(AndroidLvaAttemptRecord.launch(state, loggerId, AndroidLvaClientArm.TREATMENT))

  private fun resultFor(record: AndroidLvaAttemptRecord.Record) =
      Intent()
          .putExtra(AndroidLvaAttemptRecord.RESULT_EXTRA_STATE, record.state)
          .putExtra(AndroidLvaAttemptRecord.RESULT_EXTRA_LAUNCH_NONCE, record.launchNonce)

  @Test
  fun `a cancellation that identifies its launch is a cancel and clears that attempt`() {
    val record = launch()
    LoginManager().onActivityResult(Activity.RESULT_CANCELED, resultFor(record), mockCallback)

    verify(mockCallback).onCancel()
    verify(mockCallback, never()).onError(any())
    assertThat(AndroidLvaAttemptRecord.read()).isNull()
    verify(mockLogger)
        .logCompleteLogin(
            eq("logger1"),
            any(),
            eq(LoginClient.Result.Code.CANCEL),
            eq(mapOf(AndroidLvaClientArm.LOGGING_EXTRA to AndroidLvaClientArm.TREATMENT)),
            isNull(),
            anyOrNull())
  }

  @Test
  fun `a cancellation without the launch extras keeps today's mapping and the record`() {
    launch()
    LoginManager().onActivityResult(Activity.RESULT_CANCELED, Intent(), mockCallback)

    verify(mockCallback).onError(any())
    verify(mockCallback, never()).onCancel()
    assertThat(AndroidLvaAttemptRecord.read()).isNotNull
  }

  @Test
  fun `a cancellation with another launch nonce keeps today's mapping`() {
    val record = launch()
    val data =
        resultFor(record).putExtra(AndroidLvaAttemptRecord.RESULT_EXTRA_LAUNCH_NONCE, "other")
    LoginManager().onActivityResult(Activity.RESULT_CANCELED, data, mockCallback)

    verify(mockCallback).onError(any())
    assertThat(AndroidLvaAttemptRecord.read()).isEqualTo(record)
  }

  @Test
  fun `an older launch's cancellation can't end a newer launch, even with the same logger ID`() {
    val older = launch(state = STATE)
    val newer = launch(state = "{\"0_auth_logger_id\":\"logger1\",\"lva\":1,\"n\":2}")
    LoginManager().onActivityResult(Activity.RESULT_CANCELED, resultFor(older), mockCallback)

    verify(mockCallback).onError(any())
    assertThat(AndroidLvaAttemptRecord.read()).isEqualTo(newer)
  }

  @Test
  fun `a malformed RESULT_OK stays an error, even with the launch extras`() {
    val record = launch()
    LoginManager().onActivityResult(Activity.RESULT_OK, resultFor(record), mockCallback)

    verify(mockCallback).onError(any())
    verify(mockCallback, never()).onCancel()
    assertThat(AndroidLvaAttemptRecord.read()).isEqualTo(record)
  }
}
