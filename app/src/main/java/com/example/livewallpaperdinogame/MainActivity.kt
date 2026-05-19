package com.example.livewallpaperdinogame

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = getSharedPreferences("DinoPrefs", Context.MODE_PRIVATE)

        val seekGroundPlacement = findViewById<SeekBar>(R.id.seekGroundPlacement)
        val btnResetScore = findViewById<Button>(R.id.btnResetScore)
        val btnSetWallpaper = findViewById<Button>(R.id.btnSetWallpaper)

        // Load saved placement (default 50 is center)
        seekGroundPlacement.progress = prefs.getInt("ground_placement", 50)

        seekGroundPlacement.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                prefs.edit().putInt("ground_placement", progress).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnResetScore.setOnClickListener {
            prefs.edit().putInt("high_score", 0).apply()
            Toast.makeText(this, "High Score Reset to 0", Toast.LENGTH_SHORT).show()
        }

        btnSetWallpaper.setOnClickListener {
            val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                putExtra(
                    WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    ComponentName(this@MainActivity, DinoWallpaperService::class.java)
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                startActivity(intent)
            } catch (e: Exception) {
                try {
                    val fallbackIntent = Intent().apply {
                        action = "android.service.wallpaper.LIVE_WALLPAPER_CHOOSER"
                    }
                    startActivity(fallbackIntent)
                } catch (e2: Exception) {
                    Toast.makeText(this, "Could not open wallpaper settings", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
