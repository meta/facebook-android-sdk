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
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.LayoutRes
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.facebook.common.R

/**
 * This Fragment is a necessary part of the overall Facebook login process but is not meant to be
 * used directly.
 *
 * @see com.facebook.FacebookActivity
 */
open class LoginFragment : Fragment() {
  private var callingPackage: String? = null
  private var request: LoginClient.Request? = null
  lateinit var loginClient: LoginClient
    private set
  lateinit var launcher: ActivityResultLauncher<Intent>
    private set

  private lateinit var progressBar: View

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    @Suppress("DEPRECATION")
    val restoredLoginClient = savedInstanceState?.getParcelable<LoginClient>(SAVED_LOGIN_CLIENT)
    loginClient =
        if (restoredLoginClient != null) {
          restoredLoginClient.fragment = this
          restoredLoginClient
        } else {
          createLoginClient()
        }
    loginClient.onCompletedListener =
        LoginClient.OnCompletedListener { outcome -> onLoginClientCompleted(outcome) }
    val activity = activity ?: return
    initializeCallingPackage(activity)
    val intent = activity.intent
    if (intent != null) {
      val bundle = intent.getBundleExtra(REQUEST_KEY)
      if (bundle != null) {
        @Suppress("DEPRECATION")
        request = bundle.getParcelable(EXTRA_REQUEST)
      }
    }

    launcher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
            getLoginMethodHandlerCallback(activity))
  }

  private fun getLoginMethodHandlerCallback(activity: FragmentActivity): (ActivityResult) -> Unit =
      { result: ActivityResult ->
        if (result.resultCode == Activity.RESULT_OK) {
          loginClient.onActivityResult(
              LoginClient.getLoginRequestCode(), result.resultCode, result.data)
        } else {
          activity.finish()
        }
      }

  protected open fun createLoginClient(): LoginClient {
    return LoginClient(this)
  }

  override fun onDestroy() {
    loginClient.cancelCurrentHandler()
    super.onDestroy()
  }

  override fun onCreateView(
      inflater: LayoutInflater,
      container: ViewGroup?,
      savedInstanceState: Bundle?
  ): View? {
    val view = inflater.inflate(layoutResId, container, false)
    activity?.let { restoreAndroidLvaProvisionalResult(it, loginClient.pendingRequest) }
    progressBar = view.findViewById<View>(R.id.com_facebook_login_fragment_progress_bar)
    loginClient.backgroundProcessingListener =
        object : LoginClient.BackgroundProcessingListener {
          override fun onBackgroundProcessingStarted() {
            showSpinner()
          }

          override fun onBackgroundProcessingStopped() {
            hideSpinner()
          }
        }
    return view
  }

  @get:LayoutRes
  protected open val layoutResId: Int
    get() = R.layout.com_facebook_login_fragment

  // An activity's result doesn't survive its recreation, so the provisional result that identifies
  // a running Android SDK LVA launch is set again on every new host. This runs from onCreateView,
  // not onCreate: FacebookActivity retains this fragment, so a configuration change it doesn't
  // handle (locale, UI mode) recreates the host without calling onCreate again.
  private fun restoreAndroidLvaProvisionalResult(
      activity: Activity,
      pendingRequest: LoginClient.Request?
  ) {
    val state = pendingRequest?.androidLvaLaunchState ?: return
    val launchNonce = pendingRequest.androidLvaLaunchNonce ?: return
    activity.setResult(
        Activity.RESULT_CANCELED,
        AndroidLvaAttemptRecord.putResultExtras(Intent(), state, launchNonce))
  }

  private fun onLoginClientCompleted(outcome: LoginClient.Result) {
    request = null
    val resultCode =
        if (outcome.code == LoginClient.Result.Code.CANCEL) Activity.RESULT_CANCELED
        else Activity.RESULT_OK
    val bundle = Bundle()
    bundle.putParcelable(RESULT_KEY, outcome)
    val resultIntent = Intent()
    resultIntent.putExtras(bundle)

    val hostActivity = activity
    // The activity might be detached we will send a cancel result in onDetach
    if (isAdded && hostActivity != null) {
      hostActivity.setResult(resultCode, resultIntent)
      hostActivity.finish()
    }
  }

  override fun onResume() {
    super.onResume()

    // If the calling package is null, this generally means that the callee was started
    // with a launchMode of singleInstance. Unfortunately, Android does not allow a result
    // to be set when the callee is a singleInstance, so we log an error and return.
    if (callingPackage == null) {
      Log.e(TAG, NULL_CALLING_PKG_ERROR_MSG)
      activity?.finish()
      return
    }
    loginClient.startOrContinueAuth(request)
  }

  override fun onPause() {
    super.onPause()
    val progressBar = view?.findViewById<View>(R.id.com_facebook_login_fragment_progress_bar)
    if (progressBar != null) {
      progressBar.visibility = View.GONE
    }
  }

  @Suppress("DEPRECATION")
  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    loginClient.onActivityResult(requestCode, resultCode, data)
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    outState.putParcelable(SAVED_LOGIN_CLIENT, loginClient)
  }

  private fun showSpinner() {
    progressBar.visibility = View.VISIBLE
    onSpinnerShown()
  }

  private fun hideSpinner() {
    progressBar.visibility = View.GONE
    onSpinnerHidden()
  }

  protected open fun onSpinnerShown() {}

  protected open fun onSpinnerHidden() {}

  private fun initializeCallingPackage(activity: Activity) {
    val componentName = activity.callingActivity ?: return
    callingPackage = componentName.packageName
  }

  companion object {
    internal const val RESULT_KEY = "com.facebook.LoginFragment:Result"
    const val REQUEST_KEY = "com.facebook.LoginFragment:Request"
    const val EXTRA_REQUEST = "request"
    private const val TAG = "LoginFragment"
    private const val NULL_CALLING_PKG_ERROR_MSG =
        "Cannot call LoginFragment with a null calling package. This can occur if the launchMode of the caller is singleInstance."
    private const val SAVED_LOGIN_CLIENT = "loginClient"
  }
}
