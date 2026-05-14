package com.example.livewallpaperdinogame

import android.content.Context
import android.graphics.*
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import kotlin.math.sin

class DinoWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine {
        return DinoEngine(this)
    }

    inner class DinoEngine(private val context: Context) : Engine() {
        private var drawThread: DrawThread? = null

        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(true)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            if (visible) {
                startDrawingThread()
            } else {
                stopDrawingThread()
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            startDrawingThread()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            stopDrawingThread()
        }

        override fun onTouchEvent(event: MotionEvent?) {
            if (event?.action == MotionEvent.ACTION_DOWN) {
                drawThread?.handleTouch(event.x, event.y)
            }
        }

        private fun startDrawingThread() {
            if (drawThread == null || !drawThread!!.isAlive) {
                drawThread = DrawThread(surfaceHolder, context).apply {
                    setRunning(true)
                    start()
                }
            }
        }

        private fun stopDrawingThread() {
            drawThread?.let {
                it.setRunning(false)
                try {
                    it.join(500)
                } catch (e: InterruptedException) {
                    e.printStackTrace()
                }
            }
            drawThread = null
        }
    }
}

class DrawThread(private val surfaceHolder: SurfaceHolder, private val context: Context) : Thread() {
    private var isRunning = false
    private val targetFPS = 60
    private val targetTime = 1000L / targetFPS

    private var backgroundBitmap: Bitmap? = null
    private var spriteBitmap: Bitmap? = null

    private var screenWidth = 0
    private var screenHeight = 0
    private var groundY = 0f

    private var dinoY = 0f
    private var dinoVelocity = 0f
    private val gravity = 2.8f
    private val jumpStrength = -48f
    private var isJumping = false
    private var walkFrame = 0
    private var frameCounter = 0

    private var gameSpeed = 12f
    private var score = 0
    private var highScore = 0
    private var scoreInterval = 0
    private var groundOffsetX = 0f
    private var isPlaying = false
    private var isGameOver = false
    private var gameOverTime = 0L

    private val spritePaint = Paint().apply {
        isAntiAlias = false
        isFilterBitmap = false
    }
    private val maskPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }

    private data class Cloud(var x: Float, var y: Float, val speed: Float, val width: Float, val height: Float)
    private val clouds = mutableListOf<Cloud>()

    private data class Obstacle(var x: Float, val width: Float, val height: Float, val srcRect: Rect)
    private val obstacles = mutableListOf<Obstacle>()

    // Refined Sprite Sheet Coordinates
    private val dinoRun1 = Rect(1514, 0, 1602, 94)
    private val dinoRun2 = Rect(1602, 0, 1690, 94)
    private val dinoJump = Rect(1338, 0, 1426, 94)
    private val cactusSmall = Rect(446, 2, 480, 72)
    private val cactusLarge = Rect(652, 2, 701, 102)
    private val cloudSrc = Rect(174, 2, 258, 29)
    private val gameOverSrc = Rect(954, 29, 1335, 50)
    private val groundSrc = Rect(0, 104, 2404, 122)

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 35f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val gameOverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        textSize = 50f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val cursorPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private var startTime = System.currentTimeMillis()

    init {
        loadResources()
    }

    private fun loadResources() {
        try {
            val options = BitmapFactory.Options().apply { inScaled = false }
            backgroundBitmap = BitmapFactory.decodeResource(context.resources, R.drawable.no_internet, options)
            spriteBitmap = BitmapFactory.decodeResource(context.resources, R.drawable.sprite, options)
            Log.d("DinoWallpaper", "Resources loaded: background=${backgroundBitmap != null}, sprite=${spriteBitmap != null}")
        } catch (e: Exception) {
            Log.e("DinoWallpaper", "Resource loading failed", e)
        }
    }

    fun setRunning(run: Boolean) {
        isRunning = run
    }

    fun handleTouch(x: Float, y: Float) {
        if (!isPlaying) {
            val dinoWidth = screenWidth * 0.15f
            val dinoHeight = dinoWidth * (dinoRun1.height().toFloat() / dinoRun1.width())
            val dinoX = screenWidth * 0.1f
            // Hitbox with 50px padding for easier tapping
            val hitbox = RectF(
                dinoX - 50f,
                dinoY - dinoHeight - 50f,
                dinoX + dinoWidth + 50f,
                dinoY + 50f
            )

            if (hitbox.contains(x, y)) {
                if (isGameOver) {
                    // Reset game state
                    isGameOver = false
                    score = 0
                    scoreInterval = 0
                    gameSpeed = screenWidth * 0.010f
                    obstacles.clear()
                    groundOffsetX = 0f
                }
                isPlaying = true
                jump()
            }
        } else {
            jump()
        }
    }

    fun jump() {
        if (!isJumping) {
            dinoVelocity = jumpStrength
            isJumping = true
        }
    }

    override fun run() {
        while (isRunning) {
            val startTimeLoop = System.nanoTime()
            Log.d("DinoWallpaper", "Drawing frame...")

            var canvas: Canvas? = null
            try {
                canvas = surfaceHolder.lockCanvas()
                if (canvas != null) {
                    synchronized(surfaceHolder) {
                        update()
                        draw(canvas)
                    }
                }
            } catch (e: Exception) {
                Log.e("DinoWallpaper", "Error in draw loop", e)
            } finally {
                canvas?.let {
                    try {
                        surfaceHolder.unlockCanvasAndPost(it)
                    } catch (e: Exception) {
                        Log.e("DinoWallpaper", "Error unlocking canvas", e)
                    }
                }
            }

            val timeMillis = (System.nanoTime() - startTimeLoop) / 1000000
            val waitTime = targetTime - timeMillis
            if (waitTime > 0) {
                try { sleep(waitTime) } catch (e: Exception) {}
            }
        }
    }

    private fun update() {
        val rect = surfaceHolder.surfaceFrame
        if (screenWidth != rect.width() || screenHeight != rect.height()) {
            screenWidth = rect.width()
            screenHeight = rect.height()
            if (screenWidth <= 0 || screenHeight <= 0) return

            groundY = screenHeight * 0.38f
            if (!isJumping) dinoY = groundY

            if (clouds.isEmpty()) {
                for (i in 0..1) { // 2 clouds
                    clouds.add(Cloud(
                        (Math.random() * screenWidth).toFloat(),
                        screenHeight * 0.15f + (Math.random() * screenHeight * 0.15f).toFloat(),
                        0.2f + (Math.random() * 0.5f).toFloat(),
                        screenWidth * 0.08f + (Math.random() * screenWidth * 0.04f).toFloat(),
                        screenHeight * 0.025f + (Math.random() * screenHeight * 0.015f).toFloat()
                    ))
                }
            }

            val glowRadius = maxOf(screenWidth * 0.35f, screenHeight * 0.15f)
            glowPaint.shader = RadialGradient(
                screenWidth / 2f, screenHeight * 0.35f, glowRadius,
                intArrayOf(Color.argb(150, 255, 255, 255), Color.argb(0, 255, 255, 255)),
                null, Shader.TileMode.CLAMP
            )
        }

        if (isGameOver) {
            return
        }

        if (!isPlaying) {
            return
        }

        scoreInterval++
        if (scoreInterval > 6) {
            score++
            scoreInterval = 0
        }
        
        val baseSpeed = screenWidth * 0.010f
        val maxSpeed = screenWidth * 0.022f
        gameSpeed = baseSpeed + (score / 100f) * (screenWidth * 0.002f)
        if (gameSpeed > maxSpeed) gameSpeed = maxSpeed

        groundOffsetX -= gameSpeed
        if (groundOffsetX <= -screenWidth) groundOffsetX = 0f

        if (isJumping) {
            dinoY += dinoVelocity
            dinoVelocity += gravity
            if (dinoY >= groundY) {
                dinoY = groundY
                dinoVelocity = 0f
                isJumping = false
            }
        } else {
            frameCounter++
            if (frameCounter >= 6) {
                walkFrame = (walkFrame + 1) % 2
                frameCounter = 0
            }
        }

        clouds.forEach { cloud ->
            cloud.x -= cloud.speed
            if (cloud.x + cloud.width < 0) {
                cloud.x = screenWidth.toFloat()
                cloud.y = screenHeight * 0.15f + (Math.random() * screenHeight * 0.15f).toFloat()
            }
        }

        // Obstacles logic
        if (obstacles.isEmpty() || (screenWidth - (obstacles.lastOrNull()?.x ?: 0f)) > screenWidth * 0.7f) {
            if (Math.random() < 0.03) {
                val isLarge = if (score < 150) false else Math.random() < 0.4
                val maxMulti = when {
                    score < 50 -> 1
                    score < 200 -> 2
                    else -> 3
                }
                val multi = (Math.random() * maxMulti).toInt() + 1 // 1, 2, or 3
                val baseSrc = if (isLarge) cactusLarge else cactusSmall
                val src = Rect(baseSrc.left, baseSrc.top, baseSrc.left + baseSrc.width() * multi, baseSrc.bottom)
                val h = if (isLarge) screenHeight * 0.08f else screenHeight * 0.05f
                val w = h * (src.width().toFloat() / src.height())
                obstacles.add(Obstacle(screenWidth.toFloat(), w, h, src))
            }
        }

        val dinoWidth = screenWidth * 0.15f
        val dinoHeight = dinoWidth * (dinoRun1.height().toFloat() / dinoRun1.width())
        val dinoX = screenWidth * 0.1f
        val dinoHitbox = RectF(dinoX + 30, dinoY - dinoHeight + 30, dinoX + dinoWidth - 30, dinoY - 10)

        val iterator = obstacles.iterator()
        while (iterator.hasNext()) {
            val obs = iterator.next()
            obs.x -= gameSpeed

            // Collision detection
            val obsHitbox = RectF(obs.x + 10, groundY - obs.height + 10, obs.x + obs.width - 10, groundY)
            if (RectF.intersects(dinoHitbox, obsHitbox)) {
                isGameOver = true
                isPlaying = false
                gameOverTime = System.currentTimeMillis()
                if (score > highScore) highScore = score
            }

            if (obs.x + obs.width < 0) {
                iterator.remove()
            }
        }
    }

    private fun draw(canvas: Canvas) {
        val currentTime = System.currentTimeMillis()
        val elapsedSinceGameOver = if (isGameOver) currentTime - gameOverTime else 0L
        val showGameElements = isPlaying || (isGameOver && elapsedSinceGameOver < 5000)

        // Background
        canvas.drawColor(Color.parseColor("#1a1a1a"))

        backgroundBitmap?.let { bg ->
            val scaleX = screenWidth.toFloat() / bg.width
            val scaleY = screenHeight.toFloat() / bg.height
            val scale = maxOf(scaleX, scaleY)
            val sw = bg.width * scale
            val sh = bg.height * scale
            val left = (screenWidth - sw) / 2f
            val top = (screenHeight - sh) / 2f

            val destRect = RectF(left, top, left + sw, top + sh)
            val paint = Paint().apply { alpha = 160 } // Dim the background
            canvas.drawBitmap(bg, null, destRect, paint)

            // Draw mask over the static Dino and game area only when active
            if (showGameElements) {
                val maskLeft = 0f
                val maskTop = groundY - 160f
                val maskRight = screenWidth - 0f
                val maskBottom = groundY - 20f
                canvas.drawRect(maskLeft, maskTop, maskRight, maskBottom, maskPaint)
            }
        }

        if (showGameElements) {
            // Draw ground sprite
            spriteBitmap?.let {
                val groundHeight = (screenWidth * 0.15f) * (groundSrc.height().toFloat() / dinoRun1.height())
                val dest1 = RectF(groundOffsetX, groundY - 2, groundOffsetX + screenWidth, groundY + groundHeight - 2)
                val dest2 = RectF(groundOffsetX + screenWidth, groundY - 2, groundOffsetX + screenWidth * 2, groundY + groundHeight - 2)
                canvas.drawBitmap(it, groundSrc, dest1, spritePaint)
                canvas.drawBitmap(it, groundSrc, dest2, spritePaint)
            }

//            // Draw pulsating glow
//            val glowAlpha = ((sin((currentTime - startTime) / 1000.0) + 1.0) / 2.0 * 50).toInt() + 10
//            glowPaint.alpha = glowAlpha
//            canvas.drawCircle(screenWidth / 2f, screenHeight * 0.35f, maxOf(screenWidth * 0.35f, screenHeight * 0.15f), glowPaint)

            // Draw clouds
            clouds.forEach { cloud ->
                spriteBitmap?.let {
                    val dest = RectF(cloud.x, cloud.y, cloud.x + cloud.width, cloud.y + cloud.height)
                    val cloudPaint = Paint().apply {
                        alpha = 140
                        isAntiAlias = false
                        isFilterBitmap = false
                    }
                    canvas.drawBitmap(it, cloudSrc, dest, cloudPaint)
                }
            }

            // Draw obstacles
            obstacles.forEach { obs ->
                spriteBitmap?.let {
                    val dest = RectF(obs.x, groundY - obs.height, obs.x + obs.width, groundY)
                    canvas.drawBitmap(it, obs.srcRect, dest, spritePaint)
                }
            }

            // Draw Score
            textPaint.color = Color.WHITE
            textPaint.alpha = 200
            val hiscoreText = "HI: ${highScore.toString().padStart(5, '0')}  "
            canvas.drawText(hiscoreText, 20f, 100f, textPaint)
            val scoreText = "SCORE: ${score.toString().padStart(5, '0')}  "
            canvas.drawText(scoreText, screenWidth/2 + 98f, 100f, textPaint)

            if (isGameOver) {
                spriteBitmap?.let {
                    val gameOverWidth = screenWidth * 0.5f
                    val gameOverHeight = gameOverWidth * (gameOverSrc.height().toFloat() / gameOverSrc.width())
                    val left = (screenWidth - gameOverWidth) / 2f
                    val top = screenHeight / 2f - screenHeight * 0.31f
                    val dest = RectF(left, top, left + gameOverWidth, top + gameOverHeight)
                    canvas.drawBitmap(it, gameOverSrc, dest, spritePaint)
                }
            }

            // Draw Dino
            val dinoWidth = screenWidth * 0.15f
            val dinoHeight = dinoWidth * (dinoRun1.height().toFloat() / dinoRun1.width())
            val dinoX = screenWidth * 0.1f
            val dinoRectDest = RectF(dinoX, dinoY - dinoHeight, dinoX + dinoWidth, dinoY)

            spriteBitmap?.let {
                val src = when {
                    isGameOver -> dinoJump
                    isJumping -> dinoJump
                    walkFrame == 0 -> dinoRun1
                    else -> dinoRun2
                }
                canvas.drawBitmap(it, src, dinoRectDest, spritePaint)
            } ?: run {
                canvas.drawRect(dinoRectDest, Paint().apply { color = Color.parseColor("#535353") })
            }
        }
    }
}
