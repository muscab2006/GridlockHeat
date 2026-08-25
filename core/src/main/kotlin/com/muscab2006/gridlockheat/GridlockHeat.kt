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
private const val VIEW_H = 760f

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

    override fun create() {
        shapes = ShapeRenderer()
        batch = SpriteBatch()
        font = BitmapFont().apply { data.setScale(1.5f) }
        layout = GlyphLayout()
        prefs = Gdx.app.getPreferences("gridlockheat")
        highscore = prefs.getFloat("highscore", 0f)
        resize(Gdx.graphics.width, Gdx.graphics.height)
        Gdx.input.inputProcessor = this
    }

    override fun resize(width: Int, height: Int) {
        val h = if (width >= height) VIEW_H * height.toFloat() / width else VIEW_H
        val w = if (width >= height) VIEW_H else VIEW_H * width.toFloat() / height
        camera.setToOrtho(false, w, h)
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
        renderFrame(acc / STEP)
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
                Physics.driftStep(car, speed, steer, dt, TURN_RATE, GRIP)

                updateCops(dt)
                updateCombo(dt)
                score += dt * 10f * combo

                spawnTimer -= dt
                if (spawnTimer <= 0f) {
                    spawnCop()
                    spawnTimer = 3.6f - min(time / 100f, 1f) * 1.6f
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
        val copBaseSpeed = 300f + min(time * 1.1f, 80f)   // 300 -> 380 cap, never > drift speed
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
        state = State.PLAYING
    }

    // ─── rendering ─────────────────────────────────────────────────────────
    private fun renderFrame(alpha: Float) {
        val icx = lerp(carPrevX, car.x, alpha)
        val icy = lerp(carPrevY, car.y, alpha)

        val sh = trauma * trauma
        val sx = (MathUtilsRandom.nextFloat() * 2 - 1) * 26f * sh
        val sy = (MathUtilsRandom.nextFloat() * 2 - 1) * 26f * sh
        camera.position.set(icx + sx, icy + sy, 0f)
        camera.update()

        Gdx.gl.glClearColor(0.06f, 0.06f, 0.08f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

        shapes.projectionMatrix = camera.combined
        shapes.begin(ShapeRenderer.ShapeType.Filled)

        drawGround()
        drawSkids()
        // blob shadows
        shadow(icx, icy)
        for (c in cops) shadow(lerp(c.prevX, c.kin.x, alpha), lerp(c.prevY, c.kin.y, alpha))
        // cars
        drawCarBody(icx, icy, lerpAngleShort(carHeadingPrev(), car.heading, alpha), PLAYER_COLOR, isPlayer = true)
        for (c in cops) {
            drawCarBody(
                lerp(c.prevX, c.kin.x, alpha), lerp(c.prevY, c.kin.y, alpha),
                c.kin.heading, COP_COLOR, isPlayer = false, phase = c.phase
            )
        }
        if (flashTimer > 0f) {
            shapes.color = Color(1f, 0.15f, 0.1f, flashTimer * 1.6f)
            rect(camera.position.x - camera.viewportWidth, camera.position.y - camera.viewportHeight,
                camera.viewportWidth * 2, camera.viewportHeight * 2)
        }
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
            shapes.color = if (alt) Color(0.13f, 0.135f, 0.15f, 1f) else Color(0.115f, 0.12f, 0.135f, 1f)
            rect(cx * cell, cy * cell, cell, cell)
            if (Physics.cellHasMark(cx, cy, SEED)) {
                shapes.color = Color(0.85f, 0.85f, 0.88f, 0.5f)
                rect(cx * cell + 30f, cy * cell + 30f, 60f, 8f)
                rect(cx * cell + 30f, cy * cell + 30f, 8f, 60f)
            }
        }
    }

    private fun drawSkids() {
        shapes.color = Color(0f, 0f, 0f, 0.35f)
        for (s in skids) {
            shapes.color.a = 0.35f * (s[3] / 2.2f)
            rectRot(s[0], s[1], 26f, 5f, s[2])
        }
    }

    private fun shadow(x: Float, y: Float) {
        shapes.color = Color(0f, 0f, 0f, 0.30f)
        ellipse(x - 6f, y - CAR_R * 0.55f, CAR_R * 2.3f, CAR_R * 1.1f)
    }

    private fun drawCarBody(x: Float, y: Float, angle: Float, color: Color, isPlayer: Boolean, phase: Float = 0f) {
        shapes.color = color
        rectRot(x, y, 46f, 24f, angle)
        shapes.color = Color(0.08f, 0.10f, 0.14f, 1f)
        rectRot(x + cos(angle) * 4f, y + sin(angle) * 4f, 16f, 18f, angle) // cabin
        if (!isPlayer) {
            // flashing lightbar
            val blue = sin(phase) > 0f
            shapes.color = if (blue) Color(0.2f, 0.5f, 1f, 1f) else Color(1f, 0.15f, 0.1f, 1f)
            rectRot(x, y, 10f, 22f, angle)
        } else {
            shapes.color = Color(1f, 0.9f, 0.3f, 0.9f)
            rectRot(x + cos(angle) * 21f, y + sin(angle) * 21f, 6f, 18f, angle) // headlights
        }
    }

    private fun drawHud() {
        batch.projectionMatrix = com.badlogic.gdx.math.Matrix4().setToOrtho2D(0f, 0f, Gdx.graphics.width.toFloat(), Gdx.graphics.height.toFloat())
        val W = Gdx.graphics.width.toFloat(); val H = Gdx.graphics.height.toFloat()
        batch.begin()
        when (state) {
            State.PLAYING -> {
                drawCenteredScreen("SCORE ${score.toInt()}", W / 2, H - 46f, 1.6f, Color.WHITE)
                if (combo > 1) drawCenteredScreen("COMBO ×$combo", W / 2, H - 110f, 2.4f, Color(1f, 0.5f, 0.95f, min(comboTimer / COMBO_TIME + 0.35f, 1f)))
                drawCenteredScreen("BEST ${highscore.toInt()}   COPS ${cops.size}", W / 2, H - 12f, 0.9f, Color(0.75f, 0.78f, 0.85f, 1f))
            }
            State.MENU -> {
                drawCenteredScreen("GRIDLOCK HEAT", W / 2, H * 0.68f, 3.2f, Color(1f, 0.25f, 0.2f, 1f))
                drawCenteredScreen("Drag left/right to steer — you never stop.", W / 2, H * 0.52f, 1.1f, Color.WHITE)
                drawCenteredScreen("Near-miss cops for combos. Survive!", W / 2, H * 0.47f, 1.1f, Color.WHITE)
                drawCenteredScreen("TAP TO DRIVE", W / 2, H * 0.34f, 1.8f, Color(1f, 0.85f, 0.2f, 1f))
                drawCenteredScreen("BUILT BY QEYTIL", W / 2, H * 0.93f, 1.0f, Color(0.55f, 0.58f, 0.66f, 1f))
            }
            State.BUSTED -> {
                drawCenteredScreen("BUSTED!", W / 2, H * 0.62f, 3.4f, Color(1f, 0.25f, 0.2f, 1f))
                drawCenteredScreen("SCORE ${score.toInt()}", W / 2, H * 0.5f, 1.8f, Color.WHITE)
                drawCenteredScreen("BEST ${highscore.toInt()}", W / 2, H * 0.44f, 1.2f, Color(0.8f, 0.83f, 0.9f, 1f))
                drawCenteredScreen("TAP TO RETRY", W / 2, H * 0.33f, 1.6f, Color(1f, 0.85f, 0.2f, 1f))
                drawCenteredScreen("BUILT BY QEYTIL", W / 2, H * 0.93f, 1.0f, Color(0.55f, 0.58f, 0.66f, 1f))
            }
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
        shapes.dispose(); batch.dispose(); font.dispose()
    }

    // tiny deterministic RNG for spawns/decoration jitter (seeded per run)
    private object MathUtilsRandom {
        private var s = System.nanoTime().toInt() xor 0x5bd1e995.toInt()
        fun reseed(v: Int) { s = v }
        fun nextFloat(): Float {
            var x = s
            x = x xor (x shl 13); x = x xor (x ushr 17); x = x xor (x shl 5)
            s = x
            return (x.toLong() and 0xFFFFFFFFL).toFloat() / 4294967296f
        }
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
