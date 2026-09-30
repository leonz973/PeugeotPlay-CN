package com.shilapi.xcertplay.network

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Wi-Fi Direct keeps the scoped link-local path (it has no DHCP server). A manual
 * AP runs DHCP, and iPhones discover it primarily over IPv4 mDNS, so those callers
 * pass preferIpv4; JmDNS cannot answer queries when bound to an IPv6 link-local.
 */
internal fun wirelessHostAddress(
    addresses: List<InetAddress>,
    interfaceIndex: Int,
    preferIpv4: Boolean = false,
): InetAddress? {
    val usableIpv4 = addresses.firstOrNull {
        it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress &&
            !it.isAnyLocalAddress && !it.isMulticastAddress
    }
    if (preferIpv4) usableIpv4?.let { return it }
    if (interfaceIndex > 0) {
        addresses.filterIsInstance<Inet6Address>().firstOrNull { it.isLinkLocalAddress }?.let {
            return Inet6Address.getByAddress(null, it.address, interfaceIndex)
        }
    }
    return usableIpv4
}
