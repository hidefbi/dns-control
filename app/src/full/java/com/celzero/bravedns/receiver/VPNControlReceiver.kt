/*
 * Copyright 2025 RethinkDNS and its authors
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

package com.celzero.bravedns.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.celzero.bravedns.service.VpnController

class VpnControlReceiver: BroadcastReceiver() {
    companion object {
        private const val ACTION_START = "com.celzero.bravedns.intent.action.VPN_START"
        private const val ACTION_STOP = "com.celzero.bravedns.intent.action.VPN_STOP"
        private const val STOP_REASON = "tasker_stop"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_START -> handleVpnStart(context)
            ACTION_STOP -> handleVpnStop(context)
        }
    }

    @Suppress("DEPRECATION")
    private fun handleVpnStart(context: Context) {
        if (VpnController.isOn()) {
            return
        }
        val prepareVpnIntent: Intent? =
            try {
                VpnService.prepare(context)
            } catch (_: NullPointerException) {
                // This shouldn't happen normally as Broadcast Intent sender apps like Tasker
                // won't come up as early as Always-on VPNs
                // Context can be null in case of auto-restart VPNs:
                // ref stackoverflow.com/questions/73147633/getting-null-in-context-while-auto-restart-with-broadcast-receiver-in-android-ap
                return
            }
        if (prepareVpnIntent == null) {
            VpnController.start(context)
            return
        }
    }

    @Suppress("DEPRECATION")
    private fun handleVpnStop(context: Context) {
        if (!VpnController.isOn()) {
            return
        }
        if (VpnController.isAlwaysOn(context)) {
            return
        }

        VpnController.stop(STOP_REASON, context)
    }

}
