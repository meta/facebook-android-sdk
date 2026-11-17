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
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * A context whose Android SDK LVA attempt record still reads what [context] stored, but whose
 * writes all fail. Tests return it from the mocked `FacebookSdk.getApplicationContext()`. Build it
 * into a local first: calling it inside `thenReturn(...)` nests stubbing, which Mockito rejects.
 */
internal fun failingAndroidLvaRecordWrites(context: Context): Context {
  val stored =
      context
          .getSharedPreferences(AndroidLvaAttemptRecord.PREFS_STORE, Context.MODE_PRIVATE)
          .getString(AndroidLvaAttemptRecord.ACTIVE_ATTEMPT, null)
  val editor = mock<SharedPreferences.Editor>()
  whenever(editor.putString(any(), anyOrNull())).thenReturn(editor)
  whenever(editor.remove(any())).thenReturn(editor)
  whenever(editor.commit()).thenReturn(false)
  val prefs = mock<SharedPreferences>()
  whenever(prefs.getString(eq(AndroidLvaAttemptRecord.ACTIVE_ATTEMPT), anyOrNull()))
      .thenReturn(stored)
  whenever(prefs.edit()).thenReturn(editor)
  val failingContext = mock<Context>()
  whenever(failingContext.getSharedPreferences(any(), any())).thenReturn(prefs)
  return failingContext
}
