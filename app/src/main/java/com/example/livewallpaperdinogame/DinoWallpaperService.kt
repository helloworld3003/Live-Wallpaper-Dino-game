package com.example.livewallpaperdinogame

import android.content.Context
import android.graphics.*
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder

class DinoWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine {
        return DinoEngine(this)
    }

    inner class DinoEngine(private val context: Context) : Engine() {
        private var drawThread: DrawThread? = null

        var backgroundBitmap: Bitmap? = null
        var staticDinoBitmap: Bitmap? = null
        var spriteBitmap: Bitmap? = null
        var bitmapsLoaded = false

        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(true)

            Thread {
                try {
                    val standardOptions = BitmapFactory.Options().apply { inScaled = false }
                    val bgOptions = BitmapFactory.Options().apply {
                        inScaled = false
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }

                    backgroundBitmap = BitmapFactory.decodeResource(context.resources, R.drawable.bg, bgOptions)
                    staticDinoBitmap = BitmapFactory.decodeResource(context.resources, R.drawable.dino, standardOptions)
                    spriteBitmap = BitmapFactory.decodeResource(context.resources, R.drawable.sprite, standardOptions)

                    bitmapsLoaded = true
                } catch (e: Exception) {
                    Log.e("DinoWallpaper", "Failed to load bitmaps", e)
                }
            }.start()
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

        override fun onDestroy() {
            super.onDestroy()
            stopDrawingThread()

            backgroundBitmap?.recycle()
            staticDinoBitmap?.recycle()
            spriteBitmap?.recycle()

            backgroundBitmap = null
            staticDinoBitmap = null
            spriteBitmap = null
        }

        override fun onTouchEvent(event: MotionEvent?) {
            if (event?.action == MotionEvent.ACTION_DOWN) {
                drawThread?.handleTouch(event.x, event.y)
            }
        }

        private fun startDrawingThread() {
            if (drawThread == null || !drawThread!!.isAlive) {
                drawThread = DrawThread(surfaceHolder, context, this).apply {
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

    inner class DrawThread(
        private val surfaceHolder: SurfaceHolder,
        private val context: Context,
        private val engine: DinoEngine
    ) : Thread() {

        private var isRunning = false
        private val prefs = context.getSharedPreferences("DinoPrefs", Context.MODE_PRIVATE)

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

        private val bgDestRect = RectF()
        private val genericDestRect = RectF()
        private val groundDestRect1 = RectF()
        private val groundDestRect2 = RectF()
        private val dinoRectDest = RectF()

        private var scaledBgBitmap: Bitmap? = null
        private var scaledStaticDinoBitmap: Bitmap? = null
        private val dimPaint = Paint().apply { alpha = 160 }
        private val spritePaint = Paint().apply {
            isAntiAlias = false
            isFilterBitmap = false
        }
        private val cloudPaint = Paint().apply {
            alpha = 140
            isAntiAlias = false
            isFilterBitmap = false
        }

        // --- HITBOX DEBUGGER ---
        private val staticHitbox = RectF()
        private val debugPaint = Paint().apply { color = Color.argb(120, 255, 0, 0) } // Semi-transparent red
        private val SHOW_DEBUG_BOX = false // CHANGE TO FALSE WHEN YOU ARE DONE ALIGNING IT

        private val clouds = mutableListOf<Cloud>()
        private val obstacles = mutableListOf<Obstacle>()

        private val dinoRun1 = Rect(1514, 0, 1602, 94)
        private val dinoRun2 = Rect(1602, 0, 1690, 94)
        private val dinoJump = Rect(1338, 0, 1426, 94)
        private val cactusSmall = Rect(446, 2, 480, 72)
        private val cactusLarge = Rect(652, 2, 701, 102)
        private val cloudSrc = Rect(174, 2, 258, 29)
        private val gameOverSrc = Rect(954, 29, 1335, 50)
        private val groundSrc = Rect(0, 104, 2404, 122)

        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            alpha = 200
            textSize = 35f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }

        fun setRunning(run: Boolean) {
            isRunning = run
        }

        fun handleTouch(x: Float, y: Float) {
            if (!engine.bitmapsLoaded) return

            if (!isPlaying) {
                // Only start the game if they tap exactly inside our custom defined hitbox
                if (staticHitbox.contains(x, y)) {
                    if (isGameOver) {
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
                jump() // Tap anywhere to jump while playing
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
                val currentFPS = if (isPlaying) 60 else 15
                val currentTargetTime = 1000L / currentFPS

                if (surfaceHolder.surface.isValid && engine.bitmapsLoaded) {
                    var canvas: Canvas? = null
                    try {
                        canvas = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            try {
                                surfaceHolder.lockHardwareCanvas()
                            } catch (e: Exception) {
                                surfaceHolder.lockCanvas()
                            }
                        } else {
                            surfaceHolder.lockCanvas()
                        }

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
                            } catch (e: Exception) {}
                        }
                    }
                }

                val timeMillis = (System.nanoTime() - startTimeLoop) / 1000000
                val waitTime = currentTargetTime - timeMillis
                if (waitTime > 0) {
                    try { sleep(waitTime) } catch (e: Exception) {}
                }
            }
        }

        private fun update() {
            highScore = prefs.getInt("high_score", 0)
            val placementProgress = prefs.getInt("ground_placement", 50)
            val rect = surfaceHolder.surfaceFrame

            if (screenWidth != rect.width() || screenHeight != rect.height()) {
                screenWidth = rect.width()
                screenHeight = rect.height()

                if (screenWidth > 0 && screenHeight > 0) {
                    engine.backgroundBitmap?.let { bg ->
                        val scale = maxOf(screenWidth.toFloat() / bg.width, screenHeight.toFloat() / bg.height)
                        val sw = (bg.width * scale).toInt()
                        val sh = (bg.height * scale).toInt()
                        scaledBgBitmap?.recycle()
                        scaledBgBitmap = Bitmap.createScaledBitmap(bg, sw, sh, true)
                        bgDestRect.set((screenWidth - sw) / 2f, (screenHeight - sh) / 2f, (screenWidth + sw) / 2f, (screenHeight + sh) / 2f)
                    }

                    engine.staticDinoBitmap?.let { staticDino ->
                        val scale = maxOf(screenWidth.toFloat() / staticDino.width, screenHeight.toFloat() / staticDino.height)
                        val sw = (staticDino.width * scale).toInt()
                        val sh = (staticDino.height * scale).toInt()
                        scaledStaticDinoBitmap?.recycle()
                        scaledStaticDinoBitmap = Bitmap.createScaledBitmap(staticDino, sw, sh, true)
                    }
                }
            }

            if (screenWidth <= 0 || screenHeight <= 0) return

            // MANUAL STATIC HITBOX
            // Adjust these percentages until the red box perfectly covers your static Dino!
            staticHitbox.set(
                screenWidth * 0.1f,   // Left edge (5% from left side)
                screenHeight * 0.27f,  // Top edge (40% down from the top of the screen)
                screenWidth * 0.29f,   // Right edge (35% from the left side)
                screenHeight * 0.37f   // Bottom edge (60% down from the top)
            )

            val placementRatio = 0.2f + (placementProgress / 100f) * 0.4f
            groundY = screenHeight * placementRatio

            if (!isPlaying) {
                dinoY = groundY // Sync dino position to ground while idle
            }

            if (isGameOver || !isPlaying) return

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

            if (obstacles.isEmpty() || (screenWidth - (obstacles.lastOrNull()?.x ?: 0f)) > screenWidth * 0.7f) {
                if (Math.random() < 0.03) {
                    val isLarge = score >= 150 && Math.random() < 0.4
                    val multi = ((Math.random() * (if(score < 50) 1 else if(score < 200) 2 else 3)).toInt() + 1)
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
                val obsHitbox = RectF(obs.x + 10, groundY - obs.height + 10, obs.x + obs.width - 10, groundY)
                if (RectF.intersects(dinoHitbox, obsHitbox)) {
                    isGameOver = true
                    isPlaying = false
                    gameOverTime = System.currentTimeMillis()
                    if (score > highScore) {
                        highScore = score
                        prefs.edit().putInt("high_score", highScore).apply()
                    }
                }
                if (obs.x + obs.width < 0) iterator.remove()
            }
        }

        private fun draw(canvas: Canvas) {
            val currentTime = System.currentTimeMillis()
            val elapsedSinceGameOver = if (isGameOver) currentTime - gameOverTime else 0L
            val showGameElements = isPlaying || (isGameOver && elapsedSinceGameOver < 5000)

            canvas.drawColor(Color.parseColor("#1a1a1a"))

            scaledBgBitmap?.let { bg ->
                canvas.drawBitmap(bg, bgDestRect.left, bgDestRect.top, dimPaint)
            }

            val dinoWidth = screenWidth * 0.15f
            val dinoX = screenWidth * 0.1f

            if (showGameElements) {
                engine.spriteBitmap?.let { sprite ->
                    val groundHeight = dinoWidth * (groundSrc.height().toFloat() / dinoRun1.height())
                    groundDestRect1.set(groundOffsetX, groundY - 2, groundOffsetX + screenWidth, groundY + groundHeight - 2)
                    groundDestRect2.set(groundOffsetX + screenWidth, groundY - 2, groundOffsetX + screenWidth * 2, groundY + groundHeight - 2)
                    canvas.drawBitmap(sprite, groundSrc, groundDestRect1, spritePaint)
                    canvas.drawBitmap(sprite, groundSrc, groundDestRect2, spritePaint)

                    clouds.forEach { cloud ->
                        genericDestRect.set(cloud.x, cloud.y, cloud.x + cloud.width, cloud.y + cloud.height)
                        canvas.drawBitmap(sprite, cloudSrc, genericDestRect, cloudPaint)
                    }

                    obstacles.forEach { obs ->
                        genericDestRect.set(obs.x, groundY - obs.height, obs.x + obs.width, groundY)
                        canvas.drawBitmap(sprite, obs.srcRect, genericDestRect, spritePaint)
                    }

                    val hiscoreText = "HI: ${highScore.toString().padStart(5, '0')} "
                    canvas.drawText(hiscoreText, 20f, 100f, textPaint)
                    val scoreText = "SCORE: ${score.toString().padStart(5, '0')} "
                    canvas.drawText(scoreText, screenWidth / 2 + 98f, 100f, textPaint)

                    if (isGameOver) {
                        val gameOverWidth = screenWidth * 0.5f
                        val gameOverHeight = gameOverWidth * (gameOverSrc.height().toFloat() / gameOverSrc.width())
                        val left = (screenWidth - gameOverWidth) / 2f
                        val top = screenHeight / 2f - screenHeight * 0.31f
                        genericDestRect.set(left, top, left + gameOverWidth, top + gameOverHeight)
                        canvas.drawBitmap(sprite, gameOverSrc, genericDestRect, spritePaint)
                    }

                    val dinoHeight = dinoWidth * (dinoRun1.height().toFloat() / dinoRun1.width())
                    dinoRectDest.set(dinoX, dinoY - dinoHeight, dinoX + dinoWidth, dinoY)

                    val src = when {
                        isGameOver -> dinoJump
                        isJumping -> dinoJump
                        walkFrame == 0 -> dinoRun1
                        else -> dinoRun2
                    }
                    canvas.drawBitmap(sprite, src, dinoRectDest, spritePaint)
                }
            } else {
                // --- IDLE STATE ---
                scaledStaticDinoBitmap?.let { staticDino ->
                    canvas.drawBitmap(staticDino, bgDestRect.left, bgDestRect.top, spritePaint)
                }

                // DRAW THE VISUAL DEBUG HITBOX
                if (SHOW_DEBUG_BOX) {
                    canvas.drawRect(staticHitbox, debugPaint)
                }
            }
        }
    }
}

// ---------------------------------------------------------
// DATA CLASSES MOVED OUTSIDE TO FIX "CLASS NOT ALLOWED" ERROR
// ---------------------------------------------------------
private data class Cloud(var x: Float, var y: Float, val speed: Float, val width: Float, val height: Float)
private data class Obstacle(var x: Float, val width: Float, val height: Float, val srcRect: Rect)