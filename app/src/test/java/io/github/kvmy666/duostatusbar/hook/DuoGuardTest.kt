package io.github.kvmy666.duostatusbar.hook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Rive crash breaker must stop a crash *loop*, not the sibling bars in a healthy process.
 *
 * There are three status bars (main, keyguard, shade header); the main bar's own Rive attempt raises the
 * per-process count, which used to make the other two fall back to Canvas in the same process (measured
 * on OxygenOS 16: `renderer=Canvas, riveAttempts=2` on the lock screen while the home bar drew with Rive).
 */
class DuoGuardTest {

    /** Mirrors the private constants: 2 processes may die, and the count stays armed for 2 minutes. */
    private val maxAttempts = 2
    private val crashWindowMs = 120_000L

    @Test
    fun `once this process has taken the risk, the other bars may use Rive`() {
        assertTrue(DuoGuard.shouldAllowRive(maxAttempts, attemptedThisProcess = true, attemptAgeMs = 0L))
    }

    @Test
    fun `a count below the limit is allowed`() {
        assertTrue(DuoGuard.shouldAllowRive(0, attemptedThisProcess = false, attemptAgeMs = 0L))
        assertTrue(DuoGuard.shouldAllowRive(1, attemptedThisProcess = false, attemptAgeMs = 0L))
    }

    @Test
    fun `a full, recent count is a crash loop and is refused`() {
        assertFalse(DuoGuard.shouldAllowRive(maxAttempts, attemptedThisProcess = false, attemptAgeMs = 1_000L))
        assertFalse(DuoGuard.shouldAllowRive(9, attemptedThisProcess = false, attemptAgeMs = crashWindowMs))
    }

    @Test
    fun `a full but stale count is allowed again`() {
        assertTrue(
            DuoGuard.shouldAllowRive(maxAttempts, attemptedThisProcess = false, attemptAgeMs = crashWindowMs + 1)
        )
    }
}
