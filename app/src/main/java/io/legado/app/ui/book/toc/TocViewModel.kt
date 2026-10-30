package io.legado.app.ui.book.toc


import android.app.Application
import android.net.Uri
import androidx.lifecycle.MutableLiveData
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.http.newCallStrResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.book.getBookSource
import io.legado.app.model.ReadBook
import io.legado.app.model.localBook.LocalBook
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.FileDoc
import io.legado.app.utils.GSON
import io.legado.app.utils.createFileIfNotExist
import io.legado.app.utils.openOutputStream
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.writeText
import com.google.gson.JsonObject
import java.net.URLEncoder

class TocViewModel(application: Application) : BaseViewModel(application) {
    var bookUrl: String = ""
    var bookData = MutableLiveData<Book>()
    var chapterListCallBack: ChapterListCallBack? = null
    var bookMarkCallBack: BookmarkCallBack? = null
    var searchKey: String? = null

    fun initBook(bookUrl: String) {
        this.bookUrl = bookUrl
        execute {
            appDb.bookDao.getBook(bookUrl)?.let {
                bookData.postValue(it)
            }
        }
    }

    /** 自建书源返回的 tocUrl 是相对路径（/api/legado/toc?...），直连请求前需补全服务器地址。 */
    private fun absoluteTocUrl(book: Book): String {
        val tocUrl = book.tocUrl
        if (tocUrl.startsWith("http://") || tocUrl.startsWith("https://")) return tocUrl
        val base = book.getBookSource()?.bookSourceUrl?.trimEnd('/')
            ?: originOf(book.bookUrl)
        if (base.isEmpty()) return tocUrl
        return base.trimEnd('/') + if (tocUrl.startsWith("/")) tocUrl else "/$tocUrl"
    }

    /** 从任意绝对地址里取出 scheme://host 部分。 */
    private fun originOf(url: String): String {
        val m = Regex("^(https?://[^/?#]+)").find(url) ?: return ""
        return m.groupValues[1]
    }

    /** 把相对路径补成绝对地址（相对 toc 所在服务器）。 */
    private fun absoluteUrl(base: String, url: String): String {
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        val origin = originOf(base)
        if (origin.isEmpty()) return url
        return origin + if (url.startsWith("/")) url else "/$url"
    }

    /** 拉取服务器上该书各分章规则的匹配数（配合自建书源 API）。 */
    fun loadServerTocRules(book: Book, cb: (Result<JsonObject>) -> Unit) {
        execute {
            val tocUrl = absoluteTocUrl(book)
            val rulesUrl = tocUrl.replace("/toc?", "/toc-rules?")
            if (rulesUrl == tocUrl) {
                throw NoStackTraceException("该书目录地址不是自建书源格式")
            }
            val json = okHttpClient.newCallStrResponse {
                url(rulesUrl)
            }.body ?: throw NoStackTraceException("规则列表响应为空")
            val obj = GSON.fromJson(json, JsonObject::class.java)
            if (obj == null || !obj.has("rules")) {
                throw NoStackTraceException("服务器未返回规则列表（请确认书源为自建书源）")
            }
            cb(Result.success(obj))
        }.onError {
            cb(Result.failure(it))
        }
    }

    /**
     * 切换服务器分章规则并重新拉取目录。
     *
     * 直接请求自建书源 API 并自行解析章节，不依赖本地书源记录
     * （书源被重新导入 / 换域名后旧书 origin 找不到书源，走 WebBook 会报「书源不存在」）。
     * rule 为空或 __auto__ = 恢复自动选优。
     */
    fun switchServerTocRule(book: Book, rule: String?, cb: (Throwable?) -> Unit) {
        execute {
            val tocUrl = absoluteTocUrl(book)
            // 去掉旧的 rule 参数
            var target = tocUrl.replace(Regex("([?&])rule=[^&]*"), "$1")
                .replace(Regex("[?&]+&"), "?")
                .trimEnd('&', '?')
            if (!rule.isNullOrEmpty()) {
                target += if (target.contains("?")) "&" else "?"
                target += "rule=" + URLEncoder.encode(rule, "UTF-8")
            }
            val json = kotlin.runCatching {
                okHttpClient.newCallStrResponse { url(target) }.body
            }.getOrElse {
                throw NoStackTraceException("请求失败(${it.localizedMessage}): $target")
            } ?: throw NoStackTraceException("目录响应为空: $target")

            val obj = GSON.fromJson(json, JsonObject::class.java)
                ?: throw NoStackTraceException("目录解析失败")
            val chapterArr = obj.getAsJsonArray("chapters")
                ?: throw NoStackTraceException("目录响应缺少 chapters 字段")
            if (chapterArr.size() == 0) {
                throw NoStackTraceException("该规则没有解析出任何章节，请换一个规则")
            }
            val base = book.bookUrl
            val chapters = chapterArr.mapIndexed { i, el ->
                val o = el.asJsonObject
                val chUrl = o.get("url")?.asString ?: ""
                BookChapter(
                    url = absoluteUrl(base, chUrl),
                    title = o.get("name")?.asString ?: "第${i + 1}章",
                    baseUrl = base,
                    bookUrl = book.bookUrl,
                    index = i
                )
            }
            appDb.bookChapterDao.delByBook(book.bookUrl)
            appDb.bookChapterDao.insert(*chapters.toTypedArray())
            book.tocUrl = target
            book.latestChapterTitle = chapters.lastOrNull()?.title
            book.totalChapterNum = chapters.size
            appDb.bookDao.update(book)
            ReadBook.onChapterListUpdated(book)
            bookData.postValue(book)
        }.onSuccess {
            cb(null)
        }.onError {
            cb(it)
        }
    }

    fun upBookTocRule(book: Book, complete: (Throwable?) -> Unit) {
        execute {
            appDb.bookDao.update(book)
            LocalBook.getChapterList(book).let {
                appDb.bookChapterDao.delByBook(book.bookUrl)
                appDb.bookChapterDao.insert(*it.toTypedArray())
                appDb.bookDao.update(book)
                ReadBook.onChapterListUpdated(book)
                bookData.postValue(book)
            }
        }.onSuccess {
            complete.invoke(null)
        }.onError {
            complete.invoke(it)
        }
    }

    fun reverseToc(success: (book: Book) -> Unit) {
        execute {
            bookData.value?.apply {
                setReverseToc(!getReverseToc())
                val toc = appDb.bookChapterDao.getChapterList(bookUrl)
                val newToc = toc.reversed()
                newToc.forEachIndexed { index, bookChapter ->
                    bookChapter.index = index
                }
                appDb.bookChapterDao.insert(*newToc.toTypedArray())
            }
        }.onSuccess {
            it?.let(success)
        }
    }

    fun startChapterListSearch(newText: String?) {
        chapterListCallBack?.upChapterList(newText)
    }

    fun startBookmarkSearch(newText: String?) {
        bookMarkCallBack?.upBookmark(newText)
    }

    fun upChapterListAdapter() {
        chapterListCallBack?.upAdapter()
    }

    fun saveBookmark(treeUri: Uri) {
        execute {
            val book = bookData.value
                ?: throw NoStackTraceException(context.getString(R.string.no_book))
            val fileName = "bookmark-${book.name} ${book.author}.json"
            val doc = FileDoc.fromUri(treeUri, true)
            doc.createFileIfNotExist(fileName).writeText(
                GSON.toJson(
                    appDb.bookmarkDao.getByBook(book.name, book.author)
                )
            )
        }.onError {
            AppLog.put("导出失败\n${it.localizedMessage}", it, true)
        }.onSuccess {
            context.toastOnUi("导出成功")
        }
    }

    fun saveBookmarkMd(treeUri: Uri) {
        execute {
            val book = bookData.value
                ?: throw NoStackTraceException(context.getString(R.string.no_book))
            val fileName = "bookmark-${book.name} ${book.author}.md"
            val treeDoc = FileDoc.fromUri(treeUri, true)
            val fileDoc = treeDoc.createFileIfNotExist(fileName)
                .openOutputStream()
                .getOrThrow()
            fileDoc.use { outputStream ->
                outputStream.write("## ${book.name} ${book.author}\n\n".toByteArray())
                appDb.bookmarkDao.getByBook(book.name, book.author).forEach {
                    outputStream.write("#### ${it.chapterName}\n\n".toByteArray())
                    outputStream.write("###### 原文\n ${it.bookText}\n\n".toByteArray())
                    outputStream.write("###### 摘要\n ${it.content}\n\n".toByteArray())
                }
            }
        }.onError {
            AppLog.put("导出失败\n${it.localizedMessage}", it, true)
        }.onSuccess {
            context.toastOnUi("导出成功")
        }
    }

    interface ChapterListCallBack {
        fun upChapterList(searchKey: String?)

        fun clearDisplayTitle()

        fun upAdapter()
    }

    interface BookmarkCallBack {
        fun upBookmark(searchKey: String?)
    }
}