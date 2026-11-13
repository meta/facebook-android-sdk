/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.login

import android.net.Uri
import com.facebook.internal.Utility
import org.json.JSONObject

/**
 * The check both Custom Tab callback entry points run before navigating: a callback for an Android
 * SDK Login Via App (LVA) attempt is forwarded only while its attempt is live. An enhanced attempt
 * marks its client `state` JSON with [STATE_DISCRIMINATOR], and the same `state` survives
 * Continue. A callback without the mark is unaffected.
 *
 * This protects the user experience against stale callbacks; it is not a security control, and the
 * existing challenge validation still applies.
 */
internal object AndroidLvaCallbackCheck {
  const val STATE_DISCRIMINATOR = "lva"

  sealed class Result {
    /** Not an LVA callback: handled exactly as before. */
    object Unmarked : Result()

    /** The callback of the live attempt [record]; its version is used for any later clearIf. */
    data class Accepted(val record: AndroidLvaAttemptRecord.Record) : Result()

    /** An LVA callback without a live attempt (missing, unreadable, expired or another one). */
    object Rejected : Result()
  }

  fun check(callbackUrl: String?, now: Long = System.currentTimeMillis()): Result {
    val state = getState(callbackUrl)
    if (state == null || !hasDiscriminator(state)) {
      return Result.Unmarked
    }
    val record = AndroidLvaAttemptRecord.read()
    if (record == null || record.state != state) {
      return Result.Rejected
    }
    if (!AndroidLvaAttemptRecord.isLive(record, now)) {
      // Removed only if it is still this expired record, under the record's lock.
      AndroidLvaAttemptRecord.clearIf(record.state, record.version, expiredAt = now)
      return Result.Rejected
    }
    return Result.Accepted(record)
  }

  // The result parameters: query, then fragment, which wins. The encoded components are parsed so
  // each value is decoded exactly once, keeping the state equal to the stored one.
  private fun getState(callbackUrl: String?): String? =
      try {
        callbackUrl?.let {
          val uri = Uri.parse(it)
          val parameters = Utility.parseUrlQueryString(uri.encodedQuery)
          parameters.putAll(Utility.parseUrlQueryString(uri.encodedFragment))
          parameters.getString("state")
        }
      } catch (e: Exception) {
        null
      }

  private fun hasDiscriminator(state: String): Boolean =
      try {
        JSONObject(state).optInt(STATE_DISCRIMINATOR) == 1
      } catch (e: Exception) {
        false
      }
}
