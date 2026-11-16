package com.warpgate.app.vpn

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.warpgate.app.data.Store

/**
 * Session controller: starts/stops the VpnService and exposes connection
 * state to the UI.
 */
object VpnSession {
    var activeProfileId by mutableStateOf<String?>(null)
        private set
    var connected by mutableStateOf(false)
        private set
    var connecting by mutableStateOf(false)
        private set

    fun onStateChanged() {
        connected = WarpVpnService.isActive
        connecting = false
    }

    fun start(context: Context, profileId: String) {
        android.util.Log.w("WarpGate", "VpnSession.start profile=$profileId")
        activeProfileId = profileId
        connecting = true
        val st = Store.load().copy(activeProfileId = profileId)
        Store.save(st)
        val intent = Intent(context, WarpVpnService::class.java).apply {
            action = WarpVpnService.ACTION_CONNECT
        }
        context.startService(intent)
    }

    fun stop(context: Context) {
        connecting = false
        val intent = Intent(context, WarpVpnService::class.java).apply {
            action = WarpVpnService.ACTION_DISCONNECT
        }
        context.startService(intent)
        activeProfileId = null
        val st = Store.load().copy(activeProfileId = null)
        Store.save(st)
    }
}
