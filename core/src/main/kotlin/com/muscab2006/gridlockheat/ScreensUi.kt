package com.muscab2006.gridlockheat

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * Shared visual identity for all [ScreensUi] screens.
 * Colors are libGDX Color instances owned by the caller and reused every frame —
 * ScreensUi never mutates them (it works on its own scratch colors only).
 *
 * @param accent  primary hot color (orange family, default Color(1f,0.62f,0.15f,1f))
 * @param accent2 secondary police-light cyan (default Color(0.25f,0.75f,1f,1f))
 * @param panel   translucent dark panel fill (default Color(0f,0f,0f,0.55f))
 * @param dim     secondary/dimmed text gray
 */
data class UiSkin(
    val accent: Color,
    val accent2: Color,
    val panel: Color,
    val dim: Color
)

/** One selectable map card on the menu. Game fills 3 (Themes.ALL) + selection index. */
class MapCard(val name: String, val tagline: String, val swatch: Color)

const val QEYTIL_CREDIT = "BUILT BY QEYTIL"

/**
 * Cinematic-premium screen-space UI for GRIDLOCK HEAT (menu / run HUD / BUSTED overlay).
 *
 * CONTRACT
 *  - Caller owns batch/font/layout and sets `batch.projectionMatrix = Matrix4().setToOrtho2D(0,0,W,H)`
 *    before calling; all coordinates here are screen pixels with origin at BOTTOM-LEFT (y-up).
 *  - Caller owns [whitePx]: a 1x1 white Texture. All panels/edges are tinted quads of it,
 *    so this file needs zero textures of its own and stays in one batched pass.
 *  - [BitmapFont.getScale] is changed freely inside but ALWAYS restored to 1f before returning.
 *  - Allocation discipline: zero per-frame allocations except unavoidable result Strings,
 *    built through one reused StringBuilder. Scratch Colors live at object scope.
 */
object ScreensUi {

    // ── look & feel constants ────────────────────────────────────────────────
    private const val HAIRLINE = 1.5f   // standard 1px-class accent edge
    private const val EDGE_SELECTED = 2f// selected map card border (spec: 2px)
    private const val GRAD_BANDS = 20   // fallback background gradient slices

    // scratch (object scope → zero per-frame allocation; GL thread only)
    private val tmp = Color()
    private val tmp2 = Color()
    private val sb = StringBuilder(64)

    // fixed palette pieces
    private val SHADOW = Color(0f, 0f, 0f, 0.85f)
    private val OVERLAY = Color(0f, 0f, 0f, 0.62f)
    private val SCRIM = Color(0f, 0f, 0f, 0.42f)
    private val BTN_TEXT = Color(0.08f, 0.05f, 0.03f, 1f)
    private val COMBO_PINK = Color(1f, 0.5f, 0.95f, 1f)
    private val RETRY_GOLD = Color(1f, 0.85f, 0.2f, 1f)
    private val BAR_TRACK = Color(0f, 0f, 0f, 0.45f)
    private val BG_TOP = Color(0.040f, 0.043f, 0.058f, 1f)
    private val BG_BOT = Color(0.105f, 0.088f, 0.072f, 1f)

    // ─────────────────────────────────────────────────────────────────────
    // MENU
    //
    // Layout map (fractions of W,H; y measured UP from bottom):
    //   best chip        : center (0.50, 0.955)
    //   title            : center (0.50, 0.845)  scale 4.2·(1+0.02·sin(2t)), shadow +4,-6
    //   tagline          : center (0.50, 0.762)
    //   hint line 1      : center (0.50, 0.700)
    //   hint line 2      : center (0.50, 0.662)
    //   start button     : x 0.06..0.94, y 0.315..0.405 (h = 0.09H), pulsing fill
    //   map cards ×3     : y 0.206..0.291 (h = 0.085H), margins/gaps 0.06W / 0.02W,
    //                      selected lifts +6px with 2px accent border
    //   footer credit    : center (0.50, 0.045)
    //
    // tapZonesOut (size ≥ 16, DRAW-SPACE pixels, y-up — caller flips Gdx input y
    // with `yIn = H - screenY` before hit-testing):
    //   [0] start button  {x,y,w,h}
    //   [1..3] map cards  {x,y,w,h}  (unlifted rects — stable hit areas)
    // ─────────────────────────────────────────────────────────────────────
    fun drawMenu(
        batch: SpriteBatch, font: BitmapFont, layout: GlyphLayout, skin: UiSkin,
        whitePx: Texture,
        W: Float, H: Float, bgKeyArt: Texture?, highscore: Float,
        cards: Array<MapCard>, selectedCard: Int,
        titlePulseT: Float, tapZonesOut: FloatArray
    ) {
        try {
            val mX = W * 0.06f

            // ── backdrop ──
            if (bgKeyArt != null) {
                val tw = bgKeyArt.width.toFloat()
                val th = bgKeyArt.height.toFloat()
                val s = max(W / tw, H / th)               // cover-fit
                val dw = tw * s
                val dh = th * s
                batch.color = Color.WHITE
                batch.draw(bgKeyArt, (W - dw) / 2f, (H - dh) / 2f, dw, dh)
                tmp.set(SCRIM)
                fill(batch, whitePx, 0f, 0f, W, H, tmp)   // readability scrim
                tmp.set(0f, 0f, 0f, 0.28f)
                fill(batch, whitePx, 0f, 0f, W, H * 0.22f, tmp)  // bottom vignette
            } else {
                // gradient fallback: cool-dark top → warm-dark bottom
                for (i in 0 until GRAD_BANDS) {
                    val t = i / (GRAD_BANDS - 1).toFloat()
                    tmp.set(BG_BOT).lerp(BG_TOP, t)
                    fill(batch, whitePx, 0f, H * i / GRAD_BANDS, W, H / GRAD_BANDS + 1f, tmp)
                }
                // accent horizon glow behind the title
                tmp.set(skin.accent)
                for (k in 4 downTo 0) {
                    tmp.a = 0.028f + (4 - k) * 0.021f
                    val gh = H * (0.025f + k * 0.036f)
                    fill(batch, whitePx, 0f, H * 0.845f - gh / 2f, W, gh, tmp)
                }
            }

            // ── best chip (top-center) ──
            run {
                val s = bestStr(highscore)
                val bw = measure(font, layout, s, 0.95f)
                val bh = measureH(font, layout, s, 0.95f)
                val pw = bw + 34f
                val ph = bh + 18f
                panel(batch, whitePx, W / 2f - pw / 2f, H * 0.955f - ph / 2f, pw, ph, skin)
                tmp.set(skin.dim)
                centered(batch, font, layout, s, W / 2f, H * 0.955f, 0.95f, tmp)
            }

            // ── title: double-draw (dark shadow +4,-6, accent on top) ──
            val ts = 4.2f * (1f + 0.02f * sin(titlePulseT * 2f))
            tmp.set(SHADOW)
            centered(batch, font, layout, "GRIDLOCK HEAT", W / 2f + 4f, H * 0.845f - 6f, ts, tmp)
            tmp.set(skin.accent)
            centered(batch, font, layout, "GRIDLOCK HEAT", W / 2f, H * 0.845f, ts, tmp)

            tmp.set(skin.dim)
            centered(batch, font, layout, "DRIFT • EVADE • SURVIVE", W / 2f, H * 0.762f, 1.0f, tmp)
            centered(batch, font, layout, "DRAG LEFT / RIGHT TO STEER — YOU NEVER STOP",
                W / 2f, H * 0.700f, 0.92f, tmp)
            centered(batch, font, layout, "GRAZE COPS TO CHAIN COMBO MULTIPLIERS",
                W / 2f, H * 0.662f, 0.92f, tmp)

            // ── start button: full-width bar above the cards, pulsing fill ──
            val btnH = H * 0.09f
            val btnY = H * 0.315f
            tmp.set(skin.accent)
            tmp.a = 0.85f + 0.15f * sin(titlePulseT * 3f)
            fill(batch, whitePx, mX, btnY, W - 2f * mX, btnH, tmp)
            tmp.set(0f, 0f, 0f, 0.55f)
            frame(batch, whitePx, mX, btnY, W - 2f * mX, btnH, HAIRLINE, tmp)
            run {
                val s = "TAP TO DRIVE"
                val lh = measureH(font, layout, s, 1.7f)
                tmp.set(BTN_TEXT)
                centered(batch, font, layout, s, W / 2f, btnY + btnH / 2f - lh * 0.08f, 1.7f, tmp)
            }

            // ── map cards ──
            val gap = W * 0.02f
            val cardW = (W - 2f * mX - 2f * gap) / 3f
            val cardH = H * 0.085f
            val cardY = btnY - H * 0.024f - cardH
            for (i in cards.indices) {
                if (i > 2) break                          // design holds exactly 3
                val sel = i == selectedCard
                val x = mX + i * (cardW + gap)
                val y = cardY + if (sel) 6f else 0f       // slight lift when selected

                // underglow shadow for the lifted card
                if (sel) {
                    tmp.set(skin.accent)
                    tmp.a = 0.18f
                    fill(batch, whitePx, x - 2f, cardY - 5f, cardW + 4f, 10f, tmp)
                }

                tmp.set(skin.panel)
                if (sel) tmp.lerp(Color.WHITE, 0.07f)
                fill(batch, whitePx, x, y, cardW, cardH, tmp)

                // swatch strip
                tmp.set(cards[i].swatch)
                if (!sel) tmp.a = 0.8f
                fill(batch, whitePx, x + cardW * 0.09f, y + cardH * 0.24f,
                    5f, cardH * 0.52f, tmp)

                // border
                if (sel) {
                    tmp.set(skin.accent)
                    frame(batch, whitePx, x, y, cardW, cardH, EDGE_SELECTED, tmp)
                } else {
                    tmp.set(skin.dim)
                    tmp.a = 0.45f
                    frame(batch, whitePx, x, y, cardW, cardH, HAIRLINE, tmp)
                }

                // labels (centered right of the swatch zone)
                val ncx = x + cardW * 0.58f
                tmp.set(if (sel) Color.WHITE else skin.dim)
                centered(batch, font, layout, cards[i].name, ncx, y + cardH * 0.62f,
                    if (sel) 1.0f else 0.92f, tmp)
                tmp.set(skin.dim)
                tmp.a = if (sel) 0.95f else 0.75f
                centered(batch, font, layout, cards[i].tagline, ncx, y + cardH * 0.28f, 0.62f, tmp)

                // hit zone (unlifted rect, stable)
                if (tapZonesOut.size >= 16) {
                    val o = (i + 1) * 4
                    tapZonesOut[o] = x
                    tapZonesOut[o + 1] = cardY
                    tapZonesOut[o + 2] = cardW
                    tapZonesOut[o + 3] = cardH
                }
            }

            // start-button hit zone
            if (tapZonesOut.size >= 16) {
                tapZonesOut[0] = mX
                tapZonesOut[1] = btnY
                tapZonesOut[2] = W - 2f * mX
                tapZonesOut[3] = btnH
            }

            // footer credit
            tmp.set(skin.dim)
            centered(batch, font, layout, QEYTIL_CREDIT, W / 2f, H * 0.045f, 0.95f, tmp)
        } finally {
            font.data.setScale(1f)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // In-run HUD
    //
    // Layout map (fractions of W,H; y up):
    //   score panel      : top-left, margin x 0.035W / y-top 0.016H
    //                      ("SCORE" caption + big value, accent bar on left edge)
    //   combo chip       : directly under score panel (only when combo > 1)
    //   mission chip     : top-center (same y-top margin); label + progress bar
    //   wanted cluster   : top-right (same margin): "COPS n" + "n KM/H"
    //   hint             : center (0.50, 0.034)
    // ─────────────────────────────────────────────────────────────────────
    fun drawHud(
        batch: SpriteBatch, font: BitmapFont, layout: GlyphLayout, skin: UiSkin,
        whitePx: Texture,
        W: Float, H: Float, score: Float, combo: Int, missionText: String?,
        missionRatio: Float, copsAlive: Int, speedKmh: Int
    ) {
        try {
            val pad = W * 0.035f
            val topMargin = H * 0.016f

            // ── score block (top-left) ──
            val scoreS = intStr(score.toInt())
            val numW = measure(font, layout, scoreS, 2.2f)
            val numH = measureH(font, layout, scoreS, 2.2f)
            val capW = measure(font, layout, "SCORE", 0.72f)
            val capH = measureH(font, layout, "SCORE", 0.72f)
            run {
                val pw = max(numW, capW) + 42f
                val ph = numH + capH + 30f
                val px = pad
                val py = H - topMargin - ph
                panel(batch, whitePx, px, py, pw, ph, skin)
                tmp.set(skin.accent)                       // hot edge bar
                fill(batch, whitePx, px, py, 3.5f, ph, tmp)
                tmp.set(skin.dim)
                centered(batch, font, layout, "SCORE", px + pw / 2f, py + ph - capH / 2f - 11f, 0.72f, tmp)
                tmp.set(Color.WHITE)
                centered(batch, font, layout, scoreS, px + pw / 2f, py + 8f + numH / 2f, 2.2f, tmp)

                // ── combo chip (under score, only while chaining) ──
                if (combo > 1) {
                    val cs = "COMBO ×$combo"
                    val cw = measure(font, layout, cs, 1.35f)
                    val ch = measureH(font, layout, cs, 1.35f)
                    val cpw = cw + 28f
                    val cph = ch + 18f
                    val cpy = py - cph - 10f
                    panel(batch, whitePx, px, cpy, cpw, cph, skin)
                    tmp.set(COMBO_PINK)
                    centered(batch, font, layout, cs, px + cpw / 2f, cpy + cph / 2f, 1.35f, tmp)
                }
            }

            // ── mission progress chip (top-center) ──
            if (missionText != null) {
                val mw = measure(font, layout, missionText, 1.0f)
                val mh = measureH(font, layout, missionText, 1.0f)
                val pw = mw + 32f
                val ph = mh + 30f
                val px = W / 2f - pw / 2f
                val py = H - topMargin - ph
                panel(batch, whitePx, px, py, pw, ph, skin)
                tmp.set(skin.dim)
                centered(batch, font, layout, missionText, W / 2f, py + ph - mh / 2f - 12f, 1.0f, tmp)
                // progress bar
                val bx = px + 14f
                val bw = pw - 28f
                tmp.set(BAR_TRACK)
                fill(batch, whitePx, bx, py + 9f, bw, 5f, tmp)
                val r = missionRatio.coerceIn(0f, 1f)
                tmp.set(if (r >= 1f) skin.accent2 else skin.accent)
                fill(batch, whitePx, bx, py + 9f, bw * r, 5f, tmp)
            }

            // ── wanted cluster (top-right) ──
            run {
                val copsS = "COPS $copsAlive"
                val spdS = "$speedKmh KM/H"
                val w1 = measure(font, layout, copsS, 1.3f)
                val h1 = measureH(font, layout, copsS, 1.3f)
                val w2 = measure(font, layout, spdS, 0.85f)
                val h2 = measureH(font, layout, spdS, 0.85f)
                val pw = max(w1, w2) + 36f
                val ph = h1 + h2 + 27f
                val px = W - pad - pw
                val py = H - topMargin - ph
                panel(batch, whitePx, px, py, pw, ph, skin)
                tmp.set(skin.accent2)
                centered(batch, font, layout, copsS, px + pw / 2f, py + ph - h1 / 2f - 12f, 1.3f, tmp)
                tmp.set(skin.dim)
                centered(batch, font, layout, spdS, px + pw / 2f, py + 9f + h2 / 2f, 0.85f, tmp)
            }

            // ── hint (bottom) ──
            tmp.set(skin.dim)
            tmp.a = 0.85f
            centered(batch, font, layout, "DRAG TO STEER • GRAZE COPS FOR COMBOS",
                W / 2f, H * 0.034f, 0.9f, tmp)
        } finally {
            font.data.setScale(1f)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // BUSTED overlay
    //
    // Layout map (fractions of W,H; y up):
    //   full-screen dim  : 0..1 × 0..1, black a 0.62
    //   red glow         : stacked quads behind title around y 0.79
    //   BUSTED           : center (0.50, 0.790) scale 3.9, red-shifted accent, shadow +4,-6
    //   FINAL SCORE      : center (0.50, 0.700)
    //   score value      : center (0.50, 0.656) scale 2.3
    //   NEW RECORD badge : center (0.50, 0.615) pulsing accent (isNewBest only)
    //   stats panel      : center (0.50, 0.500): stats line / divider / BEST line
    //   mission verdict  : center (0.50, 0.412) accent2
    //   TAP TO RETRY     : center (0.50, 0.320) pulsing alpha
    //   footer credit    : center (0.50, 0.045) dim
    // ─────────────────────────────────────────────────────────────────────
    fun drawBusted(
        batch: SpriteBatch, font: BitmapFont, layout: GlyphLayout, skin: UiSkin,
        whitePx: Texture,
        W: Float, H: Float, score: Float, best: Float, isNewBest: Boolean,
        nearMisses: Int, topCombo: Int, survivedSec: Int,
        missionVerdict: String, pulseT: Float
    ) {
        try {
            // dim the world behind
            tmp.set(OVERLAY)
            fill(batch, whitePx, 0f, 0f, W, H, tmp)

            // red-shifted accent (blend with red 0.5)
            tmp2.set(skin.accent).lerp(Color.RED, 0.5f)

            // stacked glow behind the title
            for (k in 4 downTo 0) {
                tmp.set(tmp2)
                tmp.a = 0.030f + (4 - k) * 0.022f
                val gh = H * (0.025f + k * 0.038f)
                fill(batch, whitePx, W * 0.04f, H * 0.79f - gh / 2f, W * 0.92f, gh, tmp)
            }

            // BUSTED — double-draw shadow then red-shifted accent
            tmp.set(SHADOW)
            centered(batch, font, layout, "BUSTED", W / 2f + 4f, H * 0.79f - 6f, 3.9f, tmp)
            tmp.set(tmp2)
            centered(batch, font, layout, "BUSTED", W / 2f, H * 0.79f, 3.9f, tmp)

            // score block
            tmp.set(skin.dim)
            centered(batch, font, layout, "FINAL SCORE", W / 2f, H * 0.700f, 0.85f, tmp)
            val scoreS = intStr(score.toInt())
            tmp.set(Color.WHITE)
            centered(batch, font, layout, scoreS, W / 2f, H * 0.656f, 2.3f, tmp)

            if (isNewBest) {
                tmp.set(skin.accent)
                tmp.a = 0.6f + 0.4f * abs(sin(pulseT * 5f))
                centered(batch, font, layout, "NEW RECORD!", W / 2f, H * 0.615f, 1.0f, tmp)
            }

            // stats panel: run stats / divider / best
            run {
                val statsS = statsLine(nearMisses, topCombo, survivedSec)
                val bestS = bestStr(best)
                val wStats = measure(font, layout, statsS, 0.95f)
                val hStats = measureH(font, layout, statsS, 0.95f)
                val wBest = measure(font, layout, bestS, 1.25f)
                val hBest = measureH(font, layout, bestS, 1.25f)
                val pw = max(wStats, wBest) + 48f
                val ph = hStats + hBest + 46f
                val pcx = W / 2f
                val pcy = H * 0.500f
                val px = pcx - pw / 2f
                val py = pcy - ph / 2f
                panel(batch, whitePx, px, py, pw, ph, skin)
                tmp.set(skin.dim)
                centered(batch, font, layout, statsS, pcx, pcy + ph / 2f - hStats / 2f - 14f, 0.95f, tmp)
                tmp.set(skin.accent)
                tmp.a = 0.35f
                fill(batch, whitePx, px + 18f, pcy - HAIRLINE / 2f, pw - 36f, HAIRLINE, tmp)
                tmp.set(if (isNewBest) skin.accent else Color.WHITE)
                centered(batch, font, layout, bestS, pcx, pcy - ph / 2f + hBest / 2f + 13f, 1.25f, tmp)
            }

            // mission verdict
            if (missionVerdict.isNotEmpty()) {
                tmp.set(skin.accent2)
                centered(batch, font, layout, missionVerdict, W / 2f, H * 0.412f, 1.05f, tmp)
            }

            // tap-to-retry pulse
            tmp.set(RETRY_GOLD)
            tmp.a = 0.5f + 0.5f * abs(sin(pulseT * 3f))
            centered(batch, font, layout, "TAP TO RETRY", W / 2f, H * 0.320f, 1.75f, tmp)

            // footer credit
            tmp.set(skin.dim)
            centered(batch, font, layout, QEYTIL_CREDIT, W / 2f, H * 0.045f, 0.95f, tmp)
        } finally {
            font.data.setScale(1f)
        }
    }

    // ── primitives (all zero-allocation) ───────────────────────────────────

    /** Tinted quad via the caller-owned 1x1 white texture. */
    private fun fill(batch: SpriteBatch, whitePx: Texture, x: Float, y: Float, w: Float, h: Float, c: Color) {
        batch.color = c
        batch.draw(whitePx, x, y, w, h)
    }

    /** Thin rectangular outline made of 4 quads (square corners by design). */
    private fun frame(batch: SpriteBatch, whitePx: Texture, x: Float, y: Float, w: Float, h: Float, t: Float, c: Color) {
        fill(batch, whitePx, x, y, w, t, c)               // bottom
        fill(batch, whitePx, x, y + h - t, w, t, c)       // top
        fill(batch, whitePx, x, y, t, h, c)               // left
        fill(batch, whitePx, x + w - t, y, t, h, c)       // right
    }

    /** Translucent dark panel + hairline accent edge (alpha .8). */
    private fun panel(batch: SpriteBatch, whitePx: Texture, x: Float, y: Float, w: Float, h: Float, skin: UiSkin) {
        fill(batch, whitePx, x, y, w, h, skin.panel)
        tmp.set(skin.accent)
        tmp.a = 0.8f
        frame(batch, whitePx, x, y, w, h, HAIRLINE, tmp)
    }

    /** Horizontally centered text at [cx]; [cy] is the optical center (game's own convention). Returns width. */
    private fun centered(
        batch: SpriteBatch, font: BitmapFont, layout: GlyphLayout,
        s: String, cx: Float, cy: Float, scale: Float, c: Color
    ): Float {
        font.color = c
        font.data.setScale(scale)
        layout.setText(font, s)
        font.draw(batch, s, cx - layout.width / 2f, cy + layout.height / 2f)
        return layout.width
    }

    /** Measure-only (sets scale; caller restores via finally). */
    private fun measure(font: BitmapFont, layout: GlyphLayout, s: String, scale: Float): Float {
        font.data.setScale(scale)
        layout.setText(font, s)
        return layout.width
    }

    private fun measureH(font: BitmapFont, layout: GlyphLayout, s: String, scale: Float): Float {
        font.data.setScale(scale)
        layout.setText(font, s)
        return layout.height
    }

    // ── reused-buffer string builders (one String alloc per line, per spec) ─
    private fun intStr(v: Int): String {
        sb.setLength(0)
        sb.append(v)
        return sb.toString()
    }

    private fun bestStr(best: Float): String {
        sb.setLength(0)
        sb.append("BEST ").append(best.toInt())
        return sb.toString()
    }

    private fun statsLine(nearMisses: Int, topCombo: Int, survivedSec: Int): String {
        sb.setLength(0)
        sb.append("NEAR MISSES ").append(nearMisses)
        sb.append("      TOP COMBO ×").append(topCombo)
        sb.append("      SURVIVED ").append(survivedSec).append('s')
        return sb.toString()
    }
}
