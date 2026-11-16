package com.swordfish.lemuroid.app.tv.phonepad

import android.app.Instrumentation
import android.view.KeyEvent
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.swordfish.lemuroid.lib.library.SystemID
import com.swordfish.libretrodroid.GLRetroView
import timber.log.Timber
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Работает в процессе игры (:game). Подключается к PhonePadServer основного процесса
 * и передаёт нажатия с телефонов в эмулятор. Пока открыто меню игры (экран на паузе),
 * стрелки и кнопки телефона управляют меню.
 */
class PhonePadClient private constructor(
    private val view: GLRetroView,
    private val system: String,
) : DefaultLifecycleObserver {
    @Volatile private var running = true
    @Volatile private var inGame = true
    @Volatile private var socket: Socket? = null
    private val injector = Executors.newSingleThreadExecutor()
    private val lastDpad = Array(PhonePadServer.MAX_PLAYERS) { intArrayOf(0, 0) }
    private val pressed = Array(PhonePadServer.MAX_PLAYERS) { HashSet<Int>() }

    init {
        thread(name = "phonepad-game", isDaemon = true) { loop() }
    }

    private fun loop() {
        while (running) {
            runCatching { connectOnce() }.onFailure { Timber.d(it, "PhonePad: нет связи") }
            if (running) Thread.sleep(2000)
        }
    }

    private fun connectOnce() {
        for (p in PhonePadServer.FIRST_PORT until PhonePadServer.FIRST_PORT + 10) {
            val s = Socket()
            try {
                s.connect(InetSocketAddress("127.0.0.1", p), 300)
                s.tcpNoDelay = true
                s.getOutputStream().apply { write("GET /game HTTP/1.1\r\n\r\nsys $system\n".toByteArray()); flush() }
                val r = BufferedReader(InputStreamReader(s.getInputStream()))
                if (r.readLine() != "DENDY") { s.close(); continue }
                socket = s
                while (running) {
                    val l = r.readLine() ?: break
                    view.post { runCatching { onLine(l) } }
                }
                return
            } catch (e: Exception) {
                runCatching { s.close() }
            }
        }
    }

    private fun onLine(line: String) {
        val p = line.split(' ')
        val port = p.getOrNull(0)?.toIntOrNull() ?: return
        if (port !in 0 until PhonePadServer.MAX_PLAYERS) return
        when (p.getOrNull(1)) {
            "k" -> {
                val code = p.getOrNull(2)?.toIntOrNull() ?: return
                val down = p.getOrNull(3) == "1"
                if (!inGame) {
                    if (down) menuKey(code)
                    return
                }
                if (down) pressed[port] += code else pressed[port] -= code
                view.sendKeyEvent(if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, code, port)
            }
            "d" -> {
                val x = p.getOrNull(2)?.toFloatOrNull() ?: 0f
                val y = p.getOrNull(3)?.toFloatOrNull() ?: 0f
                if (!inGame) {
                    val ix = x.toInt()
                    val iy = y.toInt()
                    if (ix != lastDpad[port][0] && ix != 0) inject(if (ix > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT)
                    if (iy != lastDpad[port][1] && iy != 0) inject(if (iy > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP)
                    lastDpad[port][0] = ix
                    lastDpad[port][1] = iy
                    return
                }
                view.sendMotionEvent(GLRetroView.MOTION_SOURCE_DPAD, x, y, port)
            }
            "a" -> {
                if (!inGame) return
                val src = if (p.getOrNull(2) == "1") GLRetroView.MOTION_SOURCE_ANALOG_RIGHT else GLRetroView.MOTION_SOURCE_ANALOG_LEFT
                view.sendMotionEvent(src, p.getOrNull(3)?.toFloatOrNull() ?: 0f, p.getOrNull(4)?.toFloatOrNull() ?: 0f, port)
            }
            "menu" -> inject(KeyEvent.KEYCODE_BACK)
            "reset" -> {
                pressed[port].toList().forEach { view.sendKeyEvent(KeyEvent.ACTION_UP, it, port) }
                pressed[port].clear()
                view.sendMotionEvent(GLRetroView.MOTION_SOURCE_DPAD, 0f, 0f, port)
                view.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_LEFT, 0f, 0f, port)
                view.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_RIGHT, 0f, 0f, port)
            }
        }
    }

    private fun menuKey(code: Int) {
        when (code) {
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_START -> inject(KeyEvent.KEYCODE_DPAD_CENTER)
            KeyEvent.KEYCODE_BUTTON_B -> inject(KeyEvent.KEYCODE_BACK)
        }
    }

    private fun inject(code: Int) {
        injector.execute { runCatching { Instrumentation().sendKeyDownUpSync(code) } }
    }

    override fun onResume(owner: LifecycleOwner) {
        inGame = true
    }

    override fun onPause(owner: LifecycleOwner) {
        inGame = false
    }

    override fun onDestroy(owner: LifecycleOwner) {
        running = false
        runCatching { socket?.close() }
        injector.shutdown()
    }

    companion object {
        fun attach(
            view: GLRetroView,
            systemId: String,
            owner: LifecycleOwner,
        ) {
            val sys =
                when (systemId) {
                    SystemID.NES.dbname -> "nes"
                    SystemID.GENESIS.dbname -> "md"
                    SystemID.PSX.dbname -> "psx"
                    else -> "nes"
                }
            owner.lifecycle.addObserver(PhonePadClient(view, sys))
        }
    }
}
