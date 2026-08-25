package com.muscab2006.gridlockheat

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * v0.6 LIVE CITY: deterministic street grid + solid buildings + flowing traffic.
 *
 * Geometry contract:
 *  - Avenues run every [CELL] units on both axes (centers at k*CELL).
 *  - Roads are [ROAD_HALF]*2 wide; everything between is a city block.
 *  - Buildings are derived per-block from a pure hash, so collision, render
 *    and future saves all agree without storing anything.
 */
object City {
    const val CELL = 340f
    const val ROAD_HALF = 60f
    private const val BSEED = 0x51c7 // building hash salt

    private val ASPHALT = Color(0.05f, 0.05f, 0.065f, 1f)
    private val CURB = Color(0.13f, 0.13f, 0.16f, 1f)
    private val LANE_DASH = Color(1f, 0.93f, 0.62f, 0.30f)
    private val CROSSWALK = Color(0.85f, 0.85f, 0.9f, 0.10f)

    private val tmp = Color()

    /** Nearest avenue center-line coordinate for a world coordinate. */
    fun nearestAvenue(v: Float): Float = floor(v / CELL + 0.5f) * CELL

    private fun hash3(a: Int, b: Int, c: Int): Int {
        var h = (a.toLong() * 374761393L + b.toLong() * 668265263L + c.toLong() * 2654435761L).toInt()
        h = h xor (h ushr 13)
        h *= 1274126177
        h = h xor (h ushr 16)
        return h
    }

    private fun frac01(h: Int): Float = (h and 0xFFFF).toFloat() / 65535f

    data class Building(val x: Float, val y: Float, val w: Float, val h: Float, val tone: Float, val litRows: Int)

    /** Deterministic building for a block cell, or null if the block is open. */
    fun buildingAt(bx: Int, by: Int, seed: Int): Building? {
        val h = hash3(bx, by, seed xor BSEED)
        if (frac01(h) > 0.72f) return null            // some blocks are open lots
        val inner = CELL - 2f * ROAD_HALF - 46f       // sidewalk margin
        val bw = inner * (0.52f + frac01(h shr 4) * 0.44f)
        val bh = inner * (0.52f + frac01(h shr 9) * 0.44f)
        val cx = bx * CELL + (frac01(h shr 14) - 0.5f) * (inner - bw)
        val cy = by * CELL + (frac01(h shr 19) - 0.5f) * (inner - bh)
        return Building(cx, cy, bw, bh, frac01(h shr 24), 1 + ((h ushr 28) and 1))
    }

    /**
     * Draw roads + buildings visible in the given view rect.
     * sunX/sunY drive the extrusion offset; night = emissive windows.
     */
    fun drawWorld(shapes: ShapeRenderer, viewL: Float, viewB: Float, viewR: Float, viewT: Float,
                  themeSeed: Int, sunX: Float, sunY: Float, nightWindows: Boolean) {
        val gx0 = floor((viewL - ROAD_HALF) / CELL).toInt() - 1
        val gx1 = floor((viewR + ROAD_HALF) / CELL).toInt() + 1
        val gy0 = floor((viewB - ROAD_HALF) / CELL).toInt() - 1
        val gy1 = floor((viewT + ROAD_HALF) / CELL).toInt() + 1

        // ── asphalt bands ──
        shapes.setColor(ASPHALT)
        for (gx in gx0..gx1) shapes.rect(gx * CELL - ROAD_HALF, viewB - 40f, ROAD_HALF * 2f, viewT - viewB + 80f)
        for (gy in gy0..gy1) shapes.rect(viewL - 40f, gy * CELL - ROAD_HALF, viewR - viewL + 80f, ROAD_HALF * 2f)

        // curbs
        shapes.setColor(CURB)
        for (gx in gx0..gx1) {
            shapes.rect(gx * CELL - ROAD_HALF - 7f, viewB - 40f, 7f, viewT - viewB + 80f)
            shapes.rect(gx * CELL + ROAD_HALF, viewB - 40f, 7f, viewT - viewB + 80f)
        }
        for (gy in gy0..gy1) {
            shapes.rect(viewL - 40f, gy * CELL - ROAD_HALF - 7f, viewR - viewL + 80f, 7f)
            shapes.rect(viewL - 40f, gy * CELL + ROAD_HALF, viewR - viewL + 80f, 7f)
        }

        // dashed center lines + crosswalks near intersections
        shapes.setColor(LANE_DASH)
        val dashL = 44f; val dashG = 92f
        var d = floor(viewB / dashG).toInt() * dashG
        while (d < viewT) {
            for (gx in gx0..gx1) {
                val ax = gx * CELL
                var nearCross = false
                for (gy2 in gy0..gy1) if (abs(d - gy2 * CELL) < ROAD_HALF + 26f) nearCross = true
                if (!nearCross) shapes.rect(ax - 2.6f, d, 5.2f, dashL)
            }
            d += dashG
        }
        d = floor(viewL / dashG).toInt() * dashG
        while (d < viewR) {
            for (gy in gy0..gy1) {
                val ay = gy * CELL
                var nearCross = false
                for (gx2 in gx0..gx1) if (abs(d - gx2 * CELL) < ROAD_HALF + 26f) nearCross = true
                if (!nearCross) shapes.rect(d, ay - 2.6f, dashL, 5.2f)
            }
            d += dashG
        }

        // ── buildings (pseudo-3D: shadow slab + extrusion wall + roof) ──
        val bx0 = floor((viewL - CELL) / CELL).toInt()
        val bx1 = floor((viewR + CELL) / CELL).toInt()
        val by0 = floor((viewB - CELL) / CELL).toInt()
        val by1 = floor((viewT + CELL) / CELL).toInt()
        for (bx in bx0..bx1) for (by in by0..by1) {
            val b = buildingAt(bx, by, themeSeed) ?: continue
            // ground-contact shadow toward the sun-opposite side
            tmp.set(0f, 0f, 0f, 0.34f)
            shapes.setColor(tmp)
            shapes.rect(b.x - b.w / 2f - sunX * 14f, b.y - b.h / 2f - sunY * 14f, b.w, b.h)
            // extruded wall (dark side facing away from sun)
            tmp.set(0.055f, 0.05f, 0.08f, 1f)
            shapes.setColor(tmp)
            shapes.rect(b.x - b.w / 2f - sunX * 8f, b.y - b.h / 2f - sunY * 8f, b.w, b.h)
            // roof face — tone varies per building
            tmp.set(0.115f + b.tone * 0.06f, 0.11f + b.tone * 0.05f, 0.15f + b.tone * 0.07f, 1f)
            shapes.setColor(tmp)
            shapes.rect(b.x - b.w / 2f, b.y - b.h / 2f, b.w, b.h)
            // rooftop lip
            tmp.set(0.22f, 0.21f, 0.27f, 1f)
            shapes.setColor(tmp)
            shapes.rect(b.x - b.w / 2f, b.y + b.h / 2f - 4f, b.w, 4f)
            // emissive windows on the roof-face illusion (night only)
            if (nightWindows) {
                val rows = 2 + b.litRows; val cols = 3
                for (r in 0 until rows) for (cx2 in 0 until cols) {
                    val wh = hash3(bx * 31 + r, by * 17 + cx2, themeSeed xor 0x77)
                    if (frac01(wh) > 0.45f) continue                    // some windows dark
                    val wx = b.x - b.w / 2f + b.w * (cx2 + 0.5f) / cols
                    val wy = b.y - b.h / 2f + b.h * (r + 0.55f) / (rows + 0.6f)
                    tmp.set(if (frac01(wh shr 8) > 0.75f) Color(0.45f, 0.95f, 1f, 1f) else Color(1f, 0.82f, 0.35f, 1f))
                    tmp.a = 0.35f + frac01(wh shr 12) * 0.5f
                    shapes.setColor(tmp)
                    shapes.rect(wx - 4f, wy - 3f, 8f, 6f)
                }
            }
        }
    }

    /**
     * Solid push-out against nearby buildings. Returns true if a hit occurred.
     * px/py are mutated to the resolved position.
     */
    fun collideBuildings(pos: FloatArray, radius: Float, seed: Int): Boolean {
        val bx = floor((pos[0] + CELL / 2f) / CELL).toInt()
        val by = floor((pos[1] + CELL / 2f) / CELL).toInt()
        var hit = false
        for (ox in -1..1) for (oy in -1..1) {
            val b = buildingAt(bx + ox, by + oy, seed) ?: continue
            val hw = b.w / 2f + radius; val hh = b.h / 2f + radius
            val dx = pos[0] - b.x; val dy = pos[1] - b.y
            if (abs(dx) < hw && abs(dy) < hh) {
                val penX = hw - abs(dx); val penY = hh - abs(dy)
                if (penX < penY) pos[0] = b.x + (if (dx >= 0f) hw else -hw)
                else pos[1] = b.y + (if (dy >= 0f) hh else -hh)
                hit = true
            }
        }
        return hit
    }
}

/**
 * Ambient traffic: cars flowing along avenues in both directions.
 * Flat float stride: x, y, heading, speed, axis(0=H,1=V), dir(+1/-1), tintIx
 */
class Traffic(private val count: Int = 14) {
    private val STRIDE = 7
    private val d = FloatArray(count * STRIDE)

    companion object {
        val TINTS = arrayOf(
            Color(0.85f, 0.87f, 0.9f, 1f),    // silver
            Color(0.2f, 0.25f, 0.32f, 1f),    // slate
            Color(0.75f, 0.18f, 0.15f, 1f),   // red sedan
            Color(0.95f, 0.78f, 0.1f, 1f),    // taxi yellow
            Color(0.16f, 0.42f, 0.7f, 1f),    // blue hatch
            Color(0.88f, 0.88f, 0.94f, 1f)    // white van
        )
        private const val RESPAWN_R = 1500f
        private const val CULL_R = 2100f
    }

    fun reset(seed: Long, px: Float, py: Float) {
        val rng = kotlin.random.Random(seed)
        for (i in 0 until count) {
            spawn(i, rng, px, py, initial = true)
        }
    }

    private fun spawn(i: Int, rng: kotlin.random.Random, px: Float, py: Float, initial: Boolean) {
        val o = i * STRIDE
        val axis = if (rng.nextFloat() < 0.5f) 0 else 1
        val dir = if (rng.nextFloat() < 0.5f) 1f else -1f
        val ave = (floor(((if (axis == 0) py else px)) / City.CELL + (rng.nextFloat() - 0.5f) * 5f).toInt()) * City.CELL
        val along = if (initial) (rng.nextFloat() - 0.5f) * 2400f
        else (if (rng.nextFloat() < 0.5f) 1f else -1f) * (700f + rng.nextFloat() * 800f)
        val laneOff = 27f * dir   // right-hand traffic
        if (axis == 0) {          // horizontal avenue → travels ±X
            d[o] = px + along; d[o + 1] = ave + laneOff
            d[o + 2] = if (dir > 0f) 0f else Math.PI.toFloat() // heading right/left
        } else {                  // vertical avenue → travels ±Y
            d[o] = ave - laneOff; d[o + 1] = py + along
            d[o + 2] = if (dir > 0f) 1.5707964f else -1.5707964f
        }
        d[o + 3] = 70f + rng.nextFloat() * 65f
        d[o + 4] = axis.toFloat(); d[o + 5] = dir; d[o + 6] = (rng.nextFloat() * TINTS.size).toInt().toFloat()
    }

    /** Advance flow; respawn strays around the player so streets always feel alive. */
    fun update(dt: Float, px: Float, py: Float, seed: Long) {
        for (i in 0 until count) {
            val o = i * STRIDE
            val sp = d[o + 3]
            when (d[o + 4].toInt()) {
                0 -> d[o] += cos(d[o + 2]) * sp * dt
                else -> d[o + 1] += sin(d[o + 2]) * sp * dt
            }
            val dx = d[o] - px; val dy = d[o + 1] - py
            if (dx * dx + dy * dy > CULL_R * CULL_R) {
                val rng = kotlin.random.Random((seed + i * 7919L + System.nanoTime()).toString().hashCode().toLong())
                spawn(i, rng, px, py, initial = false)
            }
        }
    }

    /** Player contact test; returns index of first car within hitDist or -1. */
    fun hitTest(px: Float, py: Float, hitDist: Float): Int {
        for (i in 0 until count) {
            val o = i * STRIDE
            val dx = d[o] - px; val dy = d[o + 1] - py
            if (dx * dx + dy * dy < hitDist * hitDist) return i
        }
        return -1
    }

    /** Perpendicular nudge after a crash so cars shove aside instead of overlapping. */
    fun nudgeAway(i: Int, px: Float, py: Float) {
        val o = i * STRIDE
        val dx = d[o] - px; val dy = d[o + 1] - py
        val len = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
        d[o] += dx / len * 46f; d[o + 1] += dy / len * 46f
        d[o + 3] *= 0.4f // stunned driver brakes
    }

    fun x(i: Int) = d[i * STRIDE]
    fun y(i: Int) = d[i * STRIDE + 1]
    fun heading(i: Int) = d[i * STRIDE + 2]

    fun draw(batch: SpriteBatch, tex: Texture, lengthUnits: Float, RAD2DEG: Float) {
        for (i in 0 until count) {
            val o = i * STRIDE
            val scale = lengthUnits / tex.height
            val w = tex.width * scale; val h = lengthUnits
            batch.color = TINTS[d[o + 6].toInt() % TINTS.size]
            batch.draw(
                tex, d[o] - w / 2f, d[o + 1] - h / 2f,
                w / 2f, h / 2f, w, h, 1f, 1f,
                d[o + 2] * RAD2DEG - 90f,
                0, 0, tex.width, tex.height, false, false
            )
        }
        batch.color = Color.WHITE
    }
}
