package com.example.gpssocket

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.RingtoneManager
import android.os.AsyncTask
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.preference.PreferenceManager
import android.text.Spannable
import android.text.SpannableString
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.IOException
import java.net.InetAddress
import java.text.DecimalFormat
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.Timer
import java.util.TimerTask
import kotlin.math.abs

class MainActivity : AppCompatActivity(), ClientCallback {

    private lateinit var mapView: MapView
    private val TAG = MainActivity::class.java.simpleName
    private val REQUEST_PERMISSIONS_CODE: Int = 1
    private lateinit var locationManager: LocationManager
    private lateinit var myLocationNewOverlay: MyLocationNewOverlay
    private var timer: Timer? = null
    private var connectSocket = false
    private var lastlocation : Location? = null
    private var updateInterval: Long = 1000
    private lateinit var latitudeValue: TextView
    private lateinit var longitudeValue: TextView
    private lateinit var buttonConnect: Button
    private lateinit var etIpAddress: TextView
    private lateinit var geoCoderAddress: TextView
    private val callback: ClientCallback = this
    private var statusMsg = true


    private val locationListener: LocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val latitude = location.latitude
            val longitude = location.longitude
            latitudeValue.text = getFormattedCoordinate(latitude, "N")
            longitudeValue.text = getFormattedCoordinate(longitude, "E")

            locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            myLocationNewOverlay.runOnFirstFix {
                runOnUiThread {
                    val myLocation = myLocationNewOverlay.myLocation
                    if (myLocation != null) {
                        mapView.controller.animateTo(myLocation, 20.0, 1L)
                    }
                }
            }
            getAddressFromCoordinates(latitude, longitude)
            lastlocation = location // Cập nhật lastlocation với vị trí mới
        }

        override fun onProviderDisabled(provider: String) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onStatusChanged(provider: String, status: Int, extras: Bundle) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, getPreferences(MODE_PRIVATE))
        setContentView(R.layout.activity_main)

        mapView = findViewById(R.id.map)
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.zoomController.setVisibility(CustomZoomButtonsController.Visibility.ALWAYS)
        mapView.setMultiTouchControls(true)

        this.myLocationNewOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this), mapView)
        this.myLocationNewOverlay.enableMyLocation()
        mapView.overlays.add(this.myLocationNewOverlay)

        etIpAddress = findViewById(R.id.et_ip_address)
        latitudeValue = findViewById(R.id.latitude_value)
        longitudeValue = findViewById(R.id.longitude_value)
        geoCoderAddress = findViewById(R.id.address)

        buttonConnect = findViewById(R.id.btn_connect_service)
        buttonConnect.setOnClickListener {
            if (!connectSocket) {
                val address = etIpAddress.text.toString()
                if (address.isEmpty()) {
                    Toast.makeText(this, "Please enter the IP address", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }

                val (host, port) = parseAddress(address)
                PingTask(host, port).execute()
            } else {
                // Nếu đang kết nối, đóng kết nối
                SocketClient.closeConnect() // Đóng kết nối
                //MqttClientManager.disconnect()
                connectSocket = false // Cập nhật trạng thái kết nối
                // Cập nhật giao diện
                buttonConnect.text = "Connect"
                buttonConnect.setBackgroundColor(
                    ContextCompat.getColor(
                        this,
                        R.color.normal
                    )
                )
            }
        }

        requestPermissionsIfNecessary(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        )
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }

    @SuppressLint("ServiceCast")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if(ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED)
        {
            ActivityCompat.requestPermissions(
                this,
                arrayOf<String>(
                    Manifest.permission.ACCESS_FINE_LOCATION
                ),
                REQUEST_PERMISSIONS_CODE
            )
        } else {
            locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            myLocationNewOverlay.runOnFirstFix {
                runOnUiThread {
                    val myLocation = myLocationNewOverlay.myLocation
                    if (myLocation != null) {
                        mapView.controller.animateTo(myLocation, 20.0, 1L)
                    }
                }
            }
            startLocationUpdates()
        }
    }

    private fun requestPermissionsIfNecessary(permissions: Array<String>) {
        val permissionsToRequest = ArrayList<String>()
        for (permission in permissions) {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    permission
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(permission)
            }
        }
        if (permissionsToRequest.size > 0) {
            ActivityCompat.requestPermissions(
                this,
                permissionsToRequest.toTypedArray<String>(),
                REQUEST_PERMISSIONS_CODE
            )
        }
    }

    private fun startLocationUpdates() {

        if (ActivityCompat.checkSelfPermission(
                this,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                this,
                android.Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION),
                REQUEST_PERMISSIONS_CODE
            )
            return
        }

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            1000, // 1 second
            0f,
            locationListener
        )

        timer = Timer()
        timer?.schedule(object : TimerTask() {
            // Lấy thời gian hiện tại bằng LocalDateTime
            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this@MainActivity)
            var count_check = 0
            var count = sharedPreferences.getInt("Packagecount", 0)
            @RequiresApi(Build.VERSION_CODES.O)
            override fun run() {
                count_check += 1
                println("time$updateInterval")
                val currentDateTime = LocalDateTime.now()
                val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
                val formattedDateTime = currentDateTime.format(formatter)

                if (ActivityCompat.checkSelfPermission(
                        this@MainActivity,
                        android.Manifest.permission.ACCESS_FINE_LOCATION
                    ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                        this@MainActivity,
                        android.Manifest.permission.ACCESS_COARSE_LOCATION
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    Log.e(TAG, "Location permission not granted.")
                    return
                }

                val lastKnownLocation: Location? = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                if (lastKnownLocation == null) {
                    Log.e(TAG, "Last known location is null.")
                    return
                }

                lastKnownLocation.let {
                    val latitude = it.latitude
                    val longitude = it.longitude
                    val speed = it.speed
                    count += 1

                    val editor = sharedPreferences.edit()
                    editor.putInt("Package count", count)
                    editor.apply()

                    val locationData = mapOf(
                        "latitude" to latitude,
                        "longitude" to longitude,
                        "speed" to speed,
                        "timestamp" to formattedDateTime,
                    )

                    val jsonLocationData = JSONObject(locationData).toString()

                    Log.d("Check",jsonLocationData)
                    runOnUiThread {
                        latitudeValue.text = getFormattedCoordinate(latitude, "N")
                        longitudeValue.text = getFormattedCoordinate(longitude, "E")
                    }
                    if (connectSocket) {
                        SocketClient.sendToServer(jsonLocationData, callback) // Pass the callback here
                        getAddressFromCoordinates(latitude, longitude) // Update address
                    }
                }
            }
        }, 0, updateInterval ) // Delay 0ms, repeat every 1000ms (1 second)
    }


    private fun formatLocation(coordinate: Double): String {
        val degrees = coordinate.toInt()
        val minutesFull = abs((coordinate - degrees) * 60)
        val minutes = minutesFull.toInt()
        val secondsFull = (minutesFull - minutes) * 60
        val seconds = String.format("%.3f", secondsFull)
        return "$degrees°${minutes}'${seconds}\""
    }
    private fun getFormattedCoordinate(coordinate: Double, direction: String): SpannableString {
        // Format the coordinate to a maximum of 8 decimal places
        val decimalFormat = DecimalFormat("#.########")
        val coordinateDoubleString = decimalFormat.format(coordinate)
        val coordinateDMSString = "${formatLocation(coordinate)} $direction"
        val formattedString = SpannableString("$coordinateDoubleString ($coordinateDMSString)")

        // Set the style for the double value
        formattedString.setSpan(StyleSpan(Typeface.BOLD), 0, coordinateDoubleString.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        // Set the size for the DMS value
        formattedString.setSpan(RelativeSizeSpan(0.8f), coordinateDoubleString.length + 1, formattedString.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

        return formattedString
    }

    private fun parseAddress(address: String): Pair<String, Int> {
        val url = address.removePrefix("tcp://")
        val parts = url.split(":")
        val host = parts[0]
        val port = parts[1].toInt()
        return Pair(host, port)
    }

    private inner class PingTask(private val host: String, private val port: Int) :
        AsyncTask<Void, Void, Boolean>() {
        override fun doInBackground(vararg params: Void?): Boolean {
            return try {
                val address = InetAddress.getByName(host)
                address.isReachable(5000) // Ping timeout of 5 seconds
            } catch (e: IOException) {
                false
            }
        }

        override fun onPostExecute(result: Boolean) {
            if (result) {
                SocketClient.connectServer(host, port, object : ClientCallback {
                    override fun receiveServerMsg(msg: String) {
                        runOnUiThread {
                            checkStatusMsg(msg)
                        }
                    }

                    override fun otherMsg(msg: String) {
                        runOnUiThread {
                            if (msg.startsWith("Failed to connect")) {
                                showReconnectDialog("Unable to reach server. Please check the IP address and try again.", host, port)
                                playAlertSound()
                            } else if (msg.startsWith("Connected successfully")) {
                                connectSocket = true
                                showMsg("Connected to the service")
                                buttonConnect.text = "Disconnect"
                                buttonConnect.setBackgroundColor(
                                    getResources().getColor(
                                        R.color.connecting
                                    )
                                )
                                startReconnectTimer() // Start timer to check connection status
                            } else if (msg.startsWith("Socket is not connected")) {
                                connectSocket = false
                                showReconnectDialog("Connection lost. Do you want to reconnect?", host, port)
                                showMsg("Disconnected from service")
                                buttonConnect.text = "Connect"
                                buttonConnect.setBackgroundColor(
                                    getResources().getColor(
                                        R.color.normal
                                    )
                                )
                            }
                        }
                    }
                })
            } else {
                showAlertDialog("Unable to reach server. Please check the IP address and try again.")
                playAlertSound()
            }
        }
    }

    private fun checkStatusMsg(msg: String) {
        if (msg == "false") {
            statusMsg = false
            val color = ContextCompat.getColor(this, R.color.missing_msg)
            buttonConnect.setBackgroundColor(color)
        }
    }

    private fun showReconnectDialog(message: String, host: String, port: Int) {
        val builder = AlertDialog.Builder(this@MainActivity)
        builder.setTitle("Connection Error")
        builder.setMessage(message)
        builder.setPositiveButton("Change IP", null)
        builder.setNegativeButton("Reconnect") { _, _ ->
            SocketClient.reconnect(host, port, object : ClientCallback {
                override fun receiveServerMsg(msg: String) {
                    runOnUiThread {
                        checkStatusMsg(msg)
                    }
                }

                override fun otherMsg(msg: String) {
                    runOnUiThread {
                        if (msg.startsWith("Unable to reconnect after")) {
                            showAlertDialog("Unable to reconnect after multiple attempts. Please check the server status.")
                            playAlertSound()
                            resetConnectButton()
                        } else if (msg.startsWith("Reconnected successfully")) {
                            connectSocket = true
                            showMsg("Connected to the service")
                            buttonConnect.text = "Disconnect"
                            buttonConnect.setBackgroundColor(
                                ContextCompat.getColor(this@MainActivity, R.color.connecting)
                            )
                            showAlertDialog("Reconnected successfully.")
                        }
                    }
                }
            })
        }
        builder.show()
    }

    private fun showAlertDialog(message: String) {
        val builder = AlertDialog.Builder(this@MainActivity)
        builder.setTitle("Connection Error")
        builder.setMessage(message)
        builder.setPositiveButton("OK", null)
        builder.show()
    }

    private fun playAlertSound() {
        val notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val ringtone = RingtoneManager.getRingtone(this@MainActivity, notification)
        ringtone.play()
    }

    private fun resetConnectButton() {
        connectSocket = false
        buttonConnect.text = "Connect"
        buttonConnect.setBackgroundColor(
            ContextCompat.getColor(this, R.color.normal)
        )
    }

    private fun showMsg(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun startReconnectTimer() {
        val reconnectHandler = Handler(Looper.getMainLooper())
        val reconnectRunnable = object : Runnable {
            override fun run() {
                if (!SocketClient.isConnected()) {
                    reconnectHandler.removeCallbacks(this)
                    val address = etIpAddress.text.toString()
                    if (address.isNotEmpty()) {
                        val (host, port) = parseAddress(address)
                        PingTask(host, port).execute()
                    }
                } else {
                    reconnectHandler.postDelayed(this, 5000) // Check every 5 seconds
                }
            }
        }
        reconnectHandler.post(reconnectRunnable)
        // Add code to show reconnect dialog if needed
        if (!SocketClient.isConnected()) {
            val address = etIpAddress.text.toString()
            if (address.isNotEmpty()) {
                val (host, port) = parseAddress(address)
                showReconnectDialog("Connection lost. Do you want to reconnect?", host, port)
            }
        }
    }

    private fun getAddressFromCoordinates(latitude: Double, longitude: Double) {
        val geocoder = Geocoder(this, Locale.getDefault())
        try {
            val addresses = geocoder.getFromLocation(latitude, longitude, 1)
            if (addresses != null) {
                if (addresses.isNotEmpty()) {
                    val address = addresses[0].getAddressLine(0)
                    runOnUiThread {
                        geoCoderAddress.text = address
                    }
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    override fun receiveServerMsg(msg: String) {
        checkStatusMsg(msg)
    }

    override fun otherMsg(msg: String) {
        Log.d(TAG, msg)
    }
}