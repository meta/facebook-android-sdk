/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.login

import androidx.fragment.app.Fragment
import androidx.test.core.app.ApplicationProvider
import com.facebook.AccessToken
import com.facebook.FacebookPowerMockTestCase
import com.facebook.FacebookSdk
import com.facebook.TestUtils
import com.facebook.login.LoginClient.Result.Companion.createTokenResult
import java.util.HashSet
import java.util.UUID
import java.util.concurrent.Executor
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.powermock.api.mockito.PowerMockito.mockStatic
import org.powermock.core.classloader.annotations.PrepareForTest

@PrepareForTest(FacebookSdk::class)
class LoginClientTest : FacebookPowerMockTestCase() {
  private val serialExecutor: Executor = FacebookSerialExecutor()

  var mockFragment: Fragment = mock()

  override fun setup() {
    super.setup()

    mockStatic(FacebookSdk::class.java)
    whenever(FacebookSdk.isInitialized()).thenReturn(true)
    whenever(FacebookSdk.getApplicationId()).thenReturn("123456789")
    whenever(FacebookSdk.getExecutor()).thenReturn(serialExecutor)
    whenever(FacebookSdk.getAutoLogAppEventsEnabled()).thenReturn(false)
    whenever(FacebookSdk.getApplicationContext())
        .thenReturn(ApplicationProvider.getApplicationContext())
  }

  @Test
  fun testReauthorizationWithSameFbidSucceeds() {
    val request = createRequest()
    val token =
        AccessToken(ACCESS_TOKEN, APP_ID, USER_ID, PERMISSIONS, null, null, null, null, null, null)
    var result = createTokenResult(request, token)
    val listener = mock<LoginClient.OnCompletedListener>()
    val client = LoginClient(mockFragment)
    client.onCompletedListener = listener
    client.completeAndValidate(result)
    val resultArgumentCaptor = argumentCaptor<LoginClient.Result>()
    verify(listener).onCompleted(resultArgumentCaptor.capture())
    result = resultArgumentCaptor.firstValue
    assertThat(result).isNotNull
    assertThat(LoginClient.Result.Code.SUCCESS).isEqualTo(result.code)
    val resultToken = result.token
    checkNotNull(resultToken)
    assertThat(ACCESS_TOKEN).isEqualTo(resultToken.token)

    // We don't care about ordering.
    assertThat(PERMISSIONS).isEqualTo(resultToken.permissions)
  }

  @Test
  fun testRequestParceling() {
    val request = createRequest()
    val unparceledRequest = checkNotNull(TestUtils.parcelAndUnparcel(request))
    assertThat(LoginBehavior.NATIVE_WITH_FALLBACK).isEqualTo(unparceledRequest.loginBehavior)
    assertThat(LoginTargetApp.FACEBOOK).isEqualTo(unparceledRequest.loginTargetApp)
    assertThat(HashSet(PERMISSIONS)).isEqualTo(unparceledRequest.permissions)
    assertThat(DefaultAudience.FRIENDS).isEqualTo(unparceledRequest.defaultAudience)
    assertThat("1234").isEqualTo(unparceledRequest.applicationId)
    assertThat("5678").isEqualTo(unparceledRequest.authId)
    assertThat(unparceledRequest.isRerequest).isFalse
    assertThat(unparceledRequest.isFamilyLogin).isFalse
    assertThat(unparceledRequest.shouldSkipAccountDeduplication()).isFalse
    assertThat(unparceledRequest.forceConfirmation).isFalse
    assertThat(unparceledRequest.androidSsoContext).isNull()
  }

  @Test
  fun testRequestParcelingWithAndroidSsoContext() {
    val request = createRequest()
    request.androidSsoContext = "fb4a_not_installed"
    val unparceledRequest = checkNotNull(TestUtils.parcelAndUnparcel(request))
    assertThat(unparceledRequest.androidSsoContext).isEqualTo("fb4a_not_installed")
  }

  @Test
  fun testRequestParcelingWithAndroidLvaClientArm() {
    val request = createRequest()
    request.androidLvaClientArm = "treatment"
    val unparceledRequest = checkNotNull(TestUtils.parcelAndUnparcel(request))
    assertThat(unparceledRequest.androidLvaClientArm).isEqualTo("treatment")
    assertThat(checkNotNull(TestUtils.parcelAndUnparcel(createRequest())).androidLvaClientArm)
        .isNull()
  }

  @Test
  fun testResultParceling() {
    val request =
        LoginClient.Request(
            LoginBehavior.WEB_ONLY,
            null,
            DefaultAudience.EVERYONE,
            AUTH_TYPE,
            FacebookSdk.getApplicationId(),
            AUTH_ID,
            LoginTargetApp.FACEBOOK)
    request.isRerequest = true
    request.resetMessengerState = false
    request.messengerPageId = "1928"
    request.isFamilyLogin = true
    request.setShouldSkipAccountDeduplication(true)
    request.forceConfirmation = true
    val token1 =
        AccessToken(
            "Token2",
            FacebookSdk.getApplicationId(),
            "1000",
            null,
            null,
            null,
            null,
            null,
            null,
            null)
    val result =
        LoginClient.Result(request, LoginClient.Result.Code.SUCCESS, token1, "error 1", "123")
    val unparceledResult = checkNotNull(TestUtils.parcelAndUnparcel(result))
    val unparceledRequest = checkNotNull(unparceledResult.request)
    assertThat(LoginBehavior.WEB_ONLY).isEqualTo(unparceledRequest.loginBehavior)
    assertThat(LoginTargetApp.FACEBOOK).isEqualTo(unparceledRequest.loginTargetApp)
    assertThat(HashSet<String>()).isEqualTo(unparceledRequest.permissions)
    assertThat(DefaultAudience.EVERYONE).isEqualTo(unparceledRequest.defaultAudience)
    assertThat(FacebookSdk.getApplicationId()).isEqualTo(unparceledRequest.applicationId)
    assertThat(AUTH_ID).isEqualTo(unparceledRequest.authId)
    assertThat(unparceledRequest.isRerequest).isTrue
    assertThat(unparceledRequest.isFamilyLogin).isTrue
    assertThat(unparceledRequest.shouldSkipAccountDeduplication()).isTrue
    assertThat(unparceledRequest.forceConfirmation).isTrue
    assertThat(LoginClient.Result.Code.SUCCESS).isEqualTo(unparceledResult.code)
    assertThat(token1).isEqualTo(unparceledResult.token)
    assertThat("error 1").isEqualTo(unparceledResult.errorMessage)
    assertThat("123").isEqualTo(unparceledResult.errorCode)
    assertThat("1928").isEqualTo(unparceledRequest.messengerPageId)
    assertThat(false).isEqualTo(unparceledRequest.resetMessengerState)
  }

  @Test
  fun testGetHandlersForFBSSOLogin() {
    val request =
        LoginClient.Request(
            LoginBehavior.NATIVE_WITH_FALLBACK,
            null,
            DefaultAudience.EVERYONE,
            "test auth",
            FacebookSdk.getApplicationId(),
            "test auth id",
            LoginTargetApp.FACEBOOK)
    val client = LoginClient(mockFragment)
    val handlers = client.getHandlersToTry(request)
    checkNotNull(handlers)
    assertThat(handlers.size).isEqualTo(4)
    assertThat(handlers[0]).isInstanceOf(GetTokenLoginMethodHandler::class.java)
    assertThat(handlers[1]).isInstanceOf(KatanaProxyLoginMethodHandler::class.java)
    assertThat(handlers[2]).isInstanceOf(CustomTabLoginMethodHandler::class.java)
    assertThat(handlers[3]).isInstanceOf(WebViewLoginMethodHandler::class.java)
  }

  @Test
  fun testGetHandlersForFBWebLoginOnly() {
    val request =
        LoginClient.Request(
            LoginBehavior.WEB_ONLY,
            null,
            DefaultAudience.EVERYONE,
            AUTH_TYPE,
            FacebookSdk.getApplicationId(),
            AUTH_ID,
            LoginTargetApp.FACEBOOK)
    val client = LoginClient(mockFragment)
    val handlers = client.getHandlersToTry(request)
    checkNotNull(handlers)
    assertThat(handlers.size).isEqualTo(2)
    assertThat(handlers[0]).isInstanceOf(CustomTabLoginMethodHandler::class.java)
    assertThat(handlers[1]).isInstanceOf(WebViewLoginMethodHandler::class.java)
  }

  @Test
  fun testGetHandlersForIGSSOLogin() {
    val request =
        LoginClient.Request(
            LoginBehavior.NATIVE_WITH_FALLBACK,
            null,
            DefaultAudience.EVERYONE,
            "test auth",
            FacebookSdk.getApplicationId(),
            "test auth id",
            LoginTargetApp.INSTAGRAM)
    val client = LoginClient(mockFragment)
    val handlers = client.getHandlersToTry(request)
    checkNotNull(handlers)
    assertThat(handlers.size).isEqualTo(3)
    assertThat(handlers[0]).isInstanceOf(InstagramAppLoginMethodHandler::class.java)
    assertThat(handlers[1]).isInstanceOf(CustomTabLoginMethodHandler::class.java)
    assertThat(handlers[2]).isInstanceOf(WebViewLoginMethodHandler::class.java)
  }

  @Test
  fun testGetHandlersForIGWebLoginOnly() {
    val request =
        LoginClient.Request(
            LoginBehavior.WEB_ONLY,
            null,
            DefaultAudience.EVERYONE,
            AUTH_TYPE,
            FacebookSdk.getApplicationId(),
            AUTH_ID,
            LoginTargetApp.INSTAGRAM)
    val client = LoginClient(mockFragment)
    val handlers = client.getHandlersToTry(request)
    checkNotNull(handlers)
    assertThat(handlers.size).isEqualTo(2)
    assertThat(handlers[0]).isInstanceOf(CustomTabLoginMethodHandler::class.java)
    assertThat(handlers[1]).isInstanceOf(WebViewLoginMethodHandler::class.java)
  }

  private fun createRequest(): LoginClient.Request {
    return LoginClient.Request(
        LoginBehavior.NATIVE_WITH_FALLBACK,
        HashSet(PERMISSIONS),
        DefaultAudience.FRIENDS,
        "rerequest",
        "1234",
        "5678",
        LoginTargetApp.FACEBOOK)
  }

  companion object {
    private const val ACCESS_TOKEN = "An access token for user 1"
    private const val USER_ID = "1001"
    private const val APP_ID = "2002"
    private const val AUTH_TYPE = "test auth type"
    private val AUTH_ID = UUID.randomUUID().toString()
    private val PERMISSIONS = HashSet(listOf("go outside", "come back in"))
  }
}
