/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.login

import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import com.facebook.FacebookPowerMockTestCase
import com.facebook.FacebookSdk
import com.facebook.appevents.InternalAppEventsLogger
import java.util.concurrent.ScheduledExecutorService
import org.assertj.core.api.Assertions.assertThat
import org.json.JSONObject
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.powermock.api.mockito.PowerMockito
import org.powermock.core.classloader.annotations.PrepareForTest
import org.powermock.reflect.Whitebox

@PrepareForTest(FacebookSdk::class)
class LoginLoggerTest : FacebookPowerMockTestCase() {
  private lateinit var mockInternalAppEventsLogger: InternalAppEventsLogger
  private lateinit var loginLogger: LoginLogger
  private lateinit var testLoginRequest: LoginClient.Request
  private lateinit var mockExecutor: ScheduledExecutorService

  override fun setup() {
    super.setup()
    PowerMockito.mockStatic(FacebookSdk::class.java)
    whenever(FacebookSdk.isInitialized()).thenReturn(true)
    whenever(FacebookSdk.getApplicationId()).thenReturn("123456789")
    whenever(FacebookSdk.getApplicationContext())
        .thenReturn(ApplicationProvider.getApplicationContext())
    mockInternalAppEventsLogger = mock()
    mockExecutor = mock()
    loginLogger = LoginLogger(ApplicationProvider.getApplicationContext(), "123456789")
    Whitebox.setInternalState(loginLogger, "logger", mockInternalAppEventsLogger)
    Whitebox.setInternalState(LoginLogger::class.java, "worker", mockExecutor)
    testLoginRequest =
        LoginClient.Request(
            LoginBehavior.NATIVE_WITH_FALLBACK,
            setOf("email"),
            DefaultAudience.EVERYONE,
            "rerequest",
            "123456789",
            "5678")
  }

  @Test
  fun `test logStartLogin logging information in login request`() {
    val bundleCaptor = argumentCaptor<Bundle>()

    loginLogger.logStartLogin(testLoginRequest)

    verify(mockInternalAppEventsLogger)
        .logEventImplicitly(eq(LoginLogger.EVENT_NAME_LOGIN_START), bundleCaptor.capture())
    val params = bundleCaptor.firstValue
    assertThat(params.getString(LoginLogger.EVENT_PARAM_AUTH_LOGGER_ID))
        .isEqualTo(testLoginRequest.authId)
    val extras = JSONObject(params.getString(LoginLogger.EVENT_PARAM_EXTRAS) ?: "")
    assertThat(extras.getString(LoginLogger.EVENT_EXTRAS_LOGIN_BEHAVIOR))
        .isEqualTo(testLoginRequest.loginBehavior.toString())
    assertThat(extras.getString(LoginLogger.EVENT_EXTRAS_PERMISSIONS)).isEqualTo("email")
    assertThat(extras.getString(LoginLogger.EVENT_EXTRAS_DEFAULT_AUDIENCE))
        .isEqualTo(testLoginRequest.defaultAudience.toString())
  }

  @Test
  fun `test logStartLogin with a custom event name logging information in login request`() {
    val bundleCaptor = argumentCaptor<Bundle>()

    loginLogger.logStartLogin(testLoginRequest, "custom_login_event")

    verify(mockInternalAppEventsLogger)
        .logEventImplicitly(eq("custom_login_event"), bundleCaptor.capture())
    val params = bundleCaptor.firstValue
    assertThat(params.getString(LoginLogger.EVENT_PARAM_AUTH_LOGGER_ID))
        .isEqualTo(testLoginRequest.authId)
    val extras = JSONObject(params.getString(LoginLogger.EVENT_PARAM_EXTRAS) ?: "")
    assertThat(extras.getString(LoginLogger.EVENT_EXTRAS_LOGIN_BEHAVIOR))
        .isEqualTo(testLoginRequest.loginBehavior.toString())
    assertThat(extras.getString(LoginLogger.EVENT_EXTRAS_PERMISSIONS)).isEqualTo("email")
    assertThat(extras.getString(LoginLogger.EVENT_EXTRAS_DEFAULT_AUDIENCE))
        .isEqualTo(testLoginRequest.defaultAudience.toString())
  }

  @Test
  fun `test logCompleteLogin with cancel result logging the result logging value`() {
    val bundleCaptor = argumentCaptor<Bundle>()

    loginLogger.logCompleteLogin(
        testLoginRequest.authId, mapOf(), LoginClient.Result.Code.CANCEL, null, null)

    verify(mockInternalAppEventsLogger)
        .logEventImplicitly(eq(LoginLogger.EVENT_NAME_LOGIN_COMPLETE), bundleCaptor.capture())
    val params = bundleCaptor.firstValue
    assertThat(params.getString(LoginLogger.EVENT_PARAM_AUTH_LOGGER_ID))
        .isEqualTo(testLoginRequest.authId)
    assertThat(params.getString(LoginLogger.EVENT_PARAM_LOGIN_RESULT))
        .isEqualTo(LoginClient.Result.Code.CANCEL.loggingValue)
  }

  @Test
  fun `test logCompleteLogin with exception logging the error message`() {
    val errorMessage = "Test login failure"
    val bundleCaptor = argumentCaptor<Bundle>()

    loginLogger.logCompleteLogin(
        testLoginRequest.authId,
        mapOf(),
        LoginClient.Result.Code.ERROR,
        null,
        Exception(errorMessage))

    verify(mockInternalAppEventsLogger)
        .logEventImplicitly(eq(LoginLogger.EVENT_NAME_LOGIN_COMPLETE), bundleCaptor.capture())
    val params = bundleCaptor.firstValue
    assertThat(params.getString(LoginLogger.EVENT_PARAM_AUTH_LOGGER_ID))
        .isEqualTo(testLoginRequest.authId)
    assertThat(params.getString(LoginLogger.EVENT_PARAM_LOGIN_RESULT))
        .isEqualTo(LoginClient.Result.Code.ERROR.loggingValue)
    assertThat(params.getString(LoginLogger.EVENT_PARAM_ERROR_MESSAGE)).isEqualTo(errorMessage)
  }

  @Test
  fun `test logCompleteLogin with success result logging the result logging value and starting heartbeat logging`() {
    val errorMessage = "Test login failure"
    val bundleCaptor = argumentCaptor<Bundle>()
    val jobCaptor = argumentCaptor<Runnable>()

    loginLogger.logCompleteLogin(
        testLoginRequest.authId,
        mapOf(),
        LoginClient.Result.Code.SUCCESS,
        null,
        Exception(errorMessage))

    verify(mockInternalAppEventsLogger)
        .logEventImplicitly(eq(LoginLogger.EVENT_NAME_LOGIN_COMPLETE), bundleCaptor.capture())
    val params = bundleCaptor.firstValue
    assertThat(params.getString(LoginLogger.EVENT_PARAM_AUTH_LOGGER_ID))
        .isEqualTo(testLoginRequest.authId)
    assertThat(params.getString(LoginLogger.EVENT_PARAM_LOGIN_RESULT))
        .isEqualTo(LoginClient.Result.Code.SUCCESS.loggingValue)
    verify(mockExecutor).schedule(jobCaptor.capture(), any(), any())
    val job = jobCaptor.firstValue
    job.run()
    verify(mockInternalAppEventsLogger)
        .logEventImplicitly(eq(LoginLogger.EVENT_NAME_LOGIN_HEARTBEAT), any<Bundle>())
  }

  @Test
  fun `test logCompleteLogin with custom event name`() {
    val bundleCaptor = argumentCaptor<Bundle>()

    loginLogger.logCompleteLogin(
        testLoginRequest.authId,
        mapOf(),
        LoginClient.Result.Code.CANCEL,
        null,
        null,
        "custom_event_name")

    verify(mockInternalAppEventsLogger)
        .logEventImplicitly(eq("custom_event_name"), bundleCaptor.capture())
    val params = bundleCaptor.firstValue
    assertThat(params.getString(LoginLogger.EVENT_PARAM_AUTH_LOGGER_ID))
        .isEqualTo(testLoginRequest.authId)
    assertThat(params.getString(LoginLogger.EVENT_PARAM_LOGIN_RESULT))
        .isEqualTo(LoginClient.Result.Code.CANCEL.loggingValue)
  }

  @Test
  fun `test logAuthorizationMethodStart logging the method`() {
    val bundleCaptor = argumentCaptor<Bundle>()

    loginLogger.logAuthorizationMethodStart(testLoginRequest.authId, "test_method")

    verify(mockInternalAppEventsLogger)
        .logEventImplicitly(eq(LoginLogger.EVENT_NAME_LOGIN_METHOD_START), bundleCaptor.capture())
    val params = bundleCaptor.firstValue
    assertThat(params.getString(LoginLogger.EVENT_PARAM_METHOD)).isEqualTo("test_method")
  }

  @Test
  fun `test logAuthorizationMethodStart with the custom event name logging the method and event name`() {
    val bundleCaptor = argumentCaptor<Bundle>()

    loginLogger.logAuthorizationMethodStart(
        testLoginRequest.authId, "test_method", "custom_event_name")

    verify(mockInternalAppEventsLogger)
        .logEventImplicitly(eq("custom_event_name"), bundleCaptor.capture())
    val params = bundleCaptor.firstValue
    assertThat(params.getString(LoginLogger.EVENT_PARAM_METHOD)).isEqualTo("test_method")
  }

  @Test
  fun `test logAuthorizationMethodStart logging extras`() {
    val bundleCaptor = argumentCaptor<Bundle>()

    loginLogger.logAuthorizationMethodStart(
        testLoginRequest.authId,
        "test_method",
        LoginLogger.EVENT_NAME_LOGIN_METHOD_START,
        mapOf("lva_client_arm" to "treatment"))

    verify(mockInternalAppEventsLogger)
        .logEventImplicitly(eq(LoginLogger.EVENT_NAME_LOGIN_METHOD_START), bundleCaptor.capture())
    val extras = JSONObject(bundleCaptor.firstValue.getString(LoginLogger.EVENT_PARAM_EXTRAS) ?: "")
    assertThat(extras.getString("lva_client_arm")).isEqualTo("treatment")
  }

  @Test
  fun `test logAuthorizationMethodStart without extras logs none`() {
    val bundleCaptor = argumentCaptor<Bundle>()

    loginLogger.logAuthorizationMethodStart(testLoginRequest.authId, "test_method")

    verify(mockInternalAppEventsLogger)
        .logEventImplicitly(eq(LoginLogger.EVENT_NAME_LOGIN_METHOD_START), bundleCaptor.capture())
    assertThat(bundleCaptor.firstValue.getString(LoginLogger.EVENT_PARAM_EXTRAS)).isEmpty()
  }

  @Test
  fun `test logAuthorizationMethodComplete logging the result, error message and error code `() {
    val result = "error"
    val errorMessage = "errorMessage"
    val errorCode = "500"
    val bundleCaptor = argumentCaptor<Bundle>()

    loginLogger.logAuthorizationMethodComplete(
        testLoginRequest.authId, "test_method", result, errorMessage, errorCode, null)

    verify(mockInternalAppEventsLogger)
        .logEventImplicitly(
            eq(LoginLogger.EVENT_NAME_LOGIN_METHOD_COMPLETE), bundleCaptor.capture())
    val params = bundleCaptor.firstValue
    assertThat(params.getString(LoginLogger.EVENT_PARAM_AUTH_LOGGER_ID))
        .isEqualTo(testLoginRequest.authId)
    assertThat(params.getString(LoginLogger.EVENT_PARAM_METHOD)).isEqualTo("test_method")
    assertThat(params.getString(LoginLogger.EVENT_PARAM_LOGIN_RESULT)).isEqualTo(result)
    assertThat(params.getString(LoginLogger.EVENT_PARAM_ERROR_MESSAGE)).isEqualTo(errorMessage)
    assertThat(params.getString(LoginLogger.EVENT_PARAM_ERROR_CODE)).isEqualTo(errorCode)
  }

  @Test
  fun `test logAuthorizationMethodComplete with custom event name logging the result, error message and error code `() {
    val result = "error"
    val errorMessage = "errorMessage"
    val errorCode = "500"
    val bundleCaptor = argumentCaptor<Bundle>()

    loginLogger.logAuthorizationMethodComplete(
        testLoginRequest.authId,
        "test_method",
        result,
        errorMessage,
        errorCode,
        null,
        "custom_event_name")

    verify(mockInternalAppEventsLogger)
        .logEventImplicitly(eq("custom_event_name"), bundleCaptor.capture())
    val params = bundleCaptor.firstValue
    assertThat(params.getString(LoginLogger.EVENT_PARAM_AUTH_LOGGER_ID))
        .isEqualTo(testLoginRequest.authId)
    assertThat(params.getString(LoginLogger.EVENT_PARAM_METHOD)).isEqualTo("test_method")
    assertThat(params.getString(LoginLogger.EVENT_PARAM_LOGIN_RESULT)).isEqualTo(result)
    assertThat(params.getString(LoginLogger.EVENT_PARAM_ERROR_MESSAGE)).isEqualTo(errorMessage)
    assertThat(params.getString(LoginLogger.EVENT_PARAM_ERROR_CODE)).isEqualTo(errorCode)
  }

  @Test
  fun `test logAuthorizationMethodNotTried logging the method`() {
    val bundleCaptor = argumentCaptor<Bundle>()

    loginLogger.logAuthorizationMethodNotTried(testLoginRequest.authId, "test_method")

    verify(mockInternalAppEventsLogger)
        .logEventImplicitly(
            eq(LoginLogger.EVENT_NAME_LOGIN_METHOD_NOT_TRIED), bundleCaptor.capture())
    val params = bundleCaptor.firstValue
    assertThat(params.getString(LoginLogger.EVENT_PARAM_METHOD)).isEqualTo("test_method")
  }

  @Test
  fun `test logAuthorizationMethodNotTried with the custom event name logging the method and event name`() {
    val bundleCaptor = argumentCaptor<Bundle>()

    loginLogger.logAuthorizationMethodNotTried(
        testLoginRequest.authId, "test_method", "custom_event_name")

    verify(mockInternalAppEventsLogger)
        .logEventImplicitly(eq("custom_event_name"), bundleCaptor.capture())
    val params = bundleCaptor.firstValue
    assertThat(params.getString(LoginLogger.EVENT_PARAM_METHOD)).isEqualTo("test_method")
  }

  @Test
  fun `test logLoginStatusStart logging the auth Id`() {
    val bundleCaptor = argumentCaptor<Bundle>()

    loginLogger.logLoginStatusStart(testLoginRequest.authId)

    verify(mockInternalAppEventsLogger)
        .logEventImplicitly(eq(LoginLogger.EVENT_NAME_LOGIN_STATUS_START), bundleCaptor.capture())
    val params = bundleCaptor.firstValue
    assertThat(params.getString(LoginLogger.EVENT_PARAM_AUTH_LOGGER_ID))
        .isEqualTo(testLoginRequest.authId)
  }

  @Test
  fun `test logLoginStatusSuccess logging the auth Id and result`() {
    val bundleCaptor = argumentCaptor<Bundle>()

    loginLogger.logLoginStatusSuccess(testLoginRequest.authId)

    verify(mockInternalAppEventsLogger)
        .logEventImplicitly(
            eq(LoginLogger.EVENT_NAME_LOGIN_STATUS_COMPLETE), bundleCaptor.capture())
    val params = bundleCaptor.firstValue
    assertThat(params.getString(LoginLogger.EVENT_PARAM_AUTH_LOGGER_ID))
        .isEqualTo(testLoginRequest.authId)
    assertThat(params.getString(LoginLogger.EVENT_PARAM_LOGIN_RESULT))
        .isEqualTo(LoginClient.Result.Code.SUCCESS.loggingValue)
  }

  @Test
  fun `test logLoginStatusFailure logging the auth Id and result`() {
    val bundleCaptor = argumentCaptor<Bundle>()

    loginLogger.logLoginStatusFailure(testLoginRequest.authId)

    verify(mockInternalAppEventsLogger)
        .logEventImplicitly(
            eq(LoginLogger.EVENT_NAME_LOGIN_STATUS_COMPLETE), bundleCaptor.capture())
    val params = bundleCaptor.firstValue
    assertThat(params.getString(LoginLogger.EVENT_PARAM_AUTH_LOGGER_ID))
        .isEqualTo(testLoginRequest.authId)
    assertThat(params.getString(LoginLogger.EVENT_PARAM_LOGIN_RESULT))
        .isEqualTo(LoginLogger.EVENT_EXTRAS_FAILURE)
  }

  @Test
  fun `test logLoginStatusError logging the auth Id, result and error message`() {
    val errorMessage = "error message"
    val error = Exception(errorMessage)
    val bundleCaptor = argumentCaptor<Bundle>()

    loginLogger.logLoginStatusError(testLoginRequest.authId, error)

    verify(mockInternalAppEventsLogger)
        .logEventImplicitly(
            eq(LoginLogger.EVENT_NAME_LOGIN_STATUS_COMPLETE), bundleCaptor.capture())
    val params = bundleCaptor.firstValue
    assertThat(params.getString(LoginLogger.EVENT_PARAM_AUTH_LOGGER_ID))
        .isEqualTo(testLoginRequest.authId)
    assertThat(params.getString(LoginLogger.EVENT_PARAM_LOGIN_RESULT))
        .isEqualTo(LoginClient.Result.Code.ERROR.loggingValue)
    assertThat(params.getString(LoginLogger.EVENT_PARAM_ERROR_MESSAGE)).isEqualTo(error.toString())
  }

  @Test
  fun `test logUnexpectedError logging the error result`() {
    val errorMessage = "error message"
    val eventName = "test_event"
    val bundleCaptor = argumentCaptor<Bundle>()

    loginLogger.logUnexpectedError(eventName, errorMessage)

    verify(mockInternalAppEventsLogger).logEventImplicitly(eq(eventName), bundleCaptor.capture())
    val params = bundleCaptor.firstValue
    assertThat(params.getString(LoginLogger.EVENT_PARAM_LOGIN_RESULT))
        .isEqualTo(LoginClient.Result.Code.ERROR.loggingValue)
    assertThat(params.getString(LoginLogger.EVENT_PARAM_ERROR_MESSAGE)).isEqualTo(errorMessage)
  }

  @Test
  fun `test logUnexpectedError with method logging the error result`() {
    val errorMessage = "error message"
    val eventName = "test_event"
    val method = "test_method"
    val bundleCaptor = argumentCaptor<Bundle>()

    loginLogger.logUnexpectedError(eventName, errorMessage, method)

    verify(mockInternalAppEventsLogger).logEventImplicitly(eq(eventName), bundleCaptor.capture())
    val params = bundleCaptor.firstValue
    assertThat(params.getString(LoginLogger.EVENT_PARAM_LOGIN_RESULT))
        .isEqualTo(LoginClient.Result.Code.ERROR.loggingValue)
    assertThat(params.getString(LoginLogger.EVENT_PARAM_ERROR_MESSAGE)).isEqualTo(errorMessage)
    assertThat(params.getString(LoginLogger.EVENT_PARAM_METHOD)).isEqualTo(method)
  }
}
