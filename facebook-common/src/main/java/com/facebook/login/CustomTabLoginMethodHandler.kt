/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.login

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import com.facebook.AccessTokenSource
import com.facebook.CustomTabMainActivity
import com.facebook.FacebookException
import com.facebook.FacebookOperationCanceledException
import com.facebook.FacebookRequestError
import com.facebook.FacebookSdk
import com.facebook.FacebookSdk.getExecutor
import com.facebook.FacebookServiceException
import com.facebook.internal.AndroidLvaGate
import com.facebook.internal.CustomTab.Companion.getURIForAction
import com.facebook.internal.CustomTabUtils.getChromePackage
import com.facebook.internal.CustomTabUtils.getValidRedirectURI
import com.facebook.internal.InstagramCustomTab
import com.facebook.internal.ServerProtocol
import com.facebook.internal.Utility.generateRandomString
import com.facebook.internal.Utility.isNullOrEmpty
import com.facebook.internal.Utility.parseUrlQueryString
import com.facebook.internal.Validate
import com.facebook.login.LoginClient.Request
import java.lang.NumberFormatException
import org.json.JSONException
import org.json.JSONObject

class CustomTabLoginMethodHandler : WebLoginMethodHandler {
  private var currentPackage: String? = null
  private var expectedChallenge: String?
  private var validRedirectURI: String

  constructor(loginClient: LoginClient) : super(loginClient) {
    expectedChallenge = generateRandomString(CHALLENGE_LENGTH)
    calledThroughLoggedOutAppSwitch = false
    validRedirectURI = getValidRedirectURI(developerDefinedRedirectURI)
  }

  override val nameForLogging: String = "custom_tab"
  override val tokenSource: AccessTokenSource = AccessTokenSource.CHROME_CUSTOM_TAB
  private val developerDefinedRedirectURI: String
    get() = super.getRedirectUrl()

  override fun getRedirectUrl(): String = validRedirectURI

  override fun getSSODevice(): String? = "chrome_custom_tab"

  override val methodStartLoggingExtras: Map<String?, String?>?
    get() =
        methodLoggingExtras?.get(AndroidLvaClientArm.LOGGING_EXTRA)?.let {
          mapOf(AndroidLvaClientArm.LOGGING_EXTRA to it)
        }

  override fun tryAuthorize(request: Request): Int {
    val loginClient = loginClient
    assignAndroidLvaClientArm(request)
    if (getRedirectUrl().isEmpty()) {
      return 0
    }
    var parameters = getParameters(request)
    parameters = addExtraParameters(parameters, request)
    if (calledThroughLoggedOutAppSwitch) {
      parameters.putString(ServerProtocol.DIALOG_PARAM_CCT_OVER_LOGGED_OUT_APP_SWITCH, "1")
    }
    if (FacebookSdk.hasCustomTabsPrefetching) {
      if (request.isInstagramLogin) {
        CustomTabPrefetchHelper.mayLaunchUrl(
            InstagramCustomTab.getURIForAction(OAUTH_DIALOG, parameters))
      } else {
        CustomTabPrefetchHelper.mayLaunchUrl(getURIForAction(OAUTH_DIALOG, parameters))
      }
    }
    val activity: Activity = loginClient.activity ?: return 0
    val intent = Intent(activity, CustomTabMainActivity::class.java)
    intent.putExtra(CustomTabMainActivity.EXTRA_ACTION, OAUTH_DIALOG)
    intent.putExtra(CustomTabMainActivity.EXTRA_PARAMS, parameters)
    intent.putExtra(CustomTabMainActivity.EXTRA_CHROME_PACKAGE, chromePackage)
    intent.putExtra(CustomTabMainActivity.EXTRA_TARGET_APP, request.loginTargetApp.toString())
    @Suppress("DEPRECATION")
    loginClient.fragment?.startActivityForResult(intent, CUSTOM_TAB_REQUEST_CODE)
    return 1
  }

  /**
   * Reads the Android SDK LVA gate once for this attempt and assigns its client arm, stored in the
   * request and logged with the method events. The gate belongs to the SDK's app, so a request for
   * another app gets no arm.
   */
  private fun assignAndroidLvaClientArm(request: Request) {
    val arm =
        if (request.applicationId == FacebookSdk.getApplicationId()) {
          AndroidLvaClientArm.assign(AndroidLvaGate.getSnapshot())
        } else {
          null
        }
    request.androidLvaClientArm = arm
    if (arm != null) {
      addLoggingExtra(AndroidLvaClientArm.LOGGING_EXTRA, arm)
    } else {
      methodLoggingExtras?.remove(AndroidLvaClientArm.LOGGING_EXTRA)
    }
  }

  private val chromePackage: String?
    get() {
      if (currentPackage != null) {
        return currentPackage
      }
      currentPackage = getChromePackage()
      return currentPackage
    }

  override fun addExtraParameters(parameters: Bundle, request: Request): Bundle {
    // Call parent implementation first to set up base parameters
    val updatedParameters = super.addExtraParameters(parameters, request)

    // For CustomTab, override redirect_uri if intentUriPackageTarget is provided
    // Priority order: redirectURI > intentUriPackageTarget > default
    val redirectUri = when {
      !request.redirectURI.isNullOrEmpty() -> request.redirectURI
      !request.intentUriPackageTarget.isNullOrEmpty() -> buildIntentUriFromPackage(request.intentUriPackageTarget!!)
      else -> getRedirectUrl()
    }
    updatedParameters.putString(ServerProtocol.DIALOG_PARAM_REDIRECT_URI, redirectUri)

    return updatedParameters
  }

  /**
   * Builds an intent URI from a package name.
   * Format: intent://<packageName>
   */
  private fun buildIntentUriFromPackage(packageName: String): String {
    return "intent://$packageName"
  }

  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
    if (data != null) {
      val hasNoBrowserException =
          data.getBooleanExtra(CustomTabMainActivity.NO_ACTIVITY_EXCEPTION, false)
      if (hasNoBrowserException) {
        return super.onActivityResult(requestCode, resultCode, data)
      }
    }
    if (requestCode != CUSTOM_TAB_REQUEST_CODE) {
      return super.onActivityResult(requestCode, resultCode, data)
    }
    val request = loginClient.pendingRequest ?: return false
    if (resultCode == Activity.RESULT_OK) {
      var extraUrl: String? = null
      if (data != null) {
        extraUrl = data.getStringExtra(CustomTabMainActivity.EXTRA_URL)
      }
      onCustomTabComplete(extraUrl, request)
      return true
    }
    super.onComplete(request, null, FacebookOperationCanceledException())
    return false
  }

  private fun onCustomTabComplete(url: String?, request: LoginClient.Request) {
    if (url != null &&
        (url.startsWith(Validate.CUSTOM_TAB_REDIRECT_URI_PREFIX) ||
            url.startsWith(super.getRedirectUrl()))) {
      val uri = Uri.parse(url)
      val values = parseUrlQueryString(uri.query)
      values.putAll(parseUrlQueryString(uri.fragment))
      if (!validateChallengeParam(values)) {
        super.onComplete(request, null, FacebookException("Invalid state parameter"))
        return
      }
      var error = values.getString("error")
      if (error == null) {
        error = values.getString("error_type")
      }
      var errorMessage = values.getString("error_msg")
      if (errorMessage == null) {
        errorMessage = values.getString("error_message")
      }
      if (errorMessage == null) {
        errorMessage = values.getString("error_description")
      }
      val errorCodeString = values.getString("error_code")
      val errorCode =
          try {
            errorCodeString?.toInt() ?: FacebookRequestError.INVALID_ERROR_CODE
          } catch (ex: NumberFormatException) {
            FacebookRequestError.INVALID_ERROR_CODE
          }
      if (isNullOrEmpty(error) &&
          isNullOrEmpty(errorMessage) &&
          errorCode == FacebookRequestError.INVALID_ERROR_CODE) {
        if (values.containsKey("access_token")) {
          super.onComplete(request, values, null)
          return
        }
        getExecutor().execute {
          try {
            val processedValues = processCodeExchange(request, values)
            onComplete(request, processedValues, null)
          } catch (ex: FacebookException) {
            onComplete(request, null, ex)
          }
        }
      } else if (error != null &&
          (error == "access_denied" || error == "OAuthAccessDeniedException")) {
        super.onComplete(request, null, FacebookOperationCanceledException())
      } else if (errorCode == API_EC_DIALOG_CANCEL) {
        super.onComplete(request, null, FacebookOperationCanceledException())
      } else {
        val requestError = FacebookRequestError(errorCode, error, errorMessage)
        super.onComplete(request, null, FacebookServiceException(requestError, errorMessage))
      }
    }
  }

  @Throws(JSONException::class)
  override fun putChallengeParam(param: JSONObject) {
    param.put(LoginLogger.EVENT_PARAM_CHALLENGE, expectedChallenge)
  }

  private fun validateChallengeParam(values: Bundle): Boolean {
    return try {
      val stateString = values.getString(ServerProtocol.DIALOG_PARAM_STATE) ?: return false
      val state = JSONObject(stateString)
      val challenge = state.getString(LoginLogger.EVENT_PARAM_CHALLENGE)
      challenge == expectedChallenge
    } catch (e: JSONException) {
      false
    }
  }

  override fun describeContents(): Int = 0

  internal constructor(source: Parcel) : super(source) {
    expectedChallenge = source.readString()
    validRedirectURI = getValidRedirectURI(developerDefinedRedirectURI)
  }

  override fun writeToParcel(dest: Parcel, flags: Int) {
    super.writeToParcel(dest, flags)
    dest.writeString(expectedChallenge)
  }

  companion object {
    private const val CUSTOM_TAB_REQUEST_CODE = 1
    private const val CHALLENGE_LENGTH = 20
    private const val API_EC_DIALOG_CANCEL = 4201
    const val OAUTH_DIALOG = "oauth"
    @JvmField var calledThroughLoggedOutAppSwitch = false
    @JvmField
    val CREATOR: Parcelable.Creator<CustomTabLoginMethodHandler> =
        object : Parcelable.Creator<CustomTabLoginMethodHandler> {
          override fun createFromParcel(source: Parcel): CustomTabLoginMethodHandler {
            return CustomTabLoginMethodHandler(source)
          }

          override fun newArray(size: Int): Array<CustomTabLoginMethodHandler?> {
            return arrayOfNulls(size)
          }
        }
  }
}
