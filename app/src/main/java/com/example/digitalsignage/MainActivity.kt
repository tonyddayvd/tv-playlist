package com.example.digitalsignage

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.launch

class MainActivity : BaseRotatableActivity() {

    private lateinit var playerView: androidx.media3.ui.PlayerView
    private lateinit var imageView: android.widget.ImageView
    private lateinit var progressBar: android.widget.ProgressBar
    private lateinit var statusText: android.widget.TextView
    
    private var player: androidx.media3.exoplayer.ExoPlayer? = null
    private val contentManager by lazy { ContentManager(this) }
    private var playlist: List<PlaylistItem> = emptyList()
    private var currentIndex = 0
    private var syncJob: kotlinx.coroutines.Job? = null

    // Secret Exit Variables
    private var exitClickCount = 0
    private var lastClickTime = 0L
    private val EXIT_TIMEOUT = 2000L 
    
    // Debug Rotation
    private var currentDebugRotation = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Keep screen on & Aggressive Wake Up (Triggers HDMI-CEC on TV Boxes)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
        
        // Modern API for Wake Up
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(android.content.Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        }
        
        // Global Exception Handler for debugging on phone
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(this, "Erro Fatal: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
        
        setContentView(R.layout.activity_main)
        
        // Check if configured
        val prefs = getSharedPreferences("xingu_prefs", MODE_PRIVATE)
        val clientId = prefs.getString("client_id", "")
        val fromAdmin = intent.getBooleanExtra("FROM_ADMIN", false)
        
        // Apply Global Rotation (from Admin)
        val root = findViewById<View>(R.id.rootContainer)
        applyRotation(root)
        
        // Backup Click Listener for Touch/Mouse
        // Backup Click Listener for Touch/Mouse
        root.setOnClickListener { handleDebugClick() }
        
        playerView = findViewById(R.id.playerView)
        imageView = findViewById(R.id.imageView)
        progressBar = findViewById(R.id.loadingProgressBar)
        statusText = findViewById(R.id.statusTextView)

        // Initialize Player (CRITICAL FIX)
        initializePlayer()
        
        // CRITICAL: Add click listeners to media views to ensure Secret Exit works!
        playerView.setOnClickListener { handleDebugClick() }
        imageView.setOnClickListener { handleDebugClick() }
        
        // Make status text clickable too just in case
        statusText.setOnClickListener { handleDebugClick() }
        
        try {
            hideSystemUI()
        } catch (e: Exception) {
            // Ignore UI hiding errors on some devices
        }
        
        // Start Kiosk Mode (Try-Catch for non-owner devices)
        val kioskMode = prefs.getBoolean("kiosk_mode", true)
        if (kioskMode) {
            try {
                startLockTask()
            } catch (e: Exception) {
                // Not a kiosk device, ignore
            }
        }
        // Fix Auto-Start: Only go to Admin if NOT configured
        if (clientId.isNullOrEmpty()) {
            startActivity(Intent(this, AdminActivity::class.java))
            finish()
            return
        }
        
        // If configured, show countdown (unless from Admin, then skip countdown)
        if (fromAdmin) {
            startSync(clientId!!)
        } else {
            showBootCountdown(clientId!!)
        }
    }

    private var countdownJob: kotlinx.coroutines.Job? = null
    private var isCountingDown = false

    private fun showBootCountdown(clientId: String) {
        isCountingDown = true
        progressBar.visibility = View.GONE
        statusText.visibility = View.VISIBLE
        imageView.visibility = View.GONE
        playerView.visibility = View.GONE
        
        countdownJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            for (i in 5 downTo 1) {
                statusText.text = "Iniciando em ${i}s...\n[CLIQUE AQUI PARA CONFIGURAR]"
                kotlinx.coroutines.delay(1000)
            }
            isCountingDown = false
            startSync(clientId)
        }
    }

    private fun initializePlayer() {
        player = androidx.media3.exoplayer.ExoPlayer.Builder(this).build()
        playerView.player = player
        
        player?.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == androidx.media3.common.Player.STATE_ENDED) {
                    playNextItem()
                }
                if (playbackState == androidx.media3.common.Player.STATE_READY) {
                     if (!hasShownDebugToast) {
                        android.widget.Toast.makeText(this@MainActivity, "Vídeo Pronto! (Tocando)", android.widget.Toast.LENGTH_SHORT).show()
                     }
                }
                if (playbackState == androidx.media3.common.Player.STATE_BUFFERING) {
                     if (!hasShownDebugToast) {
                        android.widget.Toast.makeText(this@MainActivity, "Carregando Vídeo (Buffering)...", android.widget.Toast.LENGTH_SHORT).show()
                     }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // Debug toasts removed for production
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                android.widget.Toast.makeText(this@MainActivity, "Erro Player: ${error.message}", android.widget.Toast.LENGTH_LONG).show()
                android.util.Log.e("PlayerError", "Error: ${error.message}", error)
                // Skip to next item on error
                playNextItem()
            }
        })
    }

    private var transitionDuration = 1000

    private fun startSync(clientId: String) {
        statusText.text = "Sincronizando: $clientId..."
        progressBar.visibility = View.VISIBLE
        android.widget.Toast.makeText(this, "Baixando playlist...", android.widget.Toast.LENGTH_SHORT).show()
        
        syncJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            try {
                val playlistData = contentManager.syncContent(clientId)
                playlist = playlistData.items
                transitionDuration = playlistData.settings.transitionDuration
                
                progressBar.visibility = View.GONE
                statusText.visibility = View.GONE
                
                if (playlist.isNotEmpty()) {
                    android.widget.Toast.makeText(this@MainActivity, "Playlist OK: ${playlist.size} itens", android.widget.Toast.LENGTH_SHORT).show()
                    currentIndex = -1
                    playNextItem()
                } else {
                    statusText.text = "Playlist vazia ou não encontrada: $clientId"
                    statusText.visibility = View.VISIBLE
                    android.widget.Toast.makeText(this@MainActivity, "Playlist VAZIA!", android.widget.Toast.LENGTH_LONG).show()
                    
                    // Retry after 10s?
                    kotlinx.coroutines.delay(10000)
                    startSync(clientId)
                }
            } catch (e: Exception) {
                android.widget.Toast.makeText(this@MainActivity, "Erro Sync: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                statusText.text = "Erro: ${e.message}"
                statusText.visibility = View.VISIBLE
                kotlinx.coroutines.delay(10000)
                startSync(clientId)
            }
        }
    }

    private fun playNextItem(recursionCount: Int = 0) {
        if (playlist.isEmpty()) return
        
        // Prevent infinite recursion if all files are missing
        if (recursionCount >= playlist.size) {
            android.widget.Toast.makeText(this, "Erro: Nenhum arquivo válido encontrado na playlist!", android.widget.Toast.LENGTH_LONG).show()
            statusText.text = "Erro: Arquivos não encontrados."
            statusText.visibility = View.VISIBLE
            return
        }
        
        currentIndex = (currentIndex + 1) % playlist.size
        val item = playlist[currentIndex]
        
        if (item.localPath == null) {
            // Skip if file missing
            playNextItem(recursionCount + 1)
            return
        }

        if (item.type == "video") {
            showVideo(item.localPath)
        } else {
            showImage(item.localPath, item.durationSeconds)
        }
    }

    private var hasShownDebugToast = false

    private fun showVideo(path: String) {
        val file = java.io.File(path)
        if (!file.exists() || file.length() == 0L) {
            if (!hasShownDebugToast) {
                android.widget.Toast.makeText(this, "Erro: Arquivo de vídeo inválido/vazio!", android.widget.Toast.LENGTH_LONG).show()
                hasShownDebugToast = true
            }
            // Delete corrupt file
            file.delete()
            playNextItem()
            return
        }
        
        if (!hasShownDebugToast) {
            android.widget.Toast.makeText(this, "Tocando Vídeo: ${file.length() / 1024} KB", android.widget.Toast.LENGTH_SHORT).show()
            hasShownDebugToast = true
        }

        // STRATEGY: Keep PlayerView VISIBLE always to prevent Surface destruction.
        // Just hide the ImageView to reveal the video.
        imageView.visibility = View.GONE
        imageView.setImageDrawable(null) 
        findViewById<View>(R.id.bootLogo).visibility = View.GONE // Hide Logo
        
        playerView.visibility = View.VISIBLE // Ensure it's visible
        // playerView.bringToFront() // Not needed if ImageView is hidden
        
        val mediaItem = androidx.media3.common.MediaItem.fromUri(path)
        player?.setMediaItem(mediaItem)
        player?.prepare()
        player?.play()
    }

    private fun showImage(path: String, duration: Int) {
        val file = java.io.File(path)
        if (!file.exists() || file.length() == 0L) {
             if (!hasShownDebugToast) {
                 android.widget.Toast.makeText(this, "Erro: Arquivo de imagem inválido/vazio!", android.widget.Toast.LENGTH_LONG).show()
                 hasShownDebugToast = true
             }
             file.delete()
             playNextItem()
             return
        }
        
        if (!hasShownDebugToast) {
            android.widget.Toast.makeText(this, "Mostrando Imagem: ${file.length() / 1024} KB", android.widget.Toast.LENGTH_SHORT).show()
            hasShownDebugToast = true 
        }

        // STRATEGY: Cover the video with the ImageView.
        // Do NOT hide PlayerView (keep surface alive).
        playerView.visibility = View.VISIBLE 
        imageView.visibility = View.VISIBLE
        imageView.bringToFront()
        findViewById<View>(R.id.bootLogo).visibility = View.GONE // Hide Logo
        
        // Random Transition Logic
        val transitionType = (0..2).random()
        imageView.alpha = 1f
        imageView.scaleX = 1f
        imageView.scaleY = 1f
        imageView.translationX = 0f
        
        when (transitionType) {
            0 -> { // Fade
                imageView.alpha = 0f
                imageView.animate().alpha(1f).setDuration(transitionDuration.toLong()).start()
            }
            1 -> { // Slide from Right
                imageView.translationX = 1000f
                imageView.animate().translationX(0f).setDuration(transitionDuration.toLong()).start()
            }
            2 -> { // Zoom In
                imageView.scaleX = 0.5f
                imageView.scaleY = 0.5f
                imageView.animate().scaleX(1f).scaleY(1f).setDuration(transitionDuration.toLong()).start()
            }
        }
        
        // Stop player but DO NOT clear surface (let ImageView cover it)
        player?.stop()
        // player?.clearVideoSurface() // REMOVED: Caused freeze
        
        com.bumptech.glide.Glide.with(this)
            .load(path)
            .into(imageView)
            
        // Schedule next item
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            playNextItem()
        }, duration * 1000L)
    }

    private fun hideSystemUI() {
        // Aggressive UI Hiding for TV Boxes
        val flags = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        
        window.decorView.systemUiVisibility = flags
        
        // Also try WindowCompat for newer APIs
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
        }
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent?): Boolean {
        if (ev?.action == android.view.MotionEvent.ACTION_DOWN) {
            handleDebugClick()
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent?): Boolean {
        if (event?.action == android.view.KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                android.view.KeyEvent.KEYCODE_ENTER,
                android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    handleDebugClick()
                    return true
                }
                android.view.KeyEvent.KEYCODE_BACK -> {
                    handleDebugClick() // Back button also counts for secret exit/rotation
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleDebugClick() {
        // 1. Check if we are in Countdown Mode
        if (isCountingDown) {
            countdownJob?.cancel()
            isCountingDown = false
            android.widget.Toast.makeText(this, "V2: Configurando...", android.widget.Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, AdminActivity::class.java))
            finish()
            return
        }

        // 2. Normal Secret Exit Logic
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastClickTime > EXIT_TIMEOUT) {
            exitClickCount = 1
        } else {
            exitClickCount++
        }
        lastClickTime = currentTime

        if (exitClickCount >= 5) {
            // Secret Exit -> Go to Admin
            android.widget.Toast.makeText(this, "V2: Abrindo Admin...", android.widget.Toast.LENGTH_SHORT).show()
            try { stopLockTask() } catch (e: Exception) {}
            
            try {
                startActivity(Intent(this, AdminActivity::class.java))
                finish()
            } catch (e: Exception) {
                android.widget.Toast.makeText(this, "Erro ao abrir Admin: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        } else {
            // Rotate logic removed from here as it is now handled in AdminActivity
            // But we might want to keep it as a fallback? 
            // No, user specifically asked for Admin UI rotation.
            // Let's just show a toast or do nothing.
            // Actually, let's keep it but use the BaseRotatableActivity's rotateScreen?
            // No, that would save preference and might confuse the Admin setting.
            // Let's just show a toast "Use Admin to rotate".
            // Or better: do nothing to avoid accidental rotations during playback.
        }
    }

    // Watchdog Variables
    private val WATCHDOG_INTERVAL = 5000L
    private val FREEZE_THRESHOLD = 10000L
    private var lastPosition = 0L
    private var lastPositionTime = 0L
    private val watchdogHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            checkPlaybackStatus()
            checkWifiStatus() // Check Wi-Fi every 5s
            watchdogHandler.postDelayed(this, WATCHDOG_INTERVAL)
        }
    }

    private fun checkPlaybackStatus() {
        val player = player ?: return
        
        if (player.isPlaying) {
            val currentPos = player.currentPosition
            if (currentPos == lastPosition) {
                // Position hasn't changed
                if (System.currentTimeMillis() - lastPositionTime > FREEZE_THRESHOLD) {
                    // Frozen for too long!
                    android.util.Log.e("Watchdog", "Video Frozen! Restarting...")
                    android.widget.Toast.makeText(this, "Travamento detectado! Reiniciando...", android.widget.Toast.LENGTH_LONG).show()
                    restartApp()
                }
            } else {
                // Progressing normally
                lastPosition = currentPos
                lastPositionTime = System.currentTimeMillis()
            }
        } else {
            // Not playing (maybe loading or image), reset timer
            lastPositionTime = System.currentTimeMillis()
        }
    }

    private fun restartApp() {
        val intent = baseContext.packageManager.getLaunchIntentForPackage(baseContext.packageName)
        intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(intent)
        Runtime.getRuntime().exit(0)
    }

    // Wi-Fi Watchdog
    private fun checkWifiStatus() {
        val connectivityManager = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val activeNetwork = connectivityManager.activeNetworkInfo
        
        if (activeNetwork == null || !activeNetwork.isConnected) {
            android.util.Log.w("WifiWatchdog", "Wi-Fi Disconnected! Attempting reconnect...")
            val wifiManager = applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            
            try {
                if (!wifiManager.isWifiEnabled) {
                    wifiManager.isWifiEnabled = true
                }
                wifiManager.reconnect()
            } catch (e: Exception) {
                android.util.Log.e("WifiWatchdog", "Error reconnecting: ${e.message}")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        watchdogHandler.post(watchdogRunnable)
        hideSystemUI()
        
        // Nuclear Option: Periodic UI Hiding for stubborn TV Boxes
        watchdogHandler.postDelayed(object : Runnable {
            override fun run() {
                hideSystemUI()
                if (!isFinishing) {
                    watchdogHandler.postDelayed(this, 2000)
                }
            }
        }, 2000)
    }

    override fun onPause() {
        super.onPause()
        watchdogHandler.removeCallbacks(watchdogRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        watchdogHandler.removeCallbacks(watchdogRunnable)
    }
}
