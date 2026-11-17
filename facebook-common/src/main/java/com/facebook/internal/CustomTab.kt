/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.internal

import android.app.Activity
import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.Bundle
import androidx.browser.customtabs.CustomTabsIntent
import com.facebook.FacebookSdk
import com.facebook.FacebookSdk.getGraphApiVersion
import com.facebook.internal.ServerProtocol.getDialogAuthority
import com.facebook.internal.ServerProtocol.getGamingDialogAuthority
import com.facebook.internal.Utility.buildUri
import com.facebook.internal.instrument.crashshield.AutoHandleExceptions
import com.facebook.login.CustomTabPrefetchHelper

@AutoHandleExceptions
open class CustomTab(action: String, parameters: Bundle?) {
  protected var uri: Uri
  @JvmOverloads
  fun openCustomTab(
      activity: Activity,
      packageName: String?,
      sendToExternalDefaultHandler: Boolean = false
  ): Boolean {
    val session = CustomTabPrefetchHelper.getPreparedSessionOnce()
    val customTabsIntent = CustomTabsIntent.Builder(session).build()
    customTabsIntent.intent.setPackage(packageName)
    if (sendToExternalDefaultHandler) {
      // Android SDK LVA: lets LVA's hand-off leave the Custom Tab for Instagram. The raw extra,
      // because the bundled androidx.browser predates its builder method.
      customTabsIntent.intent.putExtra(EXTRA_SEND_TO_EXTERNAL_DEFAULT_HANDLER, true)
    }
    try {
      customTabsIntent.launchUrl(activity, uri)
    } catch (e: ActivityNotFoundException) {
      return false
    }
    return true
  }

  companion object {
    internal const val EXTRA_SEND_TO_EXTERNAL_DEFAULT_HANDLER =
        "android.support.customtabs.extra.SEND_TO_EXTERNAL_HANDLER"

    // `open` keeps the JVM static non-final so Java subclasses can hide it.
    @Suppress("NON_FINAL_MEMBER_IN_OBJECT")
    @JvmStatic
    open fun getURIForAction(action: String, parameters: Bundle?): Uri {
      return buildUri(
          getDialogAuthority(),
          getGraphApiVersion() + "/" + ServerProtocol.DIALOG_PATH + action,
          parameters)
    }
  }

  init {
    val uriParameters = parameters ?: Bundle()
    uri =
        if (GamingAction.values().map { it.rawValue }.contains(action)) {
          buildUri(
              getGamingDialogAuthority(), "/" + ServerProtocol.DIALOG_PATH + action, uriParameters)
        } else {
          getURIForAction(action, uriParameters)
        }
  }
}
