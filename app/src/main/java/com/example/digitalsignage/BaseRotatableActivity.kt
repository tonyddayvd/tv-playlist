package com.example.digitalsignage

import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity

abstract class BaseRotatableActivity : AppCompatActivity() {

    protected var currentRotation = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Load saved rotation preference
        val prefs = getSharedPreferences("xingu_prefs", MODE_PRIVATE)
        currentRotation = prefs.getFloat("rotation", 0f)
    }

    protected fun applyRotation(rootView: View) {
        rootView.post {
            val metrics = android.util.DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(metrics)
            val screenW = metrics.widthPixels.toFloat()
            val screenH = metrics.heightPixels.toFloat()
            
            // Reset first
            rootView.translationX = 0f
            rootView.translationY = 0f
            rootView.scaleX = 1f
            rootView.scaleY = 1f
            rootView.rotation = 0f
            
            val params = rootView.layoutParams
            params.width = screenW.toInt()
            params.height = screenH.toInt()
            rootView.layoutParams = params

            if (currentRotation == 90f || currentRotation == 270f) {
                // Swap dimensions
                params.width = screenH.toInt()
                params.height = screenW.toInt()
                rootView.layoutParams = params
                
                rootView.rotation = currentRotation
                rootView.translationX = (screenW - screenH) / 2f
                rootView.translationY = (screenH - screenW) / 2f
            } else {
                // 180
                rootView.rotation = currentRotation
            }
        }
    }

    protected fun rotateScreen() {
        currentRotation = (currentRotation + 90f) % 360f
        
        // Save preference
        getSharedPreferences("xingu_prefs", MODE_PRIVATE)
            .edit()
            .putFloat("rotation", currentRotation)
            .apply()
            
        // Re-apply to current view
        val root = findViewById<View>(android.R.id.content)
        // Try to find specific root if possible, otherwise generic content
        val specificRoot = findViewById<View>(R.id.rootContainer)
        if (specificRoot != null) {
            applyRotation(specificRoot)
        } else {
            // Fallback for activities without rootContainer ID
             applyRotation(root)
        }
        
        android.widget.Toast.makeText(this, "Rotação: $currentRotation", android.widget.Toast.LENGTH_SHORT).show()
    }
}
