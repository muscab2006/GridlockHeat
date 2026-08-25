package com.muscab2006.gridlockheat

import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.math.MathUtils
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

// ─── tuning ──────────────────────────────────────────────────────────────────
private const val DEFAULT_VIEW_W = 520f       // mirrors GridlockHeat.VIEW_W before first apply()
private const val ORBIT_RADIUS_OF_VIEW = 0.42f  // menu orbit radius = viewW * this
private const val ORBIT_SPIN = 0.22f          // rad/s around the car
private const val ORBIT_ZOOM_AMP = 0.03f      // breathing amplitude
private const val ORBIT_ZOOM_HZ = 0.5f        // breathing angular speed (rad/s)
private const val MENU_RATE = 2f              // spec damp: k = 1 - exp(-2*dt)
private const val MENU_ZOOM_RATE = 6f
private const val MENU_ROLL_RATE = 4f
private const val FOLLOW_RATE = 6f            // PLAY anchor chase
private const val VEL_EST_RATE = 8f           // car velocity estimate convergence
private const val LEAD_TIME = 0.30f           // seconds of estimated velocity fed into look-ahead
private const val MAX_LEAD = 120f             // hard clamp on look-ahead distance
private const val LEAD_RATE = 4f
private const val ZOOM_RATE = 10f
private const val PUNCH_TAU = 0.35f           // exponential release time constant
private const val PUNCH_ABOVE = 0.55f         // comboHeat threshold that spikes punch
private const val PUNCH_GAIN = 2.2f
private const val PUNCH_MAX = 2f              // sanity cap on punch accumulation
private const val PUNCH_ZOOM = 0.06f          // zoom = 1 + PUNCH_ZOOM * punch
private const val ROLL_SPEED = 9f             // rad/s of the trauma roll wobble
private const val ROLL_MAX_DEG = 1.6f         // spec max: ±1.6°
private const val SHAKE_UNITS = 14f           // peak jitter distance at trauma = 1
private const val BUST_ZOOM = 1.28f
private const val BUST_EASE_TIME = 0.9f       // dolly-in duration
private const val BUST_ZOOM_RATE = 12f
private const val BUST_FOLLOW_RATE = 3f
private const val BUST_ROLL_RATE = 5f         // roll settles to 0 after the bust
private const val BUST_SHAKE_DECAY = 3.5f     // shake bleeds off fast regardless of trauma
private const val BUST_SHAKE_UNITS = 10f

/**
 * Render-side cinematic camera for the drift chase.
 *
 * Owns nothing but numbers: feed it the player position every rendered frame
 * (interpolated values are fine) and hand it the real camera via [apply].
 * Every mode is built from the same primitive kit:
 *
 *  - frame-rate-independent damping: k = 1 - exp(-rate * dt)
 *  - quadratic trauma (trauma^2) for roll + shake, matching GridlockHeat's feel
 *  - absolute orientation: roll lives entirely in the camera up-vector, so
 *    repeated applies never accumulate rotation
 *
 * update()/apply() allocate nothing (primitives only); everything except the
 * per-frame shake jitter is deterministic.
 */
class CinematicCam {

    enum class Mode { MENU_ORBIT, PLAY, BUST }

    var mode = Mode.PLAY
        private set

    // ─── state (primitives only — zero allocation on the hot path) ───────────
    private var camX = 0f               // smoothed camera anchor (excludes shake)
    private var camY = 0f
    private var prevCarX = 0f
    private var prevCarY = 0f
    private var velX = 0f               // damped car velocity estimate, world u/s
    private var velY = 0f
    private var leadX = 0f              // smoothed look-ahead offset
    private var leadY = 0f
    private var punchAmt = 0f           // combo excitement: spikes, decays with PUNCH_TAU
    private var playClock = 0f          // drives the trauma roll wobble
    private var bustClock = 0f          // seconds since BUST entered
    private var bustShake = 1f          // 1 -> 0 envelope multiplying trauma in BUST
    private var zoomCur = 1f
    private var rollCurDeg = 0f
    private var shakeX = 0f
    private var shakeY = 0f
    private var viewWCached = DEFAULT_VIEW_W  // refreshed by apply(); sizes the orbit ring
    private var hadPos = false          // seen a car sample since the last (re)entry
    private var reentering = true       // next update() cuts to the opening framing

    // Read-only introspection for tests/debug overlays.
    internal val zoom get() = zoomCur
    internal val punch get() = punchAmt
    internal val camCenterX get() = camX
    internal val camCenterY get() = camY

    /**
     * Switches the cinematic shot. The combo punch resets immediately and the
     * next [update] cuts to the new framing instead of gliding across the map.
     */
    fun setMode(m: Mode) {
        if (m == mode) return
        mode = m
        punchAmt = 0f
        reentering = true
    }

    /**
     * Advance the cinematic state once per frame. carX/carY may be interpolated
     * render positions; speed01 is accepted for API symmetry — the internal
     * velocity estimate derived from position deltas carries the same
     * information and survives teleport-y simulation hitches better.
     */
    fun update(dt: Float, carX: Float, carY: Float, speed01: Float, trauma: Float, comboHeat: Float, orbitT: Float) {
        if (dt <= 0f) return  // a non-positive step would poison the velocity estimate

        if (reentering) {
            snapToShot(carX, carY, orbitT)
            reentering = false
        }

        // Damped velocity estimate from raw position deltas.
        if (!hadPos) {
            velX = 0f; velY = 0f
            hadPos = true
        } else {
            val kv = damp(VEL_EST_RATE, dt)
            velX += ((carX - prevCarX) / dt - velX) * kv
            velY += ((carY - prevCarY) / dt - velY) * kv
        }
        prevCarX = carX; prevCarY = carY

        when (mode) {
            Mode.MENU_ORBIT -> updateOrbit(dt, carX, carY, orbitT)
            Mode.PLAY -> updatePlay(dt, carX, carY, trauma, comboHeat)
            Mode.BUST -> updateBust(dt, carX, carY, trauma)
        }
    }

    /**
     * Writes the computed transform into [camera]. viewW/viewH describe the
     * world-space viewport; viewW feeds back into the menu orbit radius on the
     * next [update]. (viewH is unused today — radius tracks width only.)
     */
    fun apply(camera: OrthographicCamera, viewW: Float, viewH: Float) {
        viewWCached = viewW
        camera.position.set(camX + shakeX, camY + shakeY, 0f)
        camera.zoom = zoomCur
        // Roll encoded in the up-vector => absolute each frame, never accumulates.
        val rad = rollCurDeg * MathUtils.degreesToRadians
        camera.direction.set(0f, 0f, -1f)
        camera.up.set(sin(rad), cos(rad), 0f)
        // false = skip the frustum rebuild: projection/view/combined above are
        // already fully computed and the game renders from camera.combined, while
        // Frustum.update() would demand the gdx desktop natives even headless.
        // Callers that need camera.frustum can run update(true) once per frame.
        camera.update(false)
    }

    // ─── shots ───────────────────────────────────────────────────────────────

    /** Cut straight onto the opening framing of the current mode. */
    private fun snapToShot(carX: Float, carY: Float, orbitT: Float) {
        playClock = 0f
        bustClock = 0f
        bustShake = 1f
        leadX = 0f; leadY = 0f
        shakeX = 0f; shakeY = 0f
        hadPos = false
        if (mode == Mode.MENU_ORBIT) {
            // Land exactly on the orbit ring so the menu never opens half-way there.
            camX = carX + cos(orbitT * ORBIT_SPIN) * orbitRadius()
            camY = carY + sin(orbitT * ORBIT_SPIN) * orbitRadius()
        } else {
            camX = carX; camY = carY
        }
    }

    /** MENU_ORBIT: slow circle around the car + zoom breathing, no roll/shake. */
    private fun updateOrbit(dt: Float, carX: Float, carY: Float, orbitT: Float) {
        // Smooth-damp toward the orbit point anchored on the car (k = 1-exp(-2dt)),
        // so the ring glides after a drifting car without ever snapping.
        val k = damp(MENU_RATE, dt)
        val r = orbitRadius()
        camX += (carX + cos(orbitT * ORBIT_SPIN) * r - camX) * k
        camY += (carY + sin(orbitT * ORBIT_SPIN) * r - camY) * k
        // Gentle zoom breathing, phase-locked to time spent in the menu.
        zoomCur += (1f + ORBIT_ZOOM_AMP * sin(orbitT * ORBIT_ZOOM_HZ) - zoomCur) * damp(MENU_ZOOM_RATE, dt)
        rollCurDeg += (0f - rollCurDeg) * damp(MENU_ROLL_RATE, dt)
        shakeX = 0f; shakeY = 0f
    }

    /** PLAY: velocity look-ahead follow + combo zoom-punch + trauma roll/shake. */
    private fun updatePlay(dt: Float, carX: Float, carY: Float, trauma: Float, comboHeat: Float) {
        playClock += dt

        // Look-ahead along estimated velocity, clamped so the car keeps its
        // lower-third portrait framing even at full drift-boosted speed.
        var lx = velX * LEAD_TIME
        var ly = velY * LEAD_TIME
        val len = sqrt(lx * lx + ly * ly)
        if (len > MAX_LEAD) {
            val s = MAX_LEAD / len
            lx *= s; ly *= s
        }
        val kl = damp(LEAD_RATE, dt)
        leadX += (lx - leadX) * kl
        leadY += (ly - leadY) * kl

        val kf = damp(FOLLOW_RATE, dt)
        camX += (carX + leadX - camX) * kf
        camY += (carY + leadY - camY) * kf

        // Combo punch: instant spike above the heat threshold, exponential release.
        punchAmt *= exp(-dt / PUNCH_TAU)
        val spike = ((comboHeat - PUNCH_ABOVE) * PUNCH_GAIN).coerceIn(0f, PUNCH_MAX)
        if (spike > punchAmt) punchAmt = spike
        zoomCur += (1f + PUNCH_ZOOM * punchAmt - zoomCur) * damp(ZOOM_RATE, dt)

        // Quadratic trauma, matching GridlockHeat's shake idiom.
        val sh = trauma * trauma
        rollCurDeg = sin(playClock * ROLL_SPEED) * ROLL_MAX_DEG * sh
        shakeX = (Random.Default.nextFloat() * 2f - 1f) * SHAKE_UNITS * sh
        shakeY = (Random.Default.nextFloat() * 2f - 1f) * SHAKE_UNITS * sh
    }

    /** BUST: eased dolly-in, roll settles to level, shake bleeds away fast. */
    private fun updateBust(dt: Float, carX: Float, carY: Float, trauma: Float) {
        bustClock += dt

        // Ease-out cubic push to full zoom over BUST_EASE_TIME.
        val p = (bustClock / BUST_EASE_TIME).coerceAtMost(1f)
        val eased = 1f - (1f - p) * (1f - p) * (1f - p)
        zoomCur += (1f + (BUST_ZOOM - 1f) * eased - zoomCur) * damp(BUST_ZOOM_RATE, dt)

        val kf = damp(BUST_FOLLOW_RATE, dt)
        camX += (carX - camX) * kf
        camY += (carY - camY) * kf
        rollCurDeg += (0f - rollCurDeg) * damp(BUST_ROLL_RATE, dt)

        // Shake decays fast even while incoming trauma is still pinned at 1.
        bustShake *= exp(-dt * BUST_SHAKE_DECAY)
        val amp = trauma * bustShake * BUST_SHAKE_UNITS
        shakeX = (Random.Default.nextFloat() * 2f - 1f) * amp
        shakeY = (Random.Default.nextFloat() * 2f - 1f) * amp
    }

    private fun orbitRadius() = viewWCached * ORBIT_RADIUS_OF_VIEW

    /** Frame-rate-independent smoothing: fraction of the remaining gap closed this step. */
    private fun damp(rate: Float, dt: Float) = 1f - exp(-rate * dt)
}
