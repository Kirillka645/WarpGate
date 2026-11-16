package com.warpgate.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.warpgate.app.MainActivity
import com.warpgate.app.R
import com.warpgate.app.core.VpnProtector
import com.warpgate.app.core.WarpEngine
import com.warpgate.app.core.awgIpcSettings
import com.warpgate.app.core.warpIpcSettings
import com.warpgate.app.data.Profile
import com.warpgate.app.data.ProfileType
import com.warpgate.app.data.Store

/**
 * VpnService driving the Go core (amneziawg-go). Establishes a TUN and hands
 * the file descriptor to warpcore.TunnelStart.
 */
class WarpVpnService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "com.warpgate.app.CONNECT"
        const val ACTION_DISCONNECT = "com.warpgate.app.DISCONNECT"
        const val CHANNEL_ID = "vpn"
        const val NOTIF_ID = 1

        @Volatile var tunnelHandle: Int = 0
            private set
        @Volatile var isActive: Boolean = false
            private set
    }

    private var tun: ParcelFileDescriptor? = null
    private var masqueHandle: Int = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.w("WarpGate", "onStartCommand action=${intent?.action}")
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                stopVpn()
                return START_NOT_STICKY
            }
            else -> {
                connectForeground()
                val profile = Store.load().profiles
                    .firstOrNull { it.id == VpnSession.activeProfileId }
                if (profile == null) {
                    stopVpn()
                } else {
                    val ok = runCatching { startTunnel(profile) }
                        .onFailure { android.util.Log.e("WarpGate", "startTunnel failed", it) }
                        .isSuccess
                    if (!ok) stopVpn()
                }
                return START_STICKY
            }
        }
    }

    private fun connectForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel_vpn),
                    NotificationManager.IMPORTANCE_LOW).apply {
                    description = getString(R.string.notif_channel_vpn_desc)
                }
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = getString(R.string.notif_connecting)
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("WarpGate")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notif)
    }

    private fun updateNotification(profile: Profile) {
        val nm = getSystemService(NotificationManager::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle("WarpGate")
            .setContentText(getString(R.string.notif_connected, profile.name))
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        nm.notify(NOTIF_ID, notif)
    }

    private fun startTunnel(profile: Profile) {
        if (tunnelHandle != 0) stopGoTunnel()

        val cfg = Builder()
            .setSession("WarpGate — ${profile.name}")
            .setMtu(1280)
        when (profile.type) {
            ProfileType.WARP -> {
                val w = profile.warp!!
                cfg.addAddress(w.addressV4, 32)
                if (w.addressV6.isNotBlank()) cfg.addAddress(w.addressV6, 128)
                cfg.addDnsServer("1.1.1.1")
            }
            ProfileType.AWG -> {
                val a = profile.awg!!
                for (addr in a.address.split(",").map { it.trim() }.filter { it.isNotBlank() }) {
                    val parts = addr.split("/")
                    val prefix = parts.getOrNull(1)?.toIntOrNull() ?: 32
                    cfg.addAddress(parts[0], prefix)
                }
                cfg.addDnsServer(a.dns?.takeIf { it.isNotBlank() } ?: "1.1.1.1")
            }
            ProfileType.MASQUE -> {
                val m = profile.masque!!
                cfg.addAddress(m.ipv4, 32)
                if (m.ipv6.isNotBlank()) cfg.addAddress(m.ipv6, 128)
                cfg.addDnsServer("1.1.1.1")
            }
            else -> throw IllegalArgumentException("Unsupported profile type for tunnel")
        }
        cfg.addRoute("0.0.0.0", 0)
        cfg.addRoute("::", 0)
        cfg.setBlocking(true)

        val fd = cfg.establish()?.detachFd() ?: throw IllegalStateException("establish() returned null - no VPN consent")
        android.util.Log.w("WarpGate", "tun fd=$fd")

        when (profile.type) {
            ProfileType.WARP -> {
                val settings = warpIpcSettings(profile.warp!!)
                tunnelHandle = WarpEngine.tunnelStart(
                    fd, 1280, settings, profile.warp!!.clientReserved, VpnProtector(this)
                )
        android.util.Log.w("WarpGate", "tunnel handle=$tunnelHandle")
            }
            ProfileType.AWG -> {
                val settings = awgIpcSettings(profile.awg!!)
                tunnelHandle = WarpEngine.tunnelStart(fd, 1280, settings, "", VpnProtector(this))
            }
            ProfileType.MASQUE -> {
                val m = profile.masque!!
                val json = "{\"private_key\":\"${m.privateKey}\",\"endpoint_v4\":\"${m.endpointV4}\"," +
                    "\"endpoint_v6\":\"${m.endpointV6}\",\"pub_key\":\"${m.pubKey.replace("\n", "\\n")}\"," +
                    "\"id\":\"${m.id}\",\"token\":\"${m.token}\",\"ipv4\":\"${m.ipv4}\"," +
                    "\"ipv6\":\"${m.ipv6}\",\"license\":\"${m.license ?: ""}\"}"
                masqueHandle = WarpEngine.masqueStart(fd, 1280, json, VpnProtector(this))
            }
            else -> throw IllegalArgumentException()
        }
        isActive = true
        VpnSession.onStateChanged()
        updateNotification(profile)
    }

    private fun stopVpn() {
        stopGoTunnel()
        isActive = false
        VpnSession.onStateChanged()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopGoTunnel() {
        if (tunnelHandle != 0) {
            WarpEngine.tunnelStop(tunnelHandle)
            tunnelHandle = 0
        }
        if (masqueHandle != 0) {
            WarpEngine.masqueStop(masqueHandle)
            masqueHandle = 0
        }
        runCatching { tun?.close() }
        tun = null
    }

    override fun onDestroy() {
        stopGoTunnel()
        isActive = false
        VpnSession.onStateChanged()
        super.onDestroy()
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }
}
