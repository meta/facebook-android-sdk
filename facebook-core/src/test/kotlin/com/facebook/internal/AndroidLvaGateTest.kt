/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.internal

import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import com.facebook.FacebookPowerMockTestCase
import com.facebook.FacebookRequestError
import com.facebook.FacebookSdk
import com.facebook.GraphRequest
import com.facebook.GraphResponse
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.assertj.core.api.Assertions.assertThat
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.powermock.api.mockito.PowerMockito.mockStatic
import org.powermock.core.classloader.annotations.PrepareForTest
import org.powermock.reflect.Whitebox

@PrepareForTest(FacebookSdk::class)
class AndroidLvaGateTest : FacebookPowerMockTestCase() {

  companion object {
    const val APP_ID = "123"
    const val CLIENT_TOKEN = "client-token"
    const val NOW = 1_800_000_000_000L
    val HOUR = TimeUnit.HOURS.toMillis(1)
    val MINUTE = TimeUnit.MINUTES.toMillis(1)
  }

  private val scheduled = mutableListOf<Runnable>()
  private lateinit var mockResponse: GraphResponse
  private lateinit var mockRequest: GraphRequest

  @Before
  fun init() {
    mockResponse = mock()
    mockRequest = mock()
    mockStatic(FacebookSdk::class.java)
    whenever(FacebookSdk.isInitialized()).thenReturn(true)
    whenever(FacebookSdk.getApplicationId()).thenReturn(APP_ID)
    whenever(FacebookSdk.getClientToken()).thenReturn(CLIENT_TOKEN)
    whenever(FacebookSdk.getSdkVersion()).thenReturn("19.0.0")
    whenever(FacebookSdk.getApplicationContext())
        .thenReturn(ApplicationProvider.getApplicationContext())
    val mockExecutor = mock<Executor>()
    whenever(mockExecutor.execute(any())).thenAnswer {
      scheduled.add(it.getArgument(0))
      Unit
    }
    whenever(FacebookSdk.getExecutor()).thenReturn(mockExecutor)

    whenever(mockRequest.executeAndWait()).thenReturn(mockResponse)
    val mockCompanion = mock<GraphRequest.Companion>()
    whenever(mockCompanion.newGraphPathRequest(anyOrNull(), any(), anyOrNull()))
        .thenReturn(mockRequest)
    Whitebox.setInternalState(GraphRequest::class.java, "Companion", mockCompanion)

    Whitebox.setInternalState(
        FetchedAppGateKeepersManager::class.java,
        "fetchedAppGateKeepers",
        ConcurrentHashMap<String, JSONObject>())
    Whitebox.setInternalState(
        FetchedAppGateKeepersManager::class.java, "isLoading", AtomicBoolean(false))
    Whitebox.setInternalState(FetchedAppGateKeepersManager::class.java, "timestamp", null as Long?)
    AndroidLvaGate.clearInMemoryForTesting()
  }

  private fun response(vararg gateKeepers: Pair<String, Any>): JSONObject {
    val list = gateKeepers.joinToString(",") { "{\"key\":\"${it.first}\",\"value\":${it.second}}" }
    return JSONObject("{\"data\":[{\"gatekeepers\":[$list]}]}")
  }

  private fun fetch(response: JSONObject?, hasError: Boolean = false, at: Long = NOW) {
    AndroidLvaGate.onGateKeepersFetched(APP_ID, response, hasError, at)
  }

  private fun makeManagerCacheFresh() {
    Whitebox.setInternalState(
        FetchedAppGateKeepersManager::class.java,
        "fetchedAppGateKeepers",
        ConcurrentHashMap(mapOf(APP_ID to JSONObject())))
    Whitebox.setInternalState(
        FetchedAppGateKeepersManager::class.java, "timestamp", System.currentTimeMillis())
  }

  private fun snapshot(at: Long) = AndroidLvaGate.getSnapshot(at)

  private val enabledResponse =
      response(AndroidLvaGate.GATE_KEY to true, AndroidLvaGate.CLIENT_EXPERIMENT_KEY to true)

  // --- Freshness ---

  @Test
  fun `never fetched reads as off and asks the manager for a refresh`() {
    assertThat(snapshot(NOW))
        .isEqualTo(AndroidLvaGate.Snapshot(isEnabled = false, isClientExperiment = false))
    assertThat(scheduled).hasSize(1)
  }

  @Test
  fun `fresh values are served without a refresh`() {
    fetch(enabledResponse)
    assertThat(snapshot(NOW + HOUR / 2))
        .isEqualTo(AndroidLvaGate.Snapshot(isEnabled = true, isClientExperiment = true))
    assertThat(scheduled).isEmpty()
  }

  @Test
  fun `an enabled gate older than an hour is served while a single refresh is in flight`() {
    fetch(enabledResponse)
    assertThat(snapshot(NOW + 2 * HOUR).isEnabled).isTrue
    assertThat(snapshot(NOW + 2 * HOUR + 10 * MINUTE).isEnabled).isTrue
    assertThat(scheduled).hasSize(1)
    scheduled.clear()
    assertThat(snapshot(NOW + 2 * HOUR + 16 * MINUTE).isEnabled).isTrue
    assertThat(scheduled).isEmpty() // The first refresh hasn't finished yet, so it coalesces.
  }

  @Test
  fun `values older than 24 hours read as off`() {
    fetch(enabledResponse)
    assertThat(snapshot(NOW + 25 * HOUR).isEnabled).isFalse
  }

  @Test
  fun `a timestamp in the future reads as off`() {
    fetch(enabledResponse, at = NOW + HOUR)
    assertThat(snapshot(NOW).isEnabled).isFalse
  }

  @Test
  fun `a clock set back extends the lifetime, an accepted residual`() {
    fetch(enabledResponse)
    // 25 hours later the value is off; if the clock had been set back 5 hours, the same moment
    // reads as 20 hours, and the value is still on.
    assertThat(snapshot(NOW + 25 * HOUR).isEnabled).isFalse
    assertThat(snapshot(NOW + 20 * HOUR).isEnabled).isTrue
  }

  // --- Which responses count ---

  @Test
  fun `an error response is ignored even with a body`() {
    fetch(enabledResponse, hasError = true)
    assertThat(snapshot(NOW).isEnabled).isFalse
  }

  @Test
  fun `a valid empty list turns both values off`() {
    fetch(enabledResponse)
    fetch(response(), at = NOW + 1)
    assertThat(snapshot(NOW + 1))
        .isEqualTo(AndroidLvaGate.Snapshot(isEnabled = false, isClientExperiment = false))
  }

  @Test
  fun `a response that omits the keys turns a cached true off`() {
    fetch(enabledResponse)
    fetch(response("FBSDKFeatureInstrument" to true), at = NOW + HOUR)
    assertThat(snapshot(NOW + HOUR).isEnabled).isFalse
  }

  @Test
  fun `string booleans are accepted, as the manager accepts them`() {
    fetch(response(AndroidLvaGate.GATE_KEY to "\"true\""))
    assertThat(snapshot(NOW).isEnabled).isTrue
  }

  @Test
  fun `malformed responses keep the last value and its original expiry`() {
    fetch(enabledResponse)
    val malformed =
        listOf(
            JSONObject("{}"),
            JSONObject("{\"data\":[{\"gatekeepers\":[null]}]}"),
            JSONObject("{\"data\":[{\"gatekeepers\":[{\"value\":false}]}]}"),
            JSONObject("{\"data\":[{\"gatekeepers\":[{\"key\":\"${AndroidLvaGate.GATE_KEY}\"}]}]}"),
            response(AndroidLvaGate.GATE_KEY to "\"maybe\""),
            response(AndroidLvaGate.GATE_KEY to false, "other" to true).put("data", "x"),
            // A valid gate with a malformed unrelated entry is still a malformed list.
            response(AndroidLvaGate.GATE_KEY to false, "other" to "\"maybe\""),
            JSONObject(
                "{\"data\":[{\"gatekeepers\":[" +
                    "{\"key\":\"${AndroidLvaGate.GATE_KEY}\",\"value\":false}," +
                    "{\"key\":\"other\"}]}]}"))
    malformed.forEach { fetch(it, at = NOW + 2 * HOUR) }
    assertThat(snapshot(NOW + 3 * HOUR).isEnabled).isTrue
    assertThat(snapshot(NOW + 25 * HOUR).isEnabled).isFalse
  }

  @Test
  fun `the client experiment is read independently of the gate`() {
    fetch(response(AndroidLvaGate.GATE_KEY to true, AndroidLvaGate.CLIENT_EXPERIMENT_KEY to false))
    assertThat(snapshot(NOW))
        .isEqualTo(AndroidLvaGate.Snapshot(isEnabled = true, isClientExperiment = false))
  }

  @Test
  fun `a snapshot taken before a refresh keeps its values`() {
    fetch(enabledResponse)
    val before = snapshot(NOW)
    fetch(response(AndroidLvaGate.GATE_KEY to false), at = NOW + 1)
    assertThat(before.isEnabled).isTrue
    assertThat(snapshot(NOW + 1).isEnabled).isFalse
  }

  // --- Persistence ---

  @Test
  fun `the persisted value is used after a cold start, and a persisted true expires`() {
    fetch(enabledResponse)
    AndroidLvaGate.clearInMemoryForTesting()
    assertThat(snapshot(NOW + 2 * HOUR).isEnabled).isTrue
    AndroidLvaGate.clearInMemoryForTesting()
    assertThat(snapshot(NOW + 25 * HOUR).isEnabled).isFalse
  }

  @Test
  fun `a fetch after a cold start wins over the persisted value`() {
    fetch(enabledResponse)
    AndroidLvaGate.clearInMemoryForTesting()
    fetch(response(AndroidLvaGate.GATE_KEY to false), at = NOW + HOUR)
    assertThat(snapshot(NOW + HOUR).isEnabled).isFalse
    AndroidLvaGate.clearInMemoryForTesting()
    assertThat(snapshot(NOW + HOUR).isEnabled).isFalse
  }

  // --- Failures never escape ---

  @Test
  fun `without an application context the gate reads as off`() {
    whenever(FacebookSdk.getApplicationContext()).thenReturn(null)
    fetch(enabledResponse)
    AndroidLvaGate.clearInMemoryForTesting()
    assertThat(snapshot(NOW).isEnabled).isFalse
  }

  @Test
  fun `a failing preferences store never throws out of the fetch hook`() {
    val brokenContext = mock<Context>()
    whenever(brokenContext.getSharedPreferences(any(), any()))
        .thenThrow(RuntimeException("disk"))
    whenever(FacebookSdk.getApplicationContext()).thenReturn(brokenContext)
    fetch(enabledResponse)
  }

  // --- App identity ---

  @Test
  fun `a response for another app is not recorded`() {
    whenever(FacebookSdk.getApplicationId()).thenReturn("other")
    fetch(enabledResponse)
    whenever(FacebookSdk.getApplicationId()).thenReturn(APP_ID)
    assertThat(snapshot(NOW).isEnabled).isFalse
  }

  @Test
  fun `a response is dropped if the SDK app changes while the request is in flight`() {
    whenever(mockResponse.jsonObject).thenReturn(enabledResponse)
    whenever(mockRequest.executeAndWait()).thenAnswer {
      whenever(FacebookSdk.getApplicationId()).thenReturn("other")
      mockResponse
    }
    FetchedAppGateKeepersManager.queryAppGateKeepers(APP_ID, true)
    whenever(FacebookSdk.getApplicationId()).thenReturn(APP_ID)
    assertThat(AndroidLvaGate.getSnapshot().isEnabled).isFalse
  }

  @Test
  fun `the query carries the client token of the app it records for`() {
    whenever(mockResponse.jsonObject).thenReturn(enabledResponse)
    FetchedAppGateKeepersManager.queryAppGateKeepers(APP_ID, true)

    // GraphRequest sends an access_token parameter as given, so a later app change can't alter it.
    val parameters = argumentCaptor<Bundle>()
    verify(mockRequest).parameters = parameters.capture()
    assertThat(parameters.firstValue.getString(GraphRequest.ACCESS_TOKEN_PARAM))
        .isEqualTo("$APP_ID|$CLIENT_TOKEN")
    assertThat(AndroidLvaGate.getSnapshot().isEnabled).isTrue
  }

  @Test
  fun `without a client token the query is sent as before and not recorded`() {
    whenever(FacebookSdk.getClientToken()).thenReturn("")
    whenever(mockResponse.jsonObject).thenReturn(enabledResponse)
    FetchedAppGateKeepersManager.queryAppGateKeepers(APP_ID, true)

    val parameters = argumentCaptor<Bundle>()
    verify(mockRequest).parameters = parameters.capture()
    assertThat(parameters.firstValue.containsKey(GraphRequest.ACCESS_TOKEN_PARAM)).isFalse
    assertThat(AndroidLvaGate.getSnapshot().isEnabled).isFalse
  }

  // --- Refresh scheduling ---

  @Test
  fun `a failed refresh is retried after 15 minutes and keeps the original expiry`() {
    fetch(enabledResponse)
    whenever(mockResponse.jsonObject).thenReturn(enabledResponse)
    whenever(mockResponse.error).thenReturn(mock<FacebookRequestError>())
    assertThat(snapshot(NOW + 2 * HOUR).isEnabled).isTrue
    scheduled.single().run()
    scheduled.clear()

    assertThat(snapshot(NOW + 2 * HOUR + 14 * MINUTE).isEnabled).isTrue
    assertThat(scheduled).isEmpty()
    assertThat(snapshot(NOW + 2 * HOUR + 16 * MINUTE).isEnabled).isTrue
    assertThat(scheduled).hasSize(1)
    assertThat(snapshot(NOW + 25 * HOUR).isEnabled).isFalse
  }

  @Test
  fun `a failed scheduling lets the next read try again`() {
    fetch(enabledResponse)
    var rejections = 1
    whenever(FacebookSdk.getExecutor().execute(any())).thenAnswer {
      if (rejections-- > 0) throw RuntimeException("rejected")
      scheduled.add(it.getArgument(0))
      Unit
    }
    assertThat(snapshot(NOW + 2 * HOUR).isEnabled).isTrue
    assertThat(scheduled).isEmpty()
    assertThat(snapshot(NOW + 2 * HOUR + MINUTE).isEnabled).isTrue
    assertThat(scheduled).hasSize(1)
  }

  @Test
  fun `a gate that is off leaves refreshing to a fresh manager cache`() {
    makeManagerCacheFresh()
    fetch(response(AndroidLvaGate.GATE_KEY to false))
    snapshot(NOW + 2 * HOUR)
    assertThat(scheduled).isEmpty()
  }

  // --- Wiring through the manager's fetches (real clock) ---

  @Test
  fun `the manager's async fetch records the gate`() {
    whenever(mockResponse.jsonObject).thenReturn(enabledResponse)
    FetchedAppGateKeepersManager.loadAppGateKeepersAsync()
    scheduled.forEach { it.run() }
    assertThat(AndroidLvaGate.getSnapshot().isEnabled).isTrue
  }

  @Test
  fun `the manager's fetch ignores an error response with a body`() {
    whenever(mockResponse.jsonObject).thenReturn(enabledResponse)
    whenever(mockResponse.error).thenReturn(mock<FacebookRequestError>())
    FetchedAppGateKeepersManager.queryAppGateKeepers(APP_ID, true)
    assertThat(AndroidLvaGate.getSnapshot().isEnabled).isFalse
  }

  @Test
  fun `an enabled gate refreshes itself even while the manager's cache is fresh`() {
    makeManagerCacheFresh()
    val twoHoursAgo = System.currentTimeMillis() - 2 * HOUR
    fetch(enabledResponse, at = twoHoursAgo)
    whenever(mockResponse.jsonObject).thenReturn(response(AndroidLvaGate.GATE_KEY to false))

    assertThat(AndroidLvaGate.getSnapshot().isEnabled).isTrue
    assertThat(scheduled).hasSize(1)
    scheduled.single().run()
    assertThat(AndroidLvaGate.getSnapshot().isEnabled).isFalse
  }
}
