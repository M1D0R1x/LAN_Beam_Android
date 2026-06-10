package com.example.lanbeam

import fi.iki.elonen.NanoWSD
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

class LanBeamWebSocket(port: Int) : NanoWSD(port) {

    private val connections = CopyOnWriteArrayList<WsConnection>()

    override fun openWebSocket(handshake: IHTTPSession): WebSocket {
        return WsConnection(handshake)
    }

    fun broadcastFilesChanged() {
        broadcast("""{"type":"files_changed"}""")
    }

    fun broadcastUploadComplete(fileName: String) {
        val escaped = fileName.replace("\"", "\\\"")
        broadcast("""{"type":"upload_complete","file":"$escaped"}""")
    }

    fun broadcastFileDeleted(fileName: String) {
        val escaped = fileName.replace("\"", "\\\"")
        broadcast("""{"type":"file_deleted","file":"$escaped"}""")
    }

    private fun broadcast(message: String) {
        val dead = mutableListOf<WsConnection>()
        for (conn in connections) {
            try {
                conn.send(message)
            } catch (e: IOException) {
                dead.add(conn)
            }
        }
        connections.removeAll(dead.toSet())
    }

    inner class WsConnection(handshake: IHTTPSession) : WebSocket(handshake) {
        override fun onOpen() {
            connections.add(this)
        }

        override fun onClose(code: WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
            connections.remove(this)
        }

        override fun onMessage(message: WebSocketFrame?) {
            // Client messages not needed for now — server pushes only
        }

        override fun onPong(pong: WebSocketFrame?) {}

        override fun onException(exception: IOException?) {
            connections.remove(this)
        }
    }
}
