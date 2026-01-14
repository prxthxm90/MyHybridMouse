package com.example.myhybridmouse

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors
import kotlin.math.abs

class MainActivity : AppCompatActivity(), SensorEventListener {

    private val SENSITIVITY = 25.0f
    private val SMOOTHING = 0.8f
    private val WIFI_PORT = 5005 

    private var isWifiMode = false
    private var isRunning = false
    private var pcIpAddress: InetAddress? = null
    private var udpSocket: DatagramSocket? = null

    private var bluetoothHidDevice: BluetoothHidDevice? = null
    private var hostDevice: BluetoothDevice? = null
    private val MOUSE_ID = byteArrayOf(
        0x05, 0x01, 0x09, 0x02, 0xA1.toByte(), 0x01, 0x09, 0x01, 0xA1.toByte(), 0x00,
        0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01, 0x95.toByte(), 0x03,
        0x75, 0x01, 0x81.toByte(), 0x02, 0x95.toByte(), 0x01, 0x75, 0x05, 0x81.toByte(),
        0x03, 0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x15, 0x81.toByte(), 0x25, 0x7F,
        0x75, 0x08, 0x95.toByte(), 0x02, 0x81.toByte(), 0x06, 0xC0.toByte(), 0xC0.toByte()
    )

    private lateinit var sensorManager: SensorManager
    private var gyroscope: Sensor? = null
    private var lastX = 0f
    private var lastY = 0f
    private lateinit var statusText: TextView
    private lateinit var ipInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        ipInput = findViewById(R.id.ipInput)
        val radioGroup = findViewById<RadioGroup>(R.id.modeGroup)
        val connectBtn = findViewById<Button>(R.id.connectBtn)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        if (Build.VERSION.SDK_INT >= 31) {
            ActivityCompat.requestPermissions(this, 
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE), 1)
        }

        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.rbWifi) {
                isWifiMode = true
                ipInput.visibility = View.VISIBLE
            } else {
                isWifiMode = false
                ipInput.visibility = View.GONE
            }
        }

        connectBtn.setOnClickListener {
            if (isWifiMode) startWifiMode() else startBluetoothMode()
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || !isRunning) return
        val rawX = event.values[2] * SENSITIVITY
        val rawY = event.values[0] * SENSITIVITY
        lastX = (lastX * SMOOTHING) + (rawX * (1 - SMOOTHING))
        lastY = (lastY * SMOOTHING) + (rawY * (1 - SMOOTHING))
        var dx = lastX.toInt()
        var dy = lastY.toInt()
        if (abs(dx) < 2) dx = 0
        if (abs(dy) < 2) dy = 0
        if (dx != 0 || dy != 0) {
            if (isWifiMode) sendWifiPacket("MOVE:$dx,$dy")
            else sendBluetoothPacket(dx, dy)
        }
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (!isRunning) return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (isWifiMode) sendWifiPacket("CLICK:LEFT") else sendBluetoothClick(left = true)
                return true
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (isWifiMode) sendWifiPacket("CLICK:RIGHT") else sendBluetoothClick(right = true)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (!isRunning) return super.onKeyUp(keyCode, event)
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (!isWifiMode) sendBluetoothClick(left = false)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun startWifiMode() {
        val ipString = ipInput.text.toString()
        Thread {
            try {
                pcIpAddress = InetAddress.getByName(ipString)
                udpSocket = DatagramSocket()
                isRunning = true
                runOnUiThread { 
                    statusText.text = "Sending to $ipString"
                    sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_GAME)
                }
            } catch (e: Exception) { runOnUiThread { statusText.text = "Invalid IP" } }
        }.start()
    }

    private fun sendWifiPacket(msg: String) {
        Thread {
            try {
                val data = msg.toByteArray()
                val packet = DatagramPacket(data, data.size, pcIpAddress, WIFI_PORT)
                udpSocket?.send(packet)
            } catch (e: Exception) {}
        }.start()
    }

    @SuppressLint("MissingPermission")
    private fun startBluetoothMode() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) return
        adapter.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
                if (profile == BluetoothProfile.HID_DEVICE) {
                    bluetoothHidDevice = proxy as BluetoothHidDevice
                    val sdp = BluetoothHidDeviceAppSdpSettings("Hybrid Mouse", "Pro", "Mouse", 0xC0, MOUSE_ID)
                    bluetoothHidDevice?.registerApp(sdp, null, null, Executors.newCachedThreadPool(), object : BluetoothHidDevice.Callback() {
                        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
                            if (state == BluetoothProfile.STATE_CONNECTED) {
                                hostDevice = device
                                isRunning = true
                                runOnUiThread { 
                                    statusText.text = "BT Connected!"
                                    sensorManager.registerListener(this@MainActivity, gyroscope, SensorManager.SENSOR_DELAY_GAME)
                                }
                            }
                        }
                    })
                }
            }
            override fun onServiceDisconnected(profile: Int) {}
        }, BluetoothProfile.HID_DEVICE)
    }

    @SuppressLint("MissingPermission")
    private fun sendBluetoothPacket(dx: Int, dy: Int) {
        if (hostDevice == null) return
        bluetoothHidDevice?.sendReport(hostDevice, 0, byteArrayOf(0, dx.toByte(), dy.toByte()))
    }

    @SuppressLint("MissingPermission")
    private fun sendBluetoothClick(left: Boolean = false, right: Boolean = false) {
        if (hostDevice == null) return
        var btn = 0
        if (left) btn = btn or 1
        if (right) btn = btn or 2
        bluetoothHidDevice?.sendReport(hostDevice, 0, byteArrayOf(btn.toByte(), 0, 0))
    }
}
