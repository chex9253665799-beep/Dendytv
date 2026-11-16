package com.swordfish.lemuroid.app.tv.phonepad

import android.content.Context
import com.swordfish.lemuroid.app.shared.library.LibraryIndexScheduler
import com.swordfish.lemuroid.lib.storage.DirectoriesManager
import timber.log.Timber
import java.io.File
import kotlin.concurrent.thread

/**
 * Бесплатные игры с открытой лицензией, встроенные в APK (assets/games).
 * При первом запуске (и после обновления набора) копируются во внутреннюю папку игр.
 * Список авторов и лицензий — assets/games/LICENSES.txt.
 */
object BundledGames {
    private const val PREFS = "dendy_bundled_games"

    fun install(context: Context) {
        val app = context.applicationContext
        thread(name = "bundled-games", isDaemon = true) {
            runCatching {
                val names = app.assets.list("games")?.filter { !it.endsWith(".txt") }.orEmpty()
                if (names.isEmpty()) return@thread
                val stamp = names.sorted().joinToString("|") + "@" + app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime
                val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                if (prefs.getString("stamp", null) == stamp) return@thread
                val dir = File(DirectoriesManager(app).getInternalRomsDirectory(), "Бесплатные игры").apply { mkdirs() }
                for (n in names) {
                    app.assets.open("games/$n").use { input -> File(dir, n).outputStream().use { input.copyTo(it) } }
                }
                prefs.edit().putString("stamp", stamp).apply()
                LibraryIndexScheduler.scheduleLibrarySync(app)
                Timber.i("Бесплатные игры: скопировано ${names.size}")
            }.onFailure { Timber.w(it, "Бесплатные игры") }
        }
    }
}
