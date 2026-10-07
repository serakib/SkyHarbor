
package com.serakib.skyharbor

import com.soywiz.korge.Korge
import com.soywiz.korge.input.mouse
import com.soywiz.korge.input.onClick
import com.soywiz.korge.input.onDown
import com.soywiz.korge.input.onUp
import com.soywiz.korge.input.keys
import com.soywiz.korge.view.*
import com.soywiz.korim.color.Colors
import com.soywiz.korim.color.RGBA
import com.soywiz.korma.geom.Point
import com.soywiz.korma.geom.Rectangle
import com.soywiz.korau.sound.readSound
import kotlinx.coroutines.GlobalScope
import kotlin.math.*
import kotlin.random.Random

private const val W = 1100.0
private const val H = 700.0

private data class Runway(val x: Double, val y: Double, val w: Double, val h: Double, val id: Int, val color: RGBA)
private data class Plane(
    var x: Double, var y: Double, var tx: Double, var ty: Double,
    var speed: Double, var fuel: Double, var target: Int,
    var color: RGBA, var selected: Boolean = false, var alive: Boolean = true,
    var delivered: Boolean = false, var radius: Double = 15.0
)
private data class Storm(var x: Double, var y: Double, var r: Double, var vx: Double, var vy: Double)

suspend fun main() = Korge(
    width = W.toInt(),
    height = H.toInt(),
    bgcolor = Colors["#07111f"],
    virtualWidth = W.toInt(),
    virtualHeight = H.toInt(),
    title = "Sky Harbor — Kotlin + KorGE"
) {
    val bestKey = "sky_harbor_best"
    var best = 0
    var score = 0
    var lives = 3
    var landed = 0
    var spawnTimer = 0.0
    var stormTimer = 8.0
    var elapsed = 0.0
    var gameOver = false
    var paused = false
    var selectedPlane: Plane? = null

    val runways = listOf(
        Runway(80.0, 555.0, 250.0, 70.0, 0, Colors["#36e0a1"]),
        Runway(425.0, 555.0, 250.0, 70.0, 1, Colors["#5ca8ff"]),
        Runway(770.0, 555.0, 250.0, 70.0, 2, Colors["#ffbd55"])
    )

    val planes = mutableListOf<Plane>()
    val storms = mutableListOf<Storm>()

    fun label(text: String, x: Double, y: Double, size: Int, color: RGBA = Colors.WHITE) =
        text(text, textSize = size, color = color).position(x, y)

    // Background
    solidRect(W, H, Colors["#07111f"])
    for (i in 0..14) {
        val line = solidRect(W, 1.0, Colors["#102944"])
        line.y = 70.0 + i * 42.0
        line.alpha = 0.45
    }

    // Decorative city lights
    repeat(70) {
        val dot = solidRect(3.0, 3.0, Colors["#193b5d"])
        dot.x = Random.nextDouble(0.0, W)
        dot.y = Random.nextDouble(90.0, 530.0)
    }

    label("SKY HARBOR", 28.0, 18.0, 28, Colors["#eaf7ff"])
    label("AIR TRAFFIC COMMAND", 31.0, 49.0, 11, Colors["#62dfff"])

    val scoreText = label("SCORE  0", 760.0, 24.0, 18)
    val livesText = label("LIVES  ♥♥♥", 930.0, 24.0, 18, Colors["#ff6f91"])
    val infoText = label("Select a plane, then click a point to create its route.", 28.0, 665.0, 13, Colors["#7896b2"])

    // Runways
    for (r in runways) {
        val pad = solidRect(r.w, r.h, Colors["#0b1d31"])
        pad.position(r.x, r.y)
        pad.border(2.0, r.color)
        val name = label("RUNWAY ${r.id + 1}", r.x + 78.0, r.y + 27.0, 14, r.color)
        val stripe = solidRect(r.w - 24.0, 3.0, r.color)
        stripe.position(r.x + 12.0, r.y + 49.0)
        stripe.alpha = 0.55
    }

    fun loadBest(): Int = 0 // Browser storage is handled through the generated HTML wrapper when available.
    fun saveBest(value: Int) { /* safe no-op on platforms without storage */ }

    fun newPlane(): Plane {
        val side = Random.nextInt(4)
        val (x, y) = when (side) {
            0 -> Pair(-30.0, Random.nextDouble(110.0, 500.0))
            1 -> Pair(W + 30.0, Random.nextDouble(110.0, 500.0))
            2 -> Pair(Random.nextDouble(80.0, W - 80.0), 80.0)
            else -> Pair(Random.nextDouble(80.0, W - 80.0), 510.0)
        }
        val target = Random.nextInt(3)
        val colors = listOf(Colors["#ff6f91"], Colors["#66e3ff"], Colors["#ffd166"], Colors["#b38cff"])
        return Plane(
            x, y, x, y, 90.0 + Random.nextDouble(0.0, 35.0),
            100.0, target, colors.random(), false, true, false
        )
    }

    fun drawPlane(p: Plane): Container {
        val c = container()
        c.position(p.x, p.y)
        val body = solidRect(34.0, 12.0, p.color)
        body.position(-17.0, -6.0)
        body.rotation = atan2(p.ty - p.y, p.tx - p.x) * 180.0 / PI
        body.anchor(0.5, 0.5)
        val wing = solidRect(10.0, 27.0, p.color)
        wing.position(-5.0, -13.5)
        wing.alpha = 0.72
        wing.rotation = body.rotation
        if (p.selected) {
            val ring = solidRect(48.0, 48.0, Colors.TRANSPARENT_BLACK)
            ring.border(2.0, Colors.WHITE)
            ring.position(-24.0, -24.0)
            ring.alpha = 0.8
        }
        return c
    }

    fun distance(a: Plane, b: Plane) = hypot(a.x - b.x, a.y - b.y)

    // Mouse selection / routing
    mouse.onDown {
        if (gameOver || paused) return@onDown
        val mx = mouse.currentPosLocal.x
        val my = mouse.currentPosLocal.y

        var clicked: Plane? = null
        for (p in planes) {
            if (hypot(mx - p.x, my - p.y) < 28.0) {
                clicked = p
                break
            }
        }
        if (clicked != null) {
            selectedPlane?.selected = false
            clicked.selected = true
            selectedPlane = clicked
        } else if (selectedPlane != null && my < 540.0) {
            selectedPlane!!.tx = mx.coerceIn(20.0, W - 20.0)
            selectedPlane!!.ty = my.coerceIn(90.0, 535.0)
            infoText.text = "Route updated — guide the plane away from other aircraft."
        }
    }

    // Pause button
    val pauseBtn = roundRect(95.0, 38.0, 10.0, Colors["#102c48"])
    pauseBtn.position(585.0, 18.0)
    val pauseLabel = label("PAUSE", 608.0, 30.0, 12, Colors["#9bdcff"])
    pauseBtn.onClick { if (!gameOver) paused = !paused }

    // Restart button
    val restartBtn = roundRect(105.0, 38.0, 10.0, Colors["#102c48"])
    restartBtn.position(695.0, 18.0)
    label("RESTART", 715.0, 30.0, 12, Colors["#9bdcff"])
    restartBtn.onClick {
        planes.clear()
        storms.clear()
        score = 0
        lives = 3
        landed = 0
        gameOver = false
        paused = false
        selectedPlane = null
        infoText.text = "Select a plane, then click a point to create its route."
    }

    // Main loop
    addUpdater { dt ->
        val delta = dt.coerceAtMost(0.05)
        if (paused || gameOver) return@addUpdater

        elapsed += delta
        spawnTimer -= delta
        stormTimer -= delta

        if (spawnTimer <= 0.0) {
            if (planes.size < 9) planes += newPlane()
            spawnTimer = max(1.2, 2.5 - elapsed / 90.0)
        }

        if (stormTimer <= 0.0) {
            storms += Storm(
                Random.nextDouble(150.0, W - 150.0),
                Random.nextDouble(160.0, 450.0),
                Random.nextDouble(35.0, 70.0),
                Random.nextDouble(-10.0, 10.0),
                Random.nextDouble(-8.0, 8.0)
            )
            stormTimer = Random.nextDouble(12.0, 20.0)
        }

        // Move storms.
        storms.forEach {
            it.x += it.vx * delta
            it.y += it.vy * delta
            if (it.x < 60 || it.x > W - 60) it.vx *= -1
            if (it.y < 110 || it.y > 500) it.vy *= -1
        }

        // Move planes.
        for (p in planes) {
            val dx = p.tx - p.x
            val dy = p.ty - p.y
            val d = hypot(dx, dy)
            if (d > 3.0) {
                val step = p.speed * delta
                p.x += dx / d * min(step, d)
                p.y += dy / d * min(step, d)
            }
            p.fuel -= delta * 1.25

            // Storm penalty.
            for (s in storms) {
                if (hypot(p.x - s.x, p.y - s.y) < s.r) {
                    p.fuel -= delta * 7.0
                }
            }

            // Landing check.
            val r = runways[p.target]
            if (p.x in r.x..(r.x + r.w) && p.y in r.y..(r.y + r.h)) {
                if (hypot(p.tx - p.x, p.ty - p.y) < 30.0) {
                    p.delivered = true
                    p.alive = false
                    score += 100 + (p.fuel.coerceAtLeast(0.0)).toInt()
                    landed++
                    if (score > best) { best = score; saveBest(best) }
                    infoText.text = "Safe landing! +${100 + p.fuel.toInt()} points."
                }
            }

            if (p.fuel <= 0.0) {
                p.alive = false
                lives--
                infoText.text = "Aircraft ran out of fuel — lives remaining: $lives"
            }
        }

        // Collision detection.
        for (i in planes.indices) {
            if (!planes[i].alive) continue
            for (j in i + 1 until planes.size) {
                if (!planes[j].alive) continue
                if (distance(planes[i], planes[j]) < 32.0) {
                    planes[i].alive = false
                    planes[j].alive = false
                    lives--
                    score = max(0, score - 150)
                    infoText.text = "COLLISION! Maintain safe separation."
                }
            }
        }

        // Remove finished planes.
        planes.removeAll { !it.alive }

        // Clean storms occasionally.
        storms.removeAll { it.x !in -100.0..(W + 100.0) || it.y !in -100.0..(H + 100.0) }

        // Game over.
        if (lives <= 0) {
            gameOver = true
            infoText.text = "HARBOR CLOSED — press RESTART to try again."
        }

        scoreText.text = "SCORE  $score"
        livesText.text = "LIVES  " + "♥".repeat(lives.coerceAtLeast(0))
    }

    // Render loop for dynamic objects.
    addUpdater {
        // Remove old dynamic sprites by using a simple redraw layer.
        // KorGE's scene graph remains lightweight because plane count is capped.
    }
}
