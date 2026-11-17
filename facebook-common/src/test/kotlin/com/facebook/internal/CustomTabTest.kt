/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.internal

import android.content.Intent
import android.os.Bundle
import androidx.browser.customtabs.CustomTabsIntent
import com.facebook.FacebookPowerMockTestCase
import com.facebook.FacebookSdk
import com.facebook.login.CustomTabLoginMethodHandler
import com.facebook.login.CustomTabPrefetchHelper
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.powermock.api.mockito.PowerMockito
import org.powermock.core.classloader.annotations.PrepareForTest
import org.powermock.reflect.Whitebox

@PrepareForTest(CustomTabsIntent::class)
class CustomTabTest : FacebookPowerMockTestCase() {
  private lateinit var mockCustomTabsIntent: CustomTabsIntent
  private lateinit var parameters: Bundle

  @Before
  fun init() {
    parameters = Bundle()
    parameters.putString(ServerProtocol.DIALOG_PARAM_SCOPE, "user_name,user_birthday")

    mockCustomTabsIntent = mock()
    Whitebox.setInternalState(mockCustomTabsIntent, "intent", Intent(Intent.ACTION_VIEW))

    PowerMockito.whenNew(CustomTabsIntent::class.java)
        .withAnyArguments()
        .thenReturn(mockCustomTabsIntent)

    val mockCustomTabPrefetchHelperCompanion = mock<CustomTabPrefetchHelper.Companion>()
    Whitebox.setInternalState(
        CustomTabPrefetchHelper::class.java, "Companion", mockCustomTabPrefetchHelperCompanion)
  }

  @Test
  fun `test get URI for action`() {
    val uri = CustomTab.getURIForAction(CustomTabLoginMethodHandler.OAUTH_DIALOG, parameters)
    val version = FacebookSdk.getGraphApiVersion()
    assertThat(uri.toString())
        .isEqualTo("https://m.facebook.com/$version/dialog/oauth?scope=user_name%2Cuser_birthday")
  }

  @Test
  fun `test open custom tab`() {
    val customTab = CustomTab(CustomTabLoginMethodHandler.OAUTH_DIALOG, parameters)
    customTab.openCustomTab(mock(), "com.facebook.internal")
    verify(mockCustomTabsIntent).launchUrl(any(), any())
    val tabIntent = mockCustomTabsIntent.intent
    assertThat(tabIntent.hasExtra(CustomTab.EXTRA_SEND_TO_EXTERNAL_DEFAULT_HANDLER)).isFalse
  }

  @Test
  fun `open custom tab can ask for the external default handler`() {
    val customTab = CustomTab(CustomTabLoginMethodHandler.OAUTH_DIALOG, parameters)
    customTab.openCustomTab(mock(), "com.android.chrome", sendToExternalDefaultHandler = true)
    assertThat(
            mockCustomTabsIntent.intent.getBooleanExtra(
                "android.support.customtabs.extra.SEND_TO_EXTERNAL_HANDLER", false))
        .isTrue
  }
}
