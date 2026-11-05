package com.arcioth.wlantron

import android.net.ConnectivityManager
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.arcioth.wlantron.databinding.ActivityMainBinding
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var creds: Creds
    private val io = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        creds = Creds(this)
        creds.load()?.let { (username, password) ->
            binding.username.setText(username)
            binding.password.setText(password)
            binding.saveSwitch.isChecked = true
        }
        binding.loginButton.setOnClickListener { runPortal(signIn = true) }
        binding.checkButton.setOnClickListener { runPortal(signIn = false) }
        binding.clearButton.setOnClickListener {
            creds.clear()
            binding.username.text?.clear()
            binding.password.text?.clear()
            binding.saveSwitch.isChecked = true
            log("Saved login removed.")
        }
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private fun runPortal(signIn: Boolean) {
        val username = binding.username.text?.toString()?.trim().orEmpty()
        val password = binding.password.text?.toString().orEmpty()
        if (signIn && (username.isEmpty() || password.isEmpty())) {
            log("Username and password are both required.")
            return
        }
        if (signIn && binding.saveSwitch.isChecked) {
            creds.save(username, password)
        }
        setBusy(true)
        log(if (signIn) "Looking for Wi-Fi…" else "Checking Wi-Fi…")
        io.execute {
            val network = WifiHttp.wifiNetwork(this)
            if (network == null) {
                ui {
                    log("Wi-Fi is not connected. Join HHO_WiFi, then try again.")
                    setBusy(false)
                }
                return@execute
            }
            val hints = WifiHttp.hints(this, network)
            val cm = getSystemService(ConnectivityManager::class.java)
            cm.bindProcessToNetwork(network)
            ui { log(if (hints.isEmpty()) "Wi-Fi is up." else "Wi-Fi is up. Gateway ${hints.first()}.") }
            val http = WifiHttp(network, WifiHttp.addresses(this, network))
            try {
                if (!signIn) {
                    val state = Portal.probe(http, hints)
                    ui {
                        when (state) {
                            is Probe.Online -> noteOnline(cm, network, "This Wi-Fi can open a normal site.")
                            is Probe.Portal -> log("Captive portal is up. Tap Log in.")
                            is Probe.Down -> log(state.detail)
                        }
                        setBusy(false)
                    }
                    return@execute
                }
                val outcome = Portal.signIn(http, username, password, ::uiLog, hints)
                ui {
                    when (outcome) {
                        Outcome.AlreadyOnline -> noteOnline(cm, network, "Already signed in on Wi-Fi.")
                        Outcome.SignedIn -> noteOnline(cm, network, "Signed in on Wi-Fi.")
                        is Outcome.Failed -> log(outcome.message)
                    }
                    setBusy(false)
                }
            } catch (exc: Exception) {
                ui {
                    log(exc.message ?: "Login failed.")
                    setBusy(false)
                }
            } finally {
                cm.bindProcessToNetwork(null)
            }
        }
    }

    private fun noteOnline(cm: ConnectivityManager, network: android.net.Network, message: String) {
        cm.reportNetworkConnectivity(network, true)
        log("$message Android is rechecking Wi-Fi. If a site still fails, turn mobile data off and try it again.")
    }

    private fun uiLog(line: String) {
        ui { log(line) }
    }

    private fun log(line: String) {
        val prior = binding.logView.text?.toString().orEmpty()
        binding.logView.text = if (prior.isEmpty()) line else "$prior\n$line"
        binding.scroller.post { binding.scroller.fullScroll(View.FOCUS_DOWN) }
    }

    private fun setBusy(busy: Boolean) {
        binding.loginButton.isEnabled = !busy
        binding.checkButton.isEnabled = !busy
        binding.clearButton.isEnabled = !busy
    }

    private fun ui(block: () -> Unit) {
        if (isDestroyed) return
        runOnUiThread {
            if (!isDestroyed) block()
        }
    }
}
