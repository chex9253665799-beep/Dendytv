package com.swordfish.lemuroid.app.tv.phonepad

import android.app.Instrumentation
import android.content.Context
import android.util.Base64
import android.view.KeyEvent
import com.swordfish.lemuroid.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Телефон как джойстик. Работает в основном процессе приложения.
 *
 *  GET /        — страница-пульт для телефона (res/raw/phonepad.html)
 *  GET /ws      — WebSocket от телефона: "k <код> <1|0>", "d <x> <y>", "a <0|1> <x> <y>", "menu"
 *  GET /game    — внутреннее подключение процесса игры (:game), получает строки "<игрок> <команда>"
 *
 * Пока игра не запущена, кнопки телефона управляют меню Dendy TV (стрелки, ОК, назад).
 */
object PhonePadServer {
    const val FIRST_PORT = 8080
    const val MAX_PLAYERS = 2

    class Player(val socket: Socket, val out: OutputStream, val name: String) {
        @Volatile var slot = 0
    }

    data class State(val port: Int = 0, val players: List<String> = emptyList(), val system: String = "")

    private val stateFlow = MutableStateFlow(State())
    val state: StateFlow<State> = stateFlow

    private val players = CopyOnWriteArrayList<Player>()
    private val games = CopyOnWriteArrayList<OutputStream>()
    private val keyInjector = Executors.newSingleThreadExecutor()
    private var html: ByteArray = ByteArray(0)
    @Volatile private var started = false
    @Volatile private var system = ""
    private var lastDpad = IntArray(2)

    @Synchronized
    fun start(context: Context) {
        if (started) return
        html = context.resources.openRawResource(R.raw.phonepad).use { it.readBytes() }
        for (p in FIRST_PORT until FIRST_PORT + 10) {
            val server = runCatching { ServerSocket(p) }.getOrNull() ?: continue
            started = true
            stateFlow.value = stateFlow.value.copy(port = p)
            thread(name = "phonepad-accept", isDaemon = true) {
                while (true) {
                    val s = runCatching { server.accept() }.getOrNull() ?: continue
                    thread(name = "phonepad-conn", isDaemon = true) { runCatching { handle(s) }.onFailure { Timber.w(it) }; runCatching { s.close() } }
                }
            }
            Timber.i("PhonePad: порт $p")
            return
        }
    }

    /** Адрес телевизора в домашней сети (Wi-Fi или кабель). */
    fun address(): String? {
        val all =
            runCatching { NetworkInterface.getNetworkInterfaces().toList() }.getOrDefault(emptyList())
                .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                .sortedBy { if (it.name.startsWith("wlan")) 0 else if (it.name.startsWith("eth")) 1 else 2 }
        for (i in all) {
            val a = i.inetAddresses.toList().firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            if (a != null) return a.hostAddress
        }
        return null
    }

    fun url(): String? {
        val ip = address() ?: return null
        val port = state.value.port.takeIf { it > 0 } ?: return null
        return "http://$ip:$port"
    }

    // ---------- соединения ----------

    private fun handle(s: Socket) {
        s.tcpNoDelay = true
        val input = s.getInputStream()
        val out = s.getOutputStream()
        val requestLine = readLine(input) ?: return
        val headers = HashMap<String, String>()
        while (true) {
            val l = readLine(input) ?: return
            if (l.isEmpty()) break
            val i = l.indexOf(':')
            if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
        }
        val path = requestLine.split(' ').getOrNull(1) ?: "/"
        when {
            path.startsWith("/game") -> gameConnection(input, out)
            path.startsWith("/ws") && headers["upgrade"].equals("websocket", true) ->
                phoneConnection(s, input, out, headers["sec-websocket-key"] ?: return, headers["user-agent"] ?: "")
            else -> {
                val body = if (path == "/" || path.startsWith("/?")) html else "Not found".toByteArray()
                val code = if (path == "/" || path.startsWith("/?")) "200 OK" else "404 Not Found"
                out.write(
                    ("HTTP/1.1 $code\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\n" +
                        "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray(),
                )
                out.write(body)
                out.flush()
            }
        }
    }

    private fun gameConnection(
        input: InputStream,
        out: OutputStream,
    ) {
        out.write("DENDY\n".toByteArray()); out.flush()
        games += out
        try {
            val r = BufferedReader(InputStreamReader(input))
            while (true) {
                val l = r.readLine() ?: break
                if (l.startsWith("sys ")) {
                    system = l.substring(4).trim()
                    stateFlow.value = stateFlow.value.copy(system = system)
                    broadcast("sys $system")
                }
            }
        } finally {
            games -= out
            if (games.isEmpty()) {
                system = ""
                stateFlow.value = stateFlow.value.copy(system = "")
                broadcast("sys menu")
            }
        }
    }

    private fun phoneConnection(
        s: Socket,
        input: InputStream,
        out: OutputStream,
        key: String,
        ua: String,
    ) {
        val accept =
            Base64.encodeToString(
                MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()),
                Base64.NO_WRAP,
            )
        out.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
        out.flush()

        if (players.size >= MAX_PLAYERS) {
            sendText(out, "full")
            return
        }
        val p = Player(s, out, deviceName(ua))
        synchronized(players) {
            val used = players.map { it.slot }.toSet()
            p.slot = (0 until MAX_PLAYERS).first { it !in used }
            players += p
        }
        publishPlayers()
        sendText(out, "hello ${p.slot + 1} ${system.ifEmpty { "menu" }}")
        try {
            while (true) {
                val msg = readFrame(input, out) ?: break
                onPhoneMessage(p, msg)
            }
        } finally {
            players -= p
            publishPlayers()
            // отпустить все кнопки этого игрока
            toGame("${p.slot} reset")
        }
    }

    private fun publishPlayers() {
        stateFlow.value = stateFlow.value.copy(players = players.sortedBy { it.slot }.map { "Игрок ${it.slot + 1} · ${it.name}" })
    }

    private fun deviceName(ua: String): String =
        when {
            ua.contains("iPhone") -> "iPhone"
            ua.contains("iPad") -> "iPad"
            ua.contains("Android") -> Regex("Android [^;]*; ([^)]+)\\)").find(ua)?.groupValues?.get(1)?.substringBefore(" Build")?.trim() ?: "Android"
            else -> "Телефон"
        }

    private fun onPhoneMessage(
        p: Player,
        msg: String,
    ) {
        if (games.isNotEmpty()) {
            toGame("${p.slot} $msg")
            return
        }
        // игра не запущена — управляем меню
        val parts = msg.split(' ')
        when (parts[0]) {
            "d" -> {
                val x = parts.getOrNull(1)?.toFloatOrNull()?.toInt() ?: 0
                val y = parts.getOrNull(2)?.toFloatOrNull()?.toInt() ?: 0
                if (x != lastDpad[0] && x != 0) inject(if (x > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT)
                if (y != lastDpad[1] && y != 0) inject(if (y > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP)
                lastDpad = intArrayOf(x, y)
            }
            "k" -> {
                if (parts.getOrNull(2) != "1") return
                when (parts.getOrNull(1)?.toIntOrNull()) {
                    KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_START -> inject(KeyEvent.KEYCODE_DPAD_CENTER)
                    KeyEvent.KEYCODE_BUTTON_B -> inject(KeyEvent.KEYCODE_BACK)
                }
            }
            "menu" -> inject(KeyEvent.KEYCODE_BACK)
        }
    }

    private fun inject(code: Int) {
        keyInjector.execute { runCatching { Instrumentation().sendKeyDownUpSync(code) } }
    }

    private fun toGame(line: String) {
        val b = (line + "\n").toByteArray()
        for (g in games) runCatching { synchronized(g) { g.write(b); g.flush() } }.onFailure { games -= g }
    }

    private fun broadcast(text: String) {
        for (p in players) runCatching { sendText(p.out, text) }
    }

    // ---------- WebSocket ----------

    private fun sendText(
        out: OutputStream,
        text: String,
    ) {
        val data = text.toByteArray()
        synchronized(out) {
            out.write(0x81)
            when {
                data.size < 126 -> out.write(data.size)
                else -> { out.write(126); out.write(data.size shr 8); out.write(data.size and 0xFF) }
            }
            out.write(data)
            out.flush()
        }
    }

    private fun readFrame(
        input: InputStream,
        out: OutputStream,
    ): String? {
        while (true) {
            val b0 = input.read(); if (b0 < 0) return null
            val b1 = input.read(); if (b1 < 0) return null
            val opcode = b0 and 0x0F
            var len = (b1 and 0x7F).toLong()
            if (len == 126L) len = ((input.read() shl 8) or input.read()).toLong()
            else if (len == 127L) { len = 0; repeat(8) { len = (len shl 8) or input.read().toLong() } }
            if (len > 65536) return null
            val mask = if (b1 and 0x80 != 0) ByteArray(4).also { readFully(input, it) } else null
            val payload = ByteArray(len.toInt()).also { readFully(input, it) }
            if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            when (opcode) {
                0x8 -> return null
                0x9 -> synchronized(out) { out.write(0x8A); out.write(payload.size); out.write(payload); out.flush() }
                0x1 -> return String(payload)
            }
        }
    }

    private fun readFully(
        input: InputStream,
        buf: ByteArray,
    ) {
        var o = 0
        while (o < buf.size) {
            val n = input.read(buf, o, buf.size - o)
            if (n < 0) throw java.io.EOFException()
            o += n
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 8192) return null
        }
    }
}
