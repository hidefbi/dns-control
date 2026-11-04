/*
Copyright 2020 RethinkDNS developers

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package com.celzero.bravedns.database

import android.util.Base64

class DnsCryptRelayEndpointRepository(
    private val dnsCryptRelayEndpointDAO: DnsCryptRelayEndpointDAO
) {

    suspend fun update(dnsCryptRelayEndpoint: DnsCryptRelayEndpoint) {
        dnsCryptRelayEndpointDAO.update(dnsCryptRelayEndpoint)
    }

    suspend fun insertAsync(dnsCryptRelayEndpoint: DnsCryptRelayEndpoint) {
        dnsCryptRelayEndpointDAO.insert(dnsCryptRelayEndpoint)
    }

    suspend fun deleteOlderData(date: Long) {
        dnsCryptRelayEndpointDAO.deleteOlderData(date)
    }

    suspend fun deleteDnsCryptRelayEndpoint(id: Int) {
        dnsCryptRelayEndpointDAO.deleteDnsCryptRelayEndpoint(id)
    }

    suspend fun removeConnectionStatus() {
        dnsCryptRelayEndpointDAO.removeConnectionStatus()
    }

    suspend fun unselectRelay(stamp: String) {
        dnsCryptRelayEndpointDAO.unselectRelay(stamp)
    }

    suspend fun getConnectedRelays(): List<DnsCryptRelayEndpoint> {
        return dnsCryptRelayEndpointDAO.getConnectedRelays()
    }

    suspend fun getCount(): Int {
        return dnsCryptRelayEndpointDAO.getCount()
    }

    // ipv6-only relays are left out when the device has no global ipv6 address, as they cannot be
    // reached; the remaining relays, if any, are used. with no relay left, dnscrypt connects direct.
    suspend fun getServersToAdd(hasIpv6: Boolean): String {
        val relays = getConnectedRelays()
        return relays
            .filter { hasIpv6 || !isIpv6RelayStamp(it.dnsCryptRelayURL) }
            .joinToString(separator = ",") { it.dnsCryptRelayURL }
    }

    // a relay stamp carries its address as text, and an ipv6 address is written in brackets
    private fun isIpv6RelayStamp(stamp: String): Boolean {
        return try {
            val payload = stamp.removePrefix("sdns://")
            val bytes = Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            String(bytes, Charsets.ISO_8859_1).contains('[')
        } catch (e: IllegalArgumentException) {
            false
        }
    }
}
