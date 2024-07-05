package com.example.gpssocket

import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketException

object SocketClient {

    private val TAG = SocketClient::class.java.simpleName

    private var socket: Socket? = null

    private var outputStream: OutputStream? = null

    private var inputStream: InputStream? = null

    private var isConnected: Boolean = false

    fun connectServer(host: String, port: Int, callback: ClientCallback) {
        Thread {
            try {
                socket = Socket(host, port)
                outputStream = socket?.getOutputStream()
                inputStream = socket?.getInputStream()
                isConnected = true
                ClientThread(socket!!, callback).start()
                callback.otherMsg("Connected successfully")
            } catch (e: IOException) {
                e.printStackTrace()
                isConnected = false
                callback.otherMsg("Failed to connect: ${e.message}")
            }
        }.start()
    }

    fun closeConnect() {
        try {
            inputStream?.close()
            outputStream?.close()
            socket?.apply {
                shutdownInput()
                shutdownOutput()
                close()
            }
            isConnected = false
            Log.d(TAG, "Close connection")
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    fun sendToServer(message: String, callback: ClientCallback) {
        Thread {
            try {
                if (!isConnected) {
                    Log.e(TAG, "sendToServer: Socket is not connected")
                    callback.otherMsg("Socket is not connected")
                    return@Thread
                }
                outputStream?.write(message.toByteArray())
                outputStream?.flush()
                callback.otherMsg("toServer: $message")
            } catch (e: IOException) {
                e.printStackTrace()
                if (e is SocketException && e.message?.contains("Broken pipe") == true) {
                    // Handle broken pipe exception
                    Log.e(TAG, "Broken pipe: Server closed the connection unexpectedly")
                    isConnected = false
                    callback.otherMsg("Server closed the connection unexpectedly")
                } else {
                    // Handle other IO exceptions
                    Log.e(TAG, "Failed to send message to server")
                    isConnected = false
                    callback.otherMsg("Failed to send message: ${e.message}")
                }
            }
        }.start()
    }


    class ClientThread(private val socket: Socket, private val callback: ClientCallback) : Thread() {
        override fun run() {
            try {
                val buffer = ByteArray(1024)
                var len = 0
                var receiveStr = ""
                while (!socket.isClosed && inputStream?.read(buffer).also { len = it!! } != -1) {
                    receiveStr += String(buffer, 0, len, Charsets.UTF_8)
                    if (len < 1024) {
                        callback.receiveServerMsg(receiveStr)
                        receiveStr = ""
                    }
                }
            } catch (e: IOException) {
                e.printStackTrace()
                Log.e(TAG, e.message ?: "Unknown error")
                isConnected = false
                callback.receiveServerMsg("Error: ${e.message}")
            }
        }
    }

    fun isConnected(): Boolean {
        return isConnected
    }

    fun reconnect(host: String, port: Int, callback: ClientCallback) {
        Thread {
            while (!isConnected) {
                try {
                    socket = Socket(host, port)
                    outputStream = socket?.getOutputStream()
                    inputStream = socket?.getInputStream()
                    isConnected = true
                    ClientThread(socket!!, callback).start()
                    callback.otherMsg("Reconnected successfully")
                } catch (e: IOException) {
                    e.printStackTrace()
                    callback.otherMsg("Reconnect failed: ${e.message}")
                    Thread.sleep(5000) // Wait for 5 seconds before retrying
                }
            }
        }.start()
    }
}
