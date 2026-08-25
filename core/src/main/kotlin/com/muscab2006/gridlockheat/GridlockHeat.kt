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

    private enum class State { MENU, PLAYING, BUSTED }

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
        shapes.enableBlending()
        shapes.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
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

                // missions + particles + prop collisions
                mission?.let { m -> if (m.kind == Mission.SURVIVE) m.add(dt) }
                mission?.let { m -> if (m.kind == Mission.DRIFT && sliding) m.add(dt * 9f) }
                frameNo++
                if (sliding && frameNo % 3 == 0) {
                    particles.driftPuff(car.x, car.y, car.heading, Physics.driftAmount(car))
                }
                collideProps()

                updateCops(dt)
                updateCombo(dt)
                score += dt * 10f * combo

                spawnTimer -= dt
                if (spawnTimer <= 0f) {
                    spawnCop()
                    spawnTimer = (3.6f - min(time / 100f, 1f) * 1.6f) / theme.copSpawnMul
                }
            }
            else -> {}
        }

        for (i in skids.indices.reversed()) {
            val s = skids[i]; s[3] -= dt
            if (s[3] <= 0f) skids.removeAt(i)
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
        trauma = (trauma + 0.16f).coerceAtLeast(0f)
        val px = car.x + ux * NEAR_RANGE
        val py = car.y + uy * NEAR_RANGE
        popups.add(Popup(px, py, "+${50 * combo}", Color(1f, 0.85f, 0.2f, 1f)))
        if (combo >= 4) popups.add(Popup(car.x, car.y + 70f, "×$combo", Color(1f, 0.4f, 0.95f, 1f)))
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

    private fun bust() {
        state = State.BUSTED
        hitStop = 0.09f
        trauma = 1f
        flashTimer = 0.35f
        prevHigh = highscore
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
        MathUtilsRandom.reseed((System.nanoTime() and 0x7FFFFFFFL).toInt())
        PropField.layout(theme, System.nanoTime(), props)
        mission = Mission.generate(System.nanoTime())
        particles.update(10f) // expire leftovers
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
                trauma = (trauma + 0.14f).coerceAtLeast(0f)
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
            State.PLAYING -> CinematicCam.Mode.PLAY
            else -> CinematicCam.Mode.BUST
        }
        if (cine.mode != wantMode) cine.setMode(wantMode)
        if (state == State.MENU) menuTime += rawDt
        val comboHeat = if (combo > 1 && comboTimer > 0f)
            ((combo - 1) / 11f) * (comboTimer / COMBO_TIME).coerceIn(0f, 1f) else 0f
        cine.update(rawDt, car.x, car.y, 0f, trauma, comboHeat, menuTime)
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
        shapes.begin(ShapeRenderer.ShapeType.Filled)

        drawGround()
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
        drawCarSprite(texPlayer, icx, icy, lerpAngleShort(carHeadingPrev(), car.heading, alpha),
            48f, Color(1f, 0.74f, 0.42f, 1f)) // QEYTIL orange-red hero tint
        for (c in cops) {
            drawCarSprite(texCop, lerp(c.prevX, c.kin.x, alpha), lerp(c.prevY, c.kin.y, alpha),
                c.kin.heading, 48f, Color(0.62f, 0.66f, 0.8f, 1f))
        }
        batch.end()

        // ── pass C: lightbar glows, particles, flash ──
        shapes.begin(ShapeRenderer.ShapeType.Filled)
        for (c in cops) {
            val cx = lerp(c.prevX, c.kin.x, alpha); val cy = lerp(c.prevY, c.kin.y, alpha)
            val blue = sin(c.phase) > 0f
            GlowFx.glowDisc(shapes, if (blue) Color(0.2f, 0.5f, 1f, 0.9f) else Color(1f, 0.15f, 0.1f, 0.9f),
                cx, cy, 30f, 0.85f)
            shapes.color = if (blue) Color(0.25f, 0.55f, 1f, 0.95f) else Color(1f, 0.2f, 0.12f, 0.95f)
            rectRot(cx, cy, 9f, 20f, c.kin.heading)
        }
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
                ScreensUi.drawHud(batch, font, layout, uiSkin, whitePx, W, H,
                    score = score, combo = combo,
                    missionText = m?.hudText(), missionRatio = m?.ratio() ?: 0f,
                    copsAlive = cops.size, speedKmh = kmh)
            }
            State.MENU -> ScreensUi.drawMenu(
                batch, font, layout, uiSkin, whitePx, W, H, menuBg, highscore,
                mapCards, themeIx, menuTime, tapZones)
            State.BUSTED -> ScreensUi.drawBusted(
                batch, font, layout, uiSkin, whitePx, W, H,
                score = score, best = highscore, isNewBest = score >= prevHigh && score > 0f,
                nearMisses = statNear, topCombo = statTopCombo, survivedSec = time.toInt(),
                missionVerdict = Mission.verdict(mission), pulseT = menuTime)
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
        if (state != State.PLAYING) { startRun(); return true }
        anchorX = screenX.toFloat()
        return true
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
