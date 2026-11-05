/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.login

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Parcel
import android.os.Parcelable
import android.text.TextUtils
import androidx.annotation.RestrictTo
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.facebook.AccessToken
import com.facebook.AuthenticationToken
import com.facebook.CustomTabMainActivity
import com.facebook.FacebookException
import com.facebook.FacebookSdk
import com.facebook.appevents.AppEventsConstants
import com.facebook.common.R
import com.facebook.internal.CallbackManagerImpl
import com.facebook.internal.Utility.logd
import com.facebook.internal.Utility.readNonnullStringMapFromParcel
import com.facebook.internal.Utility.writeNonnullStringMapToParcel
import com.facebook.internal.Validate
import java.lang.Exception
import java.util.UUID
import kotlin.jvm.JvmOverloads
import org.json.JSONException
import org.json.JSONObject

@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
open class LoginClient : Parcelable {
  var handlersToTry: Array<LoginMethodHandler>? = null
  private var currentHandler = -1
  var fragment: Fragment? = null
    set(value) {
      if (field != null) {
        throw FacebookException("Can't set fragment once it is already set.")
      }
      field = value
    }
  var onCompletedListener: OnCompletedListener? = null
  var backgroundProcessingListener: BackgroundProcessingListener? = null
  var checkedInternetPermission = false
  var pendingRequest: Request? = null
  var loggingExtras: MutableMap<String, String>? = null
  var extraData: MutableMap<String, String>? = null
  private var loginLogger: LoginLogger? = null
  private var numActivitiesReturned = 0
  private var numTotalIntentsFired = 0

  fun interface OnCompletedListener {
    fun onCompleted(result: Result)
  }

  interface BackgroundProcessingListener {
    fun onBackgroundProcessingStarted()
    fun onBackgroundProcessingStopped()
  }

  constructor(fragment: Fragment) {
    this.fragment = fragment
  }

  val activity: FragmentActivity?
    get() = fragment?.activity

  fun startOrContinueAuth(request: Request?) {
    if (!inProgress) {
      authorize(request)
    }
  }

  fun authorize(request: Request?) {
    if (request == null) {
      return
    }
    if (pendingRequest != null) {
      throw FacebookException("Attempted to authorize while a request is pending.")
    }
    if (AccessToken.isCurrentAccessTokenActive() && !checkInternetPermission()) {
      // We're going to need INTERNET permission later and don't have it, so fail early.
      return
    }
    pendingRequest = request
    handlersToTry = getHandlersToTry(request)
    tryNextHandler()
  }

  val inProgress: Boolean
    get() = pendingRequest != null && currentHandler >= 0

  fun cancelCurrentHandler() {
    getCurrentHandler()?.cancel()
  }

  fun getCurrentHandler(): LoginMethodHandler? {
    return if (currentHandler >= 0) {
      handlersToTry?.get(currentHandler)
    } else {
      null
    }
  }

  /** Only for internal use when the class is restored from serialized parcel. */
  protected fun setCurrentHandlerIndex(index: Int) {
    currentHandler = index
  }

  fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
    numActivitiesReturned++
    if (pendingRequest != null) {
      if (data != null) {
        // If CustomTabs throws ActivityNotFoundException, then we would eventually return here.
        // In that case, we should treat that as tryAuthorize and try the next handler
        val hasNoBrowserException =
            data.getBooleanExtra(CustomTabMainActivity.NO_ACTIVITY_EXCEPTION, false)
        if (hasNoBrowserException) {
          tryNextHandler()
          return false
        }
      }
      val currentHandler = getCurrentHandler()
      if ((currentHandler != null) &&
          (!currentHandler.shouldKeepTrackOfMultipleIntents() ||
              data != null ||
              numActivitiesReturned >= numTotalIntentsFired)) {
        return currentHandler.onActivityResult(requestCode, resultCode, data)
      }
    }
    return false
  }

  open fun getHandlersToTry(request: Request): Array<LoginMethodHandler>? {
    val handlers = ArrayList<LoginMethodHandler>()
    val behavior = request.loginBehavior
    if (request.isInstagramLogin) {
      if (!FacebookSdk.bypassAppSwitch && behavior.allowsInstagramAppAuth()) {
        handlers.add(InstagramAppLoginMethodHandler(this))
      }
    } else {
      // Only use get token auth and native FB4A auth for FB logins
      if (behavior.allowsGetTokenAuth()) {
        handlers.add(GetTokenLoginMethodHandler(this))
      }
      if (!FacebookSdk.bypassAppSwitch && behavior.allowsKatanaAuth()) {
        handlers.add(KatanaProxyLoginMethodHandler(this))
      }
    }
    if (behavior.allowsCustomTabAuth()) {
      handlers.add(CustomTabLoginMethodHandler(this))
    }
    if (behavior.allowsWebViewAuth()) {
      handlers.add(WebViewLoginMethodHandler(this))
    }
    if (!request.isInstagramLogin && behavior.allowsDeviceAuth()) {
      handlers.add(DeviceAuthMethodHandler(this))
    }
    return handlers.toTypedArray()
  }

  fun checkInternetPermission(): Boolean {
    if (checkedInternetPermission) {
      return true
    }
    val permissionCheck = checkPermission(Manifest.permission.INTERNET)
    if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
      val activity: Activity? = activity
      val errorType = activity?.getString(R.string.com_facebook_internet_permission_error_title)
      val errorDescription =
          activity?.getString(R.string.com_facebook_internet_permission_error_message)
      complete(Result.createErrorResult(pendingRequest, errorType, errorDescription))
      return false
    }
    checkedInternetPermission = true
    return true
  }

  fun tryNextHandler() {
    val currentHandlerObject = getCurrentHandler()
    if (currentHandlerObject != null) {
      logAuthorizationMethodComplete(
          currentHandlerObject.nameForLogging,
          LoginLogger.EVENT_PARAM_METHOD_RESULT_SKIPPED,
          null,
          null,
          currentHandlerObject.methodLoggingExtras)
    }
    val handlersToTry = handlersToTry
    while (handlersToTry != null && currentHandler < handlersToTry.size - 1) {
      currentHandler++
      val started = tryCurrentHandler()
      if (started) {
        return
      }
    }
    if (pendingRequest != null) {
      // We went through all handlers without successfully attempting an auth.
      completeWithFailure()
    }
  }

  private fun completeWithFailure() {
    complete(Result.createErrorResult(pendingRequest, "Login attempt failed.", null))
  }

  private fun addLoggingExtra(key: String, value: String, accumulate: Boolean) {
    val extras = loggingExtras ?: HashMap()
    if (loggingExtras == null) {
      loggingExtras = extras
    }
    extras[key] = if (extras.containsKey(key) && accumulate) "${extras[key]},$value" else value
  }

  fun addExtraData(key: String, value: String, accumulate: Boolean) {
    val extras = extraData ?: HashMap()
    if (extraData == null) {
      extraData = extras
    }
    extras[key] = if (extras.containsKey(key) && accumulate) "${extras[key]},$value" else value
  }

  fun tryCurrentHandler(): Boolean {
    val handler = getCurrentHandler() ?: return false
    if (handler.needsInternetPermission() && !checkInternetPermission()) {
      addLoggingExtra(
          LoginLogger.EVENT_EXTRAS_MISSING_INTERNET_PERMISSION,
          AppEventsConstants.EVENT_PARAM_VALUE_YES,
          false)
      return false
    }
    val pendingRequest = pendingRequest ?: return false
    val numTried = handler.tryAuthorize(pendingRequest)
    numActivitiesReturned = 0
    if (numTried > 0) {
      logger.logAuthorizationMethodStart(
          pendingRequest.authId,
          handler.nameForLogging,
          if (pendingRequest.isFamilyLogin) LoginLogger.EVENT_NAME_FOA_LOGIN_METHOD_START
          else LoginLogger.EVENT_NAME_LOGIN_METHOD_START,
          handler.methodStartLoggingExtras)
      numTotalIntentsFired = numTried
    } else {
      // We didn't try it, so we don't get any other completion
      // notification -- log that we skipped it.
      logger.logAuthorizationMethodNotTried(
          pendingRequest.authId,
          handler.nameForLogging,
          if (pendingRequest.isFamilyLogin) LoginLogger.EVENT_NAME_FOA_LOGIN_METHOD_NOT_TRIED
          else LoginLogger.EVENT_NAME_LOGIN_METHOD_NOT_TRIED)
      addLoggingExtra(LoginLogger.EVENT_EXTRAS_NOT_TRIED, handler.nameForLogging, true)
    }
    return numTried > 0
  }

  fun completeAndValidate(outcome: Result) {
    // Do we need to validate a successful result (as in the case of a reauth)?
    if (outcome.token != null && AccessToken.isCurrentAccessTokenActive()) {
      validateSameFbidAndFinish(outcome)
    } else {
      // We're done, just notify the listener.
      complete(outcome)
    }
  }

  fun complete(outcome: Result) {
    val handler = getCurrentHandler()

    // This might be null if, for some reason, none of the handlers were successfully tried
    // (in which case we already logged that).
    if (handler != null) {
      logAuthorizationMethodComplete(handler.nameForLogging, outcome, handler.methodLoggingExtras)
    }
    if (loggingExtras != null) {
      // Pass this back to the caller for logging at the aggregate level.
      outcome.loggingExtras = loggingExtras
    }
    if (extraData != null) {
      outcome.extraData = extraData
    }
    handlersToTry = null
    currentHandler = -1
    pendingRequest = null
    loggingExtras = null
    numActivitiesReturned = 0
    numTotalIntentsFired = 0
    notifyOnCompleteListener(outcome)
  }

  fun checkPermission(permission: String): Int {
    return activity?.checkCallingOrSelfPermission(permission) ?: PackageManager.PERMISSION_DENIED
  }

  fun validateSameFbidAndFinish(pendingResult: Result) {
    if (pendingResult.token == null) {
      throw FacebookException("Can't validate without a token")
    }
    val previousToken = AccessToken.getCurrentAccessToken()
    val newToken = pendingResult.token
    try {
      val result: Result
      if (previousToken != null && previousToken.userId == newToken.userId) {
        result =
            Result.createCompositeTokenResult(
                pendingRequest, pendingResult.token, pendingResult.authenticationToken)
      } else {
        result =
            Result.createErrorResult(
                pendingRequest, "User logged in as different Facebook user.", null)
      }
      complete(result)
    } catch (ex: Exception) {
      complete(Result.createErrorResult(pendingRequest, "Caught exception", ex.message))
    }
  }

  private val logger: LoginLogger
    get() {
      var loggerToReturn = loginLogger
      if (loggerToReturn == null || loggerToReturn.applicationId != pendingRequest?.applicationId) {
        loggerToReturn =
            LoginLogger(
                activity ?: FacebookSdk.getApplicationContext(),
                pendingRequest?.applicationId ?: FacebookSdk.getApplicationId())
        loginLogger = loggerToReturn
      }
      return loggerToReturn
    }

  private fun notifyOnCompleteListener(outcome: Result) {
    onCompletedListener?.onCompleted(outcome)
  }

  fun notifyBackgroundProcessingStart() {
    backgroundProcessingListener?.onBackgroundProcessingStarted()
  }

  fun notifyBackgroundProcessingStop() {
    backgroundProcessingListener?.onBackgroundProcessingStopped()
  }

  private fun logAuthorizationMethodComplete(
      method: String,
      result: Result,
      loggingExtras: Map<String?, String?>?
  ) {
    logAuthorizationMethodComplete(
        method, result.code.loggingValue, result.errorMessage, result.errorCode, loggingExtras)
  }

  private fun logAuthorizationMethodComplete(
      method: String,
      result: String,
      errorMessage: String?,
      errorCode: String?,
      loggingExtras: Map<String?, String?>?
  ) {
    val pendingRequest = pendingRequest
    if (pendingRequest == null) {
      // We don't expect this to happen, but if it does, log an event for diagnostic purposes.
      logger.logUnexpectedError(
          LoginLogger.EVENT_NAME_LOGIN_METHOD_COMPLETE,
          "Unexpected call to logCompleteLogin with null pendingAuthorizationRequest.",
          method)
    } else {
      logger.logAuthorizationMethodComplete(
          pendingRequest.authId,
          method,
          result,
          errorMessage,
          errorCode,
          loggingExtras,
          if (pendingRequest.isFamilyLogin) LoginLogger.EVENT_NAME_FOA_LOGIN_METHOD_COMPLETE
          else LoginLogger.EVENT_NAME_LOGIN_METHOD_COMPLETE)
    }
  }

  class Request : Parcelable {
    val loginBehavior: LoginBehavior
    var permissions: Set<String>
    val defaultAudience: DefaultAudience
    val applicationId: String
    var redirectURI: String? = null
    var intentUriPackageTarget: String? = null
    var authId: String
    var isRerequest = false
    var deviceRedirectUriString: String? = null
    var authType: String
    var deviceAuthTargetUserId: String? = null // used to target a specific user with device login

    var messengerPageId: String? = null
    var resetMessengerState = false
    val loginTargetApp: LoginTargetApp
    var isFamilyLogin = false
    private var shouldSkipAccountDeduplication = false
    var forceConfirmation = false
    var androidSsoContext: String? = null
    // The Android SDK LVA client arm, assigned once when the Custom Tab attempt starts.
    var androidLvaClientArm: String? = null
    val nonce: String
    val codeVerifier: String?
    val codeChallenge: String?
    val codeChallengeMethod: CodeChallengeMethod?

    @JvmOverloads
    internal constructor(
        loginBehavior: LoginBehavior,
        permissions: Set<String>?,
        defaultAudience: DefaultAudience,
        authType: String,
        applicationId: String,
        authId: String,
        targetApp: LoginTargetApp? = LoginTargetApp.FACEBOOK,
        nonce: String? = null,
        codeVerifier: String? = null,
        codeChallenge: String? = null,
        codeChallengeMethod: CodeChallengeMethod? = null,
        redirectURI: String? = null,
        intentUriPackageTarget: String? = null
    ) {
      this.loginBehavior = loginBehavior
      this.permissions = permissions ?: HashSet()
      this.defaultAudience = defaultAudience
      this.authType = authType
      this.applicationId = applicationId
      this.redirectURI = redirectURI
      this.intentUriPackageTarget = intentUriPackageTarget
      this.authId = authId
      loginTargetApp = targetApp ?: LoginTargetApp.FACEBOOK
      if (nonce == null || nonce.isEmpty()) {
        this.nonce = UUID.randomUUID().toString()
      } else {
        this.nonce = nonce
      }
      this.codeVerifier = codeVerifier
      this.codeChallenge = codeChallenge
      this.codeChallengeMethod = codeChallengeMethod
    }

    fun shouldSkipAccountDeduplication(): Boolean = shouldSkipAccountDeduplication

    fun setShouldSkipAccountDeduplication(shouldSkipAccountDeduplication: Boolean) {
      this.shouldSkipAccountDeduplication = shouldSkipAccountDeduplication
    }

    fun hasPublishPermission(): Boolean {
      for (permission in permissions) {
        if (LoginManager.isPublishPermission(permission)) {
          return true
        }
      }
      return false
    }

    val isInstagramLogin: Boolean
      get() = loginTargetApp === LoginTargetApp.INSTAGRAM

    private constructor(parcel: Parcel) {
      val loginBehaviorEnumValue = Validate.notNullOrEmpty(parcel.readString(), "loginBehavior")
      loginBehavior = LoginBehavior.valueOf(loginBehaviorEnumValue)
      val permissionsList = ArrayList<String>()
      parcel.readStringList(permissionsList)
      permissions = HashSet(permissionsList)
      val defaultAudienceEnumValue = parcel.readString()
      defaultAudience =
          if (defaultAudienceEnumValue != null) DefaultAudience.valueOf(defaultAudienceEnumValue)
          else DefaultAudience.NONE
      applicationId = Validate.notNullOrEmpty(parcel.readString(), "applicationId")
      redirectURI = parcel.readString()
      intentUriPackageTarget = parcel.readString()
      authId = Validate.notNullOrEmpty(parcel.readString(), "authId")
      isRerequest = parcel.readByte().toInt() != 0
      deviceRedirectUriString = parcel.readString()
      authType = Validate.notNullOrEmpty(parcel.readString(), "authType")
      deviceAuthTargetUserId = parcel.readString()
      messengerPageId = parcel.readString()
      resetMessengerState = parcel.readByte().toInt() != 0
      val loginTargetAppEnumValue = parcel.readString()
      loginTargetApp =
          if (loginTargetAppEnumValue != null) LoginTargetApp.valueOf(loginTargetAppEnumValue)
          else LoginTargetApp.FACEBOOK
      isFamilyLogin = parcel.readByte().toInt() != 0
      shouldSkipAccountDeduplication = parcel.readByte().toInt() != 0
      forceConfirmation = parcel.readByte().toInt() != 0
      androidSsoContext = parcel.readString()
      nonce = Validate.notNullOrEmpty(parcel.readString(), "nonce")
      codeVerifier = parcel.readString()
      codeChallenge = parcel.readString()
      val codeChallengeMethodEnumVal = parcel.readString()
      codeChallengeMethod = codeChallengeMethodEnumVal?.let { CodeChallengeMethod.valueOf(it) }
      androidLvaClientArm = parcel.readString()
    }

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
      dest.writeString(loginBehavior.name)
      dest.writeStringList(ArrayList(permissions))
      dest.writeString(defaultAudience.name)
      dest.writeString(applicationId)
      dest.writeString(redirectURI)
      dest.writeString(intentUriPackageTarget)
      dest.writeString(authId)
      dest.writeByte((if (isRerequest) 1 else 0).toByte())
      dest.writeString(deviceRedirectUriString)
      dest.writeString(authType)
      dest.writeString(deviceAuthTargetUserId)
      dest.writeString(messengerPageId)
      dest.writeByte((if (resetMessengerState) 1 else 0).toByte())
      dest.writeString(loginTargetApp.name)
      dest.writeByte((if (isFamilyLogin) 1 else 0).toByte())
      dest.writeByte((if (shouldSkipAccountDeduplication) 1 else 0).toByte())
      dest.writeByte((if (forceConfirmation) 1 else 0).toByte())
      dest.writeString(androidSsoContext)
      dest.writeString(nonce)
      dest.writeString(codeVerifier)
      dest.writeString(codeChallenge)
      dest.writeString(codeChallengeMethod?.name)
      dest.writeString(androidLvaClientArm)
    }

    companion object {
      @JvmField
      val CREATOR: Parcelable.Creator<Request> =
          object : Parcelable.Creator<Request> {
            override fun createFromParcel(source: Parcel): Request {
              return Request(source)
            }

            override fun newArray(size: Int): Array<Request?> {
              return arrayOfNulls(size)
            }
          }
    }
  }

  class Result : Parcelable {
    enum class Code(
        // For consistency across platforms, we want to use specific string values when logging
        // these results.
        val loggingValue: String
    ) {
      SUCCESS("success"),
      CANCEL("cancel"),
      ERROR("error")
    }
    @JvmField val code: Code
    @JvmField val token: AccessToken?
    @JvmField val authenticationToken: AuthenticationToken?
    @JvmField val errorMessage: String?
    @JvmField val errorCode: String?
    @JvmField val request: Request?
    @JvmField var loggingExtras: Map<String, String>? = null
    @JvmField var extraData: Map<String, String>? = null

    internal constructor(
        request: Request?,
        code: Code,
        token: AccessToken?,
        errorMessage: String?,
        errorCode: String?
    ) : this(request, code, token, null, errorMessage, errorCode) {}

    internal constructor(
        request: Request?,
        code: Code,
        accessToken: AccessToken?,
        authenticationToken: AuthenticationToken?,
        errorMessage: String?,
        errorCode: String?
    ) {
      this.request = request
      token = accessToken
      this.authenticationToken = authenticationToken
      this.errorMessage = errorMessage
      this.code = code
      this.errorCode = errorCode
    }

    @Suppress("DEPRECATION")
    private constructor(parcel: Parcel) {
      this.code = Code.valueOf(parcel.readString() ?: "error")
      token = parcel.readParcelable(AccessToken::class.java.classLoader)
      authenticationToken = parcel.readParcelable(AuthenticationToken::class.java.classLoader)
      errorMessage = parcel.readString()
      errorCode = parcel.readString()
      request = parcel.readParcelable(Request::class.java.classLoader)
      loggingExtras = readNonnullStringMapFromParcel(parcel)
      extraData = readNonnullStringMapFromParcel(parcel)
    }

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
      dest.writeString(code.name)
      dest.writeParcelable(token, flags)
      dest.writeParcelable(authenticationToken, flags)
      dest.writeString(errorMessage)
      dest.writeString(errorCode)
      dest.writeParcelable(request, flags)
      writeNonnullStringMapToParcel(dest, loggingExtras)
      writeNonnullStringMapToParcel(dest, extraData)
    }

    companion object {
      @JvmStatic
      fun createTokenResult(request: Request?, token: AccessToken): Result {
        return Result(request, Code.SUCCESS, token, null, null)
      }

      @JvmStatic
      fun createCompositeTokenResult(
          request: Request?,
          accessToken: AccessToken?,
          authenticationToken: AuthenticationToken?
      ): Result {
        return Result(request, Code.SUCCESS, accessToken, authenticationToken, null, null)
      }

      @JvmStatic
      fun createCancelResult(request: Request?, message: String?): Result {
        return Result(request, Code.CANCEL, null, message, null)
      }

      @JvmStatic
      @JvmOverloads
      fun createErrorResult(
          request: Request?,
          errorType: String?,
          errorDescription: String?,
          errorCode: String? = null
      ): Result {
        val messageParts = ArrayList<String?>()
        if (errorType != null) {
          messageParts.add(errorType)
        }
        if (errorDescription != null) {
          messageParts.add(errorDescription)
        }
        val message = TextUtils.join(": ", messageParts)
        return Result(request, Code.ERROR, null, message, errorCode)
      }

      @JvmField
      val CREATOR: Parcelable.Creator<Result> =
          object : Parcelable.Creator<Result> {
            override fun createFromParcel(source: Parcel): Result {
              return Result(source)
            }

            override fun newArray(size: Int): Array<Result?> {
              return arrayOfNulls(size)
            }
          }
    }
  }

  // Parcelable implementation
  @Suppress("DEPRECATION")
  constructor(source: Parcel) {
    val o: Array<Parcelable> =
        source.readParcelableArray(LoginMethodHandler::class.java.classLoader) ?: emptyArray()
    handlersToTry =
        o.mapNotNull {
              val handler = it as? LoginMethodHandler
              handler?.loginClient = this@LoginClient
              handler
            }
            .toTypedArray()
    currentHandler = source.readInt()
    pendingRequest = source.readParcelable(Request::class.java.classLoader)
    loggingExtras = readNonnullStringMapFromParcel(source)?.toMutableMap()
    extraData = readNonnullStringMapFromParcel(source)?.toMutableMap()
  }

  override fun describeContents(): Int = 0

  override fun writeToParcel(dest: Parcel, flags: Int) {
    dest.writeParcelableArray(handlersToTry, flags)
    dest.writeInt(currentHandler)
    dest.writeParcelable(pendingRequest, flags)
    writeNonnullStringMapToParcel(dest, loggingExtras)
    writeNonnullStringMapToParcel(dest, extraData)
  }

  companion object {
    private val TAG = LoginClient::class.java.simpleName

    @JvmStatic
    fun getLoginRequestCode(): Int = CallbackManagerImpl.RequestCodeOffset.Login.toRequestCode()

    @JvmStatic
    fun getE2E(): String {
      val e2e = JSONObject()
      try {
        e2e.put("init", System.currentTimeMillis())
      } catch (e: JSONException) {
        logd(TAG, "Failed to create e2e JSON", e)
      }
      return e2e.toString()
    }

    @JvmField
    val CREATOR: Parcelable.Creator<LoginClient> =
        object : Parcelable.Creator<LoginClient> {
          override fun createFromParcel(source: Parcel): LoginClient {
            return LoginClient(source)
          }

          override fun newArray(size: Int): Array<LoginClient?> {
            return arrayOfNulls(size)
          }
        }
  }
}
