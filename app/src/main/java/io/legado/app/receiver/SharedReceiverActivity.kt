package io.legado.app.receiver

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import io.legado.app.help.QuickBackup
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.main.MainActivity
import io.legado.app.utils.startActivity
import splitties.init.appCtx

class SharedReceiverActivity : AppCompatActivity() {

    private val receivingType = "text/plain"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 备份要跑几秒，期间留着这个透明界面，避免进程被系统回收
        if (initIntent()) {
            finish()
        }
    }

    @SuppressLint("ObsoleteSdkInt")
    private fun initIntent(): Boolean {
        when {
            intent.action == Intent.ACTION_SEND && intent.type == receivingType -> {
                intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
                    dispose(it)
                }
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && intent.action == Intent.ACTION_PROCESS_TEXT
                    && intent.type == receivingType -> {
                intent.getStringExtra(Intent.EXTRA_PROCESS_TEXT)?.let {
                    dispose(it)
                }
            }
            intent.getStringExtra("action") == "readAloud" -> {
                MediaButtonReceiver.readAloud(appCtx, false)
            }
            // 桌面快捷方式：立即备份到 WebDav，备份结束（无论成败）再关掉自己
            intent.getStringExtra("action") == "quickBackup" -> {
                QuickBackup.backup(appCtx) { finish() }
                return false
            }
        }
        return true
    }

    private fun dispose(text: String) {
        if (text.isBlank()) {
            return
        }
        val urls = text.split("\\s".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        val result = StringBuilder()
        for (url in urls) {
            if (url.matches("http.+".toRegex()))
                result.append("\n").append(url.trim { it <= ' ' })
        }
        if (result.length > 1) {
            startActivity<MainActivity>()
        } else {
            SearchActivity.start(this, text)
        }
    }
}