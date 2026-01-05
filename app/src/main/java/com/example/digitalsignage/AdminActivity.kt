package com.example.digitalsignage

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import kotlinx.coroutines.launch

class AdminActivity : BaseRotatableActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin)

        // Apply initial rotation
        val root = findViewById<View>(R.id.rootContainer)
        applyRotation(root)

        val loginSection = findViewById<LinearLayout>(R.id.loginSection)
        val configSection = findViewById<LinearLayout>(R.id.configSection)
        val passwordInput = findViewById<EditText>(R.id.passwordInput)
        val loginButton = findViewById<Button>(R.id.loginButton)
        val rotateButton = findViewById<Button>(R.id.rotateButton)
        // saveButton is declared later

        // Check if already configured (Auto-login logic could go here, but for now always show login if opened)
        val prefs = getSharedPreferences("xingu_prefs", MODE_PRIVATE)

        // Keypad Logic
        val keypadClickListener = View.OnClickListener { v ->
            val tag = v.tag.toString()
            val currentText = passwordInput.text.toString()
            
            when (tag) {
                "C" -> passwordInput.setText("")
                "DEL" -> {
                    if (currentText.isNotEmpty()) {
                        passwordInput.setText(currentText.dropLast(1))
                    }
                }
                else -> {
                    if (currentText.length < 8) { // Max length
                        passwordInput.setText(currentText + tag)
                    }
                }
            }
        }

        // Attach listener to all keypad buttons
        // Attach listener to all keypad buttons
        try {
            val loginSection = findViewById<LinearLayout>(R.id.loginSection)
            var gridLayout: android.widget.GridLayout? = null
            
            // Find GridLayout inside LoginSection
            for (i in 0 until loginSection.childCount) {
                val child = loginSection.getChildAt(i)
                if (child is android.widget.GridLayout) {
                    gridLayout = child
                    break
                }
            }

            if (gridLayout != null) {
                for (i in 0 until gridLayout.childCount) {
                    gridLayout.getChildAt(i).setOnClickListener(keypadClickListener)
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Erro ao init teclado: ${e.message}", Toast.LENGTH_LONG).show()
        }

        // Password Masking
        passwordInput.transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()

        val clientListContainer = findViewById<LinearLayout>(R.id.clientListContainer)
        val refreshButton = findViewById<android.widget.ImageButton>(R.id.refreshClientsButton)
        val saveButton = findViewById<Button>(R.id.saveButton)
        val kioskCheckBox = findViewById<android.widget.CheckBox>(R.id.kioskModeCheckBox)
        
        val contentManager = ContentManager(this)
        var selectedClientId = ""

        // Load Kiosk Pref
        kioskCheckBox.isChecked = prefs.getBoolean("kiosk_mode", true)

        // Function to update UI with selected client
        fun updateSelectionUI() {
            for (i in 0 until clientListContainer.childCount) {
                val btn = clientListContainer.getChildAt(i) as Button
                val client = btn.tag as ClientItem
                
                // If button has focus (user is hovering), keep it Blue
                if (btn.hasFocus()) {
                    btn.setBackgroundColor(android.graphics.Color.parseColor("#2196F3"))
                } else {
                    if (client.id == selectedClientId) {
                        // Force color for selected
                        btn.setBackgroundColor(android.graphics.Color.parseColor("#4CAF50")) // Green
                    } else {
                        // Force color for unselected
                        btn.setBackgroundColor(android.graphics.Color.parseColor("#444444")) // Dark Gray
                    }
                }
            }
        }

        // Function to load clients
        fun loadClients() {
            Toast.makeText(this, "Buscando clientes...", Toast.LENGTH_SHORT).show()
            clientListContainer.removeAllViews()
            
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                val clients = contentManager.fetchClients()
                if (clients.isNotEmpty()) {
                    val saved = prefs.getString("client_id", "")
                    
                    clients.forEach { client ->
                        val btn = Button(this@AdminActivity)
                        btn.text = client.name
                        btn.tag = client
                        btn.setTextColor(android.graphics.Color.WHITE)
                        
                        // Remove default material background to allow setBackgroundColor to work
                        btn.background = null
                        btn.setBackgroundColor(android.graphics.Color.parseColor("#444444"))

                        btn.layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            setMargins(0, 0, 0, 16)
                        }
                        
                        btn.setOnClickListener {
                            selectedClientId = client.id
                            updateSelectionUI()
                        }

                        // FOCUS LOGIC FOR TV BOX REMOTE (D-PAD)
                        btn.setOnFocusChangeListener { view, hasFocus ->
                            if (hasFocus) {
                                // Highlight when navigating (Blue)
                                view.setBackgroundColor(android.graphics.Color.parseColor("#2196F3"))
                            } else {
                                // Restore color when focus leaves
                                val currentClient = view.tag as ClientItem
                                if (currentClient.id == selectedClientId) {
                                    view.setBackgroundColor(android.graphics.Color.parseColor("#4CAF50")) // Green
                                } else {
                                    view.setBackgroundColor(android.graphics.Color.parseColor("#444444")) // Dark Gray
                                }
                            }
                        }
                        
                        clientListContainer.addView(btn)
                        
                        if (client.id == saved) {
                            selectedClientId = saved
                        }
                    }
                    updateSelectionUI()
                    
                } else {
                    val saved = prefs.getString("client_id", "")
                    if (!saved.isNullOrEmpty()) {
                        android.widget.Toast.makeText(this@AdminActivity, "Sem internet. Usando salvo: $saved", android.widget.Toast.LENGTH_LONG).show()
                        
                        // Create a fallback button for the saved client
                        val btn = Button(this@AdminActivity)
                        btn.text = "Offline: $saved"
                        btn.tag = ClientItem(saved, saved)
                        btn.setTextColor(android.graphics.Color.WHITE)
                        btn.background = null
                        btn.setBackgroundColor(android.graphics.Color.parseColor("#4CAF50")) // Green
                        
                        btn.layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { setMargins(0, 0, 0, 16) }
                        
                        btn.setOnClickListener {
                            selectedClientId = saved
                            updateSelectionUI()
                        }
                        
                        // Auto-select
                        selectedClientId = saved
                        clientListContainer.addView(btn)
                    } else {
                        Toast.makeText(this@AdminActivity, "Erro de Rede e sem cliente salvo.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        // Load on start (if config visible, or wait for login?)
        // Let's load when config becomes visible or on button click
        refreshButton.setOnClickListener { loadClients() }

        // Fetch Remote Password
        var adminPassword = "55555" // Default
        
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                val url = "https://raw.githubusercontent.com/tonyddayvd/tv-playlist/main/playlists/admin.json?t=${System.currentTimeMillis()}"
                val jsonString = java.net.URL(url).readText()
                val json = org.json.JSONObject(jsonString)
                if (json.has("password")) {
                    adminPassword = json.getString("password")
                }
            } catch (e: Exception) {
                // Keep default if fail
            }
        }

        loginButton.setOnClickListener {
            val pwd = passwordInput.text.toString()
            if (pwd == adminPassword || pwd == "55555") { // Allow both for safety
                loginSection.visibility = View.GONE
                configSection.visibility = View.VISIBLE
                Toast.makeText(this, "Acesso Permitido", Toast.LENGTH_SHORT).show()
                loadClients() // Load clients now
            } else {
                Toast.makeText(this, "Senha Incorreta", Toast.LENGTH_SHORT).show()
            }
        }

        rotateButton.setOnClickListener {
            rotateScreen()
        }

        saveButton.setOnClickListener {
            if (selectedClientId.isNotEmpty()) {
                prefs.edit().putString("client_id", selectedClientId).apply()
                prefs.edit().putBoolean("kiosk_mode", kioskCheckBox.isChecked).apply()
                
                Toast.makeText(this, "Configuração Salva: $selectedClientId", Toast.LENGTH_SHORT).show()
                
                // Start Player
                val intent = Intent(this, MainActivity::class.java)
                intent.putExtra("FROM_ADMIN", true)
                startActivity(intent)
                finish()
            } else {
                Toast.makeText(this, "Selecione um Cliente", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.exitButton).setOnClickListener {
            Toast.makeText(this, "Abrindo Configurações do Android...", Toast.LENGTH_LONG).show()
            try { stopLockTask() } catch (e: Exception) {}
            
            try {
                // Try to open Android Settings (Best way to escape Kiosk/Launcher loop)
                startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
            } catch (e: Exception) {
                try {
                    // Fallback: Try Wi-Fi settings if general settings fail
                    startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
                } catch (e2: Exception) {
                    Toast.makeText(this, "Erro ao abrir configurações. Tentando fechar...", Toast.LENGTH_SHORT).show()
                    finishAffinity()
                    kotlin.system.exitProcess(0)
                }
            }
        }

        // --- UI FOCUS POLISH (TV BOX) ---
        val focusColor = android.graphics.Color.parseColor("#2196F3") // Blue

        fun setFocusLogic(view: View, defaultColorHex: String) {
            val defaultColor = android.graphics.Color.parseColor(defaultColorHex)
            // Set initial state (overrides XML backgroundTint)
            view.setBackgroundColor(defaultColor)
            
            view.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    v.setBackgroundColor(focusColor)
                } else {
                    v.setBackgroundColor(defaultColor)
                }
            }
        }

        // Apply to all interactive elements
        setFocusLogic(rotateButton, "#555555") // Gray (was Blue in XML)
        setFocusLogic(loginButton, "#FF9800")  // Orange
        setFocusLogic(saveButton, "#FF9800")   // Orange
        setFocusLogic(findViewById(R.id.exitButton), "#D32F2F") // Red
        setFocusLogic(refreshButton, "#FF9800") // Orange

        // Checkbox needs special handling (Transparent default)
        kioskCheckBox.setOnFocusChangeListener { v, hasFocus ->
             if (hasFocus) {
                 v.setBackgroundColor(focusColor)
             } else {
                 v.setBackgroundColor(android.graphics.Color.TRANSPARENT)
             }
        }
    }
}
