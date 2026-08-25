package com.muscab2006.gridlockheat

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pure drift-chase math — no libGDX types here so everything is unit-testable
 * on the plain JVM (skill §6 gate requirement).
 */
object Physics {

    /** Shortest signed difference b-a in radians, wrapped to (-PI, PI]. */
    fun angleDiff(a: Float, b: Float): Float {
        var d = b - a
        while (d > Math.PI.toFloat()) d -= (2 * Math.PI).toFloat()
        while (d <= -Math.PI.toFloat()) d += (2 * Math.PI).toFloat()
        return d
    }

    /** Linear interpolation between angles taking the short way around. */
    fun lerpAngle(a: Float, b: Float, t: Float): Float =
        a + angleDiff(a, b) * t

    /**
     * One drift-model integration step.
     *
     * Auto-throttle arcade model: heading rotates toward [steer] input;
     * velocity direction chases the heading with [grip] convergence —
     * the gap between them IS the visible drift angle.
     *
     * @param state mutable car kinematic state (radians throughout)
     * @param speed forward speed (units/sec), applied along velocity direction
     * @param steer -1..1 analog steering input
     * @param turnRate rad/sec at full lock
     * @param grip how fast velocity direction converges to heading (1/sec)
     */
    fun driftStep(
        state: CarKinematics,
        speed: Float,
        steer: Float,
        dt: Float,
        turnRate: Float,
        grip: Float,
    ) {
        val clampedSteer = steer.coerceIn(-1f, 1f)
        state.heading += clampedSteer * turnRate * dt

        // wrap heading into (-PI, PI]
        if (state.heading > Math.PI.toFloat()) state.heading -= (2 * Math.PI).toFloat()
        if (state.heading <= -Math.PI.toFloat()) state.heading += (2 * Math.PI).toFloat()

        state.velAngle = lerpAngle(state.velAngle, state.heading, (grip * dt).coerceIn(0f, 1f))

        state.x += cos(state.velAngle) * speed * dt
        state.y += sin(state.velAngle) * speed * dt
    }

    /** Visible drift magnitude in radians (how sideways the car is sliding). */
    fun driftAmount(state: CarKinematics): Float = abs(angleDiff(state.heading, state.velAngle))

    /**
     * Near-miss classifier for one cop against the player.
     *
     * @param prevDist distance last frame
     * @param dist distance this frame
     * @param bustRange closer than this = collision (not a near-miss)
     * @param nearRange inside this band = close call territory
     * @param relSpeed closing speed (units/sec)
     * @param minRelSpeed required closing speed to count as a thrill
     * @return HIT when within bustRange, NEAR when entering the band fast enough, else NONE
     */
    fun classifyProximity(
        prevDist: Float,
        dist: Float,
        bustRange: Float,
        nearRange: Float,
        relSpeed: Float,
        minRelSpeed: Float,
    ): Proximity {
        if (dist < bustRange) return Proximity.HIT
        return if (prevDist >= nearRange && dist < nearRange && relSpeed >= minRelSpeed) {
            Proximity.NEAR
        } else {
            Proximity.NONE
        }
    }

    /**
     * Deterministic pseudo-random decoration flag for an infinite-grid cell.
     * Same inputs must ALWAYS yield same output (unit-tested).
     */
    fun cellHasMark(cellX: Int, cellY: Int, seed: Int): Boolean {
        var h = seed
        h = h * 31 + cellX
        h = h * 31 + cellY
        // murmur3 finalizer — decorrelates neighboring cells thoroughly
        h = h xor (h ushr 16)
        h *= 0x85ebca6b.toInt()
        h = h xor (h ushr 13)
        h *= 0xc2b2ae35.toInt()
        h = h xor (h ushr 16)
        return (h and 0xFF) < 38 // ~15% of cells carry a painted mark
    }
}

enum class Proximity { NONE, NEAR, HIT }

/** Mutable kinematic carrier for the drift model. */
class CarKinematics(
    var x: Float = 0f,
    var y: Float = 0f,
    var heading: Float = 0f,
    var velAngle: Float = 0f,
) {
    fun reset(nx: Float, ny: Float, heading: Float) {
        x = nx; y = ny; this.heading = heading; velAngle = heading
    }

    fun copyFrom(o: CarKinematics) { x = o.x; y = o.y; heading = o.heading; velAngle = o.velAngle }
}

/** atan2 wrapper kept here so tests don't need Gdx math. */
fun atan2Deg(y: Float, x: Float): Float = atan2(y, x)
