/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.internal

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.RestrictTo
import androidx.annotation.VisibleForTesting
import com.facebook.FacebookSdk
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * The Android SDK Login Via App (LVA) gate and its full-treatment mode for the SDK's app, with
 * their own freshness rules. With full treatment on, every install gets the new client behavior;
 * off or absent, installs are split 50/50 with today's behavior (the first client-only experiment,
 * Stage A in the design doc).
 *
 * Unlike [FetchedAppGateKeepersManager], which keeps serving stale or merged values, a value here
 * comes only from an error-free, well-formed gatekeeper list, a key that list omits means false,
 * and a value whose last successful fetch is more than 24 hours old, or in the future, reads as
 * false. So turning the gate off on the server reaches every client within 24 hours (about 1 hour
 * for active, online clients), assuming a roughly correct device clock.
 *
 * com.facebook.internal is solely for the use of other packages within the Facebook SDK for
 * Android. Use of any of the classes in this package is unsupported, and they may be modified or
 * removed without warning at any time.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
object AndroidLvaGate {
    const val GATE_KEY = "FBSDKFeatureAndroidLva"
    const val FULL_TREATMENT_KEY = "FBSDKFeatureAndroidLvaFullTreatment"

    private const val PREFS_STORE = "com.facebook.internal.preferences.ANDROID_LVA_GATE"
    private const val ENABLED = "enabled"
    private const val FULL_TREATMENT = "full_treatment"
    private const val FETCHED_AT = "fetched_at"
    private val REFRESH_AFTER_MS = TimeUnit.HOURS.toMillis(1)
    private val EXPIRE_AFTER_MS = TimeUnit.HOURS.toMillis(24)
    private val RETRY_AFTER_MS = TimeUnit.MINUTES.toMillis(15)

    private val records: MutableMap<String, Record> = ConcurrentHashMap()
    private val isRefreshing = AtomicBoolean(false)
    @Volatile private var lastRefreshAttempt: Long? = null

    /** The gate values an attempt reads once at its start and uses throughout. */
    data class Snapshot(val isEnabled: Boolean, val isFullTreatment: Boolean)

    private val OFF = Snapshot(isEnabled = false, isFullTreatment = false)

    private data class Record(
        val isEnabled: Boolean,
        val isFullTreatment: Boolean,
        val fetchedAt: Long
    )

    /**
     * Records the gate from a response to the gatekeeper query made for [applicationId], if it is
     * error-free and well formed, and the SDK's app is still that app. It runs inside the manager's
     * fetch, so it never throws.
     */
    @JvmStatic
    fun onGateKeepersFetched(
        applicationId: String,
        response: JSONObject?,
        hasError: Boolean,
        now: Long = System.currentTimeMillis()
    ) {
        try {
            if (hasError || applicationId != FacebookSdk.getApplicationId()) {
                return
            }
            val gateKeepers =
                response?.optJSONArray("data")?.optJSONObject(0)?.optJSONArray("gatekeepers")
                    ?: return
            val record = parse(gateKeepers, now) ?: return
            publish(applicationId, record)
        } catch (e: Exception) {
            Utility.logd(Utility.LOG_TAG, e)
        }
    }

    /**
     * The gate for a new attempt. It reads as off when the last successful fetch is more than 24
     * hours old, in the future, or absent, and when anything goes wrong.
     */
    @JvmStatic
    fun getSnapshot(now: Long = System.currentTimeMillis()): Snapshot {
        return try {
            val record = getRecord(FacebookSdk.getApplicationId())
            val age = record?.let { now - it.fetchedAt }
            if (age == null || age < 0 || age > REFRESH_AFTER_MS) {
                refresh(record, now)
            }
            if (record == null || age == null || age < 0 || age > EXPIRE_AFTER_MS) {
                OFF
            } else {
                Snapshot(record.isEnabled, record.isFullTreatment)
            }
        } catch (e: Exception) {
            Utility.logd(Utility.LOG_TAG, e)
            OFF
        }
    }

    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    fun clearInMemoryForTesting() {
        records.clear()
        lastRefreshAttempt = null
        isRefreshing.set(false)
    }

    /**
     * The record from a gatekeeper list, or null if any entry is malformed: not an object, without a
     * string key, or without a boolean value.
     */
    private fun parse(gateKeepers: JSONArray, now: Long): Record? {
        var isEnabled = false
        var isFullTreatment = false
        for (i in 0 until gateKeepers.length()) {
            val gateKeeper = gateKeepers.opt(i) as? JSONObject ?: return null
            val key = gateKeeper.opt("key") as? String ?: return null
            val value = parseBoolean(gateKeeper.opt("value")) ?: return null
            when (key) {
                GATE_KEY -> isEnabled = value
                FULL_TREATMENT_KEY -> isFullTreatment = value
            }
        }
        return Record(isEnabled, isFullTreatment, now)
    }

    // The manager accepts the same representations (JSONObject.getBoolean).
    private fun parseBoolean(value: Any?): Boolean? =
        when (value) {
            is Boolean -> value
            "true" -> true
            "false" -> false
            else -> null
        }

    /**
     * Refreshes the gatekeepers. A gate that is on refreshes on its own schedule, at most every 15
     * minutes, because the manager's freshness rules can keep serving a cache this gate rejects.
     * Every other app keeps the manager's ordinary refresh, so it sees no extra fetches.
     */
    private fun refresh(record: Record?, now: Long) {
        var isClaimed = false
        try {
            if (record?.isEnabled != true) {
                FetchedAppGateKeepersManager.loadAppGateKeepersAsync()
                return
            }
            val lastAttempt = lastRefreshAttempt
            if (lastAttempt != null && now - lastAttempt in 0 until RETRY_AFTER_MS) {
                return
            }
            if (!isRefreshing.compareAndSet(false, true)) {
                return
            }
            isClaimed = true
            lastRefreshAttempt = now
            FacebookSdk.getExecutor().execute {
                try {
                    FetchedAppGateKeepersManager.queryAppGateKeepers(
                        FacebookSdk.getApplicationId(), true)
                } catch (e: Exception) {
                    Utility.logd(Utility.LOG_TAG, e)
                } finally {
                    isRefreshing.set(false)
                }
            }
        } catch (e: Exception) {
            // Scheduling failed: allow the next attempt, and don't affect the snapshot.
            if (isClaimed) {
                lastRefreshAttempt = null
                isRefreshing.set(false)
            }
            Utility.logd(Utility.LOG_TAG, e)
        }
    }

    // Loading and publishing hold the same lock, so a persisted record read before a fetch can't
    // replace the fetched one.
    @Synchronized
    private fun publish(applicationId: String, record: Record) {
        records[applicationId] = record
        val json =
            JSONObject()
                .put(ENABLED, record.isEnabled)
                .put(FULL_TREATMENT, record.isFullTreatment)
                .put(FETCHED_AT, record.fetchedAt)
        getPreferences()?.edit()?.putString(applicationId, json.toString())?.apply()
    }

    @Synchronized
    private fun getRecord(applicationId: String): Record? {
        records[applicationId]?.let {
            return it
        }
        val json = getPreferences()?.getString(applicationId, null) ?: return null
        return try {
            val parsed = JSONObject(json)
            Record(
                parsed.getBoolean(ENABLED),
                parsed.getBoolean(FULL_TREATMENT),
                parsed.getLong(FETCHED_AT))
                .also { records[applicationId] = it }
        } catch (e: JSONException) {
            Utility.logd(Utility.LOG_TAG, e)
            null
        }
    }

    private fun getPreferences(): SharedPreferences? {
        // A nullable local keeps this guard, as in FetchedAppGateKeepersManager: without a context
        // the gate reads as off instead of throwing.
        val context: Context? = FacebookSdk.getApplicationContext()
        return context?.getSharedPreferences(PREFS_STORE, Context.MODE_PRIVATE)
    }
}
