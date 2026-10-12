package com.celzero.bravedns

import android.app.Activity
import com.celzero.bravedns.service.AppUpdater

/** Local no-op updater: never contacts any server. */
class NonStoreAppUpdater : AppUpdater {
    override fun checkForAppUpdate(
        isInteractive: AppUpdater.UserPresent,
        activity: Activity,
        listener: AppUpdater.InstallStateListener
    ) {
        listener.onUpToDate(AppUpdater.InstallSource.OTHER, isInteractive)
    }

    override fun completeUpdate() {}

    override fun unregisterListener(listener: AppUpdater.InstallStateListener) {}
}
