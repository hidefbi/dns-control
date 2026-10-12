/*
 * Copyright 2025 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.celzero.bravedns.util

/**
 * Crash reporting is deliberately disabled in this privacy-hardened build.
 * Go crash data remains available through the local tombstone/log facilities.
 */
object CrashReporter {
    fun recordGoCrash(@Suppress("UNUSED_PARAMETER") message: String) = Unit
}
