package io.legado.app.help.book

import io.legado.app.data.appDb
import io.legado.app.data.entities.BookGroup
import io.legado.app.constant.AppLog
import io.legado.app.help.config.AppConfig

/**
 * 「最近阅读」分组：书源阅读的书自动进入该分组。
 * 普通分组语义：进度同步、备份恢复均为原生行为。
 * 注意：不要重命名该分组，flowAll() 的排除逻辑按组名匹配。
 */
object RecentGroup {

    const val NAME = "最近阅读"
    const val KEEP_COUNT_DEFAULT = 1000

    /** 书架标签固定顺序：「全部」第一，「最近阅读」第二，其余自定义分组按各自 order 排在后面 */
    private const val ORDER_ALL = -1000
    private const val ORDER_RECENT = -999

    /** 分组不存在则创建，返回 groupId（需在 IO 线程调用） */
    fun ensure(): Long {
        // 「全部」的 order 在旧库里可能被分组管理重新编号过，这里拉回到最小，保证它永远第一
        appDb.bookGroupDao.getByID(BookGroup.IdAll)?.let { all ->
            if (all.order > ORDER_ALL) {
                all.order = ORDER_ALL
                appDb.bookGroupDao.update(all)
            }
        }
        appDb.bookGroupDao.getByName(NAME)?.let { existing ->
            if (existing.order != ORDER_RECENT) {
                existing.order = ORDER_RECENT
                appDb.bookGroupDao.update(existing)
            }
            return existing.groupId
        }
        val group = BookGroup(
            groupId = appDb.bookGroupDao.getUnusedId(),
            groupName = NAME,
            order = ORDER_RECENT,
            show = true,
        )
        appDb.bookGroupDao.insert(group)
        return group.groupId
    }

    fun groupId(): Long? = appDb.bookGroupDao.getByName(NAME)?.groupId

    /** 判断某个 groupId 是否为「最近阅读」分组 */
    fun isRecentGroup(groupId: Long): Boolean = groupId() == groupId

    /** 按数量清理：只保留最近 keepCount 本（按最后阅读时间），超出部分删除记录 */
    fun cleanup() {
        val id = groupId() ?: return
        val keepCount = AppConfig.recentKeepCount
        val books = appDb.bookDao.getBooksByGroup(id)
            .sortedByDescending { it.durChapterTime }
        if (books.size <= keepCount) return
        val stale = books.drop(keepCount)
        appDb.bookDao.delete(*stale.toTypedArray())
        AppLog.put("最近阅读清理：移除 ${stale.size} 本")
    }
}
