package com.workout2scroll.guard

import org.junit.Assert.assertEquals
import org.junit.Test

class ForegroundSessionTest {
    @Test fun chargesOnlyInstagramIntervals() {
        val session = ForegroundSession(0)
        assertEquals(0L, session.update(1_000, true, true))
        assertEquals(250L, session.update(1_250, true, true))
        assertEquals(250L, session.update(1_500, false, true))
        assertEquals(0L, session.update(10_000, false, true))
    }

    @Test fun neverChargesLockedScreenTime() {
        val session = ForegroundSession(0)
        session.update(0, true, true)
        assertEquals(500L, session.update(500, true, false))
        assertEquals(0L, session.update(600_000, true, false))
        assertEquals(0L, session.update(610_000, true, true))
        assertEquals(250L, session.update(610_250, true, true))
    }

    @Test fun repeatedWindowEventsCannotDoubleCharge() {
        val session = ForegroundSession(100)
        session.update(100, true, true)
        assertEquals(100L, session.update(200, true, true))
        assertEquals(0L, session.update(200, true, true))
    }
}