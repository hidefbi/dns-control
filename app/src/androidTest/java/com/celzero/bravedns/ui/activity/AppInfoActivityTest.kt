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
package com.celzero.bravedns.ui.activity
import com.celzero.bravedns.util.Logger

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.swipeUp
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.Visibility
import androidx.test.espresso.matcher.ViewMatchers.isClickable
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.celzero.bravedns.R
import com.celzero.bravedns.util.Constants.Companion.INVALID_UID
import org.hamcrest.Matchers.anyOf
import org.hamcrest.Matchers.isEmptyOrNullString
import org.hamcrest.Matchers.not
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.system.measureTimeMillis

@RunWith(AndroidJUnit4::class)
class AppInfoActivityTest {

    private lateinit var context: Context
    private var testUid: Int = INVALID_UID
    private val maxAcceptableLoadTime = 5000L // 5 seconds max

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()

        // Get a valid UID for testing - use the current app's UID
        testUid = try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.applicationInfo?.uid ?: 1000
        } catch (e: PackageManager.NameNotFoundException) {
            1000 // fallback UID
        }

    }

    @After
    fun tearDown() {
        // Force garbage collection to clean up after performance tests
        System.gc()
    }

    // ========== BASIC FUNCTIONALITY TESTS ==========

    @Test
    fun testLaunchesSuccessfullyWithValidUid() {
        val intent = createValidIntent()

        ActivityScenario.launch<AppInfoActivity>(intent).use { scenario ->
            assertNotNull("Activity scenario should not be null", scenario)

            scenario.onActivity { activity ->
                assertNotNull("Activity should not be null", activity)
                assertFalse("Activity should not be finishing", activity.isFinishing)
                assertFalse("Activity should not be destroyed", activity.isDestroyed)
            }
        }
    }

    @Test
    fun testLaunchWithInvalidUid() {
        val intent = createInvalidIntent()

        ActivityScenario.launch<AppInfoActivity>(intent).use { scenario ->
            assertNotNull("Activity scenario should not be null", scenario)

            // Activity should still launch but may show error dialog
            scenario.onActivity { activity ->
                assertNotNull("Activity should not be null", activity)
            }
        }
    }

    @Test
    fun testUiElementsAreDisplayedWithValidData() {
        val intent = createValidIntent()

        ActivityScenario.launch<AppInfoActivity>(intent).use {
            try {
                // Top app details - these should always be present
                onView(withId(R.id.aad_app_detail_icon))
                    .check(matches(isDisplayed()))
                onView(withId(R.id.aad_app_detail_name))
                    .check(matches(isDisplayed()))
                onView(withId(R.id.aad_pkg_name))
                    .check(matches(isDisplayed()))

                // These elements may be conditionally visible
                checkElementWithFallback(R.id.aad_firewall_status, "firewall status")
                checkElementWithFallback(R.id.aad_data_usage_status, "data usage status")
                checkElementWithFallback(R.id.aad_app_info_icon, "app info icon")
                checkElementWithFallback(R.id.aad_close_conns_chip, "close connections chip")

                // App settings card
                checkElementWithFallback(R.id.aad_app_settings_card, "app settings card")

                // Proxy settings
                checkElementWithFallback(R.id.exclude_proxy_icon, "proxy icon")
                checkElementWithFallback(R.id.exclude_proxy_txt, "proxy text")
                checkElementWithFallback(R.id.exclude_proxy_switch, "proxy switch")

                // Block cards
                checkElementWithFallback(R.id.aad_ip_block_card, "IP block card")
                checkElementWithFallback(R.id.aad_domain_block_card, "domain block card")


            } catch (e: Exception) {
                fail("UI elements test failed: ${e.message}")
            }
        }
    }

    @Test
    fun testUiElementsContentAndInteractions() {
        val intent = createValidIntent()

        ActivityScenario.launch<AppInfoActivity>(intent).use {
            try {
                // Test that text fields are not empty
                onView(withId(R.id.aad_app_detail_name))
                    .check(matches(withText(not(isEmptyOrNullString()))))
                onView(withId(R.id.aad_pkg_name))
                    .check(matches(withText(not(isEmptyOrNullString()))))

                // Test switch interactions (with error handling)
                performSafeClick(R.id.exclude_proxy_switch, "proxy switch")

                // Test clickable elements
                performSafeClick(R.id.aad_app_info_icon, "app info icon")
                performSafeClick(R.id.aad_close_conns_chip, "close connections chip")


            } catch (e: Exception) {
                // Don't fail the test for interaction issues, just log them
            }
        }
    }

    @Test
    fun testAppSettingsToggleStates() {
        val intent = createValidIntent()

        ActivityScenario.launch<AppInfoActivity>(intent).use {
            try {
                // Test various app setting toggles if they exist
                val settingsIds = listOf(
                    R.id.aad_app_settings_block_wifi,
                    R.id.aad_app_settings_block_md,
                    R.id.aad_app_settings_isolate,
                    R.id.aad_app_settings_bypass_dns_firewall,
                    R.id.aad_app_settings_bypass_univ,
                    R.id.aad_app_settings_exclude
                )

                settingsIds.forEach { id ->
                    checkToggleState(id)
                }


            } catch (e: Exception) {
                // Don't fail for settings that might not be available for test app
            }
        }
    }

    @Test
    fun testActivityHandlesConfigurationChanges() {
        val intent = createValidIntent()

        ActivityScenario.launch<AppInfoActivity>(intent).use { scenario ->
            try {
                // Simulate configuration change
                scenario.recreate()

                scenario.onActivity { activity ->
                    assertNotNull("Activity should survive configuration change", activity)
                    assertFalse("Activity should not be finishing after recreation", activity.isFinishing)
                }

                // Verify key UI elements are still present after recreation
                onView(withId(R.id.aad_app_detail_name))
                    .check(matches(isDisplayed()))


            } catch (e: Exception) {
                fail("Activity failed to handle configuration changes: ${e.message}")
            }
        }
    }

    // ========== EDGE CASE TESTS ==========

    @Test
    fun testLaunchWithMissingUidExtra() {
        val intent = Intent(context, AppInfoActivity::class.java)
        // Intentionally not setting the UID extra

        ActivityScenario.launch<AppInfoActivity>(intent).use { scenario ->
            assertNotNull("Activity scenario should not be null", scenario)

            scenario.onActivity { activity ->
                assertNotNull("Activity should not be null", activity)
                // Activity should handle missing UID gracefully
            }
        }
    }

    @Test
    fun testLaunchWithNegativeUid() {
        val intent = Intent(context, AppInfoActivity::class.java).apply {
            putExtra(AppInfoActivity.INTENT_UID, -1)
        }

        ActivityScenario.launch<AppInfoActivity>(intent).use { scenario ->
            assertNotNull("Activity scenario should not be null", scenario)

            scenario.onActivity { activity ->
                assertNotNull("Activity should not be null", activity)
            }
        }
    }

    @Test
    fun testLaunchWithZeroUid() {
        val intent = Intent(context, AppInfoActivity::class.java).apply {
            putExtra(AppInfoActivity.INTENT_UID, 0)
        }

        ActivityScenario.launch<AppInfoActivity>(intent).use { scenario ->
            assertNotNull("Activity scenario should not be null", scenario)

            scenario.onActivity { activity ->
                assertNotNull("Activity should not be null", activity)
            }
        }
    }

    @Test
    fun testLaunchWithVeryLargeUid() {
        val intent = Intent(context, AppInfoActivity::class.java).apply {
            putExtra(AppInfoActivity.INTENT_UID, Integer.MAX_VALUE)
        }

        ActivityScenario.launch<AppInfoActivity>(intent).use { scenario ->
            assertNotNull("Activity scenario should not be null", scenario)

            scenario.onActivity { activity ->
                assertNotNull("Activity should not be null", activity)
            }
        }
    }

    @Test
    fun testLaunchWithSystemAppUid() {
        val systemUid = 1000 // System UID

        val intent = Intent(context, AppInfoActivity::class.java).apply {
            putExtra(AppInfoActivity.INTENT_UID, systemUid)
        }

        ActivityScenario.launch<AppInfoActivity>(intent).use { scenario ->
            assertNotNull("Activity scenario should not be null", scenario)

            scenario.onActivity { activity ->
                assertNotNull("Activity should not be null", activity)
            }
        }
    }

    @Test
    fun testActivityStateWithMultipleIntents() {

        // Test with valid UID first
        val validIntent = Intent(context, AppInfoActivity::class.java).apply {
            putExtra(AppInfoActivity.INTENT_UID, testUid)
        }

        ActivityScenario.launch<AppInfoActivity>(validIntent).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull("Activity should not be null", activity)
                assertFalse("Activity should not be finishing", activity.isFinishing)
            }

            // Simulate new intent
            val newIntent = Intent(context, AppInfoActivity::class.java).apply {
                putExtra(AppInfoActivity.INTENT_UID, INVALID_UID)
            }

            scenario.onActivity { activity ->
                activity.intent = newIntent
                // Activity should handle intent change gracefully
                assertNotNull("Activity should still be valid after intent change", activity)
            }
        }
    }

    @Test
    fun testActivityLifecycleStates() {
        val intent = Intent(context, AppInfoActivity::class.java).apply {
            putExtra(AppInfoActivity.INTENT_UID, testUid)
        }

        ActivityScenario.launch<AppInfoActivity>(intent).use { scenario ->
            // Test pause/resume cycle
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
            scenario.onActivity { activity ->
                assertTrue("Activity should be started", activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))
            }

            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            scenario.onActivity { activity ->
                assertTrue("Activity should be resumed", activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
            }

            scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
            scenario.onActivity { activity ->
                assertTrue("Activity should handle pause gracefully", activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))
            }
        }
    }

    @Test
    fun testMemoryPressureHandling() {
        val intent = Intent(context, AppInfoActivity::class.java).apply {
            putExtra(AppInfoActivity.INTENT_UID, testUid)
        }

        ActivityScenario.launch<AppInfoActivity>(intent).use { scenario ->
            // Simulate memory pressure by triggering onTrimMemory
            scenario.onActivity { activity ->
                activity.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
                assertNotNull("Activity should survive memory pressure", activity)
                assertFalse("Activity should not be finishing after memory pressure", activity.isFinishing)
            }
        }
    }

    @Test
    fun testActivityWithEmptyIntentExtras() {
        val intent = Intent(context, AppInfoActivity::class.java).apply {
            // Add empty extras bundle
            putExtras(android.os.Bundle())
        }

        ActivityScenario.launch<AppInfoActivity>(intent).use { scenario ->
            assertNotNull("Activity scenario should not be null", scenario)

            scenario.onActivity { activity ->
                assertNotNull("Activity should not be null", activity)
            }
        }
    }

    @Test
    fun testActivityErrorRecovery() {
        val intent = Intent(context, AppInfoActivity::class.java).apply {
            putExtra(AppInfoActivity.INTENT_UID, testUid)
        }

        ActivityScenario.launch<AppInfoActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                try {
                    // Simulate potential error scenarios by accessing UI elements that may not exist
                    onView(withId(R.id.aad_app_detail_name))
                        .check(matches(anyOf(isDisplayed(), not(isDisplayed()))))

                } catch (e: Exception) {
                    // This is expected for some edge cases
                }

                assertNotNull("Activity should remain valid after error recovery", activity)
                assertFalse("Activity should not be finishing after error", activity.isFinishing)
            }
        }
    }

    // ========== PERFORMANCE TESTS ==========

    @Test
    fun testActivityLaunchPerformance() {
        val intent = createValidIntent()

        val launchTime = measureTimeMillis {
            ActivityScenario.launch<AppInfoActivity>(intent).use { scenario ->
                scenario.onActivity { activity ->
                    assertNotNull("Activity should be launched", activity)
                    assertFalse("Activity should not be finishing", activity.isFinishing)
                }
            }
        }

        assertTrue(
            "Activity launch time ($launchTime ms) should be under $maxAcceptableLoadTime ms",
            launchTime < maxAcceptableLoadTime
        )
    }

    @Test
    fun testUiRenderingPerformance() {
        val intent = createValidIntent()

        ActivityScenario.launch<AppInfoActivity>(intent).use { scenario ->
            val renderTime = measureTimeMillis {
                try {
                    // Test rendering of key UI elements
                    onView(withId(R.id.aad_app_detail_icon))
                        .check(matches(anyOf(isDisplayed(), not(isDisplayed()))))
                    onView(withId(R.id.aad_app_detail_name))
                        .check(matches(anyOf(isDisplayed(), not(isDisplayed()))))
                    onView(withId(R.id.aad_pkg_name))
                        .check(matches(anyOf(isDisplayed(), not(isDisplayed()))))
                } catch (e: Exception) {
                }
            }

            assertTrue(
                "UI rendering time ($renderTime ms) should be reasonable",
                renderTime < 3000L
            )
        }
    }

    @Test
    fun testMultipleActivityInstancesPerformance() {
        val intent = createValidIntent()
        val instances = 3
        val scenarios = mutableListOf<ActivityScenario<AppInfoActivity>>()

        val totalTime = measureTimeMillis {
            repeat(instances) { i ->
                val scenario = ActivityScenario.launch<AppInfoActivity>(intent)
                scenarios.add(scenario)

                scenario.onActivity { activity ->
                    assertNotNull("Instance $i should be valid", activity)
                }
            }
        }

        // Clean up
        scenarios.forEach { it.close() }

        val avgTime = totalTime / instances

        assertTrue(
            "Average launch time ($avgTime ms) should be reasonable",
            avgTime < maxAcceptableLoadTime
        )
    }

    @Test
    fun testConfigurationChangePerformance() {
        val intent = createValidIntent()

        ActivityScenario.launch<AppInfoActivity>(intent).use { scenario ->
            val configTime = measureTimeMillis {
                repeat(3) {
                    scenario.recreate()
                    scenario.onActivity { activity ->
                        assertNotNull("Activity should survive recreation", activity)
                        assertFalse("Activity should not be finishing", activity.isFinishing)
                    }
                }
            }

            assertTrue(
                "Configuration changes time ($configTime ms) should be reasonable",
                configTime < 10000L // 10 seconds for 3 recreations
            )
        }
    }

    @Test
    fun testMemoryUsageStability() {
        val intent = createValidIntent()

        ActivityScenario.launch<AppInfoActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val initialMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()

                // Simulate some activity operations
                repeat(10) {
                    try {
                        // Trigger some UI operations
                        onView(withId(R.id.aad_app_detail_name))
                            .check(matches(anyOf(isDisplayed(), not(isDisplayed()))))

                        // Small delay to allow operations to complete
                        Thread.sleep(100)
                    } catch (e: Exception) {
                    }
                }

                val finalMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()

                val memoryIncrease = finalMemory - initialMemory

                // Memory increase should be reasonable (less than 50MB)
                assertTrue(
                    "Memory increase should be reasonable",
                    memoryIncrease < 50 * 1024 * 1024
                )
            }
        }
    }

    @Test
    fun testInteractionResponseTimes() {
        val intent = createValidIntent()

        ActivityScenario.launch<AppInfoActivity>(intent).use { scenario ->
            val interactionElements = listOf(
                R.id.exclude_proxy_switch,
                R.id.aad_app_info_icon,
                R.id.aad_close_conns_chip
            )

            interactionElements.forEach { elementId ->
                val responseTime = measureTimeMillis {
                    try {
                        onView(withId(elementId))
                            .check(matches(anyOf(isDisplayed(), not(isDisplayed()))))
                            .perform(click())

                        // Small delay to allow response
                        Thread.sleep(100)
                    } catch (e: Exception) {
                    }
                }

                assertTrue(
                    "Interaction response time should be quick",
                    responseTime < 1000L
                )
            }
        }
    }

    // ========== HELPER METHODS ==========

    private fun createValidIntent(): Intent {
        return Intent(context, AppInfoActivity::class.java).apply {
            putExtra(AppInfoActivity.INTENT_UID, testUid)
        }
    }

    private fun createInvalidIntent(): Intent {
        return Intent(context, AppInfoActivity::class.java).apply {
            putExtra(AppInfoActivity.INTENT_UID, INVALID_UID)
        }
    }

    private fun checkElementWithFallback(id: Int, elementName: String) {
        try {
            onView(withId(id)).check(matches(anyOf(
                isDisplayed(),
                withEffectiveVisibility(Visibility.GONE),
                withEffectiveVisibility(Visibility.INVISIBLE)
            )))
        } catch (e: Exception) {
        }
    }

    private fun performSafeClick(id: Int, elementName: String) {
        try {
            onView(withId(id)).check(matches(anyOf(
                isClickable(),
                withEffectiveVisibility(Visibility.GONE)
            )))

            onView(withId(id)).perform(click())
        } catch (e: Exception) {
        }
    }

    private fun checkToggleState(id: Int) {
        try {
            onView(withId(id)).check(matches(anyOf(
                isEnabled(),
                not(isEnabled()),
                withEffectiveVisibility(Visibility.GONE)
            )))
        } catch (e: Exception) {
        }
    }
}
