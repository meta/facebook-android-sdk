/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.login

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.facebook.AccessToken
import com.facebook.FacebookSdk
import com.facebook.internal.CustomTabUtils
import com.facebook.internal.ServerProtocol

/**
 * The client checks that decide whether a treatment attempt of Android SDK Login Via App (LVA) is
 * in scope. Only an in-scope attempt gets the Chrome extra and the LVA marker; every other attempt
 * behaves exactly as today. The device results are used only for this decision and are never
 * logged or sent.
 *
 * Facebook Login for Business and Limited Login have no Android SDK request form here; the server
 * enforces those parts of the contract.
 */
internal object AndroidLvaEligibility {
  // No minimum version: Chrome has honored the extra since M54 (2016), and an older Chrome ignores
  // it, so LVA's hand-off loads in the tab and falls back to the login page.
  const val CHROME_PACKAGE = "com.android.chrome"
  const val INSTAGRAM_PACKAGE = "com.instagram.android"

  // The URL with which LVA hands off: the server's default (unified) LVA path. Facebook apps claim
  // it too, so the check resolves it for Instagram only.
  const val HAND_OFF_URL = "https://m.facebook.com/unified/login_via/app/"

  // Facebook apps that can handle or receive the LVA hand-off.
  private val FACEBOOK_APP_PACKAGES = listOf("com.facebook.katana", "com.facebook.wakizashi")

  /**
   * Whether [request] is in scope. [redirectUri] is the redirect_uri the attempt sends, and
   * [chromePackage] the package its Custom Tab opens in. It never throws.
   */
  fun isInScope(
      request: LoginClient.Request,
      redirectUri: String?,
      chromePackage: String?,
      packageManager: PackageManager
  ): Boolean =
      try {
        isOrdinaryFacebookLogin(request, redirectUri) &&
            isDeviceInScope(chromePackage, packageManager)
      } catch (e: Exception) {
        false
      }

  private fun isOrdinaryFacebookLogin(request: LoginClient.Request, redirectUri: String?): Boolean =
      request.androidLvaClientArm == AndroidLvaClientArm.TREATMENT &&
          request.loginTargetApp == LoginTargetApp.FACEBOOK &&
          // The default callback, so no developer redirect or intentUriPackageTarget.
          redirectUri == CustomTabUtils.getDefaultRedirectURI() &&
          // No auth_type other than the SDK's default rerequest, and no actual re-request.
          (request.authType.isEmpty() ||
              request.authType == ServerProtocol.DIALOG_REREQUEST_AUTH_TYPE) &&
          !request.isRerequest &&
          !request.isFamilyLogin &&
          request.messengerPageId == null &&
          // Not gaming login; the server also excludes gaming apps.
          AccessToken.getCurrentAccessToken()?.graphDomain != FacebookSdk.GAMING

  private fun isDeviceInScope(chromePackage: String?, packageManager: PackageManager): Boolean {
    if (chromePackage != CHROME_PACKAGE ||
        FACEBOOK_APP_PACKAGES.any { isInstalled(packageManager, it) }) {
      return false
    }
    val handOff = Intent(Intent.ACTION_VIEW, Uri.parse(HAND_OFF_URL)).setPackage(INSTAGRAM_PACKAGE)
    return packageManager.resolveActivity(handOff, 0) != null
  }

  private fun isInstalled(packageManager: PackageManager, packageName: String): Boolean =
      try {
        packageManager.getPackageInfo(packageName, 0)
        true
      } catch (e: PackageManager.NameNotFoundException) {
        false
      }
}
