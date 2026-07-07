package com.helloworld3003.livewallpaperdinogame

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
        var activeTheme = -1
        var isLoadingTheme = false

        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(true)
            val prefs = context.getSharedPreferences("DinoPrefs", Context.MODE_PRIVATE)
            loadThemeAssets(prefs.getInt("theme_mode", 1))
        }

        fun loadThemeAssets(themeMode: Int) {
            if (isLoadingTheme || activeTheme == themeMode) return
            isLoadingTheme = true
            bitmapsLoaded = false

            Thread {
                try {
                    backgroundBitmap?.recycle()
                    staticDinoBitmap?.recycle()
                    spriteBitmap?.recycle()

                    val standardOptions = BitmapFactory.Options().apply { inScaled = false }
                    val bgOpts = BitmapFactory.Options().apply {
                        inScaled = false
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }

                    backgroundBitmap = when (themeMode) {
                        0 -> BitmapFactory.decodeResource(context.resources, R.drawable.bg_light, bgOpts)
                        2 -> BitmapFactory.decodeResource(context.resources, R.drawable.bg_dark_1, bgOpts)
                        else -> BitmapFactory.decodeResource(context.resources, R.drawable.bg_dark, bgOpts)
                    }

                    val dinoRes = if (themeMode == 0) R.drawable.dino_light else R.drawable.dino_dark
                    val spriteRes = if (themeMode == 0) R.drawable.sprite_light else R.drawable.sprite_dark

                    staticDinoBitmap = BitmapFactory.decodeResource(context.resources, dinoRes, standardOptions)
                    spriteBitmap = BitmapFactory.decodeResource(context.resources, spriteRes, standardOptions)

                    activeTheme = themeMode
                    bitmapsLoaded = true
                    isLoadingTheme = false
                } catch (e: Exception) {
                    Log.e("DinoWallpaper", "Failed to load theme bitmaps", e)
                    isLoadingTheme = false
                }
            }.start()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            if (visible) startDrawingThread() else stopDrawingThread()
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
                try { it.join(500) } catch (e: InterruptedException) {}
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
        private var gravity = 0f
        private var jumpStrength = 0f
        private var maxJumpHeight = 0f
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
        private var lastProcessedTheme = -1

        private var customDinoX = 0f
        private var customTextY = 0f
        private var showDebugBox = false
        private var showStars = false

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

        // Paint for background static stars
        private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeCap = Paint.Cap.ROUND
        }

        // --- NEW PAINTS FOR CSS-STYLE SHOOTING STARS ---
        private val starHeadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }

        private val starTailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }

        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }

        private val staticHitbox = RectF()
        private val debugPaint = Paint().apply { color = Color.argb(120, 255, 0, 0) }

        private val clouds = mutableListOf<Cloud>()
        private val obstacles = mutableListOf<Obstacle>()

        // Upgraded Star Engine Lists
        private val staticStars = mutableListOf<StaticStar>()
        private val shootingStars = mutableListOf<ShootingStar>()

        private val dinoRun1 = Rect(1514, 0, 1602, 94)
        private val dinoRun2 = Rect(1602, 0, 1690, 94)
        private val dinoJump = Rect(1338, 0, 1426, 94)
        private val cactusSmall = Rect(446, 2, 480, 72)
        private val cactusLarge = Rect(652, 2, 701, 102)
        
        // --- ADD THE BIRDS ---
        private val bird1 = Rect(260, 14, 352, 78) // Wings Up
        private val bird2 = Rect(352, 14, 444, 78) // Wings Down
        private val cloudSrc = Rect(174, 2, 258, 29)
        private val gameOverSrc = Rect(954, 29, 1335, 50)
        private val groundSrc = Rect(0, 104, 2404, 122)

        fun setRunning(run: Boolean) { isRunning = run }

        fun handleTouch(x: Float, y: Float) {
            if (!engine.bitmapsLoaded) return

            if (!isPlaying) {
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
                val currentFPS = if (isPlaying) 60 else 15
                val currentTargetTime = 1000L / currentFPS

                if (surfaceHolder.surface.isValid && engine.bitmapsLoaded) {
                    var canvas: Canvas? = null
                    try {
                        canvas = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            try { surfaceHolder.lockHardwareCanvas() } catch (e: Exception) { surfaceHolder.lockCanvas() }
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
                            try { surfaceHolder.unlockCanvasAndPost(it) } catch (e: Exception) {}
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
            val requestedTheme = prefs.getInt("theme_mode", 1)
            showStars = prefs.getBoolean("show_stars", false)
            val placementProgress = prefs.getInt("ground_placement", 50)
            val dinoXProgress = prefs.getInt("dino_x", 10)
            val textYProgress = prefs.getInt("text_y", 8)
            showDebugBox = prefs.getBoolean("show_debug", false)

            if (requestedTheme != engine.activeTheme && !engine.isLoadingTheme) {
                engine.loadThemeAssets(requestedTheme)
            }

            val rect = surfaceHolder.surfaceFrame

            if ((screenWidth != rect.width() || screenHeight != rect.height() || lastProcessedTheme != engine.activeTheme) && engine.bitmapsLoaded) {
                screenWidth = rect.width()
                screenHeight = rect.height()
                lastProcessedTheme = engine.activeTheme
                
                val maxCactusH = screenHeight * 0.08f
                maxJumpHeight = maxCactusH * 2.5f
                gravity = maxJumpHeight / 180f
                jumpStrength = -gravity * 19f
                textPaint.textSize = screenWidth * 0.045f
                textPaint.color = if (engine.activeTheme == 0) Color.parseColor("#535353") else Color.WHITE

                // FIX: 255 makes the Light background fully visible! (0 made it invisible)
                dimPaint.alpha = if (engine.activeTheme == 0) 255 else 160

                if (screenWidth > 0 && screenHeight > 0) {
                    engine.backgroundBitmap?.let { bg ->
                        if (!bg.isRecycled) {
                            try {
                                val scale = maxOf(screenWidth.toFloat() / bg.width, screenHeight.toFloat() / bg.height)
                                val sw = (bg.width * scale).toInt()
                                val sh = (bg.height * scale).toInt()

                                if (sw > 0 && sh > 0) {
                                    val tempBg = Bitmap.createScaledBitmap(bg, sw, sh, true)
                                    if (scaledBgBitmap != null && scaledBgBitmap != bg && scaledBgBitmap != tempBg) {
                                        scaledBgBitmap?.recycle()
                                    }
                                    scaledBgBitmap = tempBg
                                    bgDestRect.set((screenWidth - sw) / 2f, (screenHeight - sh) / 2f, (screenWidth + sw) / 2f, (screenHeight + sh) / 2f)
                                }
                            } catch (e: Exception) {}
                        }
                    }

                    engine.staticDinoBitmap?.let { staticDino ->
                        if (!staticDino.isRecycled) {
                            try {
                                val scale = maxOf(screenWidth.toFloat() / staticDino.width, screenHeight.toFloat() / staticDino.height)
                                val sw = (staticDino.width * scale).toInt()
                                val sh = (staticDino.height * scale).toInt()

                                if (sw > 0 && sh > 0) {
                                    val tempDino = Bitmap.createScaledBitmap(staticDino, sw, sh, true)
                                    if (scaledStaticDinoBitmap != null && scaledStaticDinoBitmap != staticDino && scaledStaticDinoBitmap != tempDino) {
                                        scaledStaticDinoBitmap?.recycle()
                                    }
                                    scaledStaticDinoBitmap = tempDino
                                }
                            } catch (e: Exception) {}
                        }
                    }
                }
            }

            if (screenWidth <= 0 || screenHeight <= 0) return

            // FIX: Spawn Clouds if the list is empty!
            if (clouds.isEmpty() && screenWidth > 0) {
                clouds.add(Cloud(screenWidth * 0.2f, screenHeight * 0.15f, screenWidth * 0.001f, screenWidth * 0.12f, screenHeight * 0.04f))
                clouds.add(Cloud(screenWidth * 0.8f, screenHeight * 0.25f, screenWidth * 0.0015f, screenWidth * 0.15f, screenHeight * 0.05f))
                clouds.add(Cloud(screenWidth * 1.5f, screenHeight * 0.1f, screenWidth * 0.0008f, screenWidth * 0.1f, screenHeight * 0.03f))
            }

            val placementRatio = 0.2f + (placementProgress / 100f) * 0.4f
            groundY = screenHeight * placementRatio
            customDinoX = screenWidth * (dinoXProgress / 100f)
            customTextY = screenHeight * (textYProgress / 100f)

            val dWidth = screenWidth * 0.15f
            val dHeight = dWidth * (dinoRun1.height().toFloat() / dinoRun1.width())
            val padding = dWidth * 0.5f
            staticHitbox.set(customDinoX - padding, (groundY - dHeight) - padding, customDinoX + dWidth + padding, groundY + padding)

            // --- UPGRADED STAR ENGINE ---
            if (engine.activeTheme == 1 && showStars) {
                // 1. Initialize twinkling background stars
                if (staticStars.isEmpty()) {
                    for (i in 0..50) {
                        staticStars.add(StaticStar(
                            x = (Math.random() * screenWidth).toFloat(),
                            y = (Math.random() * screenHeight * 0.6f).toFloat(), // Only spawn in top 60% of sky
                            size = (1f + Math.random() * 3f).toFloat(),
                            alpha = (Math.random() * 255).toFloat(),
                            fadingIn = Math.random() > 0.5
                        ))
                    }
                }

                // 2. Twinkle the background stars
                staticStars.forEach { star ->
                    if (star.fadingIn) {
                        star.alpha += 2f
                        if (star.alpha >= 255f) { star.alpha = 255f; star.fadingIn = false }
                    } else {
                        star.alpha -= 2f
                        if (star.alpha <= 50f) { star.alpha = 50f; star.fadingIn = true }
                    }
                }

                // 3. Spawn fast, long shooting stars (CSS Style)
                if (Math.random() < 0.04 && shootingStars.size < 4) {
                    shootingStars.add(ShootingStar(
                        x = screenWidth + 50f + (Math.random() * 200f).toFloat(), 
                        y = -(Math.random() * screenHeight * 0.2f).toFloat(), // Spawn slightly above screen
                        speedX = (30f + Math.random() * 20f).toFloat(),       // Much faster X movement
                        speedY = (15f + Math.random() * 10f).toFloat(),       // Steeper Y angle
                        alpha = 255f,
                        length = (100f + Math.random() * 150f).toFloat(),     // Massive, long CSS-style tails
                        thickness = (2f + Math.random() * 3f).toFloat()       
                    ))
                }

                // 4. Animate shooting stars
                val starIterator = shootingStars.iterator()
                while (starIterator.hasNext()) {
                    val star = starIterator.next()
                    star.x -= star.speedX
                    star.y += star.speedY

                    // Fade out as it crosses the screen
                    if (star.x < screenWidth * 0.3f) {
                        star.alpha -= 15f
                    } else {
                        star.alpha -= 3f
                    }

                    if (star.alpha <= 0f || star.y > screenHeight) {
                        starIterator.remove()
                    }
                }
            } else {
                staticStars.clear()
                shootingStars.clear()
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
                    // Only start spawning birds after score reaches 300 (30% chance to be a bird)
                    val spawnBird = score >= 300 && Math.random() < 0.3

                    if (spawnBird) {
                        // Birds fly at 3 different heights
                        val birdHeights = listOf(
                            screenHeight * 0.02f,  // Low
                            maxJumpHeight * 0.3f,  // Medium
                            maxJumpHeight * 0.75f  // High
                        )
                        val yOff = birdHeights.random()
                        val h = screenHeight * 0.05f
                        val w = h * (bird1.width().toFloat() / bird1.height())
                        
                        obstacles.add(Obstacle(screenWidth.toFloat(), w, h, bird1, true, yOff))
                    } else {
                        // Spawn standard Cactus
                        val isLarge = score >= 150 && Math.random() < 0.4
                        val multi = ((Math.random() * (if(score < 50) 1 else if(score < 200) 2 else 3)).toInt() + 1)
                        val baseSrc = if (isLarge) cactusLarge else cactusSmall
                        val src = Rect(baseSrc.left, baseSrc.top, baseSrc.left + baseSrc.width() * multi, baseSrc.bottom)
                        val h = if (isLarge) screenHeight * 0.08f else screenHeight * 0.05f
                        val w = h * (src.width().toFloat() / src.height())
                        
                        obstacles.add(Obstacle(screenWidth.toFloat(), w, h, src, false, 0f))
                    }
                }
            }

            val dinoWidth = screenWidth * 0.15f
            val dinoHeight = dinoWidth * (dinoRun1.height().toFloat() / dinoRun1.width())
            val dinoHitbox = RectF(customDinoX + 30, dinoY - dinoHeight + 30, customDinoX + dinoWidth - 30, dinoY - 10)

            val iterator = obstacles.iterator()
            while (iterator.hasNext()) {
                val obs = iterator.next()
                
                // Birds fly slightly faster than the ground moves
                if (obs.isBird) {
                    obs.x -= gameSpeed * 1.15f
                    // Animate the wings flapping using the frameCounter
                    obs.srcRect = if (frameCounter % 12 < 6) bird1 else bird2
                } else {
                    obs.x -= gameSpeed
                }

                // Apply the yOffset so the bird floats, and calculate the hitbox
                val obsHitbox = RectF(
                    obs.x + 10, 
                    (groundY - obs.height - obs.yOffset) + 10, 
                    obs.x + obs.width - 10, 
                    groundY - obs.yOffset
                )
                
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

            canvas.drawColor(if (engine.activeTheme == 0) Color.WHITE else Color.parseColor("#1a1a1a"))

            scaledBgBitmap?.let { bg ->
                if (!bg.isRecycled) {
                    canvas.drawBitmap(bg, bgDestRect.left, bgDestRect.top, dimPaint)
                }
            }

            if (engine.activeTheme == 1 && showStars) {
                // Draw twinkling static stars
                staticStars.forEach { star ->
                    starPaint.alpha = star.alpha.toInt()
                    starPaint.strokeWidth = star.size
                    canvas.drawPoint(star.x, star.y, starPaint)
                }

                // --- DRAW CSS-STYLE SHOOTING STARS ---
                shootingStars.forEach { star ->
                    val tailX = star.x + (star.speedX * (star.length / 10f))
                    val tailY = star.y - (star.speedY * (star.length / 10f))

                    // 1. The Fading Tail (Replicates CSS linear-gradient)
                    starTailPaint.shader = LinearGradient(
                        star.x, star.y, 
                        tailX, tailY,
                        Color.argb(star.alpha.toInt(), 255, 255, 255), // Solid white at the head
                        Color.TRANSPARENT,                             // Fades cleanly to transparent
                        Shader.TileMode.CLAMP
                    )
                    starTailPaint.strokeWidth = star.thickness
                    canvas.drawLine(star.x, star.y, tailX, tailY, starTailPaint)

                    // 2. The Glowing Head (Replicates CSS box-shadow glow)
                    // Draw outer soft glow
                    starHeadPaint.alpha = (star.alpha * 0.5f).toInt()
                    canvas.drawCircle(star.x, star.y, star.thickness * 2f, starHeadPaint)
                    
                    // Draw inner bright core
                    starHeadPaint.alpha = star.alpha.toInt()
                    canvas.drawCircle(star.x, star.y, star.thickness, starHeadPaint)
                }
            }

            val dinoWidth = screenWidth * 0.15f

            if (showGameElements) {
                engine.spriteBitmap?.let { sprite ->
                    if (!sprite.isRecycled) {
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
                            // Subtract the yOffset from both top and bottom coordinates
                            genericDestRect.set(obs.x, groundY - obs.height - obs.yOffset, obs.x + obs.width, groundY - obs.yOffset)
                            canvas.drawBitmap(sprite, obs.srcRect, genericDestRect, spritePaint)
                        }

                        val sideMargin = screenWidth * 0.05f

                        textPaint.textAlign = Paint.Align.LEFT
                        val hiscoreText = "HI: ${highScore.toString().padStart(5, '0')}"
                        canvas.drawText(hiscoreText, sideMargin, customTextY, textPaint)

                        textPaint.textAlign = Paint.Align.RIGHT
                        val scoreText = "SCORE: ${score.toString().padStart(5, '0')}"
                        canvas.drawText(scoreText, screenWidth - sideMargin, customTextY, textPaint)

                        if (isGameOver) {
                            val gameOverWidth = screenWidth * 0.5f
                            val gameOverHeight = gameOverWidth * (gameOverSrc.height().toFloat() / gameOverSrc.width())
                            val left = (screenWidth - gameOverWidth) / 2f
                            val top = screenHeight / 2f - screenHeight * 0.31f
                            genericDestRect.set(left, top, left + gameOverWidth, top + gameOverHeight)
                            canvas.drawBitmap(sprite, gameOverSrc, genericDestRect, spritePaint)
                        }

                        val dinoHeight = dinoWidth * (dinoRun1.height().toFloat() / dinoRun1.width())
                        dinoRectDest.set(customDinoX, dinoY - dinoHeight, customDinoX + dinoWidth, dinoY)

                        val src = when {
                            isGameOver -> dinoJump
                            isJumping -> dinoJump
                            walkFrame == 0 -> dinoRun1
                            else -> dinoRun2
                        }
                        canvas.drawBitmap(sprite, src, dinoRectDest, spritePaint)
                    }
                }
            } else {
                scaledStaticDinoBitmap?.let { staticDino ->
                    if (!staticDino.isRecycled) {
                        canvas.drawBitmap(staticDino, bgDestRect.left, bgDestRect.top, spritePaint)
                    }
                }

                if (showDebugBox) {
                    canvas.drawRect(staticHitbox, debugPaint)
                }
            }
        }
    }
}

// ---------------------------------------------------------
// DATA CLASSES
// ---------------------------------------------------------
private data class Cloud(var x: Float, var y: Float, val speed: Float, val width: Float, val height: Float)
private data class Obstacle(
    var x: Float, 
    val width: Float, 
    val height: Float, 
    var srcRect: Rect, 
    val isBird: Boolean = false, 
    val yOffset: Float = 0f
)
// Upgraded Star Engine Models
private data class StaticStar(var x: Float, var y: Float, var size: Float, var alpha: Float, var fadingIn: Boolean)
private data class ShootingStar(var x: Float, var y: Float, var speedX: Float, var speedY: Float, var alpha: Float, var length: Float, var thickness: Float)