@file:Suppress("DEPRECATION")

package io.legado.app.ui.book.toc

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentPagerAdapter
import com.google.android.material.tabs.TabLayout
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.data.entities.Book
import io.legado.app.databinding.ActivityChapterListBinding
import io.legado.app.help.book.isLocalTxt
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.model.ReadBook
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.toc.rule.TxtTocRuleDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.applyTint
import io.legado.app.utils.gone
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import com.google.gson.JsonObject

/**
 * 目录
 */
class TocActivity : VMBaseActivity<ActivityChapterListBinding, TocViewModel>(),
    TxtTocRuleDialog.CallBack {

    override val binding by viewBinding(ActivityChapterListBinding::inflate)
    override val viewModel by viewModels<TocViewModel>()

    private lateinit var tabLayout: TabLayout
    private var menu: Menu? = null
    private var searchView: SearchView? = null
    private val waitDialog by lazy { WaitDialog(this) }
    private val exportDir = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            when (it.requestCode) {
                1 -> viewModel.saveBookmark(uri)
                2 -> viewModel.saveBookmarkMd(uri)
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        tabLayout = binding.titleBar.findViewById(R.id.tab_layout)
        tabLayout.isTabIndicatorFullWidth = false
        tabLayout.setSelectedTabIndicatorColor(accentColor)
        binding.viewPager.adapter = TabFragmentPageAdapter()
        tabLayout.setupWithViewPager(binding.viewPager)
        tabLayout.tabGravity = TabLayout.GRAVITY_CENTER
        viewModel.bookData.observe(this) {
            menu?.setGroupVisible(R.id.menu_group_text, it.isLocalTxt)
        }
        intent.getStringExtra("bookUrl")?.let {
            viewModel.initBook(it)
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.book_toc, menu)
        this.menu = menu
        val search = menu.findItem(R.id.menu_search)
        searchView = (search.actionView as SearchView).apply {
            applyTint(primaryTextColor)
            maxWidth = resources.displayMetrics.widthPixels
            onActionViewCollapsed()
            setOnCloseListener {
                tabLayout.visible()
                false
            }
            setOnSearchClickListener { tabLayout.gone() }
            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String): Boolean {
                    viewModel.searchKey = query
                    return false
                }

                override fun onQueryTextChange(newText: String): Boolean {
                    viewModel.searchKey = newText
                    if (tabLayout.selectedTabPosition == 1) {
                        viewModel.startBookmarkSearch(newText)
                    } else {
                        viewModel.startChapterListSearch(newText)
                    }
                    return false
                }
            })
            setOnQueryTextFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    searchView?.isIconified = true
                }
            }
        }
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        if (tabLayout.selectedTabPosition == 1) {
            menu.setGroupVisible(R.id.menu_group_bookmark, true)
            menu.setGroupVisible(R.id.menu_group_toc, false)
            menu.setGroupVisible(R.id.menu_group_text, false)
        } else {
            menu.setGroupVisible(R.id.menu_group_bookmark, false)
            menu.setGroupVisible(R.id.menu_group_toc, true)
            menu.setGroupVisible(R.id.menu_group_text, viewModel.bookData.value?.isLocalTxt == true)
        }
        menu.findItem(R.id.menu_use_replace)?.isChecked =
            AppConfig.tocUiUseReplace
        menu.findItem(R.id.menu_load_word_count)?.isChecked =
            AppConfig.tocCountWords
        menu.findItem(R.id.menu_split_long_chapter)?.isChecked =
            viewModel.bookData.value?.getSplitLongChapter() == true
        return super.onMenuOpened(featureId, menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_toc_regex -> showDialogFragment(
                TxtTocRuleDialog(viewModel.bookData.value?.tocUrl)
            )

            R.id.menu_toc_rule_server -> {
                viewModel.bookData.value?.let { book ->
                    toastOnUi("正在获取规则列表…")
                    viewModel.loadServerTocRules(book) { result ->
                        result.onSuccess { obj ->
                            showServerTocRuleDialog(book, obj)
                        }.onFailure {
                            toastOnUi("获取规则失败: ${it.localizedMessage}")
                        }
                    }
                }
            }

            R.id.menu_split_long_chapter -> {
                viewModel.bookData.value?.let { book ->
                    item.isChecked = !item.isChecked
                    book.setSplitLongChapter(item.isChecked)
                    upBookAndToc(book)
                }
            }

            R.id.menu_reverse_toc -> viewModel.reverseToc {
                viewModel.chapterListCallBack?.upChapterList(searchView?.query?.toString())
                setResult(RESULT_OK, Intent().apply {
                    putExtra("index", it.durChapterIndex)
                    putExtra("chapterPos", 0)
                })
            }

            R.id.menu_use_replace -> {
                AppConfig.tocUiUseReplace = !item.isChecked
                viewModel.chapterListCallBack?.clearDisplayTitle()
                viewModel.chapterListCallBack?.upChapterList(searchView?.query?.toString())
            }

            R.id.menu_load_word_count -> {
                AppConfig.tocCountWords = !item.isChecked
                viewModel.upChapterListAdapter()
            }

            R.id.menu_export_bookmark -> exportDir.launch {
                requestCode = 1
            }

            R.id.menu_export_md -> exportDir.launch {
                requestCode = 2
            }

            R.id.menu_log -> showDialogFragment<AppLogDialog>()
        }
        return super.onCompatOptionsItemSelected(item)
    }

    override fun onTocRegexDialogResult(tocRegex: String) {
        viewModel.bookData.value?.let { book ->
            book.tocUrl = tocRegex
            upBookAndToc(book)
        }
    }

    /** 服务器分章规则选择对话框（配合自建书源 API）。 */
    private fun showServerTocRuleDialog(book: Book, obj: JsonObject) {
        val current = obj.get("current")?.asString ?: ""
        val names = mutableListOf("自动（选优算法）")
        obj.getAsJsonArray("rules")?.forEach { el ->
            val o = el.asJsonObject
            names.add("${o.get("name").asString}（${o.get("count").asInt} 章）")
        }
        val checked = if (current.isEmpty()) 0 else names.indexOfFirst {
            it.startsWith(current)
        }
        AlertDialog.Builder(this)
            .setTitle("切换分章规则")
            .setSingleChoiceItems(names.toTypedArray(), checked) { dialog, which ->
                dialog.dismiss()
                val rule = if (which == 0) "__auto__"
                else names[which].substringBefore("（")
                val wait = WaitDialog(this)
                wait.setText("正在按新规则刷新目录…")
                wait.show()
                viewModel.switchServerTocRule(book, rule) { err ->
                    wait.dismiss()
                    if (err != null) {
                        toastOnUi("切换失败: ${err.localizedMessage}")
                    } else {
                        toastOnUi(if (which == 0) "已恢复自动选优" else "已切换规则: $rule")
                        viewModel.chapterListCallBack?.upChapterList(
                            searchView?.query?.toString()
                        )
                        setResult(RESULT_OK, Intent().apply {
                            putExtra("index", ReadBook.durChapterIndex)
                            putExtra("chapterPos", 0)
                        })
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun upBookAndToc(book: Book) {        waitDialog.show()
        viewModel.upBookTocRule(book) {
            waitDialog.dismiss()
            if (ReadBook.book == book) {
                if (it == null) {
                    ReadBook.upMsg(null)
                } else {
                    ReadBook.upMsg("LoadTocError:${it.localizedMessage}")
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private inner class TabFragmentPageAdapter :
        FragmentPagerAdapter(supportFragmentManager, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {

        override fun getItem(position: Int): Fragment {
            return when (position) {
                1 -> BookmarkFragment()
                else -> ChapterListFragment()
            }
        }

        override fun getCount(): Int {
            return 2
        }

        override fun getPageTitle(position: Int): CharSequence {
            return when (position) {
                1 -> getString(R.string.bookmark)
                else -> getString(R.string.chapter_list)
            }
        }

    }

}