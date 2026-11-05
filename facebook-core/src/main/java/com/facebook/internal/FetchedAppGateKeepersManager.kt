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
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RestrictTo
import androidx.annotation.VisibleForTesting
import com.facebook.FacebookSdk
import com.facebook.GraphRequest
import com.facebook.internal.Utility.isNullOrEmpty
import com.facebook.internal.gatekeeper.GateKeeper
import com.facebook.internal.gatekeeper.GateKeeperRuntimeCache
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * com.facebook.internal is solely for the use of other packages within the Facebook SDK for
 * Android. Use of any of the classes in this package is unsupported, and they may be modified or
 * removed without warning at any time.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
object FetchedAppGateKeepersManager {
    private const val TAG = "FetchedAppGateKeepersManager"
    private const val APP_GATEKEEPERS_PREFS_STORE =
        "com.facebook.internal.preferences.APP_GATEKEEPERS"
    private const val APP_GATEKEEPERS_PREFS_KEY_FORMAT = "com.facebook.internal.APP_GATEKEEPERS.%s"
    private const val APP_PLATFORM = "android"
    private const val APPLICATION_GATEKEEPER_EDGE = "mobile_sdk_gk"
    private const val APPLICATION_GATEKEEPER_FIELD = "gatekeepers"
    private const val APPLICATION_GRAPH_DATA = "data"
    private const val APPLICATION_FIELDS = GraphRequest.FIELDS_PARAM
    private const val APPLICATION_PLATFORM = "platform"
    private const val APPLICATION_SDK_VERSION = "sdk_version"
    private val isLoading = AtomicBoolean(false)
    private val callbacks = ConcurrentLinkedQueue<Callback>()
    private val fetchedAppGateKeepers: MutableMap<String, JSONObject> = ConcurrentHashMap()
    private const val APPLICATION_GATEKEEPER_CACHE_TIMEOUT = 60 * 60 * 1000.toLong()
    @Volatile private var timestamp: Long? = null

    // GateKeeper values in runtime. It may be changed through the UI.
    @Volatile private var gateKeeperRuntimeCache: GateKeeperRuntimeCache? = null

    @Synchronized
    private fun getGateKeepersForAppId(applicationId: String): JSONObject? {
        return fetchedAppGateKeepers[applicationId]
    }

    @Synchronized
    private fun setGateKeepersForAppId(applicationId: String, json: JSONObject) {
        fetchedAppGateKeepers[applicationId] = json
    }

    fun loadAppGateKeepersAsync() {
        loadAppGateKeepersAsync(null)
    }

    @JvmStatic
    @Synchronized
    fun loadAppGateKeepersAsync(callback: Callback?) {
        if (callback != null) {
            callbacks.add(callback)
        }
        val applicationId = FacebookSdk.getApplicationId()
        if (isTimestampValid(timestamp) && getGateKeepersForAppId(applicationId) != null) {
            pollCallbacks()
            return
        }
        // Nullable locals keep these guards: this runs from FacebookException's constructor, which
        // must not throw.
        val context: Context? = FacebookSdk.getApplicationContext()
        val gateKeepersKey = String.format(APP_GATEKEEPERS_PREFS_KEY_FORMAT, applicationId)
        if (context == null) {
            return
        }

        // See if we had a cached copy of gatekeepers and use that immediately
        val gateKeepersSharedPrefs =
            context.getSharedPreferences(APP_GATEKEEPERS_PREFS_STORE, Context.MODE_PRIVATE)
        val gateKeepersJSONString = gateKeepersSharedPrefs.getString(gateKeepersKey, null)
        if (!gateKeepersJSONString.isNullOrEmpty()) {
            var gateKeepersJSON: JSONObject? = null
            try {
                gateKeepersJSON = JSONObject(gateKeepersJSONString)
            } catch (je: JSONException) {
                Utility.logd(Utility.LOG_TAG, je)
            }
            if (gateKeepersJSON != null) {
                parseAppGateKeepersFromJSON(applicationId, gateKeepersJSON)
            }
        }
        val executor: Executor? = FacebookSdk.getExecutor()
        if (executor == null) {
            return
        }
        if (!isLoading.compareAndSet(false, true)) {
            return
        }
        executor.execute {
            val gateKeepersResultJSON = getAppGateKeepersQueryResponse()
            if (gateKeepersResultJSON.length() != 0) {
                parseAppGateKeepersFromJSON(applicationId, gateKeepersResultJSON)
                gateKeepersSharedPrefs
                    .edit()
                    .putString(gateKeepersKey, gateKeepersResultJSON.toString())
                    .apply()
                // Update timestamp only when the GKs are successfully fetched and stored
                timestamp = System.currentTimeMillis()
            }
            pollCallbacks()
            isLoading.set(false)
        }
    }

    private fun pollCallbacks() {
        val handler = Handler(Looper.getMainLooper())
        while (!callbacks.isEmpty()) {
            val callback = callbacks.poll()
            // callback can be null when function pollCallbacks is called in multiple threads
            if (callback != null) {
                handler.post { callback.onCompleted() }
            }
        }
    }

    // Note that this method makes a synchronous Graph API call, so should not be called from the
    // main thread. This call can block for long time if network is not available and network
    // timeout is long.
    @JvmStatic
    fun queryAppGateKeepers(applicationId: String, forceRequery: Boolean): JSONObject {
        if (!forceRequery) {
            val cached = getGateKeepersForAppId(applicationId)
            if (cached != null) {
                return cached
            }
        }
        val response = getAppGateKeepersQueryResponse()
        val context = FacebookSdk.getApplicationContext()
        val gateKeepersKey = String.format(APP_GATEKEEPERS_PREFS_KEY_FORMAT, applicationId)
        val gateKeepersSharedPrefs =
            context.getSharedPreferences(APP_GATEKEEPERS_PREFS_STORE, Context.MODE_PRIVATE)
        gateKeepersSharedPrefs.edit().putString(gateKeepersKey, response.toString()).apply()
        return parseAppGateKeepersFromJSON(applicationId, response)
    }

    /**
     * Obtain all gatekeeper values as a map
     *
     * @param applicationId the app id that gatekeepers related to
     * @return map of all settings/gatekeepers
     */
    fun getGateKeepersForApplication(applicationId: String?): Map<String, Boolean> {
        loadAppGateKeepersAsync()
        if (applicationId == null || getGateKeepersForAppId(applicationId) == null) {
            return HashMap()
        }
        val cacheList = gateKeeperRuntimeCache?.dumpGateKeepers(applicationId)
        return if (cacheList != null) {
            val cacheMap = HashMap<String, Boolean>()
            cacheList.forEach { cacheMap[it.name] = it.value }
            cacheMap
        } else {
            val output: MutableMap<String, Boolean> = HashMap()
            val jsonObject: JSONObject = getGateKeepersForAppId(applicationId) ?: JSONObject()
            val jsonIterator = jsonObject.keys()
            while (jsonIterator.hasNext()) {
                val key = jsonIterator.next()
                output[key] = jsonObject.optBoolean(key)
            }
            val runtimeCache = gateKeeperRuntimeCache ?: GateKeeperRuntimeCache()
            runtimeCache.setGateKeepers(applicationId, output.map { GateKeeper(it.key, it.value) })
            gateKeeperRuntimeCache = runtimeCache
            output
        }
    }

    @JvmStatic
    fun getGateKeeperForKey(name: String, applicationId: String?, defaultValue: Boolean): Boolean {
        val map = getGateKeepersForApplication(applicationId)
        return if (!map.containsKey(name)) {
            defaultValue
        } else map[name] ?: return defaultValue
    }

    /**
     * Set GateKeeper values in the runtime cache, so that it will affect GK reading later. Only if GK
     * exists in the cache, it will be updated.
     *
     * @param applicationId Application ID
     * @param gateKeeper name-value pair of the Gate Keeper to be set
     */
    @JvmStatic
    fun setRuntimeGateKeeper(
        applicationId: String = FacebookSdk.getApplicationId(),
        gateKeeper: GateKeeper
    ) {
        if (gateKeeperRuntimeCache?.getGateKeeper(applicationId, gateKeeper.name) != null) {
            gateKeeperRuntimeCache?.setGateKeeper(applicationId, gateKeeper)
        } else {
            Log.w(TAG, "Missing gatekeeper runtime cache")
        }
    }

    /**
     * Invalid runtime GateKeeper cache so that the manager will load original GK values next time.
     */
    @JvmStatic
    fun resetRuntimeGateKeeperCache() {
        gateKeeperRuntimeCache?.resetCache()
    }

    // Note that this method makes a synchronous Graph API call, so should not be called from the
    // main thread.
    private fun getAppGateKeepersQueryResponse(): JSONObject {
        // The request is sent with the client token of the app read here, the same token that
        // GraphRequest would add, so the gate can record the response for that app. Without one,
        // GraphRequest adds whatever is configured when it sends, and the gate ignores the
        // response. Nullable locals keep this from ever failing the manager's fetch.
        val applicationId: String? = FacebookSdk.getApplicationId()
        val clientToken: String? = FacebookSdk.getClientToken()
        val isBoundToApp = !applicationId.isNullOrEmpty() && !clientToken.isNullOrEmpty()
        val appGateKeepersParams = Bundle()
        appGateKeepersParams.putString(APPLICATION_PLATFORM, APP_PLATFORM)
        appGateKeepersParams.putString(APPLICATION_SDK_VERSION, FacebookSdk.getSdkVersion())
        appGateKeepersParams.putString(APPLICATION_FIELDS, APPLICATION_GATEKEEPER_FIELD)
        if (isBoundToApp) {
            appGateKeepersParams.putString(
                GraphRequest.ACCESS_TOKEN_PARAM, "$applicationId|$clientToken")
        }
        val request =
            GraphRequest.newGraphPathRequest(
                null, String.format("app/%s", APPLICATION_GATEKEEPER_EDGE), null
            )
        request.parameters = appGateKeepersParams
        val response = request.executeAndWait()
        AndroidLvaGate.onGateKeepersFetched(
            applicationId.orEmpty(), response.jsonObject, response.error != null || !isBoundToApp)
        return response.jsonObject ?: JSONObject()
    }

    @Synchronized
    @JvmStatic
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal fun parseAppGateKeepersFromJSON(
        applicationId: String,
        gateKeepersJSON: JSONObject?
    ): JSONObject {
        val existing = getGateKeepersForAppId(applicationId)
        val result = if (existing != null) JSONObject(existing.toString()) else JSONObject()
        val gateKeepers =
            gateKeepersJSON?.optJSONArray(APPLICATION_GRAPH_DATA)?.optJSONObject(0) ?: JSONObject()
        // If there does exist a valid JSON object in arr, initialize result with this JSON object
        val data = gateKeepers.optJSONArray(APPLICATION_GATEKEEPER_FIELD) ?: JSONArray()
        for (i in 0 until data.length()) {
            try {
                val gk = data.getJSONObject(i)
                result.put(gk.getString("key"), gk.getBoolean("value"))
            } catch (je: JSONException) {
                Utility.logd(Utility.LOG_TAG, je)
            }
        }
        setGateKeepersForAppId(applicationId, result)
        return result
    }

    private fun isTimestampValid(timestamp: Long?): Boolean {
        return if (timestamp == null) {
            false
        } else System.currentTimeMillis() - timestamp < APPLICATION_GATEKEEPER_CACHE_TIMEOUT
    }

    /** Callback for fetch GK when the GK results are valid. */
    fun interface Callback {
        /** The method that will be called when the GK request completes. */
        fun onCompleted()
    }
}
