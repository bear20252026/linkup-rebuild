package com.youloft.watcher.service

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/**
 * The gate table of the collection switch, without Robolectric: the controller is state plus two injected
 * side effects, so every rule is a lambda flip away.
 */
class CollectControllerTest {

    @Test
    fun `all three gates must pass before a start is issued`() {
        val harness = Harness()

        harness.refresh()
        assertEquals(0, harness.starts, "no consent, no service")

        harness.privacy = true
        harness.refresh()
        assertEquals(0, harness.starts, "consent alone is not enough — the report route needs an account")

        harness.signedIn = true
        harness.refresh()
        assertEquals(0, harness.starts, "an account without the location permission still cannot collect")

        harness.canLocate = true
        harness.refresh()
        assertEquals(1, harness.starts, "the last gate opens: exactly one start")
    }

    @Test
    fun `a refresh that changes nothing issues nothing`() {
        val harness = Harness(privacy = true, canLocate = true, signedIn = true)
        harness.refresh()
        harness.refresh()
        harness.refresh()
        assertEquals(1, harness.starts, "idempotent: the decision, not the call, drives the service")
    }

    @Test
    fun `losing any gate stops the service`() {
        val harness = Harness(privacy = true, canLocate = true, signedIn = true)
        harness.refresh()
        assertEquals(1, harness.starts)

        harness.canLocate = false
        harness.refresh()
        assertEquals(1, harness.stops, "permission revoked mid-session: the service comes down")

        harness.canLocate = true
        harness.refresh()
        assertEquals(2, harness.starts, "and comes back up when it returns")
    }

    @Test
    fun `sign-out is a stop`() {
        val harness = Harness(privacy = true, canLocate = true, signedIn = true)
        harness.refresh()
        harness.signedIn = false
        harness.refresh()
        assertEquals(1, harness.stops, "fixes without an account cannot be filed (the route is behind the JWT)")
    }

    @Test
    fun `the foreground count follows activity pairs, not calls`() {
        val harness = Harness(privacy = true, canLocate = true, signedIn = true)
        harness.refresh()

        harness.onResume()
        assertFalse(harness.controller.backgrounded)

        harness.onPause()
        assertTrue(harness.controller.backgrounded, "the last resumed activity left: dormancy")

        // Two overlapping activities: the count, not any single pair, decides.
        harness.onResume()
        harness.onResume()
        harness.onPause()
        assertFalse(harness.controller.backgrounded, "one activity is still up")
        harness.onPause()
        assertTrue(harness.controller.backgrounded)
    }

    @Test
    fun `an extra pause never drives the count negative`() {
        val harness = Harness()
        harness.onPause()
        harness.onPause()
        assertFalse(harness.controller.backgrounded, "nothing was ever resumed; backgrounded stays false")
    }

    /** Records the side effects and holds the gate booleans the tests flip. */
    private class Harness(
        var privacy: Boolean = false,
        var canLocate: Boolean = false,
        var signedIn: Boolean = false,
    ) {
        var starts = 0
        var stops = 0

        val controller = CollectController(
            startService = { starts += 1 },
            stopService = { stops += 1 },
            gates = CollectController.Gates(
                privacyAgreed = { privacy },
                canLocate = { canLocate },
                signedIn = { signedIn },
            ),
        )

        fun refresh() = controller.refresh()

        fun onResume() = controller.onActivityResumed()

        fun onPause() = controller.onActivityPaused()
    }
}
