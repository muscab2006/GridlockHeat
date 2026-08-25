package com.muscab2006.gridlockheat

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture

/** One piece of terrain dressing. All numbers are world units. */
class Prop(val x: Float, val y: Float, val kind: Int, val rot: Float, val scale: Float) {
    companion object {
        const val CONE = 0
        const val BARREL_RED = 1
        const val BARREL_BLUE = 2
        const val BARRIER = 3
        const val ROCK = 4
        const val BUILDING = 5 // drawn procedurally, no sprite
    }
}

/**
 * Deterministic prop layouts per map. Regenerated once per run start.
 * Zero allocations after [layout] returns.
 */
object PropField {

    fun layout(theme: MapTheme, seed: Long, out: MutableList<Prop>) {
        out.clear()
        val rng = kotlin.random.Random(seed)
        when (theme.propSet) {
            PropSet.CITY -> {
                // parked-car rows + cone clusters + barriers; buildings handled separately
                for (i in 0 until 26) {
                    val gx = (rng.nextFloat() * 2 - 1) * 2600f
                    val gy = (rng.nextFloat() * 2 - 1) * 2600f
                    when (i % 4) {
                        0 -> out.add(Prop(gx, gy, Prop.BARRIER, (rng.nextFloat() * 2 - 1) * 0.6f, 1f))
                        1 -> out.add(Prop(gx, gy, Prop.CONE, 0f, 1f))
                        2 -> out.add(Prop(gx, gy, Prop.BARREL_RED, 0f, 1f))
                        else -> out.add(Prop(gx, gy, Prop.BARREL_BLUE, 0f, 1f))
                    }
                }
            }
            PropSet.SNOW -> {
                for (i in 0 until 30) {
                    val gx = (rng.nextFloat() * 2 - 1) * 2800f
                    val gy = (rng.nextFloat() * 2 - 1) * 2800f
                    when {
                        i % 3 == 0 -> out.add(Prop(gx, gy, Prop.ROCK, rng.nextFloat() * 6.28f, 1.15f))
                        i % 3 == 1 -> out.add(Prop(gx, gy, Prop.CONE, 0f, 1f))
                        else -> out.add(Prop(gx, gy, Prop.BARRIER, rng.nextFloat() * 6.28f, 1f))
                    }
                }
            }
            PropSet.CANYON -> {
                for (i in 0 until 34) {
                    val gx = (rng.nextFloat() * 2 - 1) * 3000f
                    val gy = (rng.nextFloat() * 2 - 1) * 3000f
                    if (i % 5 == 4) out.add(Prop(gx, gy, Prop.BARREL_RED, 0f, 1f))
                    else out.add(Prop(gx, gy, Prop.ROCK, rng.nextFloat() * 6.28f, 0.9f + rng.nextFloat() * 0.9f))
                }
            }
        }
    }

    /** Sprites used by props, indexed by Prop.kind where sprite-based. */
    fun textureFor(tex: Array<Texture?>, kind: Int): Texture? = when (kind) {
        Prop.CONE -> tex[0]
        Prop.BARREL_RED -> tex[1]
        Prop.BARREL_BLUE -> tex[2]
        Prop.BARRIER -> tex[3]
        Prop.ROCK -> tex[(tex.size.coerceAtMost(8) - 1).coerceAtLeast(4)] // rocks occupy slots 4..7
        else -> null
    }

    /** Collision radius in world units per kind (0 = pass-through decoration). */
    fun radiusOf(p: Prop): Float = when (p.kind) {
        Prop.CONE -> 9f
        Prop.BARREL_RED, Prop.BARREL_BLUE -> 13f
        Prop.BARRIER -> 22f
        Prop.ROCK -> 20f * p.scale
        else -> 0f
    }
}
