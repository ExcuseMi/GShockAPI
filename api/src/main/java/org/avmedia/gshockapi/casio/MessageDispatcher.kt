/*
 * Created by Ivo Zivkov (izivkov@gmail.com) on 2022-03-30, 12:06 a.m.
 * Copyright (c) 2022 . All rights reserved.
 * Last modified 2022-03-29, 10:02 a.m.
 */

package org.avmedia.gshockapi.casio

import android.os.Build
import androidx.annotation.RequiresApi
import org.avmedia.gshockapi.io.ExerciseIO
import org.avmedia.gshockapi.protocols.WatchProtocol
import org.json.JSONObject
import timber.log.Timber

/**
 * The single-watch messages and notifications: both go to the one link's [LinkDispatcher], which carries an outbound
 * action out with that link's own objects and routes a notification with that link's own model's protocol.
 */
@RequiresApi(Build.VERSION_CODES.O)
object MessageDispatcher {
    /** The actions that are not per link yet (other models): they reach the single-watch objects. */
    private val singleWatchSenders: Map<String, (String) -> Unit> =
        mapOf(
            "GET_EXERCISE_PROFILE" to { ExerciseIO.sendToWatch(it) },
            "SET_EXERCISE_PROFILE" to { ExerciseIO.sendToWatchSet(it) },
        )

    private fun extractAction(message: String): String? =
        runCatching { JSONObject(message).getString("action") }
            .onFailure { Timber.e("Failed to parse action from message: $message") }
            .getOrNull()

    fun sendToWatch(message: String) {
        val action = extractAction(message) ?: return
        val dispatcher = WatchLink.dispatcher
        when {
            dispatcher.sends(action) -> dispatcher.sendToWatch(message)
            action in singleWatchSenders -> singleWatchSenders.getValue(action)(message)
            else -> Timber.e("No sender registered for action: $action")
        }
    }

    /**
     * A notification of the one watch: its link's dispatcher routes it ([LinkDispatcher]) with the protocol of that
     * link's model. [protocol] only for a test that names one.
     */
    fun onReceived(
        data: String,
        protocol: WatchProtocol? = null,
    ) {
        val dispatcher = WatchLink.dispatcher
        if (protocol == null) dispatcher.onReceived(data, source = WatchLink.currentSource())
        else dispatcher.onReceived(data, protocol, WatchLink.currentSource())
    }
}
