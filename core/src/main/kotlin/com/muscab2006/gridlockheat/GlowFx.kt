package com.muscab2006.gridlockheat

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.math.Polygon
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Texture-free post-processing overlays (fake bloom, headlights, vignette,
 * color grade) drawn through the game's existing Filled [ShapeRenderer].
 *
 * Contract: every function assumes the renderer is already between
 * begin(ShapeType.Filled) / end(); none of them begins, ends, flushes, or
 * allocates. All scratch (spoke tables, cone vertices, Polygon, scratch
 * Colors) is sized once at first touch of [GlowFx] and reused forever.
 *
 * Triangle cost per call: glowDisc 10 · headlightCones 4 · vignette 24 · grade 2.
 */
object GlowFx {

    // ─── tuning ──────────────────────────────────────────────────────────────
    private const val DISC_LAYERS = 5            // concentric quads per glow disc (2 tris each)
    private const val CONE_LEN = 150f            // headlight reach, world units
    private const val CONE_HALF = 11f            // wide-cone half-spread, degrees
    private const val CORE_HALF = 6f             // hot inner-cone half-spread, degrees
    private const val CORE_FRAC = 0.70f          // inner cone length vs wide cone
    private const val LAMP_FWD = 21f             // lamp offset ahead of car center
    private const val LAMP_SIDE = 8f             // lamp lateral offset (±)
    private const val VIGNETTE_FANS = 3          // concentric fans…
    private const val VIGNETTE_SEGS = 8          // …of 8 sectors → 24 triangles total
    private val RAD = (Math.PI / 180.0).toFloat() // deg→rad (non-const: folds Math.PI)

    // ─── sized-once scratch (zero allocation per call) ───────────────────────
    // Spread trig is constant, so it is resolved once; the ±half-angle rays
    // come free per call from angle-addition sign flips.
    private val wideCos = cos(CONE_HALF * RAD)
    private val wideSin = sin(CONE_HALF * RAD)
    private val coreCos = cos(CORE_HALF * RAD)
    private val coreSin = sin(CORE_HALF * RAD)

    /** Unit vectors of the 8 vignette spokes: cos,sin pairs, filled in init. */
    private val spokeX = FloatArray(VIGNETTE_SEGS)
    private val spokeY = FloatArray(VIGNETTE_SEGS)

    /** Reused headlight cone triangle: apex + two base points (x,y ×3). */
    private val coneVerts = FloatArray(6)
    private val outCone = Polygon()

    /** Scratch colors for the per-corner triangle overload (vignette only). */
    private val cCenter = Color(0f, 0f, 0f, 0f)
    private val cEdge = Color(0f, 0f, 0f, 1f)

    init {
        for (i in 0 until VIGNETTE_SEGS) {
            val a = i * 6.2831855f / VIGNETTE_SEGS
            spokeX[i] = cos(a); spokeY[i] = sin(a)
        }
    }

    // ─── public API ──────────────────────────────────────────────────────────

    /**
     * Additive-style glow disc (fake bloom): [DISC_LAYERS] concentric
     * alpha-faded quads centered on (x,y), drawn outer→inner so hot cores
     * composite on top of faint halos. Intensity 0..1 scales both size
     * (40–100 % of radius) and alpha. 10 triangles.
     */
    fun glowDisc(shapes: ShapeRenderer, tint: Color, x: Float, y: Float, radius: Float, intensity: Float) {
        val k = intensity.coerceIn(0f, 1f)
        if (k <= 0f || radius <= 0f) return
        val size = radius * (0.40f + 0.60f * k)      // intensity scales size…
        val base = tint.a * (0.30f + 0.70f * k)      // …and alpha
        val step = size / DISC_LAYERS
        var m = 0.10f                                 // outermost layer alpha share
        var half = size
        repeat(DISC_LAYERS) {
            shapes.setColor(tint.r, tint.g, tint.b, base * m)
            quad(shapes, x, y, half)
            m = (m * 1.9f).coerceAtMost(1f)           // .10 .19 .36 .69 1.0 inward
            half -= step
        }
    }

    /**
     * Headlight cones for one car. Both lamps sit [LAMP_FWD] units along
     * angleRad, ±[LAMP_SIDE] lateral; each throws a [CONE_LEN]-unit triangle
     * of ±[CONE_HALF]° total arc whose vertices are written into the reused
     * [outCone] polygon (setVertices keeps the same backing array). Drawn
     * softly: wide cone at alpha 0.05 under a shorter, narrower hot core at
     * 0.10, both scaled by tint.a. Per-call trig is one cos/sin pair; the
     * spread rays use precomputed angle-addition terms. 4 triangles.
     */
    fun headlightCones(shapes: ShapeRenderer, tint: Color, x: Float, y: Float, angleRad: Float) {
        val ca = cos(angleRad); val sa = sin(angleRad)
        val nx = x + ca * LAMP_FWD; val ny = y + sa * LAMP_FWD
        val px = -sa * LAMP_SIDE; val py = ca * LAMP_SIDE
        // faint wide spreads first, hot cores after, so cores composite on top
        lampCone(shapes, tint, nx + px, ny + py, ca, sa, false)
        lampCone(shapes, tint, nx - px, ny - py, ca, sa, false)
        lampCone(shapes, tint, nx + px, ny + py, ca, sa, true)
        lampCone(shapes, tint, nx - px, ny - py, ca, sa, true)
    }

    /**
     * Full-screen vignette without textures: [VIGNETTE_FANS] concentric
     * 8-sector fans around (cx,cy). Each wedge fades from fully transparent at
     * the shared center vertex to dark at its rim, so stacked fans build a
     * smooth stepped falloff that is deepest at the corners of the w×h rect;
     * the outermost rim always reaches the farthest corner. Rim alphas are
     * 0.07/0.13/0.18 × strength (single-pass cap 0.38·strength). Exactly
     * 3 × 8 = 24 triangles, zero allocation.
     */
    fun vignette(shapes: ShapeRenderer, w: Float, h: Float, cx: Float, cy: Float, strength: Float) {
        val s = strength.coerceIn(0f, 1f)
        if (s <= 0f || w <= 0f || h <= 0f) return
        // farthest-corner distance ⇒ outer rim always covers the whole rect
        val fx = max(abs(cx), abs(cx - w))
        val fy = max(abs(cy), abs(cy - h))
        val rim = sqrt(fx * fx + fy * fy) * 1.02f
        fanRing(shapes, cx, cy, rim * 0.40f, 0.07f * s)   // inner: whisper
        fanRing(shapes, cx, cy, rim * 0.72f, 0.13f * s)   // mid
        fanRing(shapes, cx, cy, rim, 0.18f * s)           // outer: darkest
    }

    /**
     * Color-grade wash: a single axis-aligned w×h rect centered on (cx,cy),
     * tinted with c (its alpha carries the wash strength). Cheap mood pass.
     * 2 triangles.
     */
    fun grade(shapes: ShapeRenderer, w: Float, h: Float, cx: Float, cy: Float, c: Color) {
        shapes.setColor(c.r, c.g, c.b, c.a)
        shapes.rect(cx - w / 2f, cy - h / 2f, w, h)
    }

    // ─── helpers (rotation-safe, allocation-free) ────────────────────────────

    private fun lampCone(shapes: ShapeRenderer, tint: Color, lx: Float, ly: Float, ca: Float, sa: Float, core: Boolean) {
        val ch = if (core) coreCos else wideCos
        val sh = if (core) coreSin else wideSin
        val len = if (core) CONE_LEN * CORE_FRAC else CONE_LEN
        val dxP = ca * ch - sa * sh; val dyP = sa * ch + ca * sh   // +half-angle ray
        val dxM = ca * ch + sa * sh; val dyM = sa * ch - ca * sh   // −half-angle ray
        coneVerts[0] = lx; coneVerts[1] = ly
        coneVerts[2] = lx + dxP * len; coneVerts[3] = ly + dyP * len
        coneVerts[4] = lx + dxM * len; coneVerts[5] = ly + dyM * len
        outCone.setVertices(coneVerts)                 // reuses the same FloatArray
        val a = if (core) 0.10f else 0.05f
        shapes.setColor(tint.r, tint.g, tint.b, tint.a * a)
        shapes.triangle(coneVerts[0], coneVerts[1], coneVerts[2], coneVerts[3], coneVerts[4], coneVerts[5])
    }

    /** One 8-sector fan whose wedges fade center-transparent → rim-dark. */
    private fun fanRing(shapes: ShapeRenderer, cx: Float, cy: Float, radius: Float, alpha: Float) {
        cEdge.a = alpha
        for (j in 0 until VIGNETTE_SEGS) {
            val k = (j + 1) % VIGNETTE_SEGS
            shapes.triangle(
                cx, cy,
                cx + spokeX[j] * radius, cy + spokeY[j] * radius,
                cx + spokeX[k] * radius, cy + spokeY[k] * radius,
                cCenter, cEdge, cEdge
            )
        }
    }

    /** Axis-aligned centered quad as two triangles (matches house rect style). */
    private fun quad(shapes: ShapeRenderer, cx: Float, cy: Float, half: Float) {
        shapes.triangle(cx - half, cy - half, cx + half, cy - half, cx + half, cy + half)
        shapes.triangle(cx - half, cy - half, cx + half, cy + half, cx - half, cy + half)
    }
}
