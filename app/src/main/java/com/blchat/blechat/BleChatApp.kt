package com.blchat.blechat

import android.app.Application
import java.io.File

/**
 * 应用级单例：持有 BleChatManager，供 Activity 与前台服务共用。
 * 同时安装全局未捕获异常处理器，把崩溃堆栈写入 crash.log 便于排查。
 */
class BleChatApp : Application() {

    lateinit var manager: BleChatManager
        private set

    override fun onCreate() {
        super.onCreate()
        manager = BleChatManager(this)
        installCrashLogger()
    }

    private fun installCrashLogger() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val dir = getExternalFilesDir(null) ?: filesDir
                val f = File(dir, "crash.log")
                f.writeText(
                    "time=${System.currentTimeMillis()}\n" +
                        "thread=${t?.name}\n" +
                        "throwable=$e\n\n" +
                        (e?.stackTraceToString() ?: "")
                )
            } catch (_: Throwable) {
            }
            prev?.uncaughtException(t, e)
        }
    }
}
