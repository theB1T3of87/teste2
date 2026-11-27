package com.foxgirlsupremacy.companion

import kotlin.random.Random

class CompanionController(
    private val onMove: (x: Int, y: Int) -> Unit,
    private val onState: (CharacterState) -> Unit,
    private val onFacing: (facing: Int) -> Unit,
    private val onReachLure: () -> Unit = {},
) {

    var screenWidth: Int = 0
    var spriteSize: Int = 0
    var wanderEnabled: Boolean = true
        set(value) {
            field = value
            if (!value && state == CharacterState.WALK) goIdle()
        }

    private var x: Float = 0f
    private var y: Float = 0f
    private var state: CharacterState = CharacterState.IDLE
    private var idleRemaining: Long = nextIdleDuration()
    private var targetX: Float = 0f
    private var facing: Int = 1

    private var lureX: Float? = null

    val currentX: Int get() = x.toInt()
    val currentY: Int get() = y.toInt()
    val isWalking: Boolean get() = state == CharacterState.WALK

    fun placeAt(px: Int, py: Int) {
        x = px.toFloat()
        y = py.toFloat()
        lureX = null
        goIdle()
    }

    fun lureTo(px: Int) {
        val maxX = (screenWidth - spriteSize).coerceAtLeast(0)
        lureX = px.toFloat().coerceIn(0f, maxX.toFloat())
        startWalk()
    }

    fun tick(dtMillis: Long) {
        when (state) {
            CharacterState.WALK -> stepWalk(dtMillis)
            else -> stepIdle(dtMillis)
        }
    }

    private fun stepIdle(dtMillis: Long) {
        if (lureX != null) {
            startWalk()
            return
        }
        if (!wanderEnabled) return
        idleRemaining -= dtMillis
        if (idleRemaining <= 0) startWalk()
    }

    private fun startWalk() {
        val maxX = (screenWidth - spriteSize).coerceAtLeast(0)
        targetX = lureX ?: Random.nextInt(0, maxX + 1).toFloat()
        facing = if (targetX >= x) 1 else -1
        onFacing(facing)
        state = CharacterState.WALK
        onState(CharacterState.WALK)
    }

    private fun stepWalk(dtMillis: Long) {
        val maxX = (screenWidth - spriteSize).coerceAtLeast(0).toFloat()
        val dir = if (targetX >= x) 1 else -1
        if (dir != facing) {
            facing = dir
            onFacing(dir)
        }
        x += dir * SPEED_PX_PER_MS * dtMillis

        if (x <= 0f) {
            x = 0f
            reboundTo(1, maxX)
            onMove(x.toInt(), y.toInt())
            return
        }
        if (x >= maxX) {
            x = maxX
            reboundTo(-1, maxX)
            onMove(x.toInt(), y.toInt())
            return
        }

        val reached = (dir == 1 && x >= targetX) || (dir == -1 && x <= targetX)
        onMove(x.toInt(), y.toInt())
        if (reached) {
            if (lureX != null) {

                lureX = null
                onReachLure()
            }
            goIdle()
        }
    }

    private fun reboundTo(dir: Int, maxX: Float) {
        val span = maxX.toInt().coerceAtLeast(1)

        targetX = if (dir == 1) {
            Random.nextInt(span / 2, span + 1).toFloat()
        } else {
            Random.nextInt(0, span / 2 + 1).toFloat()
        }
        facing = dir
        onFacing(dir)
    }

    private fun goIdle() {
        state = CharacterState.IDLE
        idleRemaining = nextIdleDuration()
        onState(CharacterState.IDLE)
    }

    private fun nextIdleDuration(): Long = Random.nextLong(IDLE_MIN_MS, IDLE_MAX_MS)

    companion object {

        private const val SPEED_PX_PER_MS = 0.11f
        private const val IDLE_MIN_MS = 2_500L
        private const val IDLE_MAX_MS = 7_000L
    }
}
