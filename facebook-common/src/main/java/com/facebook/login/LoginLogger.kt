/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.login

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.TextUtils
import com.facebook.appevents.InternalAppEventsLogger
import com.facebook.internal.Utility
import com.facebook.internal.instrument.crashshield.AutoHandleExceptions
import java.lang.Exception
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.jvm.JvmOverloads
import org.json.JSONException
import org.json.JSONObject

@AutoHandleExceptions
internal class LoginLogger(context: Context, val applicationId: String) {
  private val logger: InternalAppEventsLogger = InternalAppEventsLogger(context, applicationId)
  private var facebookVersion: String? = null

  private fun logAndEmit(eventName: String?, bundle: Bundle) {
    Utility.logd(TAG, "Event: $eventName | " +
        "auth_logger_id=${bundle.getString(EVENT_PARAM_AUTH_LOGGER_ID)} | " +
        "result=${bundle.getString(EVENT_PARAM_LOGIN_RESULT)} | " +
        "method=${bundle.getString(EVENT_PARAM_METHOD)} | " +
        "extras=${bundle.getString(EVENT_PARAM_EXTRAS)}")
    logger.logEventImplicitly(eventName, bundle)
  }

  @JvmOverloads
  fun logStartLogin(
      pendingLoginRequest: LoginClient.Request,
      eventName: String? = EVENT_NAME_LOGIN_START
  ) {
    val bundle = newAuthorizationLoggingBundle(pendingLoginRequest.authId)

    // Log what we already know about the call in start event
    try {
      val extras = JSONObject()
      extras.put(EVENT_EXTRAS_LOGIN_BEHAVIOR, pendingLoginRequest.loginBehavior.toString())
      extras.put(EVENT_EXTRAS_REQUEST_CODE, LoginClient.getLoginRequestCode())
      extras.put(EVENT_EXTRAS_PERMISSIONS, TextUtils.join(",", pendingLoginRequest.permissions))
      extras.put(EVENT_EXTRAS_DEFAULT_AUDIENCE, pendingLoginRequest.defaultAudience.toString())
      extras.put(EVENT_EXTRAS_IS_REAUTHORIZE, pendingLoginRequest.isRerequest)
      if (facebookVersion != null) {
        extras.put(EVENT_EXTRAS_FACEBOOK_VERSION, facebookVersion)
      }
      extras.put(EVENT_EXTRAS_TARGET_APP, pendingLoginRequest.loginTargetApp.toString())
      bundle.putString(EVENT_PARAM_EXTRAS, extras.toString())
    } catch (e: JSONException) {}
    logAndEmit(eventName, bundle)
  }

  @JvmOverloads
  fun logCompleteLogin(
      loginRequestId: String?,
      loggingExtras: Map<String, String>,
      result: LoginClient.Result.Code?,
      resultExtras: Map<String, String>?,
      exception: Exception?,
      eventName: String? = EVENT_NAME_LOGIN_COMPLETE
  ) {
    val bundle = newAuthorizationLoggingBundle(loginRequestId)
    if (result != null) {
      bundle.putString(EVENT_PARAM_LOGIN_RESULT, result.loggingValue)
    }
    if (exception?.message != null) {
      bundle.putString(EVENT_PARAM_ERROR_MESSAGE, exception.message)
    }

    // Combine extras from the request and from the result.
    var jsonObject: JSONObject? = null
    if (loggingExtras.isNotEmpty()) {
      jsonObject = JSONObject(loggingExtras)
    }
    if (resultExtras != null) {
      if (jsonObject == null) {
        jsonObject = JSONObject()
      }
      try {
        for ((key, value) in resultExtras) {
          jsonObject.put(key, value)
        }
      } catch (e: JSONException) {}
    }
    if (jsonObject != null) {
      bundle.putString(EVENT_PARAM_EXTRAS, jsonObject.toString())
    }
    logAndEmit(eventName, bundle)
    if (result == LoginClient.Result.Code.SUCCESS) {
      logHeartbeatEvent(loginRequestId)
    }
  }

  private fun logHeartbeatEvent(loginRequestId: String?) {
    val bundle = newAuthorizationLoggingBundle(loginRequestId)
    val runnable = Runnable { logAndEmit(EVENT_NAME_LOGIN_HEARTBEAT, bundle) }
    worker.schedule(runnable, 5, TimeUnit.SECONDS)
  }

  @JvmOverloads
  fun logAuthorizationMethodStart(
      authId: String?,
      method: String?,
      eventName: String? = EVENT_NAME_LOGIN_METHOD_START,
      loggingExtras: Map<String?, String?>? = null
  ) {
    val bundle = newAuthorizationLoggingBundle(authId)
    bundle.putString(EVENT_PARAM_METHOD, method)
    if (loggingExtras != null && loggingExtras.isNotEmpty()) {
      bundle.putString(
          EVENT_PARAM_EXTRAS, JSONObject(loggingExtras.filterKeys { it != null }).toString())
    }
    logAndEmit(eventName, bundle)
  }

  @JvmOverloads
  fun logAuthorizationMethodComplete(
      authId: String?,
      method: String?,
      result: String?,
      errorMessage: String?,
      errorCode: String?,
      loggingExtras: Map<String?, String?>?,
      eventName: String? = EVENT_NAME_LOGIN_METHOD_COMPLETE
  ) {
    val bundle = newAuthorizationLoggingBundle(authId)
    if (result != null) {
      bundle.putString(EVENT_PARAM_LOGIN_RESULT, result)
    }
    if (errorMessage != null) {
      bundle.putString(EVENT_PARAM_ERROR_MESSAGE, errorMessage)
    }
    if (errorCode != null) {
      bundle.putString(EVENT_PARAM_ERROR_CODE, errorCode)
    }
    if (loggingExtras != null && loggingExtras.isNotEmpty()) {
      val jsonObject = JSONObject(loggingExtras.filterKeys { it != null })
      bundle.putString(EVENT_PARAM_EXTRAS, jsonObject.toString())
    }
    bundle.putString(EVENT_PARAM_METHOD, method)
    logAndEmit(eventName, bundle)
  }

  @JvmOverloads
  fun logAuthorizationMethodNotTried(
      authId: String?,
      method: String?,
      eventName: String? = EVENT_NAME_LOGIN_METHOD_NOT_TRIED
  ) {
    val bundle = newAuthorizationLoggingBundle(authId)
    bundle.putString(EVENT_PARAM_METHOD, method)
    logAndEmit(eventName, bundle)
  }

  fun logLoginStatusStart(loggerRef: String?) {
    val bundle = newAuthorizationLoggingBundle(loggerRef)
    logAndEmit(EVENT_NAME_LOGIN_STATUS_START, bundle)
  }

  fun logLoginStatusSuccess(loggerRef: String?) {
    val bundle = newAuthorizationLoggingBundle(loggerRef)
    bundle.putString(EVENT_PARAM_LOGIN_RESULT, LoginClient.Result.Code.SUCCESS.loggingValue)
    logAndEmit(EVENT_NAME_LOGIN_STATUS_COMPLETE, bundle)
  }

  fun logLoginStatusFailure(loggerRef: String?) {
    val bundle = newAuthorizationLoggingBundle(loggerRef)
    bundle.putString(EVENT_PARAM_LOGIN_RESULT, EVENT_EXTRAS_FAILURE)
    logAndEmit(EVENT_NAME_LOGIN_STATUS_COMPLETE, bundle)
  }

  fun logLoginStatusError(loggerRef: String?, exception: Exception) {
    val bundle = newAuthorizationLoggingBundle(loggerRef)
    bundle.putString(EVENT_PARAM_LOGIN_RESULT, LoginClient.Result.Code.ERROR.loggingValue)
    bundle.putString(EVENT_PARAM_ERROR_MESSAGE, exception.toString())
    logAndEmit(EVENT_NAME_LOGIN_STATUS_COMPLETE, bundle)
  }

  @JvmOverloads
  fun logUnexpectedError(eventName: String?, errorMessage: String?, method: String? = "") {
    val bundle = newAuthorizationLoggingBundle("")
    bundle.putString(EVENT_PARAM_LOGIN_RESULT, LoginClient.Result.Code.ERROR.loggingValue)
    bundle.putString(EVENT_PARAM_ERROR_MESSAGE, errorMessage)
    bundle.putString(EVENT_PARAM_METHOD, method)
    logAndEmit(eventName, bundle)
  }

  companion object {
    private const val TAG = "LoginLogger"

    // Constants for logging login-related data.
    const val EVENT_NAME_LOGIN_METHOD_START = "fb_mobile_login_method_start"
    const val EVENT_NAME_LOGIN_METHOD_COMPLETE = "fb_mobile_login_method_complete"
    const val EVENT_NAME_LOGIN_METHOD_NOT_TRIED = "fb_mobile_login_method_not_tried"
    const val EVENT_PARAM_METHOD_RESULT_SKIPPED = "skipped"
    const val EVENT_NAME_LOGIN_START = "fb_mobile_login_start"
    const val EVENT_NAME_LOGIN_COMPLETE = "fb_mobile_login_complete"
    const val EVENT_NAME_LOGIN_STATUS_START = "fb_mobile_login_status_start"
    const val EVENT_NAME_LOGIN_STATUS_COMPLETE = "fb_mobile_login_status_complete"
    const val EVENT_NAME_LOGIN_HEARTBEAT = "fb_mobile_login_heartbeat"
    const val EVENT_NAME_SSO_LAUNCH_ATTEMPTED = "fb_mobile_sso_launch_attempted"
    const val EVENT_NAME_SSO_DELEGATED_TO_FB4A = "fb_mobile_sso_delegated_to_fb4a"
    const val EVENT_NAME_SSO_SHOWN = "fb_mobile_sso_shown"
    const val EVENT_NAME_SSO_NOT_SHOWN = "fb_mobile_sso_not_shown"
    const val EVENT_NAME_SSO_DISMISSED = "fb_mobile_sso_dismissed"
    const val EVENT_NAME_SSO_CONTINUE_CLICKED = "fb_mobile_sso_continue_clicked"
    const val EVENT_EXTRAS_FROM_SSO = "from_sso"
    const val EVENT_NAME_FOA_LOGIN_METHOD_START = "foa_mobile_login_method_start"
    const val EVENT_NAME_FOA_LOGIN_METHOD_COMPLETE = "foa_mobile_login_method_complete"
    const val EVENT_NAME_FOA_LOGIN_METHOD_NOT_TRIED = "foa_mobile_login_method_not_tried"
    const val EVENT_PARAM_FOA_METHOD_RESULT_SKIPPED = "foa_skipped"
    const val EVENT_NAME_FOA_LOGIN_START = "foa_mobile_login_start"
    const val EVENT_NAME_FOA_LOGIN_COMPLETE = "foa_mobile_login_complete"

    // Note: to ensure stability of column mappings across the four different event types, we
    // prepend a column index to each name, and we log all columns with all events, even if they are
    // empty.
    const val EVENT_PARAM_AUTH_LOGGER_ID = "0_auth_logger_id"
    const val EVENT_PARAM_TIMESTAMP = "1_timestamp_ms"
    const val EVENT_PARAM_LOGIN_RESULT = "2_result"
    const val EVENT_PARAM_METHOD = "3_method"
    const val EVENT_PARAM_ERROR_CODE = "4_error_code"
    const val EVENT_PARAM_ERROR_MESSAGE = "5_error_message"
    const val EVENT_PARAM_EXTRAS = "6_extras"
    const val EVENT_PARAM_CHALLENGE = "7_challenge"
    const val EVENT_EXTRAS_TRY_LOGIN_ACTIVITY = "try_login_activity"
    const val EVENT_EXTRAS_MISSING_INTERNET_PERMISSION = "no_internet_permission"
    const val EVENT_EXTRAS_NOT_TRIED = "not_tried"
    const val EVENT_EXTRAS_NEW_PERMISSIONS = "new_permissions"
    const val EVENT_EXTRAS_LOGIN_BEHAVIOR = "login_behavior"
    const val EVENT_EXTRAS_REQUEST_CODE = "request_code"
    const val EVENT_EXTRAS_PERMISSIONS = "permissions"
    const val EVENT_EXTRAS_DEFAULT_AUDIENCE = "default_audience"
    const val EVENT_EXTRAS_IS_REAUTHORIZE = "isReauthorize"
    const val EVENT_EXTRAS_FACEBOOK_VERSION = "facebookVersion"
    const val EVENT_EXTRAS_FAILURE = "failure"
    const val EVENT_EXTRAS_TARGET_APP = "target_app"
    const val FACEBOOK_PACKAGE_NAME = "com.facebook.katana"
    private val worker = Executors.newSingleThreadScheduledExecutor()

    private fun newAuthorizationLoggingBundle(authLoggerId: String?): Bundle {
      // We want to log all parameters for all events, to ensure stability of columns across
      // different event types.
      val bundle = Bundle()
      bundle.putLong(EVENT_PARAM_TIMESTAMP, System.currentTimeMillis())
      bundle.putString(EVENT_PARAM_AUTH_LOGGER_ID, authLoggerId)
      bundle.putString(EVENT_PARAM_METHOD, "")
      bundle.putString(EVENT_PARAM_LOGIN_RESULT, "")
      bundle.putString(EVENT_PARAM_ERROR_MESSAGE, "")
      bundle.putString(EVENT_PARAM_ERROR_CODE, "")
      bundle.putString(EVENT_PARAM_EXTRAS, "")
      return bundle
    }
  }

  init {
    // Store which version of facebook is installed
    try {
      val packageManager = context.packageManager
      if (packageManager != null) {
        val facebookInfo = packageManager.getPackageInfo(FACEBOOK_PACKAGE_NAME, 0)
        if (facebookInfo != null) {
          facebookVersion = facebookInfo.versionName
        }
      }
    } catch (e: PackageManager.NameNotFoundException) {
      // Do nothing, just ignore and not log
    }
  }
}
