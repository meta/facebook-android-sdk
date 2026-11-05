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
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.test.core.app.ApplicationProvider
import com.facebook.AccessToken
import com.facebook.AccessTokenSource
import com.facebook.AuthenticationToken
import com.facebook.CustomTabMainActivity
import com.facebook.FacebookActivity
import com.facebook.FacebookException
import com.facebook.FacebookOperationCanceledException
import com.facebook.FacebookSdk
import com.facebook.internal.AndroidLvaGate
import com.facebook.internal.Validate
import com.facebook.internal.security.OidcSecurityUtil
import com.facebook.login.AuthenticationTokenTestUtil.getEncodedAuthTokenStringForTest
import org.assertj.core.api.Assertions.assertThat
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.powermock.api.mockito.PowerMockito
import org.powermock.core.classloader.annotations.PrepareForTest
import org.powermock.reflect.Whitebox
import java.util.Date

@PrepareForTest(Validate::class, FacebookSdk::class, OidcSecurityUtil::class)
class CustomTabLoginMethodHandlerTest : LoginHandlerTestCase() {
    private lateinit var request: LoginClient.Request
    private lateinit var mockAccessTokenCompanion: AccessToken.Companion

    @Before
    fun setUp() {
        PowerMockito.mockStatic(FacebookSdk::class.java)
        whenever(FacebookSdk.isInitialized()).thenReturn(true)
        whenever(FacebookSdk.getApplicationId()).thenReturn(AuthenticationTokenTestUtil.APP_ID)

        mockAccessTokenCompanion = mock()
        whenever(mockAccessTokenCompanion.getCurrentAccessToken()).thenReturn(null)
        Whitebox.setInternalState(AccessToken::class.java, "Companion", mockAccessTokenCompanion)

        val fragment: Fragment = mock<LoginFragment>()
        whenever(mockLoginClient.fragment).thenReturn(fragment)
        request = createRequest()

        // mock and bypass signature verification
        PowerMockito.mockStatic(OidcSecurityUtil::class.java)
        whenever(OidcSecurityUtil.getRawKeyFromEndPoint(any())).thenReturn("key")
        whenever(OidcSecurityUtil.getPublicKeyFromString(any())).thenReturn(mock())
        whenever(OidcSecurityUtil.verify(any(), any(), any())).thenReturn(true)
    }

    @Test
    fun testCustomTabHandlesSuccess() {
        testCustomTabHandles(encodedAuthTokenString)
    }

    @Test
    fun testCustomTabHandlesSuccessWithEmptyAuthenticationToken() {
        val result = testCustomTabHandles("")
        val authenticationToken = result.authenticationToken
        assertThat(authenticationToken).isNull()
    }

    @Test
    fun testCustomTabHandlesSuccessWithNoAuthenticationToken() {
        val result = testCustomTabHandles(null)
        val authenticationToken = result.authenticationToken
        assertThat(authenticationToken).isNull()
    }

    @Test
    fun testIdTokenWithNonceCustomTabHandlesSuccess() {
        mockCustomTabRedirectActivity(true)
        val requestWithNonce = createRequestWithNonce()
        val handler = CustomTabLoginMethodHandler(mockLoginClient)
        val expectedIdTokenString = getEncodedAuthTokenStringForTest()
        val bundle = Bundle()
        bundle.putString("access_token", ACCESS_TOKEN)
        bundle.putString(AuthenticationToken.AUTHENTICATION_TOKEN_KEY, expectedIdTokenString)
        bundle.putString("expires_in", String.format("%d", EXPIRES_IN_DELTA))
        bundle.putString("signed_request", SIGNED_REQUEST_STR)
        handler.onComplete(requestWithNonce, bundle, null)
        val resultArgumentCaptor = argumentCaptor<LoginClient.Result>()
        verify(mockLoginClient).completeAndValidate(resultArgumentCaptor.capture())
        val result = checkNotNull(resultArgumentCaptor.firstValue)
        assertThat(result.code).isEqualTo(LoginClient.Result.Code.SUCCESS)
        val idToken = checkNotNull(result.authenticationToken)
        assertThat(idToken.token).isEqualTo(expectedIdTokenString)
        val token = checkNotNull(result.token)
        assertThat(token.token).isEqualTo(ACCESS_TOKEN)
        assertDateDiffersWithinDelta(Date(), token.expires, EXPIRES_IN_DELTA * 1000, 1000)
        assertThat(PERMISSIONS).isEqualTo(token.permissions)
    }

    @Test
    fun testIGCustomTabHandlesSuccess() {
        mockCustomTabRedirectActivity(true)
        val igRequest = createIGWebRequest()
        val handler = CustomTabLoginMethodHandler(mockLoginClient)
        val bundle = Bundle()
        bundle.putString("access_token", ACCESS_TOKEN)
        bundle.putString("graph_domain", "instagram")
        bundle.putString("signed_request", SIGNED_REQUEST_STR)
        handler.onComplete(igRequest, bundle, null)
        val resultArgumentCaptor = argumentCaptor<LoginClient.Result>()
        verify(mockLoginClient).completeAndValidate(resultArgumentCaptor.capture())
        val result = resultArgumentCaptor.firstValue
        assertThat(result).isNotNull
        assertThat(result.code).isEqualTo(LoginClient.Result.Code.SUCCESS)
        val token = checkNotNull(result.token)
        assertThat(token.token).isEqualTo(ACCESS_TOKEN)
        assertThat(token.userId).isEqualTo(USER_ID)
        assertThat(token.graphDomain).isEqualTo("instagram")
        assertThat(token.source).isEqualTo(AccessTokenSource.INSTAGRAM_CUSTOM_CHROME_TAB)
        assertThat(PERMISSIONS).isEqualTo(token.permissions)
    }

    @Test
    fun testCustomTabHandlesCancel() {
        mockCustomTabRedirectActivity(true)
        val handler = CustomTabLoginMethodHandler(mockLoginClient)
        handler.onComplete(request, null, FacebookOperationCanceledException())
        val resultArgumentCaptor = argumentCaptor<LoginClient.Result>()
        verify(mockLoginClient).completeAndValidate(resultArgumentCaptor.capture())
        val result = checkNotNull(resultArgumentCaptor.firstValue)
        assertThat(result.code).isEqualTo(LoginClient.Result.Code.CANCEL)
        assertThat(result.token).isNull()
        assertThat(result.errorMessage).isNotNull
    }

    @Test
    fun testCustomTabHandlesError() {
        mockCustomTabRedirectActivity(true)
        val handler = CustomTabLoginMethodHandler(mockLoginClient)
        handler.onComplete(request, null, FacebookException(ERROR_MESSAGE))
        val resultArgumentCaptor = argumentCaptor<LoginClient.Result>()
        verify(mockLoginClient).completeAndValidate(resultArgumentCaptor.capture())
        val result = checkNotNull(resultArgumentCaptor.firstValue)
        assertThat(result.code).isEqualTo(LoginClient.Result.Code.ERROR)
        assertThat(result.token).isNull()
        assertThat(result.errorMessage).isNotNull
        assertThat(result.errorMessage).isEqualTo(ERROR_MESSAGE)
    }

    @Test
    fun testTryAuthorizeNeedsRedirectActivity() {
        mockChromeCustomTabsSupported(true, CHROME_PACKAGE)
        mockCustomTabRedirectActivity(true)
        val handler = CustomTabLoginMethodHandler(mockLoginClient)
        assertThat(handler.tryAuthorize(request)).isEqualTo(1)
    }

    @Test
    fun testTryAuthorizeWithChromePackage() {
        mockCustomTabRedirectActivity(true)
        mockChromeCustomTabsSupported(true, CHROME_PACKAGE)
        val handler = CustomTabLoginMethodHandler(mockLoginClient)
        assertThat(handler.tryAuthorize(request)).isEqualTo(1)
    }

    @Test
    fun testTryAuthorizeWithChromeBetaPackage() {
        mockCustomTabRedirectActivity(true)
        mockChromeCustomTabsSupported(true, BETA_PACKAGE)
        val handler = CustomTabLoginMethodHandler(mockLoginClient)
        assertThat(handler.tryAuthorize(request)).isEqualTo(1)
    }

    @Test
    fun testTryAuthorizeWithChromeDevPackage() {
        mockCustomTabRedirectActivity(true)
        mockChromeCustomTabsSupported(true, DEV_PACKAGE)
        val handler = CustomTabLoginMethodHandler(mockLoginClient)
        assertThat(handler.tryAuthorize(request)).isEqualTo(1)
    }

    /**
     * mockChromeCustomTabsSupported re-mocks FacebookSdk with a mock activity as the context, so
     * this restores the app ID and gives the activity real SharedPreferences, then records [gate].
     */
    private fun setUpAndroidLvaGate(gateKeepers: String) {
        mockCustomTabRedirectActivity(true)
        mockChromeCustomTabsSupported(true, CHROME_PACKAGE)
        whenever(FacebookSdk.getApplicationId()).thenReturn(request.applicationId)
        val context: Context = ApplicationProvider.getApplicationContext()
        whenever(activity.getSharedPreferences(any(), any())).thenAnswer {
            context.getSharedPreferences(it.getArgument(0), it.getArgument(1))
        }
        AndroidLvaGate.clearInMemoryForTesting()
        AndroidLvaGate.onGateKeepersFetched(
            request.applicationId,
            JSONObject("{\"data\":[{\"gatekeepers\":[$gateKeepers]}]}"),
            false)
    }

    private val gateOnFullTreatment =
        "{\"key\":\"${AndroidLvaGate.GATE_KEY}\",\"value\":true}," +
            "{\"key\":\"${AndroidLvaGate.FULL_TREATMENT_KEY}\",\"value\":true}"

    @Test
    fun testTryAuthorizeWithGateOffAssignsNoArm() {
        setUpAndroidLvaGate("")
        val handler = CustomTabLoginMethodHandler(mockLoginClient)
        assertThat(handler.tryAuthorize(request)).isEqualTo(1)
        assertThat(request.androidLvaClientArm).isNull()
        assertThat(handler.methodStartLoggingExtras).isNull()
        assertThat(handler.methodLoggingExtras?.get(AndroidLvaClientArm.LOGGING_EXTRA)).isNull()
    }

    @Test
    fun testTryAuthorizeWithGateOnAssignsAndLogsTheArm() {
        setUpAndroidLvaGate(gateOnFullTreatment)
        val handler = CustomTabLoginMethodHandler(mockLoginClient)
        assertThat(handler.tryAuthorize(request)).isEqualTo(1)
        assertThat(request.androidLvaClientArm).isEqualTo(AndroidLvaClientArm.TREATMENT)
        assertThat(handler.methodStartLoggingExtras)
            .isEqualTo(mapOf(AndroidLvaClientArm.LOGGING_EXTRA to AndroidLvaClientArm.TREATMENT))
        assertThat(handler.methodLoggingExtras?.get(AndroidLvaClientArm.LOGGING_EXTRA))
            .isEqualTo(AndroidLvaClientArm.TREATMENT)
    }

    @Test
    fun testTryAuthorizeDropsAStaleArmWhenTheGateIsOff() {
        setUpAndroidLvaGate("")
        val handler = CustomTabLoginMethodHandler(mockLoginClient)
        handler.methodLoggingExtras =
            mutableMapOf(AndroidLvaClientArm.LOGGING_EXTRA to AndroidLvaClientArm.TREATMENT)
        request.androidLvaClientArm = AndroidLvaClientArm.TREATMENT
        handler.tryAuthorize(request)
        assertThat(request.androidLvaClientArm).isNull()
        assertThat(handler.methodLoggingExtras?.get(AndroidLvaClientArm.LOGGING_EXTRA)).isNull()
    }

    @Test
    fun testTryAuthorizeForAnotherAppAssignsNoArm() {
        setUpAndroidLvaGate(gateOnFullTreatment)
        whenever(FacebookSdk.getApplicationId()).thenReturn("another_app")
        val handler = CustomTabLoginMethodHandler(mockLoginClient)
        handler.tryAuthorize(request)
        assertThat(request.androidLvaClientArm).isNull()
    }

    private fun mockChromeCustomTabsSupported(supported: Boolean, packageName: String) {
        val resolveInfos: MutableList<ResolveInfo> = ArrayList()
        val resolveInfo = ResolveInfo()
        val serviceInfo = ServiceInfo()
        serviceInfo.packageName = packageName
        resolveInfo.serviceInfo = serviceInfo
        if (supported) {
            resolveInfos.add(resolveInfo)
        }
        val packageManager = mock<PackageManager>()
        whenever(packageManager.queryIntentServices(any<Intent>(), any<Int>())).thenReturn(
            resolveInfos
        )
        activity = mock<FacebookActivity>()
        whenever(mockLoginClient.activity).thenReturn(activity)
        whenever(activity.packageManager).thenReturn(packageManager)
        PowerMockito.mockStatic(FacebookSdk::class.java)
        whenever(FacebookSdk.getApplicationContext()).thenReturn(activity)
    }

    private fun mockCustomTabRedirectActivity(hasActivity: Boolean) {
        PowerMockito.mockStatic(Validate::class.java)
        whenever(Validate.hasCustomTabRedirectActivity(anyOrNull(), anyOrNull()))
            .thenReturn(hasActivity)
    }

    private fun testCustomTabHandles(authenticationTokenString: String?): LoginClient.Result {
        val requestWithNonce = createRequestWithNonce()
        mockCustomTabRedirectActivity(true)
        val handler = CustomTabLoginMethodHandler(mockLoginClient)
        val bundle = Bundle()
        bundle.putString("access_token", ACCESS_TOKEN)
        bundle.putString(AuthenticationToken.AUTHENTICATION_TOKEN_KEY, authenticationTokenString)
        bundle.putString("expires_in", String.format("%d", EXPIRES_IN_DELTA))
        bundle.putString("code", "Something else")
        bundle.putString("signed_request", SIGNED_REQUEST_STR)
        handler.onComplete(requestWithNonce, bundle, null)
        val resultArgumentCaptor = argumentCaptor<LoginClient.Result>()
        verify(mockLoginClient).completeAndValidate(resultArgumentCaptor.capture())
        val result = checkNotNull(resultArgumentCaptor.firstValue)
        assertThat(result.code).isEqualTo(LoginClient.Result.Code.SUCCESS)
        val token = checkNotNull(result.token)
        assertThat(token.token).isEqualTo(ACCESS_TOKEN)
        assertDateDiffersWithinDelta(Date(), token.expires, EXPIRES_IN_DELTA * 1000, 1000)
        assertThat(PERMISSIONS).isEqualTo(token.permissions)
        return result
    }

    @Test
    fun `test receiving no browser exception as activity result`() {
        mockCustomTabRedirectActivity(true)
        val data = Intent()
        data.putExtra(CustomTabMainActivity.NO_ACTIVITY_EXCEPTION, true)
        val handler = CustomTabLoginMethodHandler(mockLoginClient)
        assertThat(handler.onActivityResult(1, Activity.RESULT_CANCELED, data)).isFalse
    }

    @Test
    fun `test receiving user cancel result`() {
        mockCustomTabRedirectActivity(true)
        whenever(mockLoginClient.pendingRequest).thenReturn(request)
        val handler = CustomTabLoginMethodHandler(mockLoginClient)
        assertThat(handler.onActivityResult(1, Activity.RESULT_CANCELED, null)).isFalse
        val resultCaptor = argumentCaptor<LoginClient.Result>()
        verify(mockLoginClient).completeAndValidate(resultCaptor.capture())
        val result = resultCaptor.firstValue
        assertThat(result.code).isEqualTo(LoginClient.Result.Code.CANCEL)
        assertThat(result.errorMessage).isEqualTo(LoginMethodHandler.USER_CANCELED_LOG_IN_ERROR_MESSAGE)
    }

    @Test
    fun `test receiving user ok result`() {
        mockCustomTabRedirectActivity(true)
        whenever(mockLoginClient.pendingRequest).thenReturn(request)
        val handler = CustomTabLoginMethodHandler(mockLoginClient)
        assertThat(handler.onActivityResult(1, Activity.RESULT_OK, null)).isTrue
    }

    @Test
    fun testAddExtraParametersWithRedirectURI() {
        mockCustomTabRedirectActivity(true)
        val handler = CustomTabLoginMethodHandler(mockLoginClient)
        val testRedirectURI = "https://example.com/redirect"

        // Create a request with redirect URI
        val requestWithRedirectURI = createRequestWithRedirectURI(testRedirectURI)

        // Create initial parameters bundle
        val initialParameters = Bundle()
        initialParameters.putString("existing_param", "existing_value")

        // Call addExtraParameters using reflection to access protected method
        val updatedParameters = Whitebox.invokeMethod<Bundle>(
            handler,
            "addExtraParameters",
            initialParameters,
            requestWithRedirectURI
        )

        // Verify that redirect_uri parameter is overridden with custom URI
        assertThat(updatedParameters.getString("redirect_uri")).isEqualTo(testRedirectURI)
        // Verify https_redirect_uri parameter is NOT included (old behavior)
        assertThat(updatedParameters.containsKey("https_redirect_uri")).isFalse()
        // Verify existing parameters are preserved
        assertThat(updatedParameters.getString("existing_param")).isEqualTo("existing_value")
    }

    @Test
    fun testAddExtraParametersWithoutRedirectURI() {
        mockCustomTabRedirectActivity(true)
        val handler = CustomTabLoginMethodHandler(mockLoginClient)

        // Create a request without redirect URI (passing null as the redirectURI parameter)
        val requestWithoutRedirectURI = createRequestWithRedirectURI(null)

        // Create initial parameters bundle
        val initialParameters = Bundle()
        initialParameters.putString("existing_param", "existing_value")

        // Call addExtraParameters using reflection to access protected method
        val updatedParameters = Whitebox.invokeMethod<Bundle>(
            handler,
            "addExtraParameters",
            initialParameters,
            requestWithoutRedirectURI
        )

        // Verify that redirect_uri uses the default value when no custom URI provided
        // The default should be the handler's getRedirectUrl() which is typically fb{appid}://authorize/
        assertThat(updatedParameters.getString("redirect_uri")).isNotNull()
        assertThat(updatedParameters.getString("redirect_uri")).isNotEqualTo("")
        // Verify https_redirect_uri parameter is NOT included (old behavior)
        assertThat(updatedParameters.containsKey("https_redirect_uri")).isFalse()
        // Verify existing parameters are preserved
        assertThat(updatedParameters.getString("existing_param")).isEqualTo("existing_value")
    }

    @Test
    fun testAddExtraParametersWithEmptyRedirectURI() {
        mockCustomTabRedirectActivity(true)
        val handler = CustomTabLoginMethodHandler(mockLoginClient)

        // Create a request with empty redirect URI
        val requestWithEmptyRedirectURI = createRequestWithRedirectURI("")

        // Create initial parameters bundle
        val initialParameters = Bundle()
        initialParameters.putString("existing_param", "existing_value")

        // Call addExtraParameters using reflection to access protected method
        val updatedParameters = Whitebox.invokeMethod<Bundle>(
            handler,
            "addExtraParameters",
            initialParameters,
            requestWithEmptyRedirectURI
        )

        // Verify that redirect_uri uses the default value when empty string provided
        // The default should be the handler's getRedirectUrl() which is typically fb{appid}://authorize/
        assertThat(updatedParameters.getString("redirect_uri")).isNotNull()
        assertThat(updatedParameters.getString("redirect_uri")).isNotEqualTo("")
        // Verify https_redirect_uri parameter is NOT included (old behavior)
        assertThat(updatedParameters.containsKey("https_redirect_uri")).isFalse()
        // Verify existing parameters are preserved
        assertThat(updatedParameters.getString("existing_param")).isEqualTo("existing_value")
    }

    @Test
    fun testTryAuthorizeWithRedirectURI() {
        mockCustomTabRedirectActivity(true)
        mockChromeCustomTabsSupported(true, CHROME_PACKAGE)
        val handler = CustomTabLoginMethodHandler(mockLoginClient)
        val testRedirectURI = "https://example.com/custom/redirect"

        // Create a request with redirect URI
        val requestWithRedirectURI = createRequestWithRedirectURI(testRedirectURI)

        // Call tryAuthorize - this should process the redirect URI through addExtraParameters
        val result = handler.tryAuthorize(requestWithRedirectURI)

        // Verify that the operation completed successfully (should return 1 for success)
        assertThat(result).isEqualTo(1)
        // Verify that the request's redirect URI is preserved
        assertThat(requestWithRedirectURI.redirectURI).isEqualTo(testRedirectURI)
    }

    @Test
    fun testAddExtraParametersWithIntentUriPackageTarget() {
        mockCustomTabRedirectActivity(true)
        val handler = CustomTabLoginMethodHandler(mockLoginClient)
        val packageName = "com.example.app"
        val expectedIntentUri = "intent://$packageName"

        // Create a request with intentUriPackageTarget but no custom redirectURI
        val requestWithIntentUri = LoginClient.Request(
            LoginBehavior.NATIVE_WITH_FALLBACK,
            HashSet(PERMISSIONS),
            DefaultAudience.FRIENDS,
            "rerequest",
            "1234",
            "5678",
            LoginTargetApp.FACEBOOK,
            AuthenticationTokenTestUtil.NONCE,
            CODE_VERIFIER,
            CODE_CHALLENGE,
            CodeChallengeMethod.S256,
            null, // no custom redirectURI
            packageName) // just package name, SDK will construct intent URI

        // Create initial parameters bundle
        val initialParameters = Bundle()
        initialParameters.putString("existing_param", "existing_value")

        // Call addExtraParameters using reflection to access protected method
        val updatedParameters = Whitebox.invokeMethod<Bundle>(
            handler,
            "addExtraParameters",
            initialParameters,
            requestWithIntentUri
        )

        // Verify that redirect_uri parameter is overridden with constructed intent URI
        assertThat(updatedParameters.getString("redirect_uri")).isEqualTo(expectedIntentUri)
        // Verify https_redirect_uri parameter is NOT included (web request should only use redirect_uri)
        assertThat(updatedParameters.containsKey("https_redirect_uri")).isFalse()
        // Verify intent_uri_package_target parameter is NOT included (only for internal SDK use)
        assertThat(updatedParameters.containsKey("intent_uri_package_target")).isFalse()
        // Verify existing parameters are preserved
        assertThat(updatedParameters.getString("existing_param")).isEqualTo("existing_value")
    }

    @Test
    fun testAddExtraParametersRedirectURITakesPriorityOverIntentUriPackageTarget() {
        mockCustomTabRedirectActivity(true)
        val handler = CustomTabLoginMethodHandler(mockLoginClient)
        val customRedirectURI = "https://example.com/custom/redirect"
        val packageName = "com.example.app"

        // Create a request with both redirectURI and intentUriPackageTarget
        val requestWithBoth = LoginClient.Request(
            LoginBehavior.NATIVE_WITH_FALLBACK,
            HashSet(PERMISSIONS),
            DefaultAudience.FRIENDS,
            "rerequest",
            "1234",
            "5678",
            LoginTargetApp.FACEBOOK,
            AuthenticationTokenTestUtil.NONCE,
            CODE_VERIFIER,
            CODE_CHALLENGE,
            CodeChallengeMethod.S256,
            customRedirectURI, // custom redirectURI provided
            packageName) // intentUriPackageTarget also provided (just package name)

        // Create initial parameters bundle
        val initialParameters = Bundle()

        // Call addExtraParameters using reflection to access protected method
        val updatedParameters = Whitebox.invokeMethod<Bundle>(
            handler,
            "addExtraParameters",
            initialParameters,
            requestWithBoth
        )

        // Verify that redirect_uri uses customRedirectURI (takes priority)
        assertThat(updatedParameters.getString("redirect_uri")).isEqualTo(customRedirectURI)
        // Verify https_redirect_uri parameter is NOT included
        assertThat(updatedParameters.containsKey("https_redirect_uri")).isFalse()
        // Verify intent_uri_package_target parameter is NOT included
        assertThat(updatedParameters.containsKey("intent_uri_package_target")).isFalse()
    }

    @Test
    fun testTryAuthorizeWithIntentUriPackageTarget() {
        mockCustomTabRedirectActivity(true)
        mockChromeCustomTabsSupported(true, CHROME_PACKAGE)
        val handler = CustomTabLoginMethodHandler(mockLoginClient)
        val packageName = "com.example.app"

        // Create a request with intentUriPackageTarget
        val requestWithIntentUri = LoginClient.Request(
            LoginBehavior.NATIVE_WITH_FALLBACK,
            HashSet(PERMISSIONS),
            DefaultAudience.FRIENDS,
            "rerequest",
            "1234",
            "5678",
            LoginTargetApp.FACEBOOK,
            AuthenticationTokenTestUtil.NONCE,
            CODE_VERIFIER,
            CODE_CHALLENGE,
            CodeChallengeMethod.S256,
            null, // no custom redirectURI
            packageName) // just package name, SDK will construct intent URI

        // Call tryAuthorize - this should process the intentUriPackageTarget through addExtraParameters
        val result = handler.tryAuthorize(requestWithIntentUri)

        // Verify that the operation completed successfully (should return 1 for success)
        assertThat(result).isEqualTo(1)
        // Verify that the request's intentUriPackageTarget is preserved
        assertThat(requestWithIntentUri.intentUriPackageTarget).isEqualTo(packageName)
    }

    @Test
    fun testAddExtraParametersWithNullIntentUriPackageTarget() {
        mockCustomTabRedirectActivity(true)
        val handler = CustomTabLoginMethodHandler(mockLoginClient)

        // Create a request with null intentUriPackageTarget and no redirectURI
        val requestWithNullIntentUri = LoginClient.Request(
            LoginBehavior.NATIVE_WITH_FALLBACK,
            HashSet(PERMISSIONS),
            DefaultAudience.FRIENDS,
            "rerequest",
            "1234",
            "5678",
            LoginTargetApp.FACEBOOK,
            AuthenticationTokenTestUtil.NONCE,
            CODE_VERIFIER,
            CODE_CHALLENGE,
            CodeChallengeMethod.S256,
            null, // no custom redirectURI
            null) // null intentUriPackageTarget

        // Create initial parameters bundle
        val initialParameters = Bundle()
        initialParameters.putString("existing_param", "existing_value")

        // Call addExtraParameters using reflection to access protected method
        val updatedParameters = Whitebox.invokeMethod<Bundle>(
            handler,
            "addExtraParameters",
            initialParameters,
            requestWithNullIntentUri
        )

        // Verify that redirect_uri uses default value when both are null
        assertThat(updatedParameters.getString("redirect_uri")).isNotNull()
        // Verify no separate intent_uri_package_target parameter (should be rewritten into redirect_uri)
        assertThat(updatedParameters.containsKey("intent_uri_package_target")).isFalse()
        // Verify no separate https_redirect_uri parameter
        assertThat(updatedParameters.containsKey("https_redirect_uri")).isFalse()
        // Verify existing parameters are preserved
        assertThat(updatedParameters.getString("existing_param")).isEqualTo("existing_value")
    }

    @Test
    fun testAddExtraParametersWithEmptyIntentUriPackageTarget() {
        mockCustomTabRedirectActivity(true)
        val handler = CustomTabLoginMethodHandler(mockLoginClient)

        // Create a request with empty intentUriPackageTarget and no redirectURI
        val requestWithEmptyIntentUri = LoginClient.Request(
            LoginBehavior.NATIVE_WITH_FALLBACK,
            HashSet(PERMISSIONS),
            DefaultAudience.FRIENDS,
            "rerequest",
            "1234",
            "5678",
            LoginTargetApp.FACEBOOK,
            AuthenticationTokenTestUtil.NONCE,
            CODE_VERIFIER,
            CODE_CHALLENGE,
            CodeChallengeMethod.S256,
            null, // no custom redirectURI
            "") // empty intentUriPackageTarget

        // Create initial parameters bundle
        val initialParameters = Bundle()
        initialParameters.putString("existing_param", "existing_value")

        // Call addExtraParameters using reflection to access protected method
        val updatedParameters = Whitebox.invokeMethod<Bundle>(
            handler,
            "addExtraParameters",
            initialParameters,
            requestWithEmptyIntentUri
        )

        // Verify that redirect_uri uses default value when intentUriPackageTarget is empty
        assertThat(updatedParameters.getString("redirect_uri")).isNotNull()
        // Verify no separate intent_uri_package_target parameter
        assertThat(updatedParameters.containsKey("intent_uri_package_target")).isFalse()
        // Verify no separate https_redirect_uri parameter
        assertThat(updatedParameters.containsKey("https_redirect_uri")).isFalse()
        // Verify existing parameters are preserved
        assertThat(updatedParameters.getString("existing_param")).isEqualTo("existing_value")
    }

    private fun createRequestWithRedirectURI(redirectURI: String?): LoginClient.Request {
        return LoginClient.Request(
            LoginBehavior.NATIVE_WITH_FALLBACK,
            HashSet(PERMISSIONS),
            DefaultAudience.FRIENDS,
            "rerequest",
            "1234",
            "5678",
            LoginTargetApp.FACEBOOK,
            AuthenticationTokenTestUtil.NONCE,
            CODE_VERIFIER,
            CODE_CHALLENGE,
            CodeChallengeMethod.S256,
            redirectURI)
    }

    companion object {
        private const val SIGNED_REQUEST_STR =
            "ggarbage.eyJhbGdvcml0aG0iOiJITUFDSEEyNTYiLCJjb2RlIjoid2h5bm90IiwiaXNzdWVkX2F0IjoxNDIyNTAyMDkyLCJ1c2VyX2lkIjoiMTIzIn0"
        private const val CHROME_PACKAGE = "com.android.chrome"
        private const val DEV_PACKAGE = "com.chrome.dev"
        private const val BETA_PACKAGE = "com.chrome.beta"
    }
}
