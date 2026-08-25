package com.muscab2006.gridlockheat

import kotlin.random.Random

/** Deterministic xorshift RNG, seedable per run. Top-level so every module shares it. */
object MathUtilsRandom {
    private var s = System.nanoTime().toInt() xor 0x5bd1e995.toInt()

    fun reseed(v: Int) { s = v }

    fun nextFloat(): Float {
        var x = s
        x = x xor (x shl 13); x = x xor (x ushr 17); x = x xor (x shl 5)
        s = x
        return (x.toLong() and 0xFFFFFFFFL).toFloat() / 4294967296f
    }

    /** Seeded standalone stream (does not disturb the global one). */
    fun seeded(seed: Long): Random = Random(seed)
}
