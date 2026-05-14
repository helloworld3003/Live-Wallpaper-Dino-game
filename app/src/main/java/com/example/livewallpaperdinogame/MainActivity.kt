package com.example.livewallpaperdinogame

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btnSetWallpaper).setOnClickListener {
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
                    // Fallback 1: Specific intent for some devices
                    val fallbackIntent = Intent().apply {
                        action = "android.service.wallpaper.LIVE_WALLPAPER_CHOOSER"
                    }
                    startActivity(fallbackIntent)
                } catch (e2: Exception) {
                    try {
                        // Fallback 2: General wallpaper picker
                        val pickerIntent = Intent(Intent.ACTION_SET_WALLPAPER)
                        startActivity(Intent.createChooser(pickerIntent, "Select Wallpaper"))
                    } catch (e3: Exception) {
                        Toast.makeText(this, "Could not open wallpaper settings", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
}
