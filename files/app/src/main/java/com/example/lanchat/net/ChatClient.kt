package com.example.lanchat.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.PrintWriter
import java.net.InetAddress
import java.net.Socket

class ChatClient {
    suspend fun send(host: String, port: Int, text: String) = withContext(Dispatchers.IO) {
        Socket(InetAddress.getByName(host), port).use { s ->
            PrintWriter(s.getOutputStream(), true).use { out ->
                out.println(text)
            }
        }
    }
}
