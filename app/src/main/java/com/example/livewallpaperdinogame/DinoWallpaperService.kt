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

    private val prefs = context.getSharedPreferences("DinoPrefs", Context.MODE_PRIVATE)

    private var backgroundBitmap: Bitmap? = null
    private var staticDinoBitmap: Bitmap? = null // Add this
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

    // MEMORY LEAK FIX: Pre-allocate Rects used in the draw loop
    private val bgDestRect = RectF()
    private val genericDestRect = RectF()
    private val groundDestRect1 = RectF()
    private val groundDestRect2 = RectF()
    private val dinoRectDest = RectF()
    
    private val dimPaint = Paint().apply { alpha = 160 }
    private val darkGrayPaint = Paint().apply { color = Color.parseColor("#535353") }
    private val spritePaint = Paint().apply {
        isAntiAlias = false
        isFilterBitmap = false
    }
    private val maskPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }
    private val cloudPaint = Paint().apply {
        alpha = 140
        isAntiAlias = false
        isFilterBitmap = false
    }

    private data class Cloud(var x: Float, var y: Float, val speed: Float, val width: Float, val height: Float)
    private val clouds = mutableListOf<Cloud>()

    private data class Obstacle(var x: Float, val width: Float, val height: Float, val srcRect: Rect)
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

    init {
        loadResources()
        // Load persistent high score
        highScore = prefs.getInt("high_score", 0)
    }

    private fun loadResources() {
        try {
            val options = BitmapFactory.Options().apply { inScaled = false }
            // Load the new separated assets
            backgroundBitmap = BitmapFactory.decodeResource(context.resources, R.drawable.bg, options)
            staticDinoBitmap = BitmapFactory.decodeResource(context.resources, R.drawable.dino, options)
            spriteBitmap = BitmapFactory.decodeResource(context.resources, R.drawable.sprite, options)
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
            val hitbox = RectF(dinoX - 50f, dinoY - dinoHeight - 50f, dinoX + dinoWidth + 50f, dinoY + 50f)

            if (hitbox.contains(x, y)) {
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

            // DYNAMIC FRAME RATE: 60 FPS during gameplay, 15 FPS when idle
            val currentFPS = if (isPlaying) 60 else 15
            val currentTargetTime = 1000L / currentFPS

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
                    } catch (e: Exception) {}
                }
            }

            // Calculate how long to sleep to hit our dynamic target time
            val timeMillis = (System.nanoTime() - startTimeLoop) / 1000000
            val waitTime = currentTargetTime - timeMillis
            
            if (waitTime > 0) {
                try { 
                    sleep(waitTime) 
                } catch (e: Exception) {}
            }
        }
    }

    private fun update() {
        // ALWAYS fetch the latest score and placement dynamically so it updates immediately when changed in MainActivity
        highScore = prefs.getInt("high_score", 0)
        val placementProgress = prefs.getInt("ground_placement", 50)
        
        val rect = surfaceHolder.surfaceFrame
        if (screenWidth != rect.width() || screenHeight != rect.height()) {
            screenWidth = rect.width()
            screenHeight = rect.height()
        }
        
        if (screenWidth <= 0 || screenHeight <= 0) return

        // Calculate ground placement dynamically based on slider (0 to 100)
        // 50 is center (0.5f). Maps to a vertical range on the screen.
        val placementRatio = 0.2f + (placementProgress / 100f) * 0.4f 
        groundY = screenHeight * placementRatio
        
        if (!isJumping) dinoY = groundY

        if (clouds.isEmpty()) {
            for (i in 0..1) {
                clouds.add(Cloud(
                    (Math.random() * screenWidth).toFloat(),
                    screenHeight * 0.15f + (Math.random() * screenHeight * 0.15f).toFloat(),
                    0.2f + (Math.random() * 0.5f).toFloat(),
                    screenWidth * 0.08f + (Math.random() * screenWidth * 0.04f).toFloat(),
                    screenHeight * 0.025f + (Math.random() * screenHeight * 0.015f).toFloat()
                ))
            }
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
                
                // Save High Score permanently
                if (score > highScore) {
                    highScore = score
                    prefs.edit().putInt("high_score", highScore).apply()
                }
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

        canvas.drawColor(Color.parseColor("#1a1a1a"))

        // 1. Calculate the master destination rectangle for the full 720x1600 images
        backgroundBitmap?.let { bg ->
            val scaleX = screenWidth.toFloat() / bg.width
            val scaleY = screenHeight.toFloat() / bg.height
            val scale = maxOf(scaleX, scaleY)
            val sw = bg.width * scale
            val sh = bg.height * scale
            val left = (screenWidth - sw) / 2f
            val top = (screenHeight - sh) / 2f

            bgDestRect.set(left, top, left + sw, top + sh)
            
            // Draw the pure static background
            canvas.drawBitmap(bg, null, bgDestRect, dimPaint)
        }

        // Shared Dino variables for the animated sprite
        val dinoWidth = screenWidth * 0.15f
        val dinoX = screenWidth * 0.1f

        if (showGameElements) {
            // --- ACTIVE GAME STATE ---
            
            spriteBitmap?.let { sprite ->
                // Ground
                val groundHeight = dinoWidth * (groundSrc.height().toFloat() / dinoRun1.height())
                groundDestRect1.set(groundOffsetX, groundY - 2, groundOffsetX + screenWidth, groundY + groundHeight - 2)
                groundDestRect2.set(groundOffsetX + screenWidth, groundY - 2, groundOffsetX + screenWidth * 2, groundY + groundHeight - 2)
                canvas.drawBitmap(sprite, groundSrc, groundDestRect1, spritePaint)
                canvas.drawBitmap(sprite, groundSrc, groundDestRect2, spritePaint)

                // Clouds
                clouds.forEach { cloud ->
                    genericDestRect.set(cloud.x, cloud.y, cloud.x + cloud.width, cloud.y + cloud.height)
                    canvas.drawBitmap(sprite, cloudSrc, genericDestRect, cloudPaint)
                }

                // Obstacles
                obstacles.forEach { obs ->
                    genericDestRect.set(obs.x, groundY - obs.height, obs.x + obs.width, groundY)
                    canvas.drawBitmap(sprite, obs.srcRect, genericDestRect, spritePaint)
                }

                // Scores
                val hiscoreText = "HI: ${highScore.toString().padStart(5, '0')}  "
                canvas.drawText(hiscoreText, 20f, 100f, textPaint)
                val scoreText = "SCORE: ${score.toString().padStart(5, '0')}  "
                canvas.drawText(scoreText, screenWidth / 2 + 98f, 100f, textPaint)

                // Game Over Text
                if (isGameOver) {
                    val gameOverWidth = screenWidth * 0.5f
                    val gameOverHeight = gameOverWidth * (gameOverSrc.height().toFloat() / gameOverSrc.width())
                    val left = (screenWidth - gameOverWidth) / 2f
                    val top = screenHeight / 2f - screenHeight * 0.31f
                    genericDestRect.set(left, top, left + gameOverWidth, top + gameOverHeight)
                    canvas.drawBitmap(sprite, gameOverSrc, genericDestRect, spritePaint)
                }

                // Animated Dino
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
            
            // Draw the full-size transparent dino image using the exact same calculated rect
            // as the background. This guarantees a pixel-perfect 1:1 overlay, completely 
            // ignoring the game's groundY logic for the static image.
            staticDinoBitmap?.let { staticDino ->
                canvas.drawBitmap(staticDino, null, bgDestRect, spritePaint)
            }
        }
    }
}
