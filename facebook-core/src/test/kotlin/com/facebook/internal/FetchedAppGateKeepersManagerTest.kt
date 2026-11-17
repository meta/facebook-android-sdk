/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.internal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.facebook.FacebookPowerMockTestCase
import com.facebook.FacebookSdk
import com.facebook.internal.gatekeeper.GateKeeper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import org.assertj.core.api.Assertions.assertThat
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.powermock.api.mockito.PowerMockito.mockStatic
import org.powermock.core.classloader.annotations.PrepareForTest
import org.powermock.reflect.Whitebox

@PrepareForTest(FacebookSdk::class)
class FetchedAppGateKeepersManagerTest : FacebookPowerMockTestCase() {

  companion object {
    const val APPLICATION_NAME = "aa"
    const val GK1 = "FBSDKFeatureInstrument"
    const val GK2 = "app_events_killswitch"

    const val VALID_JSON =
        "{\n" +
            "  \"data\": [\n" +
            "    {\n" +
            "      \"gatekeepers\": [\n" +
            "        {\n" +
            "          \"key\": \"" +
            GK1 +
            "\",\n" +
            "          \"value\": true\n" +
            "        },\n" +
            "        {\n" +
            "          \"key\": \"" +
            GK2 +
            "\",\n" +
            "          \"value\": \"false\"\n" +
            "        }\n" +
            "      ]\n" +
            "    }\n" +
            "  ]\n" +
            "}"

    const val NON_BOOLEAN_VALUE_RESPONSE =
        "{\n" +
            "  \"data\": [\n" +
            "    {\n" +
            "      \"gatekeepers\": [\n" +
            "        {\n" +
            "          \"key\": \"" +
            GK1 +
            "\",\n" +
            "          \"value\": swag\n" +
            "        },\n" +
            "      ]\n" +
            "    }\n" +
            "  ]\n" +
            "}"

    const val EMPTY_GK_LIST_RESPONSE =
        "{\n" +
            "  \"data\": [\n" +
            "    {\n" +
            "      \"gatekeepers\": [\n" +
            "      ]\n" +
            "    }\n" +
            "  ]\n" +
            "}"

    const val EMPTY_RESPONSE = "{}"
    const val EMPTY_DATA_RESPONSE = "{\n" + "  \"data\": [\n" + "  ]\n" + "}"
  }
  private var asyncTaskExecutedTimes = 0

  @Before
  fun init() {
    mockStatic(FacebookSdk::class.java)
    whenever(FacebookSdk.isInitialized()).thenReturn(true)
    whenever(FacebookSdk.getApplicationId()).thenReturn(APPLICATION_NAME)
    whenever(FacebookSdk.getApplicationContext())
        .thenReturn(ApplicationProvider.getApplicationContext())
    val mockExecutor = mock<Executor>()
    whenever(mockExecutor.execute(any())).thenAnswer {
      asyncTaskExecutedTimes++
      // reset isLoading
      Whitebox.setInternalState(
          FetchedAppGateKeepersManager::class.java, "isLoading", AtomicBoolean(false))
    }
    whenever(FacebookSdk.getExecutor()).thenReturn(mockExecutor)

    // because it is a static variable which holds a lot of state about the GKs, we need to reset it
    // every time
    Whitebox.setInternalState(
        FetchedAppGateKeepersManager::class.java,
        "fetchedAppGateKeepers",
        ConcurrentHashMap<String, JSONObject>())
  }

  @After
  fun clean() {
    FetchedAppGateKeepersManager.resetRuntimeGateKeeperCache()
  }

  @Test
  fun `parse valid json_ok`() {
    val test = JSONObject(VALID_JSON)
    val result = FetchedAppGateKeepersManager.parseAppGateKeepersFromJSON(APPLICATION_NAME, test)
    assertThat(result.getBoolean(GK2)).isFalse
    assertThat(result.getBoolean(GK1)).isTrue

    val map = FetchedAppGateKeepersManager.getGateKeepersForApplication(APPLICATION_NAME)
    assertThat(map[GK1]).isTrue
    assertThat(map[GK2]).isFalse

    val gk1 = FetchedAppGateKeepersManager.getGateKeeperForKey(GK1, APPLICATION_NAME, false)
    val gk2 = FetchedAppGateKeepersManager.getGateKeeperForKey(GK2, APPLICATION_NAME, true)
    assertThat(gk1).isTrue
    assertThat(gk2).isFalse
    // "Both getGateKeepersForApplication and getGateKeeperForKey call async"
    assertThat(asyncTaskExecutedTimes).isEqualTo(3)
  }

  @Test
  fun `parse value isnt boolean_fail`() {
    val test = JSONObject(NON_BOOLEAN_VALUE_RESPONSE)
    val result = FetchedAppGateKeepersManager.parseAppGateKeepersFromJSON(APPLICATION_NAME, test)
    assertThat(result.length()).isEqualTo(0)

    val map = FetchedAppGateKeepersManager.getGateKeepersForApplication(APPLICATION_NAME)
    // current parser filters out non boolean values, otherwise map will actually return the exact
    // value
    assertThat(map[GK1]).isNull()

    val gk = FetchedAppGateKeepersManager.getGateKeeperForKey(GK1, APPLICATION_NAME, false)
    assertThat(gk).isFalse
    // "Both getGateKeepersForApplication and getGateKeeperForKey call async"
    assertThat(asyncTaskExecutedTimes).isEqualTo(2)
  }

  @Test
  fun `parse empty list of gks_fail`() {
    val test = JSONObject(EMPTY_GK_LIST_RESPONSE)
    val result = FetchedAppGateKeepersManager.parseAppGateKeepersFromJSON(APPLICATION_NAME, test)
    assertThat(result.length()).isEqualTo(0)

    val map = FetchedAppGateKeepersManager.getGateKeepersForApplication(APPLICATION_NAME)
    assertThat(map.size).isEqualTo(0)

    val gk = FetchedAppGateKeepersManager.getGateKeeperForKey("anything", APPLICATION_NAME, false)
    assertThat(gk).isFalse()
    // "Both getGateKeepersForApplication and getGateKeeperForKey call async"
    assertThat(asyncTaskExecutedTimes).isEqualTo(2)
  }

  @Test
  fun `parse empty response_fail`() {
    val test = JSONObject(EMPTY_RESPONSE)
    val result = FetchedAppGateKeepersManager.parseAppGateKeepersFromJSON(APPLICATION_NAME, test)
    assertThat(result.length()).isEqualTo(0)

    val map = FetchedAppGateKeepersManager.getGateKeepersForApplication(APPLICATION_NAME)
    assertThat(map.size).isEqualTo(0)

    val gk = FetchedAppGateKeepersManager.getGateKeeperForKey("anything", APPLICATION_NAME, false)
    assertThat(gk).isFalse
    // "Both getGateKeepersForApplication and getGateKeeperForKey call async"
    assertThat(asyncTaskExecutedTimes).isEqualTo(2)
  }

  @Test
  fun `parse empty data response of gks_fail`() {
    val test = JSONObject(EMPTY_DATA_RESPONSE)
    val result = FetchedAppGateKeepersManager.parseAppGateKeepersFromJSON(APPLICATION_NAME, test)
    assertThat(result.length()).isEqualTo(0)

    val map = FetchedAppGateKeepersManager.getGateKeepersForApplication(APPLICATION_NAME)
    assertThat(map.size).isEqualTo(0)

    val gk = FetchedAppGateKeepersManager.getGateKeeperForKey("anything", APPLICATION_NAME, false)
    assertThat(gk).isFalse
    // "Both getGateKeepersForApplication and getGateKeeperForKey call async"
    assertThat(asyncTaskExecutedTimes).isEqualTo(2)
  }

  @Test
  fun `null in gk map is still default value_ok`() {
    val map = mapOf<String, JSONObject?>(APPLICATION_NAME to null)
    Whitebox.setInternalState(
        FetchedAppGateKeepersManager::class.java, "fetchedAppGateKeepers", map)
    val gk = FetchedAppGateKeepersManager.getGateKeeperForKey("anything", APPLICATION_NAME, false)
    val gk1 = FetchedAppGateKeepersManager.getGateKeeperForKey("anything", APPLICATION_NAME, true)
    assertThat(gk).isFalse
    assertThat(gk1).isTrue
    assertThat(asyncTaskExecutedTimes).isEqualTo(2)
  }

  @Test
  fun `parseAppGateKeepersFromJSON creates new JSONObject instead of mutating existing one`() {
    FetchedAppGateKeepersManager.parseAppGateKeepersFromJSON(APPLICATION_NAME, JSONObject(VALID_JSON))
    val fetchedMap = Whitebox.getInternalState<MutableMap<String, JSONObject>>(
        FetchedAppGateKeepersManager::class.java, "fetchedAppGateKeepers")
    val firstObj = fetchedMap[APPLICATION_NAME]!!
    val refreshed = JSONObject(
        """{"data":[{"gatekeepers":[{"key":"$GK1","value":false},{"key":"new_key","value":true}]}]}""")

    FetchedAppGateKeepersManager.parseAppGateKeepersFromJSON(APPLICATION_NAME, refreshed)
    val secondObj = fetchedMap[APPLICATION_NAME]!!

    assertThat(secondObj).isNotSameAs(firstObj)
    assertThat(firstObj.getBoolean(GK1)).isTrue
    assertThat(firstObj.has("new_key")).isFalse
    assertThat(secondObj.getBoolean(GK1)).isFalse
    assertThat(secondObj.getBoolean(GK2)).isFalse
    assertThat(secondObj.getBoolean("new_key")).isTrue
  }

  @Test
  fun `concurrent read and parse does not throw ConcurrentModificationException`() {
    // Pre-populate the map with gatekeepers
    FetchedAppGateKeepersManager.parseAppGateKeepersFromJSON(APPLICATION_NAME, JSONObject(VALID_JSON))

    val errors = ConcurrentLinkedQueue<Throwable>()
    val startLatch = CountDownLatch(1)
    val threads = mutableListOf<Thread>()

    // Reader threads that iterate the JSONObject via getGateKeepersForApplication
    repeat(10) {
      threads.add(Thread {
        startLatch.await()
        try {
          repeat(100) {
            FetchedAppGateKeepersManager.resetRuntimeGateKeeperCache()
            FetchedAppGateKeepersManager.getGateKeepersForApplication(APPLICATION_NAME)
          }
        } catch (e: Throwable) {
          errors.add(e)
        }
      })
    }

    // Add distinct keys so refreshes change the map structure while readers iterate.
    repeat(5) { writer ->
      threads.add(Thread {
        startLatch.await()
        try {
          repeat(100) { update ->
            val refreshed = JSONObject(
                """{"data":[{"gatekeepers":[{"key":"new_${writer}_$update","value":true}]}]}""")
            FetchedAppGateKeepersManager.parseAppGateKeepersFromJSON(APPLICATION_NAME, refreshed)
          }
        } catch (e: Throwable) {
          errors.add(e)
        }
      })
    }

    threads.forEach { it.start() }
    startLatch.countDown()
    threads.forEach { it.join(5000) }

    assertThat(threads.filter { it.isAlive }).isEmpty()
    assertThat(errors).isEmpty()
    FetchedAppGateKeepersManager.resetRuntimeGateKeeperCache()
    assertThat(FetchedAppGateKeepersManager.getGateKeepersForApplication(APPLICATION_NAME))
        .hasSize(502)
  }

  private fun metadataResponse(enabled: Boolean): JSONObject =
      JSONObject("""{"data":[{"gatekeepers":[{"key":"FBSDKFeatureMetadataBasic","value":$enabled}]}]}""")

  private fun assertIapSnapshotPreserved(initiallyEnabled: Boolean) {
    fun response(metadataEnabled: Boolean, iapEnabled: Boolean) = JSONObject("""
        {"data":[{"gatekeepers":[
          {"key":"${FeatureManager.Feature.AppEvents.toKey()}","value":true},
          {"key":"${FeatureManager.Feature.IapLogging.toKey()}","value":true},
          {"key":"${FeatureManager.Feature.IapLoggingLib2.toKey()}","value":$iapEnabled},
          {"key":"${FeatureManager.Feature.MetadataBasic.toKey()}","value":$metadataEnabled}
        ]}]}
        """)
    FetchedAppGateKeepersManager.parseAppGateKeepersFromJSON(
        APPLICATION_NAME, response(true, initiallyEnabled))
    assertThat(FeatureManager.isEnabled(FeatureManager.Feature.IapLoggingLib2))
        .isEqualTo(initiallyEnabled)
    assertThat(FeatureManager.isEnabled(FeatureManager.Feature.MetadataBasic)).isTrue

    val refreshed = FetchedAppGateKeepersManager.parseAppGateKeepersFromJSON(
        APPLICATION_NAME, response(false, !initiallyEnabled))

    assertThat(refreshed.getBoolean(FeatureManager.Feature.IapLoggingLib2.toKey()))
        .isEqualTo(!initiallyEnabled)
    assertThat(FeatureManager.isEnabled(FeatureManager.Feature.IapLoggingLib2))
        .isEqualTo(initiallyEnabled)
    assertThat(FeatureManager.isEnabled(FeatureManager.Feature.MetadataBasic)).isFalse
  }

  @Test
  fun `metadata refresh cannot switch IAP from the legacy to the new implementation`() {
    assertIapSnapshotPreserved(false)
  }

  @Test
  fun `metadata refresh cannot switch IAP from the new to the legacy implementation`() {
    assertIapSnapshotPreserved(true)
  }

  @Test
  fun `explicit off metadata override survives an on refresh until reset`() {
    FetchedAppGateKeepersManager.parseAppGateKeepersFromJSON(
        APPLICATION_NAME, metadataResponse(false))
    FetchedAppGateKeepersManager.getGateKeepersForApplication(APPLICATION_NAME)
    FetchedAppGateKeepersManager.setRuntimeGateKeeper(
        APPLICATION_NAME, GateKeeper("FBSDKFeatureMetadataBasic", false))

    FetchedAppGateKeepersManager.parseAppGateKeepersFromJSON(
        APPLICATION_NAME, metadataResponse(true))

    assertThat(FeatureManager.isEnabled(FeatureManager.Feature.MetadataBasic)).isFalse
    FetchedAppGateKeepersManager.resetRuntimeGateKeeperCache()
    assertThat(FeatureManager.isEnabled(FeatureManager.Feature.MetadataBasic)).isTrue
  }

  @Test
  fun `metadata override before the first read does not truncate the session snapshot`() {
    FetchedAppGateKeepersManager.parseAppGateKeepersFromJSON(
        APPLICATION_NAME, JSONObject(VALID_JSON))
    FetchedAppGateKeepersManager.parseAppGateKeepersFromJSON(
        APPLICATION_NAME, metadataResponse(true))
    FetchedAppGateKeepersManager.setRuntimeGateKeeper(
        APPLICATION_NAME, GateKeeper("FBSDKFeatureMetadataBasic", false))

    val result = FetchedAppGateKeepersManager.getGateKeepersForApplication(APPLICATION_NAME)

    assertThat(result["FBSDKFeatureMetadataBasic"]).isFalse
    assertThat(result[GK1]).isTrue
    assertThat(result[GK2]).isFalse
  }

  @Test
  fun `metadata feature observes refreshed off without resetting runtime cache`() {
    FetchedAppGateKeepersManager.parseAppGateKeepersFromJSON(
        APPLICATION_NAME, metadataResponse(true))
    assertThat(FeatureManager.isEnabled(FeatureManager.Feature.MetadataBasic)).isTrue

    FetchedAppGateKeepersManager.parseAppGateKeepersFromJSON(
        APPLICATION_NAME, metadataResponse(false))

    assertThat(FeatureManager.isEnabled(FeatureManager.Feature.MetadataBasic)).isFalse
  }

  @Test
  fun `metadata feature observes refreshed on after an initial off read`() {
    FetchedAppGateKeepersManager.parseAppGateKeepersFromJSON(
        APPLICATION_NAME, metadataResponse(false))
    assertThat(FeatureManager.isEnabled(FeatureManager.Feature.MetadataBasic)).isFalse

    FetchedAppGateKeepersManager.parseAppGateKeepersFromJSON(
        APPLICATION_NAME, metadataResponse(true))

    assertThat(FeatureManager.isEnabled(FeatureManager.Feature.MetadataBasic)).isTrue
  }

  @Test
  fun `runtime override and other session values survive refresh until reset`() {
    FetchedAppGateKeepersManager.parseAppGateKeepersFromJSON(
        APPLICATION_NAME, JSONObject(VALID_JSON))
    FetchedAppGateKeepersManager.getGateKeepersForApplication(APPLICATION_NAME)
    FetchedAppGateKeepersManager.setRuntimeGateKeeper(gateKeeper = GateKeeper(GK1, false))
    val refreshed = JSONObject(
        """{"data":[{"gatekeepers":[{"key":"$GK1","value":true},{"key":"$GK2","value":true}]}]}""")

    FetchedAppGateKeepersManager.parseAppGateKeepersFromJSON(APPLICATION_NAME, refreshed)

    val result = FetchedAppGateKeepersManager.getGateKeepersForApplication(APPLICATION_NAME)
    assertThat(result[GK1]).isFalse
    assertThat(result[GK2]).isFalse

    FetchedAppGateKeepersManager.resetRuntimeGateKeeperCache()
    assertThat(FetchedAppGateKeepersManager.getGateKeeperForKey(GK1, APPLICATION_NAME, false))
        .isTrue
    assertThat(FetchedAppGateKeepersManager.getGateKeeperForKey(GK2, APPLICATION_NAME, false))
        .isTrue
  }

  @Test
  fun `refresh adds MetadataBasic while other new gatekeepers wait for reset`() {
    FetchedAppGateKeepersManager.parseAppGateKeepersFromJSON(
        APPLICATION_NAME, JSONObject(VALID_JSON))
    val previous = FetchedAppGateKeepersManager.getGateKeepersForApplication(APPLICATION_NAME)
    val refreshed = JSONObject("""
        {"data":[{"gatekeepers":[
          {"key":"FBSDKFeatureMetadataBasic","value":true},
          {"key":"new_gatekeeper","value":true}
        ]}]}
        """)

    FetchedAppGateKeepersManager.parseAppGateKeepersFromJSON(APPLICATION_NAME, refreshed)

    assertThat(FetchedAppGateKeepersManager.getGateKeeperForKey(
        "FBSDKFeatureMetadataBasic", APPLICATION_NAME, false)).isTrue
    assertThat(previous).doesNotContainKey("FBSDKFeatureMetadataBasic")
    assertThat(FetchedAppGateKeepersManager.getGateKeepersForApplication(APPLICATION_NAME))
        .doesNotContainKey("new_gatekeeper")
    FetchedAppGateKeepersManager.resetRuntimeGateKeeperCache()
    assertThat(FetchedAppGateKeepersManager.getGateKeeperForKey(
        "new_gatekeeper", APPLICATION_NAME, false)).isTrue
  }

  @Test
  fun `empty refresh preserves last fetched values and ignores unknown runtime overrides`() {
    FetchedAppGateKeepersManager.parseAppGateKeepersFromJSON(
        APPLICATION_NAME, metadataResponse(true))
    FetchedAppGateKeepersManager.getGateKeepersForApplication(APPLICATION_NAME)
    FetchedAppGateKeepersManager.setRuntimeGateKeeper(gateKeeper = GateKeeper("unknown", true))

    FetchedAppGateKeepersManager.parseAppGateKeepersFromJSON(
        APPLICATION_NAME, JSONObject(EMPTY_RESPONSE))

    val result = FetchedAppGateKeepersManager.getGateKeepersForApplication(APPLICATION_NAME)
    assertThat(result["FBSDKFeatureMetadataBasic"]).isTrue
    assertThat(result).doesNotContainKey("unknown")
  }

  @Test
  fun `runtime override and refreshed values are isolated by application`() {
    val otherApplication = "other_application"
    FetchedAppGateKeepersManager.parseAppGateKeepersFromJSON(
        APPLICATION_NAME, metadataResponse(true))
    FetchedAppGateKeepersManager.parseAppGateKeepersFromJSON(
        otherApplication, metadataResponse(true))
    FetchedAppGateKeepersManager.getGateKeepersForApplication(APPLICATION_NAME)
    FetchedAppGateKeepersManager.getGateKeepersForApplication(otherApplication)
    FetchedAppGateKeepersManager.setRuntimeGateKeeper(
        APPLICATION_NAME, GateKeeper("FBSDKFeatureMetadataBasic", true))

    FetchedAppGateKeepersManager.parseAppGateKeepersFromJSON(
        APPLICATION_NAME, metadataResponse(false))
    FetchedAppGateKeepersManager.parseAppGateKeepersFromJSON(
        otherApplication, metadataResponse(false))

    assertThat(FetchedAppGateKeepersManager.getGateKeeperForKey(
        "FBSDKFeatureMetadataBasic", APPLICATION_NAME, false)).isTrue
    assertThat(FetchedAppGateKeepersManager.getGateKeeperForKey(
        "FBSDKFeatureMetadataBasic", otherApplication, true)).isFalse
    assertThat(FetchedAppGateKeepersManager.getGateKeepersForApplication(null)).isEmpty()
  }

  @Test
  fun `expired read cannot replace a freshly parsed response with older disk values`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val preferences = context.getSharedPreferences(
        FetchedAppGateKeepersManager.APP_GATEKEEPERS_PREFS_STORE, Context.MODE_PRIVATE)
    val preferenceKey = String.format(
        FetchedAppGateKeepersManager.APP_GATEKEEPERS_PREFS_KEY_FORMAT, APPLICATION_NAME)
    preferences.edit().putString(preferenceKey, metadataResponse(true).toString()).apply()
    Whitebox.setInternalState(FetchedAppGateKeepersManager::class.java, "timestamp", null as Long?)

    try {
      // This is the refresh worker's publication point before its disk write and timestamp update.
      FetchedAppGateKeepersManager.parseAppGateKeepersFromJSON(
          APPLICATION_NAME, metadataResponse(false))

      // A normal read interleaves while the disk still contains the previous true response.
      assertThat(FeatureManager.isEnabled(FeatureManager.Feature.MetadataBasic)).isFalse
    } finally {
      preferences.edit().remove(preferenceKey).apply()
    }
  }

  @Test
  fun `missing SDK values keep callbacks queued without scheduling a fetch`() {
    val callback = mock<FetchedAppGateKeepersManager.Callback>()
    val pendingCallbacks = Whitebox.getInternalState<
        ConcurrentLinkedQueue<FetchedAppGateKeepersManager.Callback>>(
        FetchedAppGateKeepersManager::class.java, "callbacks")
    try {
      whenever(FacebookSdk.getApplicationId()).thenAnswer { null }
      FetchedAppGateKeepersManager.loadAppGateKeepersAsync(callback)
      assertThat(pendingCallbacks).contains(callback)
      assertThat(asyncTaskExecutedTimes).isEqualTo(0)
      pendingCallbacks.remove(callback)

      whenever(FacebookSdk.getApplicationId()).thenReturn(APPLICATION_NAME)
      whenever(FacebookSdk.getApplicationContext()).thenAnswer { null }
      FetchedAppGateKeepersManager.loadAppGateKeepersAsync(callback)
      assertThat(pendingCallbacks).contains(callback)
      assertThat(asyncTaskExecutedTimes).isEqualTo(0)
    } finally {
      pendingCallbacks.remove(callback)
      whenever(FacebookSdk.getApplicationId()).thenReturn(APPLICATION_NAME)
      whenever(FacebookSdk.getApplicationContext())
          .thenReturn(ApplicationProvider.getApplicationContext())
    }
  }

  @Test
  fun `reset before SDK initialization does not resolve an application id`() {
    whenever(FacebookSdk.isInitialized()).thenReturn(false)
    whenever(FacebookSdk.getApplicationId()).thenThrow(IllegalStateException("SDK not initialized"))

    FetchedAppGateKeepersManager.resetRuntimeGateKeeperCache()
  }

  @Test
  fun `set gate keeper value`() {
    val test = JSONObject(VALID_JSON)
    FetchedAppGateKeepersManager.parseAppGateKeepersFromJSON(APPLICATION_NAME, test)
    FetchedAppGateKeepersManager.getGateKeepersForApplication(APPLICATION_NAME)

    FetchedAppGateKeepersManager.setRuntimeGateKeeper(gateKeeper = GateKeeper(GK1, true))
    val map1 = FetchedAppGateKeepersManager.getGateKeepersForApplication(APPLICATION_NAME)
    assertThat(map1.getValue(GK1)).isTrue
    FetchedAppGateKeepersManager.setRuntimeGateKeeper(gateKeeper = GateKeeper(GK1, false))
    val map2 = FetchedAppGateKeepersManager.getGateKeepersForApplication(APPLICATION_NAME)
    assertThat(map2.getValue(GK1)).isFalse
  }
}
