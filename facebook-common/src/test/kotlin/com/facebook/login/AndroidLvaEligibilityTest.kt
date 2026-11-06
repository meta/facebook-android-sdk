/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.login

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import androidx.test.core.app.ApplicationProvider
import com.facebook.AccessToken
import com.facebook.FacebookPowerMockTestCase
import com.facebook.FacebookSdk
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argThat
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.powermock.api.mockito.PowerMockito.mockStatic
import org.powermock.core.classloader.annotations.PrepareForTest
import org.powermock.reflect.Whitebox

@PrepareForTest(AccessToken::class, FacebookSdk::class)
class AndroidLvaEligibilityTest : FacebookPowerMockTestCase() {

  companion object {
    // The server's default LVA hand-off path (LoginViaAppPath::LVA_ENDPOINT_PATH_UNIFIED).
    const val UNIFIED_LVA_URL = "https://m.facebook.com/unified/login_via/app/"
  }

  private lateinit var context: Context
  private lateinit var defaultRedirect: String
  private lateinit var mockAccessTokenCompanion: AccessToken.Companion
  private lateinit var packageManager: PackageManager

  @Before
  fun init() {
    context = ApplicationProvider.getApplicationContext()
    defaultRedirect = "fbconnect://cct.${context.packageName}"
    mockAccessTokenCompanion = mock()
    packageManager = mock()
    mockStatic(FacebookSdk::class.java)
    whenever(FacebookSdk.getApplicationContext()).thenReturn(context)
    Whitebox.setInternalState(AccessToken::class.java, "Companion", mockAccessTokenCompanion)
    whenever(mockAccessTokenCompanion.getCurrentAccessToken()).thenReturn(null)

    whenever(packageManager.getPackageInfo(eq("com.facebook.katana"), any<Int>()))
        .thenThrow(PackageManager.NameNotFoundException())
    whenever(packageManager.getPackageInfo(eq("com.facebook.wakizashi"), any<Int>()))
        .thenThrow(PackageManager.NameNotFoundException())
    whenever(
            packageManager.resolveActivity(
                argThat<Intent> {
                  `package` == AndroidLvaEligibility.INSTAGRAM_PACKAGE &&
                      dataString == UNIFIED_LVA_URL
                },
                any<Int>()))
        .thenReturn(ResolveInfo())
  }

  private fun request(
      authType: String = "rerequest",
      targetApp: LoginTargetApp = LoginTargetApp.FACEBOOK
  ): LoginClient.Request =
      LoginClient.Request(
              LoginBehavior.NATIVE_WITH_FALLBACK,
              setOf("public_profile"),
              DefaultAudience.FRIENDS,
              authType,
              "1234",
              "5678",
              targetApp)
          .apply { androidLvaClientArm = AndroidLvaClientArm.TREATMENT }

  private fun isInScope(
      request: LoginClient.Request = request(),
      redirectUri: String? = defaultRedirect,
      chromePackage: String? = AndroidLvaEligibility.CHROME_PACKAGE
  ): Boolean = AndroidLvaEligibility.isInScope(request, redirectUri, chromePackage, packageManager)

  @Test
  fun `an ordinary treatment attempt on an eligible device is in scope`() {
    assertThat(isInScope()).isTrue
    assertThat(isInScope(request(authType = ""))).isTrue
  }

  @Test
  fun `control and unassigned attempts are not in scope`() {
    assertThat(isInScope(request().apply { androidLvaClientArm = AndroidLvaClientArm.CONTROL }))
        .isFalse
    assertThat(isInScope(request().apply { androidLvaClientArm = null })).isFalse
  }

  @Test
  fun `only ordinary Facebook Login is in scope`() {
    assertThat(isInScope(request(targetApp = LoginTargetApp.INSTAGRAM))).isFalse
    assertThat(isInScope(request(authType = "reauthenticate"))).isFalse
    assertThat(isInScope(request(authType = "rerequest,reauthenticate"))).isFalse
    assertThat(isInScope(request().apply { isRerequest = true })).isFalse
    assertThat(isInScope(request().apply { isFamilyLogin = true })).isFalse
    assertThat(isInScope(request().apply { messengerPageId = "123" })).isFalse
  }

  @Test
  fun `gaming login is not in scope`() {
    val gamingToken = mock<AccessToken>()
    whenever(gamingToken.graphDomain).thenReturn(FacebookSdk.GAMING)
    whenever(mockAccessTokenCompanion.getCurrentAccessToken()).thenReturn(gamingToken)
    assertThat(isInScope()).isFalse
  }

  @Test
  fun `only the default callback is in scope`() {
    assertThat(isInScope(redirectUri = "https://example.com/callback")).isFalse
    assertThat(isInScope(redirectUri = "intent://${context.packageName}")).isFalse
    assertThat(isInScope(redirectUri = "$defaultRedirect/custom")).isFalse
    assertThat(isInScope(redirectUri = null)).isFalse
  }

  @Test
  fun `only stable Chrome is in scope`() {
    assertThat(isInScope(chromePackage = "com.chrome.beta")).isFalse
    assertThat(isInScope(chromePackage = null)).isFalse
  }

  // doReturn, because whenever(...) would call the throwing stub from init().
  @Test
  fun `a device with com_facebook_katana is not in scope`() {
    doReturn(PackageInfo())
        .whenever(packageManager)
        .getPackageInfo(eq("com.facebook.katana"), any<Int>())
    assertThat(isInScope()).isFalse
  }

  @Test
  fun `a device with com_facebook_wakizashi is not in scope`() {
    doReturn(PackageInfo())
        .whenever(packageManager)
        .getPackageInfo(eq("com.facebook.wakizashi"), any<Int>())
    assertThat(isInScope()).isFalse
  }

  @Test
  fun `a device where Instagram doesn't take the hand-off is not in scope`() {
    doReturn(null).whenever(packageManager).resolveActivity(anyOrNull(), any<Int>())
    assertThat(isInScope()).isFalse
  }

  @Test
  fun `Instagram taking only the IG exclusive LVA path is not enough`() {
    doReturn(null).whenever(packageManager).resolveActivity(anyOrNull(), any<Int>())
    whenever(
            packageManager.resolveActivity(
                intentWithData("https://m.facebook.com/ig/login_via/app/"), any<Int>()))
        .thenReturn(ResolveInfo())
    assertThat(isInScope()).isFalse
  }

  // Keep matcher lambdas out of backtick-named tests: PowerMock can't load the test class when a
  // lambda's class name (derived from the test name) has spaces or other non-identifier chars.
  private fun intentWithData(url: String): Intent = argThat { dataString == url }

  @Test
  fun `a failing package lookup makes the attempt out of scope instead of throwing`() {
    doThrow(RuntimeException("package manager died"))
        .whenever(packageManager)
        .getPackageInfo(eq("com.facebook.katana"), any<Int>())
    assertThat(isInScope()).isFalse
  }
}
