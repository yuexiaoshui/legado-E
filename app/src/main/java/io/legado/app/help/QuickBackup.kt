package io.legado.app.help

import android.content.Context
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.storage.Backup
import io.legado.app.lib.webdav.WebDavFile
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 快捷备份 / 恢复
 *
 * 书架菜单和桌面快捷方式共用，省掉「设置 → 备份与恢复 → 备份」的来回点击。
 * 和自动备份的区别：手动触发不受「距上次备份 24 小时」限制，但仍会刷新
 * LocalConfig.lastBackup（避免刚备份完又被自动备份一次）。
 *
 * 未配置 WebDav 时直接提示并中止，不做“只存本地”的静默降级 ——
 * 只存到 Android/data 里的 zip 用户基本拿不到，容易误以为已经同步了。
 */
object QuickBackup {

    /** 云端最新备份比本机新多少毫秒才算“有新备份”，和启动时的自动检查保持一致 */
    private const val NEW_BACKUP_TOLERANCE = 60_000L

    /** 云端有更新的备份时返回它，否则返回 null */
    suspend fun newestCloudBackup(): WebDavFile? {
        if (!AppWebDav.isOk) return null
        val file = AppWebDav.lastBackUp().getOrNull() ?: return null
        return if (file.lastModify - LocalConfig.lastBackup > NEW_BACKUP_TOLERANCE) file else null
    }

    /**
     * 立即备份（整包：书架 + 书源 + 书签 + 规则 + 配置）并上传 WebDav。
     * 跑在 App 级作用域，界面退出/Activity 销毁也会继续完成。
     */
    fun backup(context: Context = appCtx, onFinish: ((Boolean) -> Unit)? = null) {
        if (!AppWebDav.isOk) {
            appCtx.toastOnUi(R.string.quick_backup_need_webdav)
            onFinish?.invoke(false)
            return
        }
        appCtx.toastOnUi(R.string.quick_backup_running)
        Coroutine.async {
            Backup.backupLocked(context, AppConfig.backupPath)
        }.onSuccess {
            LocalConfig.lastBackup = System.currentTimeMillis()
            appCtx.toastOnUi(
                appCtx.getString(R.string.quick_backup_done, timeText())
            )
            onFinish?.invoke(true)
        }.onError {
            appCtx.toastOnUi(
                appCtx.getString(R.string.quick_backup_failed, it.localizedMessage ?: "")
            )
            onFinish?.invoke(false)
        }
    }

    /**
     * 恢复指定的云端备份（合并式：已有的书更新、没有的书补进来，不会清空本机书架）。
     */
    fun restore(name: String, onFinish: ((Boolean) -> Unit)? = null) {
        if (!AppWebDav.isOk || name.isBlank()) {
            appCtx.toastOnUi(R.string.quick_backup_need_webdav)
            onFinish?.invoke(false)
            return
        }
        appCtx.toastOnUi(R.string.quick_restore_running)
        Coroutine.async {
            AppWebDav.restoreWebDav(name)
            LocalConfig.lastBackup = System.currentTimeMillis()
        }.onSuccess {
            appCtx.toastOnUi(R.string.quick_restore_done)
            onFinish?.invoke(true)
        }.onError {
            appCtx.toastOnUi(
                appCtx.getString(R.string.quick_restore_failed, it.localizedMessage ?: "")
            )
            onFinish?.invoke(false)
        }
    }

    fun timeText(millis: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))

}
