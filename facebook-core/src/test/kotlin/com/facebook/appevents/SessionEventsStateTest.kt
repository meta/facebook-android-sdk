/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.appevents

import android.os.Bundle
import com.facebook.FacebookPowerMockTestCase
import com.facebook.GraphRequest
import com.facebook.appevents.eventdeactivation.EventDeactivationManager
import com.facebook.appevents.internal.AppEventsLoggerUtility
import com.facebook.internal.AttributionIdentifiers
import com.facebook.internal.FeatureManager
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.spy
import org.mockito.kotlin.whenever
import org.powermock.api.mockito.PowerMockito
import org.powermock.api.mockito.PowerMockito.mockStatic
import org.powermock.core.classloader.annotations.PrepareForTest
import org.powermock.reflect.Whitebox
import org.powermock.reflect.internal.WhiteboxImpl

@PrepareForTest(
    EventDeactivationManager::class,
    AppEventsLoggerUtility::class,
    SessionEventsState::class,
    FeatureManager::class
)
class SessionEventsStateTest : FacebookPowerMockTestCase() {

    private lateinit var sessionEventsState: SessionEventsState
    private val appEvent = AppEvent("ctxName", "eventName", 0.0, Bundle(), true, true, null)
    private val appEventWithOperationalData =
        AppEvent("ctxName", "eventName", 0.0, Bundle(), true, true, null, OperationalData())

    @Before
    fun init() {
        sessionEventsState = spy(SessionEventsState(AttributionIdentifiers(), "anonGUID"))
        Whitebox.setInternalState(SessionEventsState::class.java, "MAX_ACCUMULATED_LOG_EVENTS", 2)
        mockStatic(AppEventsLoggerUtility::class.java)
        mockStatic(EventDeactivationManager::class.java)
        mockStatic(FeatureManager::class.java)
    }

    @After
    fun restoreAttributionIdentifiers() {
        WhiteboxImpl.setInternalState(
            AttributionIdentifiers::class.java, "Companion", originalAttributionIdentifiers
        )
    }

    @Test
    fun `populate request with the feature uses the current identifiers`() {
        val (stored, _) = stubIdentifierSources(current = AttributionIdentifiers())
        whenever(FeatureManager.isEnabled(FeatureManager.Feature.AndroidAdvertisingIDReliability))
            .thenReturn(true)
        val state = SessionEventsState(stored, "anonGUID")
        state.addEvent(appEvent)
        val request = GraphRequest()

        state.populateRequest(request, mock(), true, false)

        assertEquals("current", request.graphObject?.optString("identifiers"))
        verify(mockAttributionIdentifiersCompanion).getAttributionIdentifiers(any())
    }

    @Test
    fun `populate request with the feature keeps a captured advertising id over a failed refresh`() {
        // A failed hourly refresh returns blank identifiers, not null.
        val (stored, _) =
            stubIdentifierSources(
                current = AttributionIdentifiers(), stored = identifiersWithAdvertisingId())
        whenever(FeatureManager.isEnabled(FeatureManager.Feature.AndroidAdvertisingIDReliability))
            .thenReturn(true)
        val state = SessionEventsState(stored, "anonGUID")
        state.addEvent(appEvent)
        val request = GraphRequest()

        state.populateRequest(request, mock(), true, false)

        assertEquals("stored", request.graphObject?.optString("identifiers"))
    }

    @Test
    fun `populate request with the feature keeps the captured limit tracking flag when collection is disabled`() {
        // With collection disabled every androidAdvertiserId is null, so the choice has to rest on
        // whether the read succeeded. A failed refresh must not replace isTrackingLimited = true.
        val (stored, _) =
            stubIdentifierSources(
                current = AttributionIdentifiers(), stored = identifiersReadWithCollectionDisabled())
        whenever(FeatureManager.isEnabled(FeatureManager.Feature.AndroidAdvertisingIDReliability))
            .thenReturn(true)
        val state = SessionEventsState(stored, "anonGUID")
        state.addEvent(appEvent)
        val request = GraphRequest()

        state.populateRequest(request, mock(), true, false)

        assertEquals("stored", request.graphObject?.optString("identifiers"))
    }

    @Test
    fun `populate request with the feature prefers a current advertising id over the captured one`() {
        val (stored, _) =
            stubIdentifierSources(
                current = identifiersWithAdvertisingId(), stored = identifiersWithAdvertisingId())
        whenever(FeatureManager.isEnabled(FeatureManager.Feature.AndroidAdvertisingIDReliability))
            .thenReturn(true)
        val state = SessionEventsState(stored, "anonGUID")
        state.addEvent(appEvent)
        val request = GraphRequest()

        state.populateRequest(request, mock(), true, false)

        assertEquals("current", request.graphObject?.optString("identifiers"))
    }

    @Test
    fun `populate request with the feature falls back to the stored identifiers`() {
        val (stored, _) = stubIdentifierSources(current = null)
        whenever(FeatureManager.isEnabled(FeatureManager.Feature.AndroidAdvertisingIDReliability))
            .thenReturn(true)
        val state = SessionEventsState(stored, "anonGUID")
        state.addEvent(appEvent)
        val request = GraphRequest()

        state.populateRequest(request, mock(), true, false)

        assertEquals("stored", request.graphObject?.optString("identifiers"))
    }

    @Test
    fun `populate request without the feature uses the stored identifiers`() {
        val (stored, _) = stubIdentifierSources(current = AttributionIdentifiers())
        val state = SessionEventsState(stored, "anonGUID")
        state.addEvent(appEvent)
        val request = GraphRequest()

        state.populateRequest(request, mock(), true, false)

        assertEquals("stored", request.graphObject?.optString("identifiers"))
        verify(mockAttributionIdentifiersCompanion, never()).getAttributionIdentifiers(any())
    }

    private lateinit var mockAttributionIdentifiersCompanion: AttributionIdentifiers.Companion
    private val originalAttributionIdentifiers = AttributionIdentifiers.Companion

    // Tags the publish params with which identifiers object the request was built from.
    private fun identifiersWithAdvertisingId(): AttributionIdentifiers {
        val identifiers = mock<AttributionIdentifiers>()
        whenever(identifiers.isAdvertisingIdRead).thenReturn(true)
        whenever(identifiers.androidAdvertiserId).thenReturn("38400000-8cf0-11bd-b23e-10b96e40000d")
        return identifiers
    }

    // What an app with advertiser ID collection disabled captures for a limit-ad-tracking user:
    // the read succeeded, the ID itself is hidden, and the limit-tracking flag is set.
    private fun identifiersReadWithCollectionDisabled(): AttributionIdentifiers {
        val identifiers = mock<AttributionIdentifiers>()
        whenever(identifiers.isAdvertisingIdRead).thenReturn(true)
        whenever(identifiers.androidAdvertiserId).thenReturn(null)
        whenever(identifiers.isTrackingLimited).thenReturn(true)
        return identifiers
    }

    private fun stubIdentifierSources(
        current: AttributionIdentifiers?,
        stored: AttributionIdentifiers = AttributionIdentifiers()
    ): Pair<AttributionIdentifiers, AttributionIdentifiers?> {
        mockAttributionIdentifiersCompanion =
            PowerMockito.mock(AttributionIdentifiers.Companion::class.java)
        WhiteboxImpl.setInternalState(
            AttributionIdentifiers::class.java, "Companion", mockAttributionIdentifiersCompanion
        )
        whenever(mockAttributionIdentifiersCompanion.getAttributionIdentifiers(any()))
            .thenReturn(current)
        whenever(
            AppEventsLoggerUtility.getJSONObjectForGraphAPICall(
                any(), eq(stored), anyOrNull(), any(), any()
            )
        )
            .thenReturn(JSONObject().put("identifiers", "stored"))
        if (current != null) {
            whenever(
                AppEventsLoggerUtility.getJSONObjectForGraphAPICall(
                    any(), eq(current), anyOrNull(), any(), any()
                )
            )
                .thenReturn(JSONObject().put("identifiers", "current"))
        }
        return Pair(stored, current)
    }

    @Test
    fun `test get events`() {
        sessionEventsState.addEvent(appEvent)
        assertEquals(1, sessionEventsState.eventsToPersist.size)
        // "The getter is doing more than get, it also clears it :)"
        assertEquals(0, sessionEventsState.eventsToPersist.size)
    }

    @Test
    fun `multiple adds`() {
        val appEvent1 = AppEvent("ctxName", "eventName1", 0.0, Bundle(), true, true, null)
        val appEvent2 = AppEvent("ctxName", "eventName2", 0.0, Bundle(), true, true, null)
        val appEvent3 = AppEvent("ctxName", "eventName3", 0.0, Bundle(), true, true, null)
        sessionEventsState.addEvent(appEvent1)
        sessionEventsState.addEvent(appEvent2)
        sessionEventsState.addEvent(appEvent3)

        assertEquals(2, sessionEventsState.accumulatedEventCount)
        val accumulatedEvents = sessionEventsState.eventsToPersist
        assertEquals(0, sessionEventsState.accumulatedEventCount)
        assertEquals(2, accumulatedEvents.size)
        assertEquals("eventName1", accumulatedEvents[0].name)
        assertEquals("eventName2", accumulatedEvents[1].name)
    }

    @Test
    fun `populate request include implicit`() {
        sessionEventsState.addEvent(appEvent)
        val result = sessionEventsState.populateRequest(GraphRequest(), mock(), true, false)
        assertEquals(1, result)
    }

    @Test
    fun `populate request without operational parameters`() {
        whenever(FeatureManager.isEnabled(FeatureManager.Feature.IapLoggingLib5To7))
            .thenReturn(false)
        whenever(FeatureManager.isEnabled(FeatureManager.Feature.IapLoggingLib8Plus))
            .thenReturn(false)
        sessionEventsState.addEvent(appEventWithOperationalData)
        val request = GraphRequest()
        val result = sessionEventsState.populateRequest(request, mock(), true, false)
        assertEquals(1, result)
        assertEquals(request.parameters.getCharSequence("operational_parameters"), null)
    }

    @Test
    fun `populate request with operational parameters for billing library v5 to v7`() {
        whenever(FeatureManager.isEnabled(FeatureManager.Feature.IapLoggingLib5To7))
            .thenReturn(true)
        sessionEventsState.addEvent(appEventWithOperationalData)
        val request = GraphRequest()
        val result = sessionEventsState.populateRequest(request, mock(), true, false)
        assertEquals(1, result)
        assertEquals(request.parameters.getCharSequence("operational_parameters"), "[{}]")
    }

    @Test
    fun `populate request with operational parameters for billing library v8 plus`() {
        whenever(FeatureManager.isEnabled(FeatureManager.Feature.IapLoggingLib5To7))
            .thenReturn(false)
        whenever(FeatureManager.isEnabled(FeatureManager.Feature.IapLoggingLib8Plus))
            .thenReturn(true)
        sessionEventsState.addEvent(appEventWithOperationalData)
        val request = GraphRequest()
        val result = sessionEventsState.populateRequest(request, mock(), true, false)
        assertEquals(1, result)
        assertEquals(request.parameters.getCharSequence("operational_parameters"), "[{}]")
    }

    @Test
    fun `populate request implicit event only`() {
        sessionEventsState.addEvent(appEvent)
        val result = sessionEventsState.populateRequest(GraphRequest(), mock(), false, false)
        assertEquals(0, result)
    }

    @Test
    fun `populate no implicit event nor logging`() {
        val appEvent1 =
            AppEvent("ctxName", "eventName3", 0.0, Bundle(), /*implicit logged*/ false, true, null)
        sessionEventsState.addEvent(appEvent1)
        val result = sessionEventsState.populateRequest(GraphRequest(), mock(), false, false)
        assertEquals(1, result)
    }
}
