package io.legado.app.ui.association

import android.os.Bundle
import androidx.activity.viewModels
import androidx.core.net.toUri
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.data.appDb
import io.legado.app.databinding.ActivityTranslucenceBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * 网络一键导入
 * 格式: legado://import/{path}?src={url}
 */
class OnLineImportActivity :
    VMBaseActivity<ActivityTranslucenceBinding, OnLineImportViewModel>() {

    override val binding by viewBinding(ActivityTranslucenceBinding::inflate)
    override val viewModel by viewModels<OnLineImportViewModel>()

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        viewModel.successLive.observe(this) {
            when (it.first) {
                "bookSource" -> showDialogFragment(
                    ImportBookSourceDialog(it.second, true)
                )
                "rssSource" -> showDialogFragment(
                    ImportRssSourceDialog(it.second, true)
                )
                "replaceRule" -> showDialogFragment(
                    ImportReplaceRuleDialog(it.second, true)
                )
                "httpTts" -> showDialogFragment(
                    ImportHttpTtsDialog(it.second, true)
                )
                "theme" -> showDialogFragment(
                    ImportThemeDialog(it.second, true)
                )
                "txtRule" -> showDialogFragment(
                    ImportTxtTocRuleDialog(it.second, true)
                )
                "dictRule" -> showDialogFragment(
                    ImportDictRuleDialog(it.second, true)
                )
            }
        }
        viewModel.errorLive.observe(this) {
            finallyDialog(getString(R.string.error), it)
        }
        intent.data?.let {
            val url = it.getQueryParameter("src")
            if (url.isNullOrEmpty()) {
                finish()
                return
            }
            when (it.path) {
                "/bookInfo" -> {
                    // 直达书籍详情页（书架里已有该书时）；未入库则回退到书源搜索
                    openBookInfo(url, it.getQueryParameter("name") ?: "",
                        it.getQueryParameter("author") ?: "")
                }

                "/bookSource" -> showDialogFragment(
                    ImportBookSourceDialog(url, true)
                )

                "/rssSource" -> showDialogFragment(
                    ImportRssSourceDialog(url, true)
                )

                "/replaceRule" -> showDialogFragment(
                    ImportReplaceRuleDialog(url, true)
                )
                "/textTocRule" -> showDialogFragment(
                    ImportTxtTocRuleDialog(url, true)
                )
                "/httpTTS" -> showDialogFragment(
                    ImportHttpTtsDialog(url, true)
                )
                "/dictRule" -> showDialogFragment(
                    ImportDictRuleDialog(url, true)
                )
                "/theme" -> showDialogFragment(
                    ImportThemeDialog(url, true)
                )
                "/readConfig" -> viewModel.getBytes(url) { bytes ->
                    viewModel.importReadConfig(bytes, this::finallyDialog)
                }
                "/addToBookshelf" -> showDialogFragment(
                    AddToBookshelfDialog(url, true)
                )
                "/importonline" -> when (it.host) {
                    "booksource" -> showDialogFragment(
                        ImportBookSourceDialog(url, true)
                    )
                    "rsssource" -> showDialogFragment(
                        ImportRssSourceDialog(url, true)
                    )
                    "replace" -> showDialogFragment(
                        ImportReplaceRuleDialog(url, true)
                    )
                    else -> {
                        viewModel.determineType(url, this::finallyDialog)
                    }
                }
                else -> viewModel.determineType(url, this::finallyDialog)
            }
        }
    }

    /**
     * 直达书籍详情页：bookUrl 命中书架直接打开，否则用书名回退书源搜索。
     * 书源按 bookUrl 的 host 匹配（书库源 bookSourceUrl 就是服务器根地址）。
     * 精确匹配失败时做两级兜底：
     *   1. 按服务端书籍 id 在 bookUrl 里模糊匹配（书源与订阅源导入时的
     *      base 地址可能不一致，如内网 IP / 域名 / 端口差异导致绝对地址不同）；
     *   2. 按 书名+作者 匹配。
     */
    private fun openBookInfo(bookUrl: String, name: String, author: String) {
        val book = appDb.bookDao.getBook(bookUrl)
            ?: bookUrl.toUri().getQueryParameter("id")?.let { id ->
                appDb.bookDao.getBookByTocId("%toc?id=$id&%", "%toc?id=$id")
            }
            ?: (if (name.isNotBlank()) appDb.bookDao.getBook(name, author) else null)
        if (book != null) {
            startActivity<BookInfoActivity> {
                putExtra("name", book.name)
                putExtra("author", book.author)
                putExtra("bookUrl", book.bookUrl)
            }
            finish()
            return
        }
        val uri = bookUrl.toUri()
        val origin = uri.let { u ->
            if (u.host.isNullOrBlank()) "" else "${u.scheme ?: "http"}://${u.host}"
        }
        val source = origin.takeIf { it.isNotBlank() }
            ?.let { appDb.bookSourceDao.getBookSource(it) }
        if (source == null || name.isBlank()) {
            toastOnUi(R.string.error)
            finish()
            return
        }
        SearchActivity.start(this, source, name)
        finish()
    }

    private fun finallyDialog(title: String, msg: String) {
        alert(title, msg) {
            okButton()
            onDismiss {
                finish()
            }
        }
    }

}