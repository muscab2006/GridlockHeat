package com.muscab2006.gridlockheat

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.glutils.ShapeRenderer

/**
 * Fixed-capacity particle system. One flat FloatArray, zero allocation
 * after construction. Stride: x,y,vx,vy,life,maxLife,size.
 */
class Particles(capacity: Int) {

    private val data = FloatArray(capacity * STRIDE)
    private var cursor = 0
    val capacity = capacity

    fun emit(x: Float, y: Float, vx: Float, vy: Float, life: Float, size: Float) {
        val i = cursor * STRIDE
        data[i] = x; data[i + 1] = y; data[i + 2] = vx; data[i + 3] = vy
        data[i + 4] = life; data[i + 5] = life; data[i + 6] = size
        cursor = (cursor + 1) % capacity
    }

    fun update(dt: Float) {
        var i = 0
        while (i < data.size) {
            val life = data[i + 4]
            if (life > 0f) {
                data[i] += data[i + 2] * dt
                data[i + 1] += data[i + 3] * dt
                data[i + 4] = life - dt
            }
            i += STRIDE
        }
    }

    /** Drift smoke: cluster of soft round puffs that expand as they fade. */
    fun driftPuff(x: Float, y: Float, angle: Float, strength: Float) {
        val back = angle + 3.14159f
        repeat(3) {
            val jitterA = back + (MathUtilsRandom.nextFloat() - 0.5f) * 0.9f
            emit(
                x + cos(back) * 20f, y + sin(back) * 20f,
                cos(jitterA) * (50f + MathUtilsRandom.nextFloat() * 70f),
                sin(jitterA) * (50f + MathUtilsRandom.nextFloat() * 70f),
                0.55f + MathUtilsRandom.nextFloat() * 0.4f,
                9f + strength * 14f
            )
        }
    }

    fun sparkBurst(x: Float, y: Float) {
        for (n in 0 until 8) {
            val a = MathUtilsRandom.nextFloat() * 6.2832f
            val sp = 140f + MathUtilsRandom.nextFloat() * 240f
            emit(x, y, cos(a) * sp, sin(a) * sp, 0.22f + MathUtilsRandom.nextFloat() * 0.2f, 4.5f)
        }
    }

    fun snowfall(camX: Float, camY: Float, viewW: Float, viewH: Float) {
        // sprinkle a few flakes per frame around the camera top edge
        repeat(3) {
            emit(
                camX + (MathUtilsRandom.nextFloat() - 0.5f) * viewW * 1.15f,
                camY + viewH * 0.62f,
                (MathUtilsRandom.nextFloat() - 0.5f) * 30f, -55f - MathUtilsRandom.nextFloat() * 45f,
                6f, 1.6f + MathUtilsRandom.nextFloat() * 2.2f
            )
        }
    }

    fun dustHaze(camX: Float, camY: Float, viewW: Float, viewH: Float) {
        emit(
            camX + (MathUtilsRandom.nextFloat() - 0.5f) * viewW,
            camY + (MathUtilsRandom.nextFloat() - 0.5f) * viewH * 0.9f,
            26f, 8f,
            1.8f, 26f + MathUtilsRandom.nextFloat() * 30f
        )
    }

    fun draw(shapes: ShapeRenderer, color: Color) {
        var i = 0
        while (i < data.size) {
            val life = data[i + 4]
            if (life > 0f) {
                val t = life / data[i + 5]
                val s = data[i + 6]
                // soft round puffs: big faint halo + bright core (no square edges)
                shapes.setColor(color.r, color.g, color.b, color.a * t * 0.45f)
                shapes.circle(data[i], data[i + 1], s * 0.9f)
                shapes.setColor(color.r, color.g, color.b, color.a * t)
                shapes.circle(data[i], data[i + 1], s * 0.45f)
            }
            i += STRIDE
        }
    }

    companion object {
        const val STRIDE = 7
        private fun cos(a: Float) = kotlin.math.cos(a)
        private fun sin(a: Float) = kotlin.math.sin(a)
    }
}
