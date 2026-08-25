package com.muscab2006.gridlockheat

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.InputProcessor
import com.badlogic.gdx.Preferences
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// ─── tuning ──────────────────────────────────────────────────────────────────
private const val STEP = 1f / 60f
private const val BASE_SPEED = 340f          // auto-throttle, u/s
private const val MAX_SPEED_RAMP = 55f       // added over 120s
private const val DRIFT_BONUS_SPEED = 45f    // reward for sliding
private const val TURN_RATE = 2.7f           // rad/s at full lock
private const val GRIP = 4.6f                // vel->heading convergence 1/s
private const val DRIFTING_RAD = 0.40f       // slide threshold (~23°)
private const val CAR_R = 24f
private const val COP_R = 26f
private const val BUST_RANGE = CAR_R + COP_R
private const val NEAR_RANGE = 96f
private const val MIN_REL_SPEED = 230f
private const val COMBO_TIME = 4f
private const val VIEW_W = 520f   // portrait: fixed world width, height follows aspect

class GridlockHeat : ApplicationAdapter(), InputProcessor {

    private enum class State { MENU, PLAYING, BUSTED, PAUSED }

    private class Cop {
        val kin = CarKinematics()
        var prevX = 0f; var prevY = 0f
        var prevDist = 9999f
        var nearArmed = true
        var phase = 0f
    }

    private class Popup(var x: Float, var y: Float, val text: String, val color: Color)

    private lateinit var shapes: ShapeRenderer
    private lateinit var batch: SpriteBatch
    private lateinit var font: BitmapFont
    private lateinit var layout: GlyphLayout
    private lateinit var prefs: Preferences
    private lateinit var texPlayer: com.badlogic.gdx.graphics.Texture
    private lateinit var texCop: com.badlogic.gdx.graphics.Texture
    private val camera = OrthographicCamera()

    private val car = CarKinematics()
    private var carPrevX = 0f; private var carPrevY = 0f
    private val cops = ArrayList<Cop>()
    private val skids = ArrayList<FloatArray>()   // x,y,angle,life
    private val popups = ArrayList<Popup>()

    private var state = State.MENU
    private var acc = 0f
    private var hitStop = 0f
    private var trauma = 0f
    private var crashCooldown = 0f   // gates crash penalties to once per 0.45s
    private var time = 0f
    private var score = 0f
    private var combo = 1
    private var comboTimer = 0f
    private var highscore = 0f
    private var spawnTimer = 2f
    private var steerInput = 0f
    private var anchorX = 0f
    private var flashTimer = 0f

    // v0.4 cinematic state
    private var themeIx = 0
    private val theme get() = Themes.ALL[themeIx.coerceIn(0, Themes.ALL.size - 1)]
    private val props = ArrayList<Prop>(64)
    private val propTex = arrayOfNulls<com.badlogic.gdx.graphics.Texture>(8)
    private lateinit var particles: Particles
    private var mission: Mission? = null
    private var slowTimer = 0f
    private var statNear = 0
    private var statTopCombo = 1
    private var frameNo = 0
    // v0.5 arcade: racing gates, pause zones, drift bank, combo tiers
    private val gates = ArrayList<FloatArray>() // x,y,angle,passed(0/1)
    private var raceTime = 0f
    private var gatesPassed = 0
    private var driftBank = 0f
    private val pauseZone = FloatArray(4)
    private val pauseBtnZones = FloatArray(12)
    private val traffic = Traffic(14)
    private val playerPos = FloatArray(2)
    private var loggedOnce = false
    private lateinit var whitePx: com.badlogic.gdx.graphics.Texture

    // v0.4 cinematic collaborators
    private val cine = CinematicCam()
    private var menuBg: com.badlogic.gdx.graphics.Texture? = null
    private var menuTime = 0f
    private val tapZones = FloatArray(16)
    private val uiSkin = UiSkin(
        Color(1f, 0.62f, 0.15f, 1f),
        Color(0.25f, 0.75f, 1f, 1f),
        Color(0f, 0f, 0f, 0.55f),
        Color(0.74f, 0.77f, 0.84f, 1f)
    )
    private val mapCards = Array(3) { i ->
        MapCard(Themes.ALL[i].displayName, Themes.ALL[i].tagline, Color(Themes.ALL[i].groundA))
    }

    override fun create() {
        shapes = ShapeRenderer()
        batch = SpriteBatch()
        font = BitmapFont().apply { data.setScale(1.5f) }
        layout = GlyphLayout()
        prefs = Gdx.app.getPreferences("gridlockheat")
        highscore = prefs.getFloat("highscore", 0f)
        texPlayer = com.badlogic.gdx.graphics.Texture(Gdx.files.internal("gfx/player.png")).apply {
            setFilter(com.badlogic.gdx.graphics.Texture.TextureFilter.Linear, com.badlogic.gdx.graphics.Texture.TextureFilter.Linear)
        }
        texCop = com.badlogic.gdx.graphics.Texture(Gdx.files.internal("gfx/cop.png")).apply {
            setFilter(com.badlogic.gdx.graphics.Texture.TextureFilter.Linear, com.badlogic.gdx.graphics.Texture.TextureFilter.Linear)
        }
        particles = Particles(240)
        propTex[0] = loadTex("gfx/prop_cone.png")
        propTex[1] = loadTex("gfx/prop_barrel_red.png")
        propTex[2] = loadTex("gfx/prop_barrel_blue.png")
        propTex[3] = loadTex("gfx/prop_barrier.png")
        propTex[4] = loadTex("gfx/prop_rock1.png")
        propTex[5] = loadTex("gfx/prop_rock2.png")
        propTex[6] = loadTex("gfx/prop_rock3.png")
        // 1x1 white for UI panels/edges
        val pm = com.badlogic.gdx.graphics.Pixmap(1, 1, com.badlogic.gdx.graphics.Pixmap.Format.RGBA8888)
        pm.setColor(1f, 1f, 1f, 1f); pm.fill()
        whitePx = com.badlogic.gdx.graphics.Texture(pm)
        pm.dispose()
        if (Gdx.files.internal("gfx/menu_bg.jpg").exists()) {
            menuBg = com.badlogic.gdx.graphics.Texture(Gdx.files.internal("gfx/menu_bg.jpg")).apply {
                setFilter(com.badlogic.gdx.graphics.Texture.TextureFilter.Linear, com.badlogic.gdx.graphics.Texture.TextureFilter.Linear)
            }
        }
        Gdx.app.log("GH", "create done menuBg=${menuBg != null} player=${texPlayer.width}x${texPlayer.height}")
        resize(Gdx.graphics.width, Gdx.graphics.height)
        Gdx.input.inputProcessor = this
    }

    private fun loadTex(path: String): com.badlogic.gdx.graphics.Texture =
        com.badlogic.gdx.graphics.Texture(Gdx.files.internal(path)).apply {
            setFilter(com.badlogic.gdx.graphics.Texture.TextureFilter.Linear, com.badlogic.gdx.graphics.Texture.TextureFilter.Linear)
        }

    override fun resize(width: Int, height: Int) {
        // portrait: constant world width, tall viewport = more look-ahead
        camera.setToOrtho(false, VIEW_W, VIEW_W * height.toFloat() / width.toFloat())
        camera.position.set(car.x, car.y, 0f)
        camera.update()
    }

    override fun render() {
        val raw = min(Gdx.graphics.deltaTime, 0.25f)
        if (hitStop > 0f) {
            hitStop -= raw
        } else {
            acc += raw
            while (acc >= STEP) {
                step(STEP)
                acc -= STEP
            }
        }
        renderFrame(acc / STEP, raw)
    }

    // ─── simulation ────────────────────────────────────────────────────────
    private fun step(dt: Float) {
        trauma = (trauma - dt * 1.4f).coerceAtLeast(0f)
        flashTimer = (flashTimer - dt).coerceAtLeast(0f)

        when (state) {
            State.PLAYING -> {
                time += dt
                val keyboard = keySteer()
                val steer = if (keyboard != 0f) keyboard else steerInput

                carPrevX = car.x; carPrevY = car.y
                val ramp = min(time / 120f, 1f)
                var speed = BASE_SPEED + ramp * MAX_SPEED_RAMP
                val sliding = Physics.driftAmount(car) > DRIFTING_RAD && abs(steer) > 0.15f
                if (sliding) {
                    speed += DRIFT_BONUS_SPEED
                    dropSkids(dt)
                }
                slowTimer = (slowTimer - dt).coerceAtLeast(0f)
                speed *= 1f - 0.45f * min(slowTimer / 0.35f, 1f)
                Physics.driftStep(car, speed, steer, dt, TURN_RATE, GRIP * theme.gripMul)

                // drift lean: the camera banks into the slide
                // damped lean: no more snap-glitch when steer flips mid-drift
                val leanTarget = if (sliding) -steer * 2.1f else 0f
                cine.extraRollDeg += (leanTarget - cine.extraRollDeg) * min(9f * dt, 1f)

                // missions + particles + prop collisions
                mission?.let { m -> if (m.kind == Mission.SURVIVE) m.add(dt) }
                mission?.let { m -> if (m.kind == Mission.DRIFT && sliding) m.add(dt * 9f) }
                frameNo++
                if (sliding && frameNo % 3 == 0) {
                    particles.driftPuff(car.x, car.y, car.heading, Physics.driftAmount(car))
                    // addictive loop: sliding literally prints money
                    driftBank += dt * 40f * combo
                    if (driftBank >= 100f) {
                        score += driftBank
                        popups.add(Popup(car.x + 40f, car.y + 46f,
                            "+${driftBank.toInt()}", Color(1f, 0.9f, 0.4f, 1f)))
                        driftBank = 0f
                    }
                }
                collideProps()

                // live city: buildings are solid, traffic flows and can be hit.
                // crashCooldown keeps grinding walls from stacking trauma per frame
                crashCooldown = (crashCooldown - dt).coerceAtLeast(0f)
                if (theme.hasRoads && crashCooldown <= 0f) {
                    traffic.update(dt, car.x, car.y, SEED.toLong())
                    playerPos[0] = car.x; playerPos[1] = car.y
                    if (City.collideBuildings(playerPos, 17f, SEED)) {
                        car.x = playerPos[0]; car.y = playerPos[1]
                        slowTimer = maxOf(slowTimer, 0.3f)
                        trauma = (trauma + 0.22f).coerceIn(0f, 1.15f)
                        combo = 1; comboTimer = 0f
                        particles.sparkBurst(car.x, car.y)
                        popups.add(Popup(car.x, car.y + 60f, "CRUNCH!", Color(1f, 0.6f, 0.2f, 1f)))
                        crashCooldown = 0.45f
                    } else {
                        val hi = traffic.hitTest(car.x, car.y, 33f)
                        if (hi >= 0) {
                            traffic.nudgeAway(hi, car.x, car.y)
                            particles.sparkBurst((car.x + traffic.x(hi)) / 2f, (car.y + traffic.y(hi)) / 2f)
                            slowTimer = maxOf(slowTimer, 0.35f)
                            trauma = (trauma + 0.3f).coerceIn(0f, 1.15f)
                            hitStop = maxOf(hitStop, 0.035f)
                            score = maxOf(score - 25f, 0f)
                            combo = 1; comboTimer = 0f
                            popups.add(Popup(car.x, car.y + 64f, "CRASH!", Color(1f, 0.3f, 0.25f, 1f)))
                            crashCooldown = 0.45f
                        }
                    }
                }

                if (!theme.isRacing) {
                    updateCops(dt)
                    updateCombo(dt)
                    score += dt * 10f * combo

                    spawnTimer -= dt
                    if (spawnTimer <= 0f) {
                        spawnCop()
                        spawnTimer = (3.6f - min(time / 100f, 1f) * 1.6f) / theme.copSpawnMul
                    }
                } else {
                    updateRace(dt)
                }
            }
            else -> {}
        }

        if (state != State.PAUSED) {
            for (i in skids.indices.reversed()) {
                val s = skids[i]; s[3] -= dt
                if (s[3] <= 0f) skids.removeAt(i)
            }
        }
        popups.removeAll { p ->
            p.color.a -= dt * 0.9f
            p.color.a <= 0.02f
        }
    }

    private fun keySteer(): Float {
        val left = Gdx.input.isKeyPressed(Input.Keys.A) || Gdx.input.isKeyPressed(Input.Keys.LEFT)
        val right = Gdx.input.isKeyPressed(Input.Keys.D) || Gdx.input.isKeyPressed(Input.Keys.RIGHT)
        return when {
            left && !right -> -1f
            right && !left -> 1f
            else -> 0f
        }
    }

    private fun updateCops(dt: Float) {
        // FAIRNESS MODEL: cops are always slower than the player's drift-boosted
        // top speed; they steer with a limited turn rate (zigzag works!); and
        // they rubber-band (ease off) when nearly touching — no cheap kills.
        val copBaseSpeed = (300f + min(time * 1.1f, 80f)) * theme.copSpeedMul
        val copTurnRate = 2.1f                             // rad/s — arcs, overshoots, dodgeable
        for (c in cops) {
            c.prevX = c.kin.x; c.prevY = c.kin.y
            val dx = car.x - c.kin.x
            val dy = car.y - c.kin.y
            val len = sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)

            // limited-turn-rate pursuit (car-like, dodgeable)
            val targetAngle = kotlin.math.atan2(dy, dx)
            c.kin.heading = Physics.lerpAngle(c.kin.heading, targetAngle, (copTurnRate * dt).coerceIn(0f, 1f))

            // rubber-band: slightly slower when close, full speed when far
            val spd = copBaseSpeed * (0.78f + 0.22f * min(len / 650f, 1f))
            c.kin.x += cos(c.kin.heading) * spd * dt
            c.kin.y += sin(c.kin.heading) * spd * dt
            c.phase += dt * 6f

            val dist = len
            val relSpeed = abs(
                distTo(c.prevX, c.prevY, carPrevX, carPrevY) - dist
            ) / dt
            when (
                Physics.classifyProximity(
                    c.prevDist, dist, BUST_RANGE, NEAR_RANGE, relSpeed, MIN_REL_SPEED
                )
            ) {
                Proximity.HIT -> { bust(); return }
                Proximity.NEAR -> if (c.nearArmed) {
                    c.nearArmed = false
                    statNear++
                    mission?.let { m -> if (m.kind == Mission.NEAR_MISS) m.add(1f) }
                    particles.sparkBurst(car.x, car.y)
                    nearMiss(ux = -dx / len, uy = -dy / len)
                }
                Proximity.NONE -> if (dist > NEAR_RANGE * 1.5f) c.nearArmed = true
            }
            c.prevDist = dist
        }
    }

    private fun updateCombo(dt: Float) {
        if (combo > 1) {
            comboTimer -= dt
            if (comboTimer <= 0f) combo = 1
        }
    }

    private fun spawnCop() {
        val cap = (3 + time / 18f).toInt().coerceAtMost(12)
        if (cops.size >= cap) return
        val ang = MathUtilsRandom.nextFloat() * 6.28318f
        val r = 900f + MathUtilsRandom.nextFloat() * 250f
        val c = Cop()
        c.kin.reset(car.x + cos(ang) * r, car.y + sin(ang) * r, ang + 3.14159f)
        c.prevDist = dist(c.kin.x, c.kin.y, car.x, car.y)
        cops.add(c)
    }

    private fun nearMiss(ux: Float, uy: Float) {
        combo = (combo + 1).coerceAtMost(12)
        statTopCombo = maxOf(statTopCombo, combo)
        comboTimer = COMBO_TIME
        score += 50f * combo
        trauma = (trauma + 0.16f).coerceIn(0f, 1.15f)
        hitStop = maxOf(hitStop, 0.035f)              // micro slow-mo juice
        cine.kick(0.45f + 0.05f * combo)              // zoom punch per near-miss
        val px = car.x + ux * NEAR_RANGE
        val py = car.y + uy * NEAR_RANGE
        popups.add(Popup(px, py, "+${50 * combo}", Color(1f, 0.85f, 0.2f, 1f)))
        when (combo) {
            4 -> popups.add(Popup(car.x, car.y + 80f, "HOT!", Color(1f, 0.5f, 0.15f, 1f)))
            8 -> popups.add(Popup(car.x, car.y + 84f, "BLAZING!", Color(1f, 0.25f, 0.5f, 1f)))
            12 -> popups.add(Popup(car.x, car.y + 88f, "INFERNO!", Color(0.7f, 0.2f, 1f, 1f)))
            else -> if (combo >= 4) popups.add(Popup(car.x, car.y + 70f, "×$combo", Color(1f, 0.4f, 0.95f, 1f)))
        }
    }

    // ─── racing mode: gates vs the clock ─────────────────────────────────────
    private fun seedGates() {
        gates.clear()
        var gx = car.x + cos(car.heading) * 520f
        var gy = car.y + sin(car.heading) * 520f
        var ang = car.heading
        for (i in 0 until 6) {
            gates.add(floatArrayOf(gx, gy, ang, 0f))
            ang += (MathUtilsRandom.nextFloat() - 0.5f) * 1.1f
            gx += cos(ang) * 430f; gy += sin(ang) * 430f
        }
    }

    private fun updateRace(dt: Float) {
        raceTime -= dt
        score += dt * 14f
        // pass detection on nearest gate
        for (g in gates) {
            if (g[3] != 0f) continue
            val dx = car.x - g[0]; val dy = car.y - g[1]
            val d2 = dx * dx + dy * dy
            if (d2 < 85f * 85f) {
                g[3] = 1f
                gatesPassed++
                mission?.add(1f)
                raceTime += 3.5f
                val bonus = 250 * combo
                score += bonus
                cine.kick(0.55f)
                hitStop = maxOf(hitStop, 0.03f)
                particles.sparkBurst(g[0], g[1])
                popups.add(Popup(g[0], g[1] + 50f, "GATE +3.5s", Color(0.3f, 1f, 0.7f, 1f)))
                popups.add(Popup(g[0], g[1] - 10f, "+$bonus", Color(1f, 0.9f, 0.4f, 1f)))
            } else if (d2 > 320f * 320f && dx * cos(g[2]) + dy * sin(g[2]) < -60f) {
                g[3] = 1f // flew past it — missed
                popups.add(Popup(g[0], g[1], "MISSED", Color(1f, 0.35f, 0.3f, 0.9f)))
            }
        }
        // keep a chain of upcoming gates
        var last = gates.lastOrNull { it[3] == 0f } ?: gates.last()
        while (gates.count { it[3] == 0f } < 5) {
            val ang = last[2] + (MathUtilsRandom.nextFloat() - 0.5f) * 1.1f
            val nx = last[0] + cos(ang) * 430f
            val ny = last[1] + sin(ang) * 430f
            gates.add(floatArrayOf(nx, ny, ang, 0f))
            last = gates[gates.size - 1]
        }
        // prune passed/missed gates far behind
        gates.removeAll { g ->
            g[3] != 0f && dist(g[0], g[1], car.x, car.y) > 900f
        }
        if (raceTime <= 0f) bust(timeUp = true)
    }

    private fun dropSkids(dt: Float) {
        val back = 20f
        val side = 11f
        val ca = cos(car.heading); val sa = sin(car.heading)
        for (s in intArrayOf(-1, 1)) {
            val x = car.x - ca * back - sa * side * s
            val y = car.y - sa * back + ca * side * s
            skids.add(floatArrayOf(x, y, car.velAngle, 2.2f))
            if (skids.size > 320) skids.removeAt(0)
        }
    }

    private fun bust(timeUp: Boolean = false) {
        state = State.BUSTED
        hitStop = if (timeUp) 0.25f else 0.09f
        trauma = 1f
        flashTimer = 0.35f
        prevHigh = highscore
        timeUpFlag = timeUp
        if (score > highscore) {
            highscore = score
            prefs.putFloat("highscore", highscore)
            prefs.flush()
        }
    }

    private fun startRun() {
        car.reset(0f, 0f, -1.5707964f)
        carPrevX = 0f; carPrevY = 0f
        cops.clear(); skids.clear(); popups.clear()
        time = 0f; score = 0f; combo = 1; comboTimer = 0f
        trauma = 0f; spawnTimer = 4f; steerInput = 0f; flashTimer = 0f
        slowTimer = 0f; statNear = 0; statTopCombo = 1
        driftBank = 0f; timeUpFlag = false
        MathUtilsRandom.reseed((System.nanoTime() and 0x7FFFFFFFL).toInt())
        PropField.layout(theme, System.nanoTime(), props)
        mission = if (theme.isRacing) Mission.generate(System.nanoTime(), Mission.GATES) else Mission.generate(System.nanoTime())
        particles.update(10f) // expire leftovers
        traffic.reset(System.nanoTime(), car.x, car.y)
        if (theme.isRacing) { raceTime = 20f; gatesPassed = 0; seedGates() } else { gates.clear() }
        state = State.PLAYING
    }

    /** Soft prop hits: sparks + shake + brief slowdown. Nothing is lethal here. */
    private fun collideProps() {
        if (slowTimer > 0f) return
        for (p in props) {
            val r = PropField.radiusOf(p)
            if (r <= 0f) continue
            val dx = car.x - p.x; val dy = car.y - p.y
            if (dx * dx + dy * dy < (CAR_R * 0.72f + r) * (CAR_R * 0.72f + r)) {
                slowTimer = 0.35f
                trauma = (trauma + 0.14f).coerceIn(0f, 1.15f)
                particles.sparkBurst(car.x, car.y)
                break
            }
        }
    }

    // ─── rendering ─────────────────────────────────────────────────────────
    private fun renderFrame(alpha: Float, rawDt: Float) {
        val icx = lerp(carPrevX, car.x, alpha)
        val icy = lerp(carPrevY, car.y, alpha)

        // ── cinematic camera brain ──
        val wantMode = when (state) {
            State.MENU -> CinematicCam.Mode.MENU_ORBIT
            State.PLAYING, State.PAUSED -> CinematicCam.Mode.PLAY
            else -> CinematicCam.Mode.BUST
        }
        if (cine.mode != wantMode) cine.setMode(wantMode)
        if (state == State.MENU) menuTime += rawDt
        val comboHeat = if (combo > 1 && comboTimer > 0f)
            ((combo - 1) / 11f) * (comboTimer / COMBO_TIME).coerceIn(0f, 1f) else 0f
        // paused: freeze the shot dead (dt=0 short-circuits the cam brain)
        cine.update(if (state == State.PAUSED) 0f else rawDt, car.x, car.y, 0f, trauma, comboHeat, menuTime)
        cine.apply(camera, VIEW_W, camera.viewportHeight)
        if (!loggedOnce) {
            loggedOnce = true
            Gdx.app.log("GH", "renderFrame#1 cam=(${camera.position.x},${camera.position.y}) zoom=${camera.zoom} viewport=${camera.viewportWidth}x${camera.viewportHeight}")
        }

        Gdx.gl.glClearColor(
            theme.groundA.r * 0.55f, theme.groundA.g * 0.55f, theme.groundA.b * 0.6f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

        // ambient weather
        if (state == State.PLAYING) {
            when (theme.weather) {
                Weather.SNOW -> particles.snowfall(
                    camera.position.x, camera.position.y, camera.viewportWidth, camera.viewportHeight)
                Weather.DUST -> if (frameNo % 4 == 0) particles.dustHaze(
                    camera.position.x, camera.position.y, camera.viewportWidth, camera.viewportHeight)
                else -> {}
            }
        }
        particles.update(rawDt)

        // ── pass A: ground, skids, headlight cones, directional shadows ──
        shapes.projectionMatrix = camera.combined
        beginShapes()

        drawGround()
        if (theme.hasRoads) {
            val halfW = VIEW_W * camera.zoom / 2f + 120f
            val halfH = camera.viewportHeight * camera.zoom / 2f + 120f
            City.drawWorld(shapes, camera.position.x - halfW, camera.position.y - halfH,
                camera.position.x + halfW, camera.position.y + halfH,
                SEED, theme.sunX, theme.sunY, theme.hasBuildings)
        }
        drawSkids()
        if (state != State.MENU) {
            GlowFx.headlightCones(shapes, Color(1f, 0.93f, 0.65f, 0.8f), icx, icy,
                lerpAngleShort(carHeadingPrev(), car.heading, alpha))
        }
        castShadow(icx, icy, lerpAngleShort(carHeadingPrev(), car.heading, alpha), 52f, 26f, 12f)
        for (c in cops) {
            castShadow(lerp(c.prevX, c.kin.x, alpha), lerp(c.prevY, c.kin.y, alpha),
                c.kin.heading, 52f, 26f, 12f)
        }
        for (p in props) {
            val r = PropField.radiusOf(p)
            if (r > 0f) castShadow(p.x, p.y, p.rot, r * 2.4f, r * 1.5f, 7f * p.scale)
        }
        shapes.end()

        // ── pass B: props + cars as real sprites ──
        batch.projectionMatrix = camera.combined
        batch.begin()
        drawProps(alpha)
        val heroAng = lerpAngleShort(carHeadingPrev(), car.heading, alpha)
        // pseudo-3D: dark extruded base slightly offset against the sun, then body
        drawCarSprite(texPlayer, icx - theme.sunX * 4f, icy - theme.sunY * 4f, heroAng,
            56f, Color(0.10f, 0.08f, 0.10f, 1f))
        drawCarSprite(texPlayer, icx, icy, heroAng,
            56f, Color(1f, 0.74f, 0.42f, 1f)) // QEYTIL orange-red hero tint
        if (theme.hasRoads) traffic.draw(batch, texCop, 52f, 57.2957795f)
        for (c in cops) {
            val cxp = lerp(c.prevX, c.kin.x, alpha); val cyp = lerp(c.prevY, c.kin.y, alpha)
            drawCarSprite(texCop, cxp - theme.sunX * 4f, cyp - theme.sunY * 4f, c.kin.heading,
                56f, Color(0.09f, 0.09f, 0.12f, 1f))
            drawCarSprite(texCop, cxp, cyp, c.kin.heading,
                56f, Color(0.62f, 0.66f, 0.8f, 1f))
        }
        batch.end()

        // ── pass C: lightbar glows, particles, flash ──
        beginShapes()
        for (c in cops) {
            val cx = lerp(c.prevX, c.kin.x, alpha); val cy = lerp(c.prevY, c.kin.y, alpha)
            val blue = sin(c.phase) > 0f
            GlowFx.glowDisc(shapes, if (blue) Color(0.2f, 0.5f, 1f, 0.9f) else Color(1f, 0.15f, 0.1f, 0.9f),
                cx, cy, 30f, 0.85f)
            shapes.color = if (blue) Color(0.25f, 0.55f, 1f, 0.95f) else Color(1f, 0.2f, 0.12f, 0.95f)
            rectRot(cx, cy, 9f, 20f, c.kin.heading)
        }
        if (theme.isRacing) drawGatesWorld()
        if (theme.hasBuildings) drawLampPools()
        val pColor = when (theme.weather) {
            Weather.SNOW -> Color(1f, 1f, 1f, 0.75f)
            Weather.DUST -> Color(0.85f, 0.68f, 0.45f, 0.16f)
            else -> Color(1f, 0.72f, 0.3f, 0.85f)
        }
        particles.draw(shapes, pColor)
        if (flashTimer > 0f) {
            shapes.color = Color(1f, 0.15f, 0.1f, flashTimer * 1.6f)
            rect(camera.position.x - camera.viewportWidth, camera.position.y - camera.viewportHeight,
                camera.viewportWidth * 2, camera.viewportHeight * 2)
        }
        GlowFx.vignette(shapes, camera.viewportWidth * 2.6f, camera.viewportHeight * 2.6f,
            camera.position.x, camera.position.y, theme.vignette)
        GlowFx.grade(shapes, camera.viewportWidth * 2.6f, camera.viewportHeight * 2.6f,
            camera.position.x, camera.position.y, theme.ambient)
        shapes.end()

        batch.projectionMatrix = camera.combined
        batch.begin()
        for (p in popups) drawCentered(p.text, p.x, p.y, 1.4f, p.color)
        batch.end()

        drawHud()
    }

    private fun carHeadingPrev() = car.heading // v1: heading interp skipped (small angles/frame)

    /** ShapeRenderer lost its own blend API in modern gdx - drive GL state directly. */
    private fun beginShapes() {
        Gdx.gl.glEnable(GL20.GL_BLEND)
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
        shapes.begin(ShapeRenderer.ShapeType.Filled)
    }

    private fun drawGround() {
        val cell = 220f
        val x0 = ((camera.position.x - camera.viewportWidth) / cell).toInt() - 1
        val x1 = ((camera.position.x + camera.viewportWidth) / cell).toInt() + 1
        val y0 = ((camera.position.y - camera.viewportHeight) / cell).toInt() - 1
        val y1 = ((camera.position.y + camera.viewportHeight) / cell).toInt() + 1
        for (cy in y0..y1) for (cx in x0..x1) {
            val alt = ((cx + cy) and 1) == 0
            shapes.color = if (alt) theme.groundA else theme.groundB
            rect(cx * cell, cy * cell, cell, cell)
            if (Physics.cellHasMark(cx, cy, SEED)) {
                shapes.color = theme.markColor
                rect(cx * cell + 30f, cy * cell + 30f, 60f, 8f)
                rect(cx * cell + 30f, cy * cell + 30f, 8f, 60f)
            }
        }
    }

    private fun drawSkids() {
        shapes.color = theme.skidColor
        for (s in skids) {
            shapes.color.a = theme.skidColor.a * (s[3] / 2.2f)
            rectRot(s[0], s[1], 26f, 5f, s[2])
        }
    }

    /** Directional soft cast shadow: two offset layers along the sun vector. */
    private fun castShadow(x: Float, y: Float, angle: Float, len: Float, wid: Float, lift: Float) {
        val ox = theme.sunX * lift; val oy = theme.sunY * lift
        shapes.color = Color(0f, 0f, 0f, 0.16f)
        rectRot(x + ox * 1.35f, y + oy * 1.35f, len * 1.12f, wid * 1.25f, angle)
        shapes.color = Color(0f, 0f, 0f, 0.22f)
        rectRot(x + ox * 0.7f, y + oy * 0.7f, len, wid, angle)
    }

    private fun drawProps(alpha: Float) {
        for (p in props) {
            val t = PropField.textureFor(propTex, p.kind) ?: continue
            val baseLen = when (p.kind) {
                Prop.CONE -> 20f
                Prop.BARREL_RED, Prop.BARREL_BLUE -> 26f
                Prop.BARRIER -> 40f
                else -> 44f * p.scale // rocks
            }
            val scaleF = baseLen / t.height
            val w = t.width * scaleF; val h = baseLen
            batch.draw(t, p.x - w / 2f, p.y - h / 2f, w / 2f, h / 2f,
                w, h, 1f, 1f, p.rot * 57.2957795f, 0, 0, t.width, t.height, false, false)
        }
    }

    /** City flavor: warm sodium street-lamp pools on a sparse deterministic grid. */
    /** Racing gates: twin neon pylons + crossbar, drawn in world space. */
    private fun drawGatesWorld() {
        val pink = theme.markColor
        for (g in gates) {
            if (g[3] != 0f) continue
            val dx = g[0] - camera.position.x; val dy = g[1] - camera.position.y
            if (dx * dx + dy * dy > 1500f * 1500f) continue
            val pa = g[2] + 1.5707964f // perpendicular
            val ox = cos(pa) * 58f; val oy = sin(pa) * 58f
            val x1 = g[0] - ox; val y1 = g[1] - oy
            val x2 = g[0] + ox; val y2 = g[1] + oy
            GlowFx.glowDisc(shapes, pink, x1, y1, 34f, 0.85f)
            GlowFx.glowDisc(shapes, pink, x2, y2, 34f, 0.85f)
            shapes.setColor(pink.r, pink.g, pink.b, 0.95f)
            shapes.circle(x1, y1, 9f); shapes.circle(x2, y2, 9f)
            for (k in 0 until 3) {
                val t0 = k / 3f; val t1 = (k + 1) / 3f
                shapes.setColor(pink.r, pink.g, pink.b, 0.5f - k * 0.14f)
                val mx = (x1 + x2) / 2f; val my = (y1 + y2) / 2f
                rectRot(mx, my, dist(x1, y1, x2, y2), 7f - k * 2f, g[2])
            }
        }
    }

    private fun drawLampPools() {
        val step = 340f
        val gx0 = ((camera.position.x - camera.viewportWidth) / step).toInt()
        val gx1 = ((camera.position.x + camera.viewportWidth) / step).toInt() + 1
        val gy0 = ((camera.position.y - camera.viewportHeight) / step).toInt()
        val gy1 = ((camera.position.y + camera.viewportHeight) / step).toInt() + 1
        var n = 0
        for (gy in gy0..gy1) for (gx in gx0..gx1) {
            if (n > 26) return
            if (Physics.cellHasMark(gx, gy, SEED xor 0x5bd1)) continue
            GlowFx.glowDisc(shapes, Color(1f, 0.78f, 0.38f, 0.5f),
                gx * step + 120f, gy * step + 90f, 52f, 0.55f)
            n++
        }
    }

    /** Kenney cars face screen-up; -90° maps art-north to our heading-0 east. */
    private fun drawCarSprite(t: com.badlogic.gdx.graphics.Texture, x: Float, y: Float, angleRad: Float, lengthUnits: Float, tint: Color) {
        val scale = lengthUnits / t.height
        val w = t.width * scale
        val h = lengthUnits
        batch.color = tint
        batch.draw(
            t, x - w / 2f, y - h / 2f,
            w / 2f, h / 2f,
            w, h,
            1f, 1f,
            angleRad * 57.2957795f - 90f,
            0, 0, t.width, t.height,
            false, false
        )
        batch.color = Color.WHITE
    }

    private var prevHigh = 0f
    private var timeUpFlag = false

    private fun drawHud() {
        batch.projectionMatrix = com.badlogic.gdx.math.Matrix4().setToOrtho2D(0f, 0f, Gdx.graphics.width.toFloat(), Gdx.graphics.height.toFloat())
        val W = Gdx.graphics.width.toFloat(); val H = Gdx.graphics.height.toFloat()
        batch.begin()
        batch.color = Color.WHITE
        when (state) {
            State.PLAYING -> {
                val kmh = ((BASE_SPEED + min(time / 120f, 1f) * MAX_SPEED_RAMP
                    + (if (Physics.driftAmount(car) > DRIFTING_RAD) DRIFT_BONUS_SPEED else 0f)) * 0.62f).toInt()
                val m = mission
                val timerTxt = if (theme.isRacing) String.format("%.1fs", raceTime) else null
                ScreensUi.drawHud(batch, font, layout, uiSkin, whitePx, W, H,
                    score = score, combo = combo,
                    missionText = m?.hudText(), missionRatio = m?.ratio() ?: 0f,
                    copsAlive = cops.size, speedKmh = kmh,
                    timerText = timerTxt, pauseZoneOut = pauseZone)
            }
            State.PAUSED -> ScreensUi.drawPause(
                batch, font, layout, uiSkin, whitePx, W, H, menuTime, pauseBtnZones)
            State.MENU -> ScreensUi.drawMenu(
                batch, font, layout, uiSkin, whitePx, W, H, menuBg, highscore,
                mapCards, themeIx, menuTime, tapZones)
            State.BUSTED -> ScreensUi.drawBusted(
                batch, font, layout, uiSkin, whitePx, W, H,
                score = score, best = highscore, isNewBest = score >= prevHigh && score > 0f,
                nearMisses = statNear, topCombo = statTopCombo, survivedSec = time.toInt(),
                missionVerdict = if (timeUpFlag) "TIME UP — ${gatesPassed} GATES CLEARED"
                                 else Mission.verdict(mission), pulseT = menuTime)
        }
        batch.end()
    }

    private fun drawCentered(text: String, x: Float, y: Float, scale: Float, color: Color) {
        font.color = color
        font.data.setScale(scale)
        layout.setText(font, text)
        font.draw(batch, text, x - layout.width / 2f, y + layout.height / 2f)
    }

    private fun drawCenteredScreen(text: String, x: Float, y: Float, scale: Float, color: Color) =
        drawCentered(text, x, y, scale, color)

    // ─── shape helpers (rotation-safe, allocation-free) ────────────────────
    private fun rect(x: Float, y: Float, w: Float, h: Float) {
        shapes.triangle(x, y, x + w, y, x + w, y + h)
        shapes.triangle(x, y, x + w, y + h, x, y + h)
    }

    private fun rectRot(cx: Float, cy: Float, w: Float, h: Float, a: Float) {
        val c = cos(a); val s = sin(a); val hw = w / 2f; val hh = h / 2f
        val x0 = cx + (-hw * c - -hh * s); val y0 = cy + (-hw * s + -hh * c)
        val x1 = cx + (hw * c - -hh * s); val y1 = cy + (hw * s + -hh * c)
        val x2 = cx + (hw * c - hh * s); val y2 = cy + (hw * s + hh * c)
        val x3 = cx + (-hw * c - hh * s); val y3 = cy + (-hw * s + hh * c)
        shapes.triangle(x0, y0, x1, y1, x2, y2)
        shapes.triangle(x0, y0, x2, y2, x3, y3)
    }

    private fun ellipse(x: Float, y: Float, w: Float, h: Float) {
        shapes.ellipse(x, y, w, h)
    }

    // ─── input ─────────────────────────────────────────────────────────────
    override fun touchDown(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
        if (state == State.MENU) {
            // hit-test ScreensUi tap zones (draw-space y is UP; input y is DOWN)
            val H = Gdx.graphics.height.toFloat()
            val fy = H - screenY
            val fx = screenX.toFloat()
            var i = 0
            while (i < tapZones.size) {
                val zx = tapZones[i]; val zy = tapZones[i + 1]
                val zw = tapZones[i + 2]; val zh = tapZones[i + 3]
                if (zw > 0f && fx >= zx && fx <= zx + zw && fy >= zy && fy <= zy + zh) {
                    if (i == 0) { startRun(); return true }
                    // i is a float-array offset (0,4,8,12); zone index = i/4
                    themeIx = (i / 4 - 1).coerceIn(0, Themes.ALL.size - 1)
                    return true
                }
                i += 4
            }
            startRun(); return true
        }
        val Hs = Gdx.graphics.height.toFloat()
        val fy2 = Hs - screenY
        val fx2 = screenX.toFloat()
        if (state == State.PLAYING) {
            if (pauseZone[2] > 0f && fx2 >= pauseZone[0] && fx2 <= pauseZone[0] + pauseZone[2] &&
                fy2 >= pauseZone[1] && fy2 <= pauseZone[1] + pauseZone[3]) {
                state = State.PAUSED
                steerInput = 0f
                return true
            }
            anchorX = screenX.toFloat()
            return true
        }
        if (state == State.PAUSED) {
            var zi = 0
            while (zi < 12) {
                val zx = pauseBtnZones[zi]; val zy = pauseBtnZones[zi + 1]
                val zw = pauseBtnZones[zi + 2]; val zh = pauseBtnZones[zi + 3]
                if (zw > 0f && fx2 >= zx && fx2 <= zx + zw && fy2 >= zy && fy2 <= zy + zh) {
                    when (zi) {
                        0 -> state = State.PLAYING
                        4 -> startRun()
                        else -> { state = State.MENU; cine.setMode(CinematicCam.Mode.MENU_ORBIT) }
                    }
                    return true
                }
                zi += 4
            }
            return true
        }
        startRun(); return true
    }

    override fun touchDragged(screenX: Int, screenY: Int, pointer: Int): Boolean {
        if (state == State.PLAYING) steerInput = ((screenX - anchorX) / 170f).coerceIn(-1f, 1f)
        return true
    }

    override fun touchUp(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
        steerInput = 0f
        return true
    }

    override fun keyDown(keycode: Int): Boolean {
        if (keycode == Input.Keys.SPACE || keycode == Input.Keys.ENTER) {
            if (state != State.PLAYING) startRun()
        }
        return true
    }

    override fun keyUp(keycode: Int): Boolean = true

    override fun keyTyped(character: Char): Boolean = true

    override fun mouseMoved(screenX: Int, screenY: Int): Boolean = true

    override fun scrolled(amountX: Float, amountY: Float): Boolean = true

    override fun touchCancelled(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
        steerInput = 0f
        return true
    }

    override fun dispose() {
        if (score > highscore) { highscore = score; prefs.putFloat("highscore", highscore); prefs.flush() }
        texPlayer.dispose(); texCop.dispose(); menuBg?.dispose(); whitePx.dispose()
        propTex.forEach { it?.dispose() }
        shapes.dispose(); batch.dispose(); font.dispose()
    }

    companion object {
        private const val SEED = 20260825
        private val PLAYER_COLOR = Color(1f, 0.62f, 0.05f, 1f)
        private val COP_COLOR = Color(0.16f, 0.17f, 0.22f, 1f)
        private fun dist(ax: Float, ay: Float, bx: Float, by: Float): Float {
            val dx = ax - bx; val dy = ay - by
            return sqrt(dx * dx + dy * dy)
        }
        private fun distTo(ax: Float, ay: Float, bx: Float, by: Float) = dist(ax, ay, bx, by)
        private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
        private fun lerpAngleShort(a: Float, b: Float, t: Float) = Physics.lerpAngle(a, b, t)
    }
}
