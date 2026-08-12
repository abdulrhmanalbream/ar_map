package com.sarab.vision

import android.content.Context

/**
 * Single shared [CampusState] across activities.
 *
 * The AR screen and the list/map screen must agree on the selected target,
 * the GPS fix and the landmark set. Creating a state object per activity
 * would mean choosing a destination in the list and having the AR view know
 * nothing about it.
 *
 * Held at application scope rather than passed through Intents because the
 * state includes live sensor streams, which are not serialisable.
 */
object CampusApp {

    @Volatile
    private var instance: CampusState? = null

    fun state(context: Context): CampusState =
        instance ?: synchronized(this) {
            instance ?: CampusState(context.applicationContext).also {
                it.load()
                instance = it
            }
        }
}
