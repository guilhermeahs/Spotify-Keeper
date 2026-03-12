package com.onix.spotifykeeper

import android.content.Context

object SpotifyControllerProvider {

    @Volatile
    private var controller: SpotifyController? = null

    fun get(context: Context): SpotifyController {
        return controller ?: synchronized(this) {
            controller ?: SpotifyController(context.applicationContext).also { created ->
                controller = created
            }
        }
    }
}
