package com.muscab2006.gridlockheat

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhysicsTest {

    private fun kin(x: Float = 0f, y: Float = 0f, heading: Float = 0f) =
        CarKinematics(x, y, heading, heading)

    // ---- angle math ----

    @Test
    fun `angle diff wraps across the PI boundary`() {
        val d = Physics.angleDiff(3.0f, -3.0f)
        assertTrue(abs(d) < 0.29f, "expected short path ~0.283 rad, got $d")
    }

    @Test
    fun `lerp angle takes the shortest path`() {
        val r = Physics.lerpAngle(3.0f, -3.0f, 0.5f)
        assertTrue(r > 3.0f || r < -3.0f, "should pass through +/-PI region, got $r")
    }

    @Test
    fun `full lerp lands exactly on target`() {
        assertEquals(1.2f, Physics.lerpAngle(0.4f, 1.2f, 1f), 1e-6f)
    }

    // ---- drift model ----

    @Test
    fun `steering rotates heading in input direction`() {
        val car = kin(heading = 0f)
        Physics.driftStep(car, speed = 300f, steer = 1f, dt = 0.1f, turnRate = 2.5f, grip = 5f)
        assertTrue(car.heading > 0f, "right steer must increase heading, got ${car.heading}")
    }

    @Test
    fun `velocity direction converges toward heading`() {
        val car = kin(heading = 0f).apply { velAngle = 2.5f }
        val before = abs(Physics.angleDiff(car.heading, car.velAngle))
        repeat(10) { Physics.driftStep(car, 300f, 0f, 0.05f, turnRate = 2.5f, grip = 5f) }
        val after = abs(Physics.angleDiff(car.heading, car.velAngle))
        assertTrue(after < before, "grip should shrink drift: $before -> $after")
    }

    @Test
    fun `car moves along velocity direction`() {
        val car = kin(heading = 0f).apply { velAngle = 0f } // facing +x
        Physics.driftStep(car, 100f, 0f, 1f, 2.5f, 50f)
        assertEquals(100f, car.x, 0.5f)
        assertEquals(0f, car.y, 0.5f)
    }

    @Test
    fun `steer is clamped`() {
        val a = kin(); Physics.driftStep(a, 300f, 99f, 1f, 2.5f, 5f)
        val b = kin(); Physics.driftStep(b, 300f, 1f, 1f, 2.5f, 5f)
        assertEquals(a.heading, b.heading, 1e-5f)
    }

    // ---- proximity classifier ----

    @Test
    fun `collision beats near-miss`() {
        val r = Physics.classifyProximity(80f, 30f, bustRange = 44f, nearRange = 90f,
            relSpeed = 400f, minRelSpeed = 200f)
        assertEquals(Proximity.HIT, r)
    }

    @Test
    fun `entering band fast counts as near`() {
        val r = Physics.classifyProximity(120f, 70f, 44f, 90f, relSpeed = 320f, minRelSpeed = 200f)
        assertEquals(Proximity.NEAR, r)
    }

    @Test
    fun `slow creep into band is not thrilling`() {
        val r = Physics.classifyProximity(120f, 70f, 44f, 90f, relSpeed = 60f, minRelSpeed = 200f)
        assertEquals(Proximity.NONE, r)
    }

    @Test
    fun `already inside band stays silent`() {
        val r = Physics.classifyProximity(70f, 69f, 44f, 90f, relSpeed = 400f, minRelSpeed = 200f)
        assertEquals(Proximity.NONE, r)
    }

    // ---- deterministic world decoration ----

    @Test
    fun `cell marks are deterministic`() {
        assertEquals(Physics.cellHasMark(12, -7, 1337), Physics.cellHasMark(12, -7, 1337))
    }

    @Test
    fun `cell marks deterministic and reasonably distributed`() {
        // exact determinism, always
        for (i in 0 until 100) {
            assertEquals(Physics.cellHasMark(i, 7, 42), Physics.cellHasMark(i, 7, 42))
        }
        // marking rate ~15%: robust band over 1000 cells
        var marked = 0
        for (i in 0 until 1000) if (Physics.cellHasMark(i, 0, 1)) marked++
        assertTrue(marked in 80..220, "marking rate out of band: $marked/1000")
        // different seed must change a healthy fraction of cells
        val changed = (0 until 200).count { Physics.cellHasMark(it, 3, 1) != Physics.cellHasMark(it, 3, 999) }
        assertTrue(changed > 40, "seed must change decorations ($changed/200)")
    }
}
