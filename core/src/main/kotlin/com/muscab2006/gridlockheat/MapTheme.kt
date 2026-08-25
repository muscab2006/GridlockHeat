package com.muscab2006.gridlockheat

import com.badlogic.gdx.graphics.Color

/** Weather particle mode for a map. */
enum class Weather { NONE, SNOW, DUST }

/** Which prop sprites dress this map. */
enum class PropSet { CITY, SNOW, CANYON }

/**
 * Everything that makes one map feel like a place.
 * Colors are libGDX Color instances reused every frame (never allocated in loop).
 */
class MapTheme(
    val id: String,
    val displayName: String,
    val tagline: String,
    // ground checker
    val groundA: Color,
    val groundB: Color,
    val markColor: Color,      // painted cell marks
    val skidColor: Color,      // tire marks on this surface
    val ambient: Color,        // full-screen color-grade overlay (very low alpha)
    val vignette: Float,       // 0..1 corner darkening strength
    // world physics flavor
    val gripMul: Float,        // <1 = icier drifts
    val copSpeedMul: Float,
    val copSpawnMul: Float,    // <1 = fewer cops
    // dressing
    val propSet: PropSet,
    val propDensity: Int,      // props per 100x100 area seed pass
    val hasBuildings: Boolean, // city blocks with emissive windows
    val weather: Weather,
    val sunX: Float,           // direction shadows are cast toward (unit-ish)
    val sunY: Float,
    // scoring flavor text
    val scoreWord: String,     // "SCORE" / "STYLE" / "HEAT"
    // racing-mode flag: gates vs the clock instead of cops
    val isRacing: Boolean = false
)

object Themes {
    val CRIME_CITY = MapTheme(
        id = "crimecity", displayName = "CRIME CITY", tagline = "lose the heat downtown",
        groundA = Color(0.09f, 0.09f, 0.11f, 1f), groundB = Color(0.105f, 0.105f, 0.13f, 1f),
        markColor = Color(1f, 1f, 1f, 0.10f),
        skidColor = Color(0f, 0f, 0f, 0.5f),
        ambient = Color(0.6f, 0.4f, 1f, 0.05f),
        vignette = 0.55f,
        gripMul = 1f, copSpeedMul = 1f, copSpawnMul = 1f,
        propSet = PropSet.CITY, propDensity = 7, hasBuildings = true,
        weather = Weather.NONE, sunX = -0.55f, sunY = -0.83f,
        scoreWord = "SCORE"
    )

    val SNOW_DRIFT = MapTheme(
        id = "snowdrift", displayName = "SNOW DRIFT", tagline = "icy lot, endless slide",
        groundA = Color(0.82f, 0.86f, 0.92f, 1f), groundB = Color(0.78f, 0.83f, 0.90f, 1f),
        markColor = Color(0.35f, 0.45f, 0.65f, 0.16f),
        skidColor = Color(0.30f, 0.38f, 0.55f, 0.42f),
        ambient = Color(0.55f, 0.75f, 1f, 0.07f),
        vignette = 0.35f,
        gripMul = 0.58f, copSpeedMul = 0.92f, copSpawnMul = 0.85f,
        propSet = PropSet.SNOW, propDensity = 9, hasBuildings = false,
        weather = Weather.SNOW, sunX = 0.4f, sunY = -0.92f,
        scoreWord = "STYLE"
    )

    val CANYON_RUSH = MapTheme(
        id = "canyon", displayName = "CANYON RUSH", tagline = "dust, rocks, no mercy",
        groundA = Color(0.48f, 0.36f, 0.26f, 1f), groundB = Color(0.52f, 0.39f, 0.28f, 1f),
        markColor = Color(1f, 0.85f, 0.5f, 0.12f),
        skidColor = Color(0.22f, 0.14f, 0.08f, 0.55f),
        ambient = Color(1f, 0.55f, 0.2f, 0.06f),
        vignette = 0.5f,
        gripMul = 0.86f, copSpeedMul = 1.06f, copSpawnMul = 1.15f,
        propSet = PropSet.CANYON, propDensity = 11, hasBuildings = false,
        weather = Weather.DUST, sunX = -0.8f, sunY = -0.6f,
        scoreWord = "HEAT"
    )

    val RACING_DUSK = MapTheme(
        id = "racing", displayName = "RACING DUSK", tagline = "beat the clock, thread the gates",
        groundA = Color(0.13f, 0.12f, 0.17f, 1f), groundB = Color(0.15f, 0.14f, 0.19f, 1f),
        markColor = Color(1f, 0.3f, 0.5f, 0.14f),
        skidColor = Color(0.05f, 0.05f, 0.08f, 0.5f),
        ambient = Color(0.9f, 0.35f, 0.6f, 0.05f),
        vignette = 0.45f,
        gripMul = 0.94f, copSpeedMul = 1f, copSpawnMul = 0f,
        propSet = PropSet.CITY, propDensity = 5, hasBuildings = false,
        weather = Weather.NONE, sunX = 0.7f, sunY = -0.72f,
        scoreWord = "TIME ATTACK",
        isRacing = true
    )

    val ALL: Array<MapTheme> = arrayOf(CRIME_CITY, SNOW_DRIFT, CANYON_RUSH, RACING_DUSK)
}

