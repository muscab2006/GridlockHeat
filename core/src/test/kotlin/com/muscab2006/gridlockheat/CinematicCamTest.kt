package com.muscab2006.gridlockheat

import com.badlogic.gdx.graphics.OrthographicCamera
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CinematicCamTest {

    private val dt = 1f / 60f
    private val camera = OrthographicCamera()  // pure-JVM math, no GL needed

    // ---- mode switching ----

    @Test
    fun `mode switch resets punch`() {
        val cam = CinematicCam()
        cam.setMode(CinematicCam.Mode.PLAY)
        var x = 0f
        repeat(90) {
            cam.update(dt, x, 0f, speed01 = 1f, trauma = 0f, comboHeat = 1f, orbitT = 0f)
            x += 220f * dt
        }
        assertTrue(cam.punch > 0.05f, "sustained max combo heat should build punch, got ${cam.punch}")
        cam.setMode(CinematicCam.Mode.BUST)
        assertEquals(0f, cam.punch, "switching modes must zero the punch immediately")
        cam.setMode(CinematicCam.Mode.MENU_ORBIT)
        assertEquals(0f, cam.punch, "punch must stay zeroed across another switch")
    }

    // ---- zoom envelope ----

    @Test
    fun `zoom stays within bounds under chaos`() {
        val cam = CinematicCam()
        var t = 0f
        var x = 0f; var y = 0f
        fun step(mode: CinematicCam.Mode, frames: Int) {
            cam.setMode(mode)
            repeat(frames) {
                t += dt
                x += 260f * dt
                y = sin(t * 3f) * 120f
                cam.update(dt, x, y, speed01 = 0.8f, trauma = 1f, comboHeat = 1f, orbitT = t)
                assertTrue(cam.zoom in 0.9f..1.4f, "zoom out of range in $mode: ${cam.zoom}")
                cam.apply(camera, 520f, 900f)
            }
        }
        step(CinematicCam.Mode.MENU_ORBIT, 60)
        step(CinematicCam.Mode.PLAY, 240)
        step(CinematicCam.Mode.BUST, 120)
    }

    // ---- bust dolly ----

    @Test
    fun `bust dollies past 1_2 zoom within one second`() {
        val cam = CinematicCam()
        cam.setMode(CinematicCam.Mode.BUST)
        repeat(60) { cam.update(dt, 0f, 0f, speed01 = 0f, trauma = 1f, comboHeat = 0f, orbitT = 0f) }
        assertTrue(cam.zoom > 1.2f, "expected bust zoom to converge above 1.2 after 1s, got ${cam.zoom}")
    }

    // ---- framing bounds ----

    @Test
    fun `play camera stays within 400 units of the car`() {
        val cam = CinematicCam()
        cam.setMode(CinematicCam.Mode.PLAY)
        var x = 0f; var y = 0f
        var vx = 340f
        repeat(600) { i ->                       // 10s of aggressive driving
            if (i % 90 == 0) vx = -vx            // hard direction flips
            x += vx * dt
            y = sin(i * 0.05f) * 180f
            val trauma = if (i % 37 == 0) 1f else 0.25f
            val heat = if (i % 45 == 0) 1f else 0.2f
            cam.update(dt, x, y, speed01 = 0.85f, trauma = trauma, comboHeat = heat, orbitT = 0f)
            cam.apply(camera, 520f, 900f)
            val dx = cam.camCenterX - x
            val dy = cam.camCenterY - y
            assertTrue(dx * dx + dy * dy <= 400f * 400f,
                "frame $i: camera drifted ${sqrt(dx * dx + dy * dy)}u from car")
        }
    }

    @Test
    fun `menu orbit stays within 1_2x radius of the car`() {
        val cam = CinematicCam()
        cam.setMode(CinematicCam.Mode.MENU_ORBIT)
        val radius = 520f * 0.42f
        val bound = radius * 1.2f
        var x = 40f; var y = -20f
        var t = 0f
        repeat(600) { i ->                       // 10s of slow drift while orbiting
            t += dt
            x += 18f * dt
            y += 7f * dt
            cam.update(dt, x, y, speed01 = 0f, trauma = 0f, comboHeat = 0f, orbitT = t)
            cam.apply(camera, 520f, 900f)
            val dx = cam.camCenterX - x
            val dy = cam.camCenterY - y
            assertTrue(dx * dx + dy * dy <= bound * bound,
                "frame $i: orbit drifted ${sqrt(dx * dx + dy * dy)}u outside bound")
        }
    }
}
