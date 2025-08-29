package com.example.lanchat.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket

class ChatServer(private val port: Int = 0, private val scope: CoroutineScope) {
    private var serverSocket: ServerSocket? = null
    private val _incoming = MutableStateFlow<List<String>>(emptyList())
    val incoming = _incoming.asStateFlow()
    val localPort: Int get() = serverSocket?.localPort ?: -1

    fun start() {
        if (serverSocket != null) return
        serverSocket = ServerSocket(port)
        val s = serverSocket!!
        scope.launch(Dispatchers.IO) {
            try {
                while (!s.isClosed) {
                    val client = s.accept()
                    handleClient(client)
                }
            } catch (_: Throwable) {
            } finally {
                runCatching { s.close() }
            }
        }
    }

    private fun handleClient(socket: Socket) = scope.launch(Dispatchers.IO) {
        socket.use { sock ->
            val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
            while (true) {
                val line = reader.readLine() ?: break
                _incoming.value = _incoming.value + line
            }
        }
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
    }
}
