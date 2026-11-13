/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.login

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.util.Pair
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.annotation.RestrictTo
import androidx.annotation.VisibleForTesting
import androidx.browser.customtabs.CustomTabsClient
import androidx.fragment.app.Fragment
import com.facebook.AccessToken
import com.facebook.AuthenticationToken
import com.facebook.CallbackManager
import com.facebook.FacebookActivity
import com.facebook.FacebookAuthorizationException
import com.facebook.FacebookCallback
import com.facebook.FacebookException
import com.facebook.FacebookSdk
import com.facebook.FacebookSdk.getApplicationContext
import com.facebook.FacebookSdk.getApplicationId
import com.facebook.FacebookSdk.getRedirectURI
import com.facebook.FacebookSdk.getIntentUriPackageTarget
import com.facebook.FacebookSdk.getGraphApiVersion
import com.facebook.GraphResponse
import com.facebook.LoginStatusCallback
import com.facebook.Profile.Companion.fetchProfileForCurrentAccessToken
import com.facebook.Profile.Companion.setCurrentProfile
import com.facebook.appevents.AppEventsConstants
import com.facebook.internal.CallbackManagerImpl
import com.facebook.internal.CallbackManagerImpl.Companion.registerStaticCallback
import com.facebook.internal.CustomTabUtils.getChromePackage
import com.facebook.internal.FragmentWrapper
import com.facebook.internal.NativeProtocol
import com.facebook.internal.PlatformServiceClient
import com.facebook.internal.ServerProtocol
import com.facebook.internal.Utility
import com.facebook.internal.Validate.sdkInitialized
import com.facebook.login.LoginMethodHandler.Companion.getUserIDFromSignedRequest
import com.facebook.login.PKCEUtil.generateCodeChallenge
import java.lang.Exception
import java.util.Date
import java.util.UUID
import kotlin.jvm.JvmOverloads
import kotlin.jvm.Volatile

/** This class manages login and permissions for Facebook. */
open class LoginManager() {
  /** The login behavior. */
  var loginBehavior = LoginBehavior.NATIVE_WITH_FALLBACK
    private set

  /** The default audience. */
  var defaultAudience = DefaultAudience.FRIENDS
    private set
  private val sharedPreferences: SharedPreferences

  /** The authType */
  var authType = ServerProtocol.DIALOG_REREQUEST_AUTH_TYPE
    private set
  private var messengerPageId: String? = null
  private var resetMessengerState = false

  /** The login target app. */
  var loginTargetApp = LoginTargetApp.FACEBOOK
    private set

  /**
   * Determines whether we are using the cross Family of Apps login experience. True if using cross
   * Family of Apps login
   */
  var isFamilyLogin = false
    private set

  /**
   * Determines if we should skip deduplicating account during x-FoA login. True if Account
   * deduplication is opted out in Family of Apps login
   */
  var shouldSkipAccountDeduplication = false
    private set

  /**
   * Starts the login process to resolve the error defined in the response. The registered login
   * callbacks will be called on completion.
   *
   * @param activity The activity which is starting the login process.
   * @param response The response that has the error.
   */
  fun resolveError(activity: Activity, response: GraphResponse) {
    startLogin(ActivityStartActivityDelegate(activity), createLoginRequestFromResponse(response))
  }

  /**
   * Starts the login process to resolve the error defined in the response. The registered login
   * callbacks will be called on completion.
   *
   * This method is deprecated and it's better to use the method with a CallbackManager as the
   * second parameter. The new method with the CallbackManager as a parameter will use AndroidX
   * activity result APIs so you won't need to override onActivityResult method on the fragment.
   *
   * @param fragment The fragment which is starting the login process.
   * @param response The response that has the error.
   */
  @Deprecated("")
  fun resolveError(fragment: Fragment, response: GraphResponse) {
    this.resolveError(FragmentWrapper(fragment), response)
  }

  /**
   * Starts the login process to resolve the error defined in the response. The registered login
   * callbacks will be called on completion.
   *
   * @param fragment The fragment which is starting the login process.
   * @param callbackManager The callback manager which is used to register callbacks.
   * @param response The response that has the error.
   */
  fun resolveError(fragment: Fragment, callbackManager: CallbackManager, response: GraphResponse) {
    val activity: ComponentActivity? = fragment.activity
    if (activity != null) {
      this.resolveError(activity, callbackManager, response)
    } else {
      throw FacebookException("Cannot obtain activity context on the fragment $fragment")
    }
  }

  /**
   * Starts the login process to resolve the error defined in the response. The registered login
   * callbacks will be called on completion.
   *
   * @param fragment The android.app.Fragment which is starting the login process.
   * @param response The response that has the error.
   */
  @Suppress("DEPRECATION")
  fun resolveError(fragment: android.app.Fragment, response: GraphResponse) {
    this.resolveError(FragmentWrapper(fragment), response)
  }

  /**
   * Starts the login process to resolve the error defined in the response. The registered login
   * callbacks will be called on completion.
   *
   * @param fragment The fragment which is starting the login process.
   * @param response The response that has the error.
   */
  private fun resolveError(fragment: FragmentWrapper, response: GraphResponse) {
    startLogin(FragmentStartActivityDelegate(fragment), createLoginRequestFromResponse(response))
  }

  /**
   * Starts the login process to resolve the error defined in the response. The registered login
   * callbacks will be called on completion.
   *
   * @param activityResultRegistryOwner The activity result register owner. Normally it is an
   * androidx ComponentActivity.
   * @param callbackManager The callback manager from Facebook SDK.
   * @param response The response that has the error.
   */
  fun resolveError(
      activityResultRegistryOwner: ActivityResultRegistryOwner,
      callbackManager: CallbackManager,
      response: GraphResponse
  ) {
    startLogin(
        AndroidxActivityResultRegistryOwnerStartActivityDelegate(
            activityResultRegistryOwner, callbackManager),
        createLoginRequestFromResponse(response))
  }

  private fun createLoginRequestFromResponse(response: GraphResponse): LoginClient.Request {
    val failedToken = response.request.accessToken
    return createLoginRequest(failedToken?.permissions?.filterNotNull())
  }

  /**
   * Registers a login callback to the given callback manager.
   *
   * @param callbackManager The callback manager that will encapsulate the callback.
   * @param callback The login callback that will be called on login completion.
   */
  fun registerCallback(
      callbackManager: CallbackManager?,
      callback: FacebookCallback<LoginResult>?
  ) {
    if (callbackManager !is CallbackManagerImpl) {
      throw FacebookException("Unexpected CallbackManager, please use the provided Factory.")
    }
    callbackManager.registerCallback(CallbackManagerImpl.RequestCodeOffset.Login.toRequestCode()) {
        resultCode,
        data ->
      onActivityResult(resultCode, data, callback)
    }
  }

  /**
   * Unregisters a login callback to the given callback manager.
   *
   * @param callbackManager The callback manager that will encapsulate the callback.
   */
  fun unregisterCallback(callbackManager: CallbackManager?) {
    if (callbackManager !is CallbackManagerImpl) {
      throw FacebookException("Unexpected CallbackManager, " + "please use the provided Factory.")
    }
    callbackManager.unregisterCallback(CallbackManagerImpl.RequestCodeOffset.Login.toRequestCode())
  }

  @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
  @JvmOverloads
  open fun onActivityResult(
      resultCode: Int,
      data: Intent?,
      callback: FacebookCallback<LoginResult>? = null
  ): Boolean {
    var exception: FacebookException? = null
    var accessToken: AccessToken? = null
    var authenticationToken: AuthenticationToken? = null
    var code = LoginClient.Result.Code.ERROR
    var loggingExtras: Map<String, String>? = null
    var originalRequest: LoginClient.Request? = null
    var isCanceled = false
    var androidLvaAuthId: String? = null
    if (data != null) {
      data.setExtrasClassLoader(LoginClient.Result::class.java.classLoader)
      @Suppress("DEPRECATION")
      val result: LoginClient.Result? = data.getParcelableExtra(LoginFragment.RESULT_KEY)
      if (result != null) {
        originalRequest = result.request
        code = result.code
        if (resultCode == Activity.RESULT_OK) {
          if (result.code == LoginClient.Result.Code.SUCCESS) {
            accessToken = result.token
            authenticationToken = result.authenticationToken
          } else {
            exception = FacebookAuthorizationException(result.errorMessage)
          }
        } else if (resultCode == Activity.RESULT_CANCELED) {
          isCanceled = true
        }
        loggingExtras = result.loggingExtras
      } else if (resultCode == Activity.RESULT_CANCELED) {
        // Android SDK LVA: a cancellation without a result (for example, the host app's task was
        // cleared while the login ran in Instagram or the browser) is a cancel only when it
        // identifies its own enhanced launch. Anything else keeps today's mapping.
        val launch = getAndroidLvaLaunch(data)
        if (launch != null) {
          isCanceled = true
          code = LoginClient.Result.Code.CANCEL
          loggingExtras = mapOf(AndroidLvaClientArm.LOGGING_EXTRA to launch.arm)
          androidLvaAuthId = launch.authLoggerId
          AndroidLvaAttemptRecord.clearIf(launch.state, launch.version)
        }
      }
    } else if (resultCode == Activity.RESULT_CANCELED) {
      isCanceled = true
      code = LoginClient.Result.Code.CANCEL
    }
    if (exception == null && accessToken == null && !isCanceled) {
      exception = FacebookException("Unexpected call to LoginManager.onActivityResult")
    }
    val wasLoginActivityTried = true
    val context: Context? = null // Sadly, there is no way to get activity context at this point.
    logCompleteLogin(
        context,
        code,
        loggingExtras,
        exception,
        wasLoginActivityTried,
        originalRequest,
        androidLvaAuthId)
    finishLogin(accessToken, authenticationToken, originalRequest, exception, isCanceled, callback)
    return true
  }

  /**
   * The active Android SDK LVA attempt that [data] identifies by both its state and its launch
   * nonce, or null. Identity comes only from the result itself, never from the caller.
   */
  private fun getAndroidLvaLaunch(data: Intent): AndroidLvaAttemptRecord.Record? {
    val state = data.getStringExtra(AndroidLvaAttemptRecord.RESULT_EXTRA_STATE) ?: return null
    val launchNonce =
        data.getStringExtra(AndroidLvaAttemptRecord.RESULT_EXTRA_LAUNCH_NONCE) ?: return null
    return AndroidLvaAttemptRecord.read()?.takeIf {
      it.state == state && it.launchNonce == launchNonce
    }
  }

  /**
   * Setter for the login behavior.
   *
   * @param loginBehavior The login behavior.
   * @return The login manager.
   */
  fun setLoginBehavior(loginBehavior: LoginBehavior): LoginManager {
    this.loginBehavior = loginBehavior
    return this
  }

  /**
   * Setter for the login target app.
   *
   * @param targetApp The login target app.
   * @return The login manager.
   */
  fun setLoginTargetApp(targetApp: LoginTargetApp): LoginManager {
    loginTargetApp = targetApp
    return this
  }

  /**
   * Setter for the default audience.
   *
   * @param defaultAudience The default audience.
   * @return The login manager.
   */
  fun setDefaultAudience(defaultAudience: DefaultAudience): LoginManager {
    this.defaultAudience = defaultAudience
    return this
  }

  /**
   * Setter for the authType
   *
   * @param authType The authType
   * @return The login manager.
   */
  fun setAuthType(authType: String): LoginManager {
    this.authType = authType
    return this
  }

  /**
   * Setter for the messengerPageId
   *
   * @param messengerPageId The messengerPageId
   * @return The login manager.
   */
  fun setMessengerPageId(messengerPageId: String?): LoginManager {
    this.messengerPageId = messengerPageId
    return this
  }

  /**
   * Setter for the resetMessengerState. For developers of the app only.
   *
   * @param resetMessengerState Whether to enable resetMessengerState
   * @return The login manager.
   */
  fun setResetMessengerState(resetMessengerState: Boolean): LoginManager {
    this.resetMessengerState = resetMessengerState
    return this
  }

  /**
   * Setter for whether we are using cross Family of Apps login
   *
   * @param isFamilyLogin Whether we are using cross Family of Apps login
   * @return The login manager.
   */
  fun setFamilyLogin(isFamilyLogin: Boolean): LoginManager {
    this.isFamilyLogin = isFamilyLogin
    return this
  }

  /**
   * Setter for whether we are skipping deduplicating account during x-FoA login.
   *
   * @param shouldSkipAccountDeduplication Whether we want to opt out account deduplication
   * experience in Family of Apps login
   * @return The login manager.
   */
  fun setShouldSkipAccountDeduplication(shouldSkipAccountDeduplication: Boolean): LoginManager {
    this.shouldSkipAccountDeduplication = shouldSkipAccountDeduplication
    return this
  }

  /** Logs out the user. */
  open fun logOut() {
    AccessToken.setCurrentAccessToken(null)
    AuthenticationToken.setCurrentAuthenticationToken(null)
    setCurrentProfile(null)
    setExpressLoginStatus(false)
  }

  /**
   * Retrieves the login status for the user. This will return an access token for the app if a user
   * is logged into the Facebook for Android app on the same device and that user had previously
   * logged into the app. If an access token was retrieved then a toast will be shown telling the
   * user that they have been logged in.
   *
   * @param context An Android context
   * @param responseCallback The callback to be called when the request completes
   */
  fun retrieveLoginStatus(context: Context, responseCallback: LoginStatusCallback) {
    retrieveLoginStatus(context, LoginStatusClient.DEFAULT_TOAST_DURATION_MS, responseCallback)
  }

  /**
   * Retrieves the login status for the user. This will return an access token for the app if a user
   * is logged into the Facebook for Android app on the same device and that user had previously
   * logged into the app. If an access token was retrieved then a toast will be shown telling the
   * user that they have been logged in.
   *
   * @param context An Android context
   * @param responseCallback The callback to be called when the request completes
   * @param toastDurationMs The duration to show the success toast in milliseconds
   */
  fun retrieveLoginStatus(
      context: Context,
      toastDurationMs: Long,
      responseCallback: LoginStatusCallback
  ) {
    retrieveLoginStatusImpl(context, responseCallback, toastDurationMs)
  }

  /**
   * Logs the user in with the requested read permissions.
   *
   * This method is deprecated and it's better to use the method with a CallbackManager as the
   * second parameter. The new method with the CallbackManager as a parameter will use AndroidX
   * activity result APIs so you won't need to override onActivityResult method on the fragment.
   *
   * @param fragment The androidx.fragment.Fragment which is starting the login process.
   * @param permissions The requested permissions.
   */
  @Deprecated("")
  fun logInWithReadPermissions(fragment: Fragment, permissions: Collection<String>) {
    logInWithReadPermissions(FragmentWrapper(fragment), permissions)
  }

  /**
   * Logs the user in with the requested read permissions.
   *
   * @param fragment The androidx.fragment.Fragment which is starting the login process.
   * @param callbackManager The callback manager which is used to register callbacks.
   * @param permissions The requested permissions.
   */
  fun logInWithReadPermissions(
      fragment: Fragment,
      callbackManager: CallbackManager,
      permissions: Collection<String>
  ) {
    val activity: ComponentActivity? = fragment.activity
    if (activity != null) {
      logInWithReadPermissions(activity, callbackManager, permissions)
    } else {
      throw FacebookException("Cannot obtain activity context on the fragment $fragment")
    }
  }

  /**
   * Logs the user in with the requested read permissions.
   *
   * @param fragment The android.app.Fragment which is starting the login process.
   * @param permissions The requested permissions.
   */
  @Suppress("DEPRECATION")
  fun logInWithReadPermissions(fragment: android.app.Fragment, permissions: Collection<String>) {
    logInWithReadPermissions(FragmentWrapper(fragment), permissions)
  }

  /**
   * Logs the user in with the requested read permissions.
   *
   * @param fragment The fragment which is starting the login process.
   * @param permissions The requested permissions.
   */
  private fun logInWithReadPermissions(fragment: FragmentWrapper, permissions: Collection<String>) {
    validateReadPermissions(permissions)
    val loginConfig = LoginConfiguration(permissions)
    logIn(fragment, loginConfig)
  }

  /**
   * Logs the user in with the requested read permissions.
   *
   * @param activity The activity which is starting the login process.
   * @param permissions The requested permissions.
   */
  fun logInWithReadPermissions(activity: Activity, permissions: Collection<String>?) {
    validateReadPermissions(permissions)
    val loginConfig = LoginConfiguration(permissions)
    logIn(activity, loginConfig)
  }

  /**
   * Logs the user in with the requested read permissions.
   *
   * @param activityResultRegistryOwner The activity result register owner. Normally it is an
   * androidx ComponentActivity.
   * @param callbackManager The callback manager from Facebook SDK.
   * @param permissions The requested permissions.
   */
  fun logInWithReadPermissions(
      activityResultRegistryOwner: ActivityResultRegistryOwner,
      callbackManager: CallbackManager,
      permissions: Collection<String>
  ) {
    validateReadPermissions(permissions)
    val loginConfig = LoginConfiguration(permissions)
    logIn(activityResultRegistryOwner, callbackManager, loginConfig)
  }

  /**
   * Logs the user in with the requested configuration.
   *
   * @param fragment The android.support.v4.app.Fragment which is starting the login process.
   * @param loginConfig The login configuration
   */
  fun logInWithConfiguration(fragment: Fragment, loginConfig: LoginConfiguration) {
    loginWithConfiguration(FragmentWrapper(fragment), loginConfig)
  }

  /**
   * Logs the user in with the requested configuration.
   *
   * @param fragment The fragment which is starting the login process.
   * @param loginConfig The login configuration.
   */
  private fun loginWithConfiguration(fragment: FragmentWrapper, loginConfig: LoginConfiguration) {
    logIn(fragment, loginConfig)
  }

  /**
   * Logs the user in with the requested configuration.
   *
   * @param activity The activity which is starting the login process.
   * @param loginConfig The login configuration
   */
  fun loginWithConfiguration(activity: Activity, loginConfig: LoginConfiguration) {
    logIn(activity, loginConfig)
  }

  /**
   * Reauthorize data access
   *
   * @param activity The activity which is starting the reauthorization process.
   */
  fun reauthorizeDataAccess(activity: Activity) {
    val loginRequest = createReauthorizeRequest()
    startLogin(ActivityStartActivityDelegate(activity), loginRequest)
  }

  /**
   * Reauthorize data access
   *
   * @param fragment The android.support.v4.app.Fragment starting the reauthorization process.
   */
  fun reauthorizeDataAccess(fragment: Fragment) {
    reauthorizeDataAccess(FragmentWrapper(fragment))
  }

  /**
   * Reauthorize data access
   *
   * @param fragment The fragment which is starting the reauthorization process.
   */
  private fun reauthorizeDataAccess(fragment: FragmentWrapper) {
    val loginRequest = createReauthorizeRequest()
    startLogin(FragmentStartActivityDelegate(fragment), loginRequest)
  }

  /**
   * Logs the user in with the requested publish permissions.
   *
   * This method is deprecated and it's better to use the method with a CallbackManager as the
   * second parameter. The new method with the CallbackManager as a parameter will use AndroidX
   * activity result APIs so you won't need to override onActivityResult method on the fragment.
   *
   * @param fragment The androidx.fragment.Fragment which is starting the login process.
   * @param permissions The requested permissions.
   */
  @Deprecated("")
  fun logInWithPublishPermissions(fragment: Fragment, permissions: Collection<String>) {
    logInWithPublishPermissions(FragmentWrapper(fragment), permissions)
  }

  /**
   * Logs the user in with the requested publish permissions.
   *
   * @param fragment The androidx.fragment.Fragment which is starting the login process.
   * @param callbackManager The callback manager which is used to register callbacks.
   * @param permissions The requested permissions.
   */
  fun logInWithPublishPermissions(
      fragment: Fragment,
      callbackManager: CallbackManager,
      permissions: Collection<String>
  ) {
    val activity: ComponentActivity? = fragment.activity
    if (activity != null) {
      logInWithPublishPermissions(activity, callbackManager, permissions)
    } else {
      throw FacebookException("Cannot obtain activity context on the fragment $fragment")
    }
  }

  /**
   * Logs the user in with the requested publish permissions.
   *
   * @param fragment The android.app.Fragment which is starting the login process.
   * @param permissions The requested permissions.
   */
  @Suppress("DEPRECATION")
  fun logInWithPublishPermissions(fragment: android.app.Fragment, permissions: Collection<String>) {
    logInWithPublishPermissions(FragmentWrapper(fragment), permissions)
  }

  /**
   * Logs the user in with the requested publish permissions.
   *
   * @param fragment The fragment which is starting the login process.
   * @param permissions The requested permissions.
   */
  private fun logInWithPublishPermissions(
      fragment: FragmentWrapper,
      permissions: Collection<String>
  ) {
    validatePublishPermissions(permissions)
    val loginConfig = LoginConfiguration(permissions)
    loginWithConfiguration(fragment, loginConfig)
  }

  /**
   * Logs the user in with the requested publish permissions.
   *
   * @param activity The activity which is starting the login process.
   * @param permissions The requested permissions.
   */
  fun logInWithPublishPermissions(activity: Activity, permissions: Collection<String>?) {
    validatePublishPermissions(permissions)
    val loginConfig = LoginConfiguration(permissions)
    loginWithConfiguration(activity, loginConfig)
  }

  /**
   * Logs the user in with the requested read permissions.
   *
   * @param activityResultRegistryOwner The activity result register owner. Normally it is an
   * androidx ComponentActivity.
   * @param callbackManager The callback manager from Facebook SDK.
   * @param permissions The requested permissions.
   */
  fun logInWithPublishPermissions(
      activityResultRegistryOwner: ActivityResultRegistryOwner,
      callbackManager: CallbackManager,
      permissions: Collection<String>
  ) {
    validatePublishPermissions(permissions)
    val loginConfig = LoginConfiguration(permissions)
    logIn(activityResultRegistryOwner, callbackManager, loginConfig)
  }

  /**
   * Logs the user in with the requested permissions.
   *
   * @param fragment The android.support.v4.app.Fragment which is starting the login process.
   * @param permissions The requested permissions.
   */
  fun logIn(fragment: Fragment, permissions: Collection<String>?) {
    logIn(FragmentWrapper(fragment), permissions)
  }

  /**
   * Logs the user in with the requested permissions.
   *
   * @param fragment The android.support.v4.app.Fragment which is starting the login process.
   * @param permissions The requested permissions.
   * @param loggerID Override the default logger ID for the request
   */
  fun logIn(fragment: Fragment, permissions: Collection<String>?, loggerID: String?) {
    logIn(FragmentWrapper(fragment), permissions, loggerID)
  }

  /**
   * Logs the user in with the requested permissions.
   *
   * @param fragment The android.app.Fragment which is starting the login process.
   * @param permissions The requested permissions.
   */
  @Suppress("DEPRECATION")
  fun logIn(fragment: android.app.Fragment, permissions: Collection<String>?) {
    logIn(FragmentWrapper(fragment), permissions)
  }

  /**
   * Logs the user in with the requested permissions.
   *
   * @param fragment The android.app.Fragment which is starting the login process.
   * @param permissions The requested permissions.
   * @param loggerID Override the default logger ID for the request
   */
  @Suppress("DEPRECATION")
  fun logIn(fragment: android.app.Fragment, permissions: Collection<String>?, loggerID: String?) {
    logIn(FragmentWrapper(fragment), permissions, loggerID)
  }

  /**
   * Logs the user in with the requested permissions.
   *
   * @param fragment The fragment which is starting the login process.
   * @param permissions The requested permissions.
   */
  fun logIn(fragment: FragmentWrapper, permissions: Collection<String>?) {
    val loginConfig = LoginConfiguration(permissions)
    logIn(fragment, loginConfig)
  }

  /**
   * Logs the user in with the requested permissions.
   *
   * @param fragment The fragment which is starting the login process.
   * @param permissions The requested permissions.
   * @param loggerID Override the default logger ID for the request
   */
  fun logIn(fragment: FragmentWrapper, permissions: Collection<String>?, loggerID: String?) {
    val loginConfig = LoginConfiguration(permissions)
    val loginRequest = createLoginRequestWithConfig(loginConfig)
    if (loggerID != null) {
      loginRequest.authId = loggerID
    }
    startLogin(FragmentStartActivityDelegate(fragment), loginRequest)
  }

  /**
   * Logs the user in with the requested permissions.
   *
   * @param activity The activity which is starting the login process.
   * @param permissions The requested permissions.
   */
  fun logIn(activity: Activity, permissions: Collection<String>?) {
    val loginConfig = LoginConfiguration(permissions)
    logIn(activity, loginConfig)
  }

  /**
   * Logs the user in with the requested login configuration.
   *
   * @param fragment The fragment which is starting the login process.
   * @param loginConfig The login config of the request
   */
  fun logIn(fragment: FragmentWrapper, loginConfig: LoginConfiguration) {
    val loginRequest = createLoginRequestWithConfig(loginConfig)
    startLogin(FragmentStartActivityDelegate(fragment), loginRequest)
  }

  /**
   * Logs the user in with the requested configuration.
   *
   * @param activity The activity which is starting the login process.
   * @param loginConfig The login config of the request
   */
  fun logIn(activity: Activity, loginConfig: LoginConfiguration) {
    if (activity is ActivityResultRegistryOwner) {
      Log.w(
          TAG,
          "You're calling logging in Facebook with an activity supports androidx activity result APIs. Please follow our document to upgrade to new APIs to avoid overriding onActivityResult().")
    }
    val loginRequest = createLoginRequestWithConfig(loginConfig)
    startLogin(ActivityStartActivityDelegate(activity), loginRequest)
  }

  /**
   * Logs the user in with the requested permissions.
   *
   * @param activity The activity which is starting the login process.
   * @param permissions The requested permissions.
   * @param loggerID Override the default logger ID for the request
   */
  fun logIn(activity: Activity, permissions: Collection<String>?, loggerID: String?) {
    val loginConfig = LoginConfiguration(permissions)
    val loginRequest = createLoginRequestWithConfig(loginConfig)
    if (loggerID != null) {
      loginRequest.authId = loggerID
    }
    startLogin(ActivityStartActivityDelegate(activity), loginRequest)
  }

  /**
   * Logs the user in with the requested configuration. This method is specialized for using
   * androidx activity results APIs.
   *
   * @param activityResultRegistryOwner The activity result register owner. Normally it is an
   * androidx ComponentActivity.
   * @param callbackManager The callback manager from Facebook SDK.
   * @param loginConfig The login config of the request.
   */
  private fun logIn(
      activityResultRegistryOwner: ActivityResultRegistryOwner,
      callbackManager: CallbackManager,
      loginConfig: LoginConfiguration
  ) {
    val loginRequest = createLoginRequestWithConfig(loginConfig)
    startLogin(
        AndroidxActivityResultRegistryOwnerStartActivityDelegate(
            activityResultRegistryOwner, callbackManager),
        loginRequest)
  }

  /**
   * Logs the user in with the requested permissions.
   *
   * @param activityResultRegistryOwner The activity result register owner. Normally it is an
   * androidx ComponentActivity.
   * @param callbackManager The callback manager from Facebook SDK.
   * @param permissions The requested permissions.
   * @param loggerID Override the default logger ID for the request
   */
  fun logIn(
      activityResultRegistryOwner: ActivityResultRegistryOwner,
      callbackManager: CallbackManager,
      permissions: Collection<String>,
      loggerID: String?
  ) {
    val loginConfig = LoginConfiguration(permissions)
    val loginRequest = createLoginRequestWithConfig(loginConfig)
    if (loggerID != null) {
      loginRequest.authId = loggerID
    }
    startLogin(
        AndroidxActivityResultRegistryOwnerStartActivityDelegate(
            activityResultRegistryOwner, callbackManager),
        loginRequest)
  }

  /**
   * Logs the user in with the requested permissions.
   *
   * @param activityResultRegistryOwner The activity result register owner. Normally it is an
   * androidx ComponentActivity.
   * @param callbackManager The callback manager from Facebook SDK.
   * @param permissions The requested permissions.
   */
  fun logIn(
      activityResultRegistryOwner: ActivityResultRegistryOwner,
      callbackManager: CallbackManager,
      permissions: Collection<String>
  ) {
    val loginConfig = LoginConfiguration(permissions)
    logIn(activityResultRegistryOwner, callbackManager, loginConfig)
  }

  /**
   * The ActivityResultContract object for login. This contract can be used as the parameter for
   * `registerForActivityResult`.
   *
   * @param callbackManager the callback manager to register login callbacks.
   * @param loggerID the logger Id for the login request.
   */
  inner class FacebookLoginActivityResultContract(
      var callbackManager: CallbackManager? = null,
      var loggerID: String? = null
  ) : ActivityResultContract<Collection<String>, CallbackManager.ActivityResultParameters>() {
    override fun createIntent(context: Context, permissions: Collection<String>): Intent {
      val loginConfig = LoginConfiguration(permissions)
      val loginRequest = createLoginRequestWithConfig(loginConfig)
      loggerID?.let { loginRequest.authId = it }
      logStartLogin(context, loginRequest)
      val intent = getFacebookActivityIntent(loginRequest)
      if (!resolveIntent(intent)) {
        val exception =
            FacebookException(
                "Log in attempt failed: FacebookActivity could not be started." +
                    " Please make sure you added FacebookActivity to the AndroidManifest.")
        logCompleteLogin(
            context, LoginClient.Result.Code.ERROR, null, exception, false, loginRequest)
        throw exception
      }
      return intent
    }

    override fun parseResult(
        resultCode: Int,
        intent: Intent?
    ): CallbackManager.ActivityResultParameters {
      this@LoginManager.onActivityResult(resultCode, intent)
      val requestCode = CallbackManagerImpl.RequestCodeOffset.Login.toRequestCode()
      callbackManager?.onActivityResult(
          requestCode,
          resultCode,
          intent,
      )
      return CallbackManager.ActivityResultParameters(requestCode, resultCode, intent)
    }
  }

  /**
   * Create the ActivityResultContract object for login. This contract can be used as the parameter
   * for `registerForActivityResult`.
   *
   * For example, in an AndroidX activity/fragment:
   * ```
   *   // onCreate
   *   val launcher = registerForActivityResult(
   *     loginManager.createLogInActivityResultContract(callbackManager)) {...}
   *   // when user click the login button
   *   launcher.launch(permissions)
   * ```
   *
   * In Jetpack Compose, we can use
   * ```
   * rememberLauncherForActivityResult(
   *   contract = loginManager.createLogInActivityResultContract(callbackManager, null),
   *   onResult = {...}
   * )
   * ```
   * to create the launcher
   *
   * @param callbackManager the callback manager to register login callbacks.
   * @param loggerID the logger Id for the login request.
   */
  @JvmOverloads
  fun createLogInActivityResultContract(
      callbackManager: CallbackManager? = null,
      loggerID: String? = null
  ): FacebookLoginActivityResultContract {
    return FacebookLoginActivityResultContract(callbackManager, loggerID)
  }

  private fun validateReadPermissions(permissions: Collection<String>?) {
    if (permissions == null) {
      return
    }
    for (permission in permissions) {
      if (isPublishPermission(permission)) {
        throw FacebookException(
            "Cannot pass a publish or manage permission ($permission) to a request for read authorization",
        )
      }
    }
  }

  private fun validatePublishPermissions(permissions: Collection<String>?) {
    if (permissions == null) {
      return
    }
    for (permission in permissions) {
      if (!isPublishPermission(permission)) {
        throw FacebookException(
            "Cannot pass a read permission ($permission) to a request for publish authorization")
      }
    }
  }

  protected open fun createLoginRequestWithConfig(
      loginConfig: LoginConfiguration
  ): LoginClient.Request {
    // init the PKCE vars (code challenge and challenge method)
    var codeChallenge: String?
    var codeChallengeMethod = CodeChallengeMethod.S256
    try {
      codeChallenge = generateCodeChallenge(loginConfig.codeVerifier, codeChallengeMethod)
    } catch (_ex: FacebookException) {
      // fallback to 'plain' if device cannot support S256 for some reason
      codeChallengeMethod = CodeChallengeMethod.PLAIN
      codeChallenge = loginConfig.codeVerifier
    }
    val request =
        LoginClient.Request(
            loginBehavior,
            loginConfig.permissions.toSet(),
            defaultAudience,
            authType,
            getApplicationId(),
            UUID.randomUUID().toString(),
            loginTargetApp,
            loginConfig.nonce,
            loginConfig.codeVerifier,
            codeChallenge,
            codeChallengeMethod,
            getRedirectURI(),
            getIntentUriPackageTarget())
    request.isRerequest = AccessToken.isCurrentAccessTokenActive()
    request.messengerPageId = messengerPageId
    request.resetMessengerState = resetMessengerState
    request.isFamilyLogin = isFamilyLogin
    request.setShouldSkipAccountDeduplication(shouldSkipAccountDeduplication)
    FBLoginSSOLauncher.pendingSsoContext?.let {
      request.androidSsoContext = it
      FBLoginSSOLauncher.pendingSsoContext = null
    }
    return request
  }

  /**
   * Starts a login flow with force_confirmation=true. Used by [FBLoginSSOLauncher] for SSO
   * fallback paths where the user needs to explicitly confirm permissions.
   */
  internal fun startLoginWithForceConfirmation(
      activity: Activity,
      permissions: Collection<String>,
      androidSsoContext: String? = null,
      authId: String? = null
  ) {
    val loginConfig = LoginConfiguration(permissions)
    val request = createLoginRequestWithConfig(loginConfig)
    request.forceConfirmation = true
    if (androidSsoContext != null) {
      request.androidSsoContext = androidSsoContext
    }
    if (authId != null) {
      request.authId = authId
    }
    startLogin(ActivityStartActivityDelegate(activity), request)
  }

  protected open fun createLoginRequest(permissions: Collection<String>?): LoginClient.Request {
    val request =
        LoginClient.Request(
            loginBehavior,
            permissions?.toSet(),
            defaultAudience,
            authType,
            getApplicationId(),
            UUID.randomUUID().toString(),
            loginTargetApp,
            redirectURI = getRedirectURI().toString(),
            intentUriPackageTarget = getIntentUriPackageTarget())
    request.isRerequest = AccessToken.isCurrentAccessTokenActive()
    request.messengerPageId = messengerPageId
    request.resetMessengerState = resetMessengerState
    request.isFamilyLogin = isFamilyLogin
    request.setShouldSkipAccountDeduplication(shouldSkipAccountDeduplication)
    FBLoginSSOLauncher.pendingSsoContext?.let {
      request.androidSsoContext = it
      FBLoginSSOLauncher.pendingSsoContext = null
    }
    return request
  }

  protected open fun createReauthorizeRequest(): LoginClient.Request {
    val request =
        LoginClient.Request(
            LoginBehavior.DIALOG_ONLY,
            HashSet(),
            defaultAudience,
            "reauthorize",
            getApplicationId(),
            UUID.randomUUID().toString(),
            loginTargetApp,
            redirectURI = getRedirectURI().toString(),
            intentUriPackageTarget = getIntentUriPackageTarget())
    request.isFamilyLogin = isFamilyLogin
    request.setShouldSkipAccountDeduplication(shouldSkipAccountDeduplication)
    return request
  }

  @Throws(FacebookException::class)
  private fun startLogin(
      startActivityDelegate: StartActivityDelegate,
      request: LoginClient.Request
  ) {
    logStartLogin(startActivityDelegate.activityContext, request)
    Utility.logd(TAG, "auth_logger_id: ${request.authId}")

    // Make sure the static handler for login is registered if there isn't an explicit callback
    registerStaticCallback(CallbackManagerImpl.RequestCodeOffset.Login.toRequestCode()) {
        resultCode,
        data ->
      onActivityResult(resultCode, data)
    }
    val started = tryFacebookActivity(startActivityDelegate, request)
    if (!started) {
      val exception =
          FacebookException(
              "Log in attempt failed: FacebookActivity could not be started." +
                  " Please make sure you added FacebookActivity to the AndroidManifest.")
      val wasLoginActivityTried = false
      logCompleteLogin(
          startActivityDelegate.activityContext,
          LoginClient.Result.Code.ERROR,
          null,
          exception,
          wasLoginActivityTried,
          request)
      throw exception
    }
  }

  private fun logStartLogin(context: Context?, loginRequest: LoginClient.Request?) {
    val loginLogger = LoginLoggerHolder.getLogger(context)
    if (loginLogger != null && loginRequest != null) {
      loginLogger.logStartLogin(
          loginRequest,
          if (loginRequest.isFamilyLogin) LoginLogger.EVENT_NAME_FOA_LOGIN_START
          else LoginLogger.EVENT_NAME_LOGIN_START)
    }
  }

  private fun logCompleteLogin(
      context: Context?,
      result: LoginClient.Result.Code,
      resultExtras: Map<String, String>?,
      exception: Exception?,
      wasLoginActivityTried: Boolean,
      request: LoginClient.Request?,
      // The logger ID of an Android SDK LVA launch identified without a request.
      androidLvaAuthId: String? = null
  ) {
    val loginLogger = LoginLoggerHolder.getLogger(context) ?: return
    if (request == null && androidLvaAuthId != null) {
      loginLogger.logCompleteLogin(
          androidLvaAuthId,
          hashMapOf(
              LoginLogger.EVENT_EXTRAS_TRY_LOGIN_ACTIVITY to
                  if (wasLoginActivityTried) AppEventsConstants.EVENT_PARAM_VALUE_YES
                  else AppEventsConstants.EVENT_PARAM_VALUE_NO,
              LoginLogger.EVENT_EXTRAS_FROM_SSO to "false"),
          result,
          resultExtras,
          exception)
    } else if (request == null) {
      // We don't expect this to happen, but if it does, log an event for diagnostic purposes.
      loginLogger.logUnexpectedError(
          LoginLogger.EVENT_NAME_LOGIN_COMPLETE,
          "Unexpected call to logCompleteLogin with null pendingAuthorizationRequest.")
    } else {
      val pendingLoggingExtras = HashMap<String, String>()
      pendingLoggingExtras[LoginLogger.EVENT_EXTRAS_TRY_LOGIN_ACTIVITY] =
          if (wasLoginActivityTried) AppEventsConstants.EVENT_PARAM_VALUE_YES
          else AppEventsConstants.EVENT_PARAM_VALUE_NO
      pendingLoggingExtras[LoginLogger.EVENT_EXTRAS_FROM_SSO] = "false"
      loginLogger.logCompleteLogin(
          request.authId,
          pendingLoggingExtras,
          result,
          resultExtras,
          exception,
          if (request.isFamilyLogin) LoginLogger.EVENT_NAME_FOA_LOGIN_COMPLETE
          else LoginLogger.EVENT_NAME_LOGIN_COMPLETE)
    }
  }

  private fun tryFacebookActivity(
      startActivityDelegate: StartActivityDelegate,
      request: LoginClient.Request
  ): Boolean {
    val intent = getFacebookActivityIntent(request)
    if (!resolveIntent(intent)) {
      return false
    }
    try {
      startActivityDelegate.startActivityForResult(intent, LoginClient.getLoginRequestCode())
    } catch (e: ActivityNotFoundException) {
      // cannot find the correct activity so the request fails
      return false
    }
    return true
  }

  private fun resolveIntent(intent: Intent): Boolean {
    val resolveInfo = getApplicationContext().packageManager.resolveActivity(intent, 0)
    return resolveInfo != null
  }

  protected open fun getFacebookActivityIntent(request: LoginClient.Request): Intent {
    val intent = Intent()
    intent.setClass(getApplicationContext(), FacebookActivity::class.java)
    intent.action = request.loginBehavior.toString()

    // Let FacebookActivity populate extras appropriately
    val extras = Bundle()
    extras.putParcelable(LoginFragment.EXTRA_REQUEST, request)
    intent.putExtra(LoginFragment.REQUEST_KEY, extras)
    return intent
  }

  private fun finishLogin(
      newToken: AccessToken?,
      newIdToken: AuthenticationToken?,
      origRequest: LoginClient.Request?,
      exception: FacebookException?,
      isCanceled: Boolean,
      callback: FacebookCallback<LoginResult>?
  ) {
    if (newToken != null) {
      AccessToken.setCurrentAccessToken(newToken)
      fetchProfileForCurrentAccessToken()
    }
    if (newIdToken != null) {
      AuthenticationToken.setCurrentAuthenticationToken(newIdToken)
    }
    if (callback != null) {
      val loginResult =
          if (newToken != null && origRequest != null)
              computeLoginResult(origRequest, newToken, newIdToken)
          else null
      // If there are no granted permissions, the operation is treated as cancel.
      if (isCanceled || (loginResult != null && loginResult.recentlyGrantedPermissions.isEmpty())) {
        callback.onCancel()
      } else if (exception != null) {
        callback.onError(exception)
      } else if (newToken != null && loginResult != null) {
        setExpressLoginStatus(true)
        callback.onSuccess(loginResult)
      }
    }
  }

  private fun retrieveLoginStatusImpl(
      context: Context,
      responseCallback: LoginStatusCallback,
      toastDurationMs: Long
  ) {
    val applicationId = getApplicationId()
    val redirectURI = getRedirectURI()
    val loggerRef = UUID.randomUUID().toString()
    val logger = LoginLogger(context, applicationId)
    if (!isExpressLoginAllowed) {
      logger.logLoginStatusFailure(loggerRef)
      responseCallback.onFailure()
      return
    }
    val client =
        LoginStatusClient.newInstance(
            context,
            applicationId,
          redirectURI,
            loggerRef,
            getGraphApiVersion(),
            toastDurationMs,
            null) // TODO T99739388: replace null with actual nonce
    val callback =
        PlatformServiceClient.CompletedListener { result ->
          if (result != null) {
            val errorType = result.getString(NativeProtocol.STATUS_ERROR_TYPE)
            val errorDescription = result.getString(NativeProtocol.STATUS_ERROR_DESCRIPTION)
            if (errorType != null) {
              handleLoginStatusError(
                  errorType, errorDescription, loggerRef, logger, responseCallback)
            } else {
              val token = result.getString(NativeProtocol.EXTRA_ACCESS_TOKEN)
              val expires =
                  Utility.getBundleLongAsDate(
                      result, NativeProtocol.EXTRA_EXPIRES_SECONDS_SINCE_EPOCH, Date(0))
              val permissions = result.getStringArrayList(NativeProtocol.EXTRA_PERMISSIONS)
              val signedRequest = result.getString(NativeProtocol.RESULT_ARGS_SIGNED_REQUEST)
              val graphDomain = result.getString(NativeProtocol.RESULT_ARGS_GRAPH_DOMAIN)
              val dataAccessExpirationTime =
                  Utility.getBundleLongAsDate(
                      result, NativeProtocol.EXTRA_DATA_ACCESS_EXPIRATION_TIME, Date(0))
              var userId: String? = null
              if (!signedRequest.isNullOrEmpty()) {
                userId = getUserIDFromSignedRequest(signedRequest)
              }
              if (!token.isNullOrEmpty() &&
                  !permissions.isNullOrEmpty() &&
                  !userId.isNullOrEmpty()) {
                val accessToken =
                    AccessToken(
                        token,
                        applicationId,
                        userId,
                        permissions,
                        null,
                        null,
                        null,
                        expires,
                        null,
                        dataAccessExpirationTime,
                        graphDomain)
                AccessToken.setCurrentAccessToken(accessToken)
                fetchProfileForCurrentAccessToken()
                logger.logLoginStatusSuccess(loggerRef)
                responseCallback.onCompleted(accessToken)
              } else {
                logger.logLoginStatusFailure(loggerRef)
                responseCallback.onFailure()
              }
            }
          } else {
            logger.logLoginStatusFailure(loggerRef)
            responseCallback.onFailure()
          }
        }
    client.setCompletedListener(callback)
    logger.logLoginStatusStart(loggerRef)
    if (!client.start()) {
      logger.logLoginStatusFailure(loggerRef)
      responseCallback.onFailure()
    }
  }

  private fun setExpressLoginStatus(isExpressLoginAllowed: Boolean) {
    val editor = sharedPreferences.edit()
    editor.putBoolean(EXPRESS_LOGIN_ALLOWED, isExpressLoginAllowed)
    editor.apply()
  }

  private val isExpressLoginAllowed: Boolean
    get() = sharedPreferences.getBoolean(EXPRESS_LOGIN_ALLOWED, true)

  private class AndroidxActivityResultRegistryOwnerStartActivityDelegate
  constructor(
      private val activityResultRegistryOwner: ActivityResultRegistryOwner,
      private val callbackManager: CallbackManager
  ) : StartActivityDelegate {
    override fun startActivityForResult(intent: Intent, requestCode: Int) {
      class LauncherHolder {
        var launcher: ActivityResultLauncher<Intent>? = null
      }

      val launcherHolder = LauncherHolder()
      launcherHolder.launcher =
          activityResultRegistryOwner.activityResultRegistry.register<Intent, Pair<Int, Intent>>(
              "facebook-login",
              object : ActivityResultContract<Intent, Pair<Int, Intent>>() {
                override fun createIntent(context: Context, input: Intent): Intent = input

                override fun parseResult(resultCode: Int, intent: Intent?): Pair<Int, Intent> {
                  return Pair.create(resultCode, intent)
                }
              },
              ActivityResultCallback<Pair<Int, Intent>> { result ->
                callbackManager.onActivityResult(
                    CallbackManagerImpl.RequestCodeOffset.Login.toRequestCode(),
                    result.first,
                    result.second)
                launcherHolder.launcher?.unregister()
                launcherHolder.launcher = null
              })
      launcherHolder.launcher?.launch(intent)
    }

    override val activityContext: Activity?
      get() =
          if (activityResultRegistryOwner is Activity) {
            activityResultRegistryOwner
          } else {
            null
          }
  }

  private class ActivityStartActivityDelegate internal constructor(activity: Activity) :
      StartActivityDelegate {
    override val activityContext: Activity = activity
    override fun startActivityForResult(intent: Intent, requestCode: Int) {
      activityContext.startActivityForResult(intent, requestCode)
    }
  }

  private class FragmentStartActivityDelegate constructor(private val fragment: FragmentWrapper) :
      StartActivityDelegate {
    override fun startActivityForResult(intent: Intent, requestCode: Int) {
      fragment.startActivityForResult(intent, requestCode)
    }

    override val activityContext: Activity? = fragment.activity
  }

  private object LoginLoggerHolder {
    private var logger: LoginLogger? = null

    @Synchronized
    fun getLogger(context: Context?): LoginLogger? {
      val resolvedContext = context ?: getApplicationContext()
      if (logger == null) {
        logger = LoginLogger(resolvedContext, getApplicationId())
      }
      return logger
    }
  }

  companion object {
    private const val PUBLISH_PERMISSION_PREFIX = "publish"
    private const val MANAGE_PERMISSION_PREFIX = "manage"
    private const val EXPRESS_LOGIN_ALLOWED = "express_login_allowed"
    private const val PREFERENCE_LOGIN_MANAGER = "com.facebook.loginManager"
    private val OTHER_PUBLISH_PERMISSIONS = otherPublishPermissions
    private val TAG = LoginManager::class.java.toString()

    @Volatile private lateinit var instance: LoginManager

    /**
     * Getter for the login manager.
     *
     * @return The login manager.
     */
    // `open` keeps the JVM static non-final so Java subclasses can hide it.
    @Suppress("NON_FINAL_MEMBER_IN_OBJECT")
    @JvmStatic
    open fun getInstance(): LoginManager {
      if (!this::instance.isInitialized) {
        synchronized(this) { instance = LoginManager() }
      }
      return instance
    }

    /**
     * To get login result extra data from the returned intent. This method is not intended to be
     * used by 3rd-party developers.
     *
     * @param intent the intent result from the activity call
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    @JvmStatic
    fun getExtraDataFromIntent(intent: Intent?): Map<String, String>? {
      if (intent == null) {
        return null
      }
      intent.setExtrasClassLoader(LoginClient.Result::class.java.classLoader)
      @Suppress("DEPRECATION")
      val result: LoginClient.Result =
          intent.getParcelableExtra(LoginFragment.RESULT_KEY) ?: return null
      return result.extraData
    }

    /**
     * To check whether the permission is for publishing content. This method is not intended to be
     * used by 3rd-party developers.
     *
     * @param permission the permission name
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    @JvmStatic
    fun isPublishPermission(permission: String?): Boolean {
      return (permission != null &&
          (permission.startsWith(PUBLISH_PERMISSION_PREFIX) ||
              permission.startsWith(MANAGE_PERMISSION_PREFIX) ||
              OTHER_PUBLISH_PERMISSIONS.contains(permission)))
    }

    private val otherPublishPermissions: Set<String>
      get() {
        return setOf("ads_management", "create_event", "rsvp_event")
      }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    @JvmStatic
    fun computeLoginResult(
        request: LoginClient.Request,
        newToken: AccessToken,
        newIdToken: AuthenticationToken?
    ): LoginResult {
      val requestedPermissions = request.permissions
      val grantedPermissions: MutableSet<String> =
          newToken.permissions.filterNotNull().toMutableSet()

      // If it's a reauth, subset the granted permissions to just the requested permissions
      // so we don't report implicit permissions like user_profile as recently granted.
      if (request.isRerequest) {
        grantedPermissions.retainAll(requestedPermissions)
      }
      val deniedPermissions: MutableSet<String> =
          requestedPermissions.filterNotNull().toMutableSet()
      deniedPermissions.removeAll(grantedPermissions)
      return LoginResult(newToken, newIdToken, grantedPermissions, deniedPermissions)
    }

    private fun handleLoginStatusError(
        errorType: String,
        errorDescription: String?,
        loggerRef: String,
        logger: LoginLogger,
        responseCallback: LoginStatusCallback
    ) {
      val exception: Exception = FacebookException("$errorType: $errorDescription")
      logger.logLoginStatusError(loggerRef, exception)
      responseCallback.onError(exception)
    }
  }

  init {
    sdkInitialized()
    sharedPreferences =
        getApplicationContext().getSharedPreferences(PREFERENCE_LOGIN_MANAGER, Context.MODE_PRIVATE)
    if (FacebookSdk.hasCustomTabsPrefetching && getChromePackage() != null) {
      // Pre-load CCT in case they are going to be used. It's happening in background,
      // and shouldn't slow anything down. Actual CCT launch should happen much faster.
      val prefetchHelper = CustomTabPrefetchHelper()
      CustomTabsClient.bindCustomTabsService(
          getApplicationContext(), "com.android.chrome", prefetchHelper)
      CustomTabsClient.connectAndInitialize(
          getApplicationContext(), getApplicationContext().packageName)
    }
  }
}
