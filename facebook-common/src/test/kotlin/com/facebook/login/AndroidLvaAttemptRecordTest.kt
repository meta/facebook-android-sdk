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
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.whenever
import org.powermock.api.mockito.PowerMockito.mockStatic
import org.powermock.core.classloader.annotations.PrepareForTest

@PrepareForTest(FacebookSdk::class)
class AndroidLvaAttemptRecordTest : FacebookPowerMockTestCase() {

  companion object {
    const val NOW = 1_800_000_000_000L
    val MINUTE = TimeUnit.MINUTES.toMillis(1)
  }

  private lateinit var context: Context
  private lateinit var prefs: SharedPreferences

  @Before
  fun init() {
    context = ApplicationProvider.getApplicationContext()
    prefs =
        context.getSharedPreferences(
            "com.facebook.login.AndroidLvaAttemptRecord", Context.MODE_PRIVATE)
    mockStatic(FacebookSdk::class.java)
    whenever(FacebookSdk.getApplicationContext()).thenReturn(context)
    prefs.edit().clear().commit()
  }

  private fun launch(state: String = "state1", at: Long = NOW) =
      checkNotNull(AndroidLvaAttemptRecord.launch(state, "logger1", "treatment", at))

  @Test
  fun `a launch stores a live record with a 30-minute window and a new nonce`() {
    val record = launch()
    assertThat(record.version).isEqualTo(1)
    assertThat(record.phase).isEqualTo(AndroidLvaAttemptRecord.Phase.LAUNCHED)
    assertThat(record.expiresAt).isEqualTo(NOW + 30 * MINUTE)
    assertThat(record.launchNonce).isNotEmpty
    assertThat(AndroidLvaAttemptRecord.read()).isEqualTo(record)
    assertThat(AndroidLvaAttemptRecord.isLive(record, NOW + 30 * MINUTE - 1)).isTrue
    assertThat(AndroidLvaAttemptRecord.isLive(record, NOW + 30 * MINUTE)).isFalse

    assertThat(launch(state = "state2").launchNonce).isNotEqualTo(record.launchNonce)
  }

  @Test
  fun `a new launch replaces the previous attempt`() {
    launch(state = "state1")
    val second = launch(state = "state2")
    assertThat(AndroidLvaAttemptRecord.read()).isEqualTo(second)
  }

  @Test
  fun `the pending deadline is set once, 10 minutes after first entering PENDING`() {
    val record = launch()
    val pending =
        checkNotNull(
            AndroidLvaAttemptRecord.enterPending(record.state, record.version, NOW + MINUTE))
    assertThat(pending.phase).isEqualTo(AndroidLvaAttemptRecord.Phase.PENDING)
    assertThat(pending.expiresAt).isEqualTo(NOW + 11 * MINUTE)

    val resumedAgain =
        AndroidLvaAttemptRecord.enterPending(record.state, record.version, NOW + 5 * MINUTE)
    assertThat(resumedAgain).isEqualTo(pending)
  }

  @Test
  fun `an expired launch can't enter PENDING`() {
    val record = launch()
    assertThat(
            AndroidLvaAttemptRecord.enterPending(record.state, record.version, NOW + 30 * MINUTE))
        .isNull()
    assertThat(AndroidLvaAttemptRecord.read()?.phase)
        .isEqualTo(AndroidLvaAttemptRecord.Phase.LAUNCHED)
  }

  @Test
  fun `continue keeps the state and nonce and starts a fresh window with a new version`() {
    val record = launch()
    AndroidLvaAttemptRecord.enterPending(record.state, record.version, NOW + MINUTE)
    val continued =
        checkNotNull(
            AndroidLvaAttemptRecord.continueLaunch(record.state, record.version, NOW + 2 * MINUTE))
    assertThat(continued.state).isEqualTo(record.state)
    assertThat(continued.launchNonce).isEqualTo(record.launchNonce)
    assertThat(continued.version).isEqualTo(2)
    assertThat(continued.phase).isEqualTo(AndroidLvaAttemptRecord.Phase.LAUNCHED)
    assertThat(continued.expiresAt).isEqualTo(NOW + 32 * MINUTE)
  }

  @Test
  fun `continue needs a live PENDING record`() {
    val record = launch()
    assertThat(AndroidLvaAttemptRecord.continueLaunch(record.state, record.version, NOW)).isNull()
    AndroidLvaAttemptRecord.enterPending(record.state, record.version, NOW)
    assertThat(
            AndroidLvaAttemptRecord.continueLaunch(record.state, record.version, NOW + 10 * MINUTE))
        .isNull()
  }

  @Test
  fun `changes for another state or an older version are ignored`() {
    val record = launch()
    AndroidLvaAttemptRecord.enterPending(record.state, record.version, NOW)
    AndroidLvaAttemptRecord.continueLaunch(record.state, record.version, NOW)
    // Version 1 is now stale.
    assertThat(AndroidLvaAttemptRecord.enterPending(record.state, 1, NOW)).isNull()
    assertThat(AndroidLvaAttemptRecord.enterPending("other", 2, NOW)).isNull()
    assertThat(AndroidLvaAttemptRecord.read()?.version).isEqualTo(2)
  }

  @Test
  fun `clearIf removes the record only when state and version both match`() {
    val record = launch()
    assertThat(AndroidLvaAttemptRecord.clearIf("other", record.version)).isFalse
    assertThat(AndroidLvaAttemptRecord.clearIf(record.state, record.version + 1)).isFalse
    assertThat(AndroidLvaAttemptRecord.read()).isNotNull

    assertThat(AndroidLvaAttemptRecord.clearIf(record.state, record.version)).isTrue
    assertThat(AndroidLvaAttemptRecord.read()).isNull()
    assertThat(AndroidLvaAttemptRecord.clearIf(record.state, record.version)).isFalse
  }

  @Test
  fun `expired cleanup from an old snapshot can't erase a record that entered PENDING`() {
    val snapshot = launch()
    // Just before the launch deadline, the activity resumes and the attempt enters PENDING.
    AndroidLvaAttemptRecord.enterPending(snapshot.state, snapshot.version, NOW + 29 * MINUTE)
    val at = NOW + 30 * MINUTE
    assertThat(AndroidLvaAttemptRecord.isLive(snapshot, at)).isFalse
    assertThat(AndroidLvaAttemptRecord.clearIf(snapshot.state, snapshot.version, expiredAt = at))
        .isFalse
    assertThat(AndroidLvaAttemptRecord.read()?.phase)
        .isEqualTo(AndroidLvaAttemptRecord.Phase.PENDING)

    // Once the pending deadline passes too, the same cleanup removes it.
    assertThat(
            AndroidLvaAttemptRecord.clearIf(
                snapshot.state, snapshot.version, expiredAt = NOW + 39 * MINUTE))
        .isTrue
  }

  @Test
  fun `a stale clear can't erase a newer attempt`() {
    val first = launch(state = "state1")
    val second = launch(state = "state2")
    assertThat(AndroidLvaAttemptRecord.clearIf(first.state, first.version)).isFalse
    assertThat(AndroidLvaAttemptRecord.read()).isEqualTo(second)
  }

  @Test
  fun `an unreadable record counts as none`() {
    prefs.edit().putString("active_attempt", "{not json").commit()
    assertThat(AndroidLvaAttemptRecord.read()).isNull()
    prefs.edit().putString("active_attempt", "{\"state\":\"s\"}").commit()
    assertThat(AndroidLvaAttemptRecord.read()).isNull()
  }

  @Test
  fun `a context that throws makes writes fail instead of throwing`() {
    whenever(FacebookSdk.getApplicationContext()).thenThrow(RuntimeException("not initialized"))
    assertThat(AndroidLvaAttemptRecord.launch("state1", "logger1", "treatment", NOW)).isNull()
    assertThat(AndroidLvaAttemptRecord.clearIf("state1", 1)).isFalse
  }

  @Test
  fun `without an application context there is no record`() {
    whenever(FacebookSdk.getApplicationContext()).thenReturn(null)
    assertThat(AndroidLvaAttemptRecord.launch("state1", "logger1", "treatment", NOW)).isNull()
    assertThat(AndroidLvaAttemptRecord.read()).isNull()
    assertThat(AndroidLvaAttemptRecord.clearIf("state1", 1)).isFalse
  }
}
