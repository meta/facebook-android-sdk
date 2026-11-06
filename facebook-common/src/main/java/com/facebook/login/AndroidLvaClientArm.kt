/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.login

import android.content.Context
import com.facebook.FacebookSdk
import com.facebook.internal.AndroidLvaGate
import kotlin.random.Random

/**
 * The client arm of an Android SDK Login Via App (LVA) attempt. It is assigned for every Custom Tab
 * login attempt of an app whose gate is on, before any eligibility check, so it says nothing about
 * the device. Every install is in treatment unless the client experiment is on, which splits
 * installs 50/50 by a random per-install value that is stored locally and never sent anywhere.
 * Control attempts behave exactly as today.
 */
internal object AndroidLvaClientArm {
  const val LOGGING_EXTRA = "lva_client_arm"
  const val TREATMENT = "treatment"
  const val CONTROL = "control"

  private const val PREFS_STORE = "com.facebook.login.AndroidLvaClientArm"
  private const val INSTALL_BUCKET = "install_bucket"
  private const val BUCKETS = 100
  private const val EXPERIMENT_TREATMENT_BUCKETS = 50

  /**
   * The arm of an attempt that read [gate] at its start, or null when the gate is off (or, before
   * the SDK is initialized, without an application context).
   */
  fun assign(gate: AndroidLvaGate.Snapshot): String? {
    if (!gate.isEnabled) {
      return null
    }
    val bucket = getInstallBucket() ?: return null
    return if (!gate.isClientExperiment || bucket < EXPERIMENT_TREATMENT_BUCKETS) TREATMENT
    else CONTROL
  }

  private fun getInstallBucket(): Int? {
    val context: Context? = FacebookSdk.getApplicationContext()
    val prefs = context?.getSharedPreferences(PREFS_STORE, Context.MODE_PRIVATE) ?: return null
    val stored = prefs.getInt(INSTALL_BUCKET, -1)
    if (stored in 0 until BUCKETS) {
      return stored
    }
    val bucket = Random.nextInt(BUCKETS)
    prefs.edit().putInt(INSTALL_BUCKET, bucket).apply()
    return bucket
  }
}
