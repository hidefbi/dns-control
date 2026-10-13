/*
 * Copyright 2020 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.celzero.bravedns.util

import java.io.File
import java.util.concurrent.TimeUnit

class Constants {

    companion object {
        // on-device blocklist download path
        val ONDEVICE_BLOCKLIST_DOWNLOAD_PATH = File.separator + "downloads" + File.separator

        // rethink base url (modified from download to dl on v053l)
        const val DOWNLOAD_BASE_URL = "https://hidefbi.github.io"

        const val FILE_TAG = "filetag.json"

        // file names which are downloaded as part of on-device blocklists
        val ONDEVICE_BLOCKLIST_FILE_TAG = File.separator + FILE_TAG
        val ONDEVICE_BLOCKLIST_FILE_BASIC_CONFIG = File.separator + "basicconfig.json"
        val ONDEVICE_BLOCKLIST_FILE_RD = File.separator + "rd.txt"
        val ONDEVICE_BLOCKLIST_FILE_TD = File.separator + "td.txt"
        val ONDEVICE_BLOCKLIST_FILE_INDEX = File.separator + "index.json"
        val ONDEVICE_BLOCKLIST_FILE_INDEX_SIG = File.separator + "index.json.sig"

        // query part
        const val ONDEVICE_BLOCKLIST_UPDATE_CHECK_QUERYPART_1 = "update"
        const val ONDEVICE_BLOCKLIST_UPDATE_CHECK_QUERYPART_2 = "blocklists"

        const val RETHINK_PACKAGE = "com.celzero.bravedns"

        // The version tag value(response) for the update check (both on-device and app update)
        // TODO: have two different response versions for blocklist update and app update
        const val UPDATE_CHECK_RESPONSE_VERSION = 1

        // the version value for rethink stamp generation
        const val RETHINK_STAMP_VERSION = 1

        // meta data for on-device blocklist
        data class OnDeviceBlocklistsMetadata(val url: String, val filename: String)

        // folder name to store the local blocklist download files
        // (eg../files/local_blocklist/<timestamp>)
        const val LOCAL_BLOCKLIST_DOWNLOAD_FOLDER_NAME = "local_blocklist"

        // url_param_compress_blob
        // search param
        private const val URL_SEARCHPARAM_COMPRESS_BLOB = "?compressed"

        // list of file details for Android Download Manager
        val ONDEVICE_BLOCKLISTS_ADM =
            listOf(
                OnDeviceBlocklistsMetadata(
                    "$DOWNLOAD_BASE_URL/blocklists",
                    ONDEVICE_BLOCKLIST_FILE_TAG
                ),
                OnDeviceBlocklistsMetadata(
                    "$DOWNLOAD_BASE_URL/basicconfig",
                    ONDEVICE_BLOCKLIST_FILE_BASIC_CONFIG
                ),
                OnDeviceBlocklistsMetadata(
                    "$DOWNLOAD_BASE_URL/rank$URL_SEARCHPARAM_COMPRESS_BLOB",
                    ONDEVICE_BLOCKLIST_FILE_RD
                ),
                OnDeviceBlocklistsMetadata(
                    "$DOWNLOAD_BASE_URL/trie$URL_SEARCHPARAM_COMPRESS_BLOB",
                    ONDEVICE_BLOCKLIST_FILE_TD
                ),
                OnDeviceBlocklistsMetadata(
                    "$DOWNLOAD_BASE_URL/index.json",
                    ONDEVICE_BLOCKLIST_FILE_INDEX
                ),
                OnDeviceBlocklistsMetadata(
                    "$DOWNLOAD_BASE_URL/index.json.sig",
                    ONDEVICE_BLOCKLIST_FILE_INDEX_SIG
                )
            )

        // list of file details for in-app downloader
        val ONDEVICE_BLOCKLISTS_IN_APP =
            listOf(
                OnDeviceBlocklistsMetadata("blocklists", ONDEVICE_BLOCKLIST_FILE_TAG),
                OnDeviceBlocklistsMetadata("basicconfig", ONDEVICE_BLOCKLIST_FILE_BASIC_CONFIG),
                OnDeviceBlocklistsMetadata("rank", ONDEVICE_BLOCKLIST_FILE_RD),
                OnDeviceBlocklistsMetadata("trie", ONDEVICE_BLOCKLIST_FILE_TD),
                OnDeviceBlocklistsMetadata("index.json", ONDEVICE_BLOCKLIST_FILE_INDEX),
                OnDeviceBlocklistsMetadata("index.json.sig", ONDEVICE_BLOCKLIST_FILE_INDEX_SIG)
            )

        // url to download the rethinkdns apk file

        // base-url for rethinkdns

        // rethinkdns sponsor link

        // base-url for rethinkdns
        const val RETHINKDNS_DOMAIN = "rethinkdns.com"

        // json object constants received as part of update check
        // FIXME: Avoid usage of these parameters, map to POJO instead
        const val JSON_VERSION = "version"
        const val JSON_UPDATE = "update"
        const val JSON_LATEST = "latest"

        // app type unknown
        const val UNKNOWN_APP = "Unknown"

        // invalid application uid
        const val INVALID_UID = -1

        // android primary user
        const val PRIMARY_USER = 0

        // missing uid, used when the uid is undermined. see ConnectionTracer#getUidQ()
        // changing this requires changes in RethinkDnsEndpointDao file
        const val MISSING_UID = -2000

        // label for rethinkdns plus doh endpoint

        // constants used as part of intent to load the viewpager's screen
        const val VIEW_PAGER_SCREEN_TO_LOAD = "view_pager_screen"

        // default custom http proxy port number
        const val HTTP_PROXY_PORT = 8118

        // default custom socks5 ip
        const val SOCKS_DEFAULT_IP = "127.0.0.1"

        // default custom socks5 port
        const val SOCKS_DEFAULT_PORT = 9050

        // constants to send type of proxy: for http
        const val HTTP = "HTTP"

        // constants generated as part of com.celzero.bravedns.BuildConfig.FLAVORS
        // (playstore/fdroid/website)
        const val FLAVOR_FDROID = "fdroid"

        const val BUILD_TYPE_ALPHA = "alpha"

        // Various notification action constants used part of NotificationCompat.Action
        const val NOTIFICATION_ACTION = "NOTIFICATION_VALUE"
        const val NOTIF_ACTION_STOP_VPN = "RETHINK_STOP" // stop vpn service
        const val NOTIF_ACTION_PAUSE_VPN = "RETHINK_PAUSE" // pause vpn
        const val NOTIF_ACTION_RESUME_VPN = "RETHINK_RESUME" // resume vpn
        const val NOTIF_ACTION_DNS_VPN = "RETHINK_DNSONLY" // battery-saver dns-only
        const val NOTIF_ACTION_DNS_FIREWALL_VPN = "RETHINK_FULLMODE" // default dns+firewall
        const val NOTIF_ACTION_RULES_FAILURE = "RETHINK_RULES_RELOAD" // load rules failure
        const val NOTIF_ACTION_NEW_APP_ALLOW = "NEW_APP_ALLOW" // allow network access for new apps
        const val NOTIF_ACTION_NEW_APP_DENY = "NEW_APP_DENY" // deny network access for new apps
        const val NOTIF_ACTION_DB_CORRUPTED_CLEAR = "DB_CORRUPTED_CLEAR" // clear core tables and rebuild
        const val NOTIF_ACTION_DB_CORRUPTED_DISMISS = "DB_CORRUPTED_DISMISS" // dismiss db corruption notification
        const val NOTIF_ACTION_RETHINK_BLOCK_DISMISS = "RETHINK_BLOCK_DISMISS" // dismiss rethink block notification

        // various notification intent extra name/values used part of notification's pending-intent
        const val NOTIF_INTENT_EXTRA_ACCESSIBILITY_NAME =
            "ACCESSIBILITY" // accessibility failure name
        const val NOTIF_INTENT_EXTRA_ACCESSIBILITY_VALUE =
            "ACCESSIBILITY_FAILURE" // accessibility failure value
        const val NOTIF_INTENT_EXTRA_NEW_APP_NAME = "NEW_APP" // new app install name
        const val NOTIF_INTENT_EXTRA_NEW_APP_VALUE =
            "NEW_APP_INSTALL_NOTIFY" // new app install value
        const val NOTIF_WG_PERMISSION_NAME = "WG_PERMISSION" // wireguard permission name
        const val NOTIF_WG_PERMISSION_VALUE = "WG_PERMISSION_NOTIFY" // wireguard permission

        // Stable notification ID for the 409 conflict alert (only one at a time)

        // Stable notification ID for the device-not-registered alert (only one at a time)

        // Stable notification ID for the 401 auth-error alert (only one at a time)

        const val NOTIF_ID_RETHINK_BLOCK = 0x411

        // new app install intent extra name for uid. see RefreshDatabase#makeNewAppVpnIntent()
        const val NOTIF_INTENT_EXTRA_APP_UID = "NEW_APP_UID"

        // DNS message type received by the DNS resolver
        const val NXDOMAIN = "NXDOMAIN"

        // The minimum interval before checking if the internal accessibility service
        // (used to block apps-not-in-use) is indeed running.
        // Ref: {@link com.celzero.bravedns.util.Utilities#isAccessibilityServiceEnabled} and
        // {@link
        // com.celzero.bravedns.util.Utilities#isAccessibilityServiceEnabledViaSettingsSecure}
        val ACCESSIBILITY_SERVICE_HEARTBEAT_THRESHOLD_MS = TimeUnit.MINUTES.toMillis(5)

        // minimum interval before checking if there is a change in active network
        // (metered/unmetered)
        val ACTIVE_NETWORK_CHECK_THRESHOLD_MS = TimeUnit.SECONDS.toMillis(60)

        // IPv4 uses 0.0.0.0 as an unspecified address
        const val UNSPECIFIED_IP_IPV4 = "0.0.0.0"

        // IPv6 uses :: as an unspecified address
        const val UNSPECIFIED_IP_IPV6 = "::"

        // special port number not assigned to any app
        const val UNSPECIFIED_PORT = 0

        // Invalid port number

        // intent for settings->vpn screen
        const val ACTION_VPN_SETTINGS_INTENT = "android.net.vpn.SETTINGS"

        // default live data page size used by recycler views
        const val LIVEDATA_PAGE_SIZE = 30

        // To initiate / reset the timestamp in milliseconds
        const val INIT_TIME_MS = 0L

        const val TIME_FORMAT_2 = "yy.MM (dd)"
        const val TIME_FORMAT_4 = "dd/MM/yy, HH:mm:ss"


        // max endpoint

        // The UID used to be generic uid used to block IP addresses which are intended to
        // block for all the applications.
        const val UID_EVERYBODY = -1000

        // to handle the "dead" packs
        const val DEAD_PACK = "dead"

        // intent extra name for uid
        const val INTENT_UID = "UID"

        data class DefaultDnsServer(
            val id: Int,
            val name: String,
            val url: String,
            val type: String
        )



        // all preset dns entries removed per user request — this list intentionally left
        // empty so the "quick pick" screen shows nothing but the custom-DNS entry option.
        // the DefaultDnsServer data class is kept only so any UI code that maps over this
        // list still compiles; it will just render an empty list.
        val DEFAULT_DNS_LIST: List<DefaultDnsServer> = emptyList()

        // wireguard folder name
        const val WIREGUARD_FOLDER_NAME = "wireguard"

        val ip4probes =
            listOf(
                "216.239.32.27", // google org
                "104.16.132.229", // cloudflare
            )

        val ip6probes =
            listOf(
                "2001:4860:4802:32::1b", // google org
                "2606:4700::6810:84e5", // cloudflare
            )

        // URLs to check IPv4 and IPv6 connectivity
        // a service to check IPv4 connectivity
        val urlV4probes = listOf("https://www.ietf.org#ipv4",
            "https://letsencrypt.org#ipv4")

        // a service to check IPv6 connectivity
        val urlV6probes = listOf("https://www.gnu.org#ipv6",
            " https://www.mozilla.org#ipv6")
    }
}
