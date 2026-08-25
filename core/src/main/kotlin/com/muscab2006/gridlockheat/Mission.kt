package com.muscab2006.gridlockheat

/** One per-run objective, generated at run start. */
class Mission private constructor(val kind: Int, val target: Float, val label: String) {
    var progress = 0f
        private set

    fun add(v: Float) { progress += v }
    fun done() = progress >= target
    fun ratio() = (progress / target).coerceIn(0f, 1f)

    companion object {
        const val SURVIVE = 0   // seconds alive
        const val NEAR_MISS = 1 // count of near misses
        const val DRIFT = 2     // cumulative drift meters
        const val GATES = 3     // racing gates cleared

        private val KINDS = intArrayOf(SURVIVE, NEAR_MISS, DRIFT)

        fun generate(seed: Long, forceKind: Int = -1): Mission {
            val r = kotlin.random.Random(seed)
            val kind = if (forceKind >= 0) forceKind else KINDS[(r.nextFloat() * 3).toInt().coerceIn(0, 2)]
            return when (kind) {
                SURVIVE -> Mission(SURVIVE, 45f + (r.nextFloat() * 30f).toInt(), "SURVIVE")
                NEAR_MISS -> Mission(NEAR_MISS, (5 + r.nextFloat() * 7).toInt().toFloat(), "NEAR MISSES")
                GATES -> Mission(GATES, (4 + r.nextFloat() * 4).toInt().toFloat(), "GATES")
                else -> Mission(DRIFT, 400f + (r.nextFloat() * 500f).toInt(), "DRIFT METERS")
            }
        }

        /** Human text for the result screen. */
        fun verdict(m: Mission?): String {
            m ?: return ""
            val p = if (m.kind == DRIFT) "${m.progress.toInt()}m" else "${m.progress.toInt()}"
            val t = if (m.target >= 100f && m.kind == DRIFT) "${m.target.toInt()}m"
            else if (m.target >= 100f) "${m.target.toInt()}"
            else "${m.target.toInt()}"
            return "MISSION ${if (m.done()) "PASSED" else "FAILED"} — $p / $t"
        }
    }

    fun hudText(): String {
        val cur = if (kind == DRIFT) "${progress.toInt()}m" else "${progress.toInt()}"
        val tgt = if (kind == DRIFT) "${target.toInt()}m" else "${target.toInt()}"
        return "$label  $cur / $tgt"
    }
}
