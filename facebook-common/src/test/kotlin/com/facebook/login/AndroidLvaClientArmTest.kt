/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.login

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.facebook.FacebookPowerMockTestCase
import com.facebook.FacebookSdk
import com.facebook.internal.AndroidLvaGate
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.whenever
import org.powermock.api.mockito.PowerMockito.mockStatic
import org.powermock.core.classloader.annotations.PrepareForTest

@PrepareForTest(FacebookSdk::class)
class AndroidLvaClientArmTest : FacebookPowerMockTestCase() {

  private lateinit var context: Context
  private lateinit var prefs: SharedPreferences

  @Before
  fun init() {
    context = ApplicationProvider.getApplicationContext()
    prefs =
        context.getSharedPreferences("com.facebook.login.AndroidLvaClientArm", Context.MODE_PRIVATE)
    mockStatic(FacebookSdk::class.java)
    whenever(FacebookSdk.getApplicationContext()).thenReturn(context)
    prefs.edit().clear().commit()
  }

  private fun gate(isEnabled: Boolean, isClientExperiment: Boolean = true) =
      AndroidLvaGate.Snapshot(isEnabled, isClientExperiment)

  private fun storeBucket(bucket: Int) {
    prefs.edit().putInt("install_bucket", bucket).commit()
  }

  @Test
  fun `gate off assigns no arm and stores nothing`() {
    assertThat(AndroidLvaClientArm.assign(gate(isEnabled = false))).isNull()
    assertThat(prefs.all).isEmpty()
  }

  @Test
  fun `the client experiment splits installs by their stored bucket`() {
    storeBucket(10)
    assertThat(AndroidLvaClientArm.assign(gate(isEnabled = true)))
        .isEqualTo(AndroidLvaClientArm.TREATMENT)
    storeBucket(75)
    assertThat(AndroidLvaClientArm.assign(gate(isEnabled = true)))
        .isEqualTo(AndroidLvaClientArm.CONTROL)
  }

  @Test
  fun `without the client experiment every install is in treatment`() {
    storeBucket(99)
    assertThat(AndroidLvaClientArm.assign(gate(isEnabled = true, isClientExperiment = false)))
        .isEqualTo(AndroidLvaClientArm.TREATMENT)
  }

  @Test
  fun `the install bucket is created once and stays stable`() {
    val first = AndroidLvaClientArm.assign(gate(isEnabled = true))
    val bucket = prefs.getInt("install_bucket", -1)
    assertThat(bucket).isBetween(0, 99)
    repeat(5) { assertThat(AndroidLvaClientArm.assign(gate(isEnabled = true))).isEqualTo(first) }
    assertThat(prefs.getInt("install_bucket", -1)).isEqualTo(bucket)
  }

  @Test
  fun `an invalid stored bucket is replaced`() {
    storeBucket(250)
    AndroidLvaClientArm.assign(gate(isEnabled = true))
    assertThat(prefs.getInt("install_bucket", -1)).isBetween(0, 99)
  }

  @Test
  fun `without an application context no arm is assigned`() {
    whenever(FacebookSdk.getApplicationContext()).thenReturn(null)
    assertThat(AndroidLvaClientArm.assign(gate(isEnabled = true))).isNull()
  }
}
