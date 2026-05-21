package com.example.livewallpaperdinogame

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Switch
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = getSharedPreferences("DinoPrefs", Context.MODE_PRIVATE)

        val radioGroupTheme = findViewById<RadioGroup>(R.id.radioGroupTheme)
        val switchStars = findViewById<Switch>(R.id.switchStars)
        val seekGround = findViewById<SeekBar>(R.id.seekGround)
        val seekDinoX = findViewById<SeekBar>(R.id.seekDinoX)
        val seekTextY = findViewById<SeekBar>(R.id.seekTextY)
        val switchDebug = findViewById<Switch>(R.id.switchDebug)
        val btnResetDefaults = findViewById<Button>(R.id.btnResetDefaults)
        val btnResetScore = findViewById<Button>(R.id.btnResetScore)
        val btnSetWallpaper = findViewById<Button>(R.id.btnSetWallpaper)

        // Load saved values
        when (prefs.getInt("theme_mode", 1)) {
            0 -> radioGroupTheme.check(R.id.rbLight)
            1 -> radioGroupTheme.check(R.id.rbDarkPlain)
            2 -> radioGroupTheme.check(R.id.rbDarkAlt)
        }
        
        switchStars.isChecked = prefs.getBoolean("show_stars", false)
        seekGround.progress = prefs.getInt("ground_placement", 50)
        seekDinoX.progress = prefs.getInt("dino_x", 10)
        seekTextY.progress = prefs.getInt("text_y", 8)
        switchDebug.isChecked = prefs.getBoolean("show_debug", false)

        // Theme and Star Listeners
        radioGroupTheme.setOnCheckedChangeListener { _, checkedId ->
            val themeMode = when (checkedId) {
                R.id.rbLight -> 0
                R.id.rbDarkAlt -> 2
                else -> 1
            }
            prefs.edit().putInt("theme_mode", themeMode).apply()
        }

        switchStars.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("show_stars", isChecked).apply()
        }

        val sliderListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    when (seekBar?.id) {
                        R.id.seekGround -> prefs.edit().putInt("ground_placement", progress).apply()
                        R.id.seekDinoX -> prefs.edit().putInt("dino_x", progress).apply()
                        R.id.seekTextY -> prefs.edit().putInt("text_y", progress).apply()
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }

        seekGround.setOnSeekBarChangeListener(sliderListener)
        seekDinoX.setOnSeekBarChangeListener(sliderListener)
        seekTextY.setOnSeekBarChangeListener(sliderListener)

        switchDebug.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("show_debug", isChecked).apply()
        }

        btnResetDefaults.setOnClickListener {
            prefs.edit().apply {
                putInt("theme_mode", 1)
                putBoolean("show_stars", false)
                putInt("ground_placement", 50)
                putInt("dino_x", 10)
                putInt("text_y", 8)
                putBoolean("show_debug", false)
                apply()
            }
            radioGroupTheme.check(R.id.rbDarkPlain)
            switchStars.isChecked = false
            seekGround.progress = 50
            seekDinoX.progress = 10
            seekTextY.progress = 8
            switchDebug.isChecked = false
            Toast.makeText(this, "Layout restored to defaults!", Toast.LENGTH_SHORT).show()
        }

        btnResetScore.setOnClickListener {
            prefs.edit().putInt("high_score", 0).apply()
            Toast.makeText(this, "Score Reset!", Toast.LENGTH_SHORT).show()
        }

        btnSetWallpaper.setOnClickListener {
            try {
                val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                    putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(this@MainActivity, DinoWallpaperService::class.java))
                }
                startActivity(intent)
            } catch (e: Exception) {
                startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
            }
        }
    }
}
