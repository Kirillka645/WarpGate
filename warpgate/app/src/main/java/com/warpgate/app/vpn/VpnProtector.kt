package com.warpgate.app.core

import android.net.VpnService

/**
 * Go-side socket protection callback: protects UDP sockets created by the
 * tunnel engine from being routed into the VPN itself.
 */
class VpnProtector(private val svc: VpnService) : warpcore.Protector {
    override fun protect(fd: Int): Boolean = runCatching { svc.protect(fd) }.getOrDefault(false)
}
