package com.spw.multilyrics.ui

import com.spw.multilyrics.domain.LyricsCandidate
import com.spw.multilyrics.domain.LyricsSource
import com.spw.multilyrics.domain.TrackQuery
import com.spw.multilyrics.search.LyricsResolver
import com.spw.multilyrics.storage.LyricsCache
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Frame
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.SwingWorker

/**
 * 手动搜索歌词对话框（独立 Swing 窗口）。
 *
 * 当自动搜索失败时弹出，用户可输入关键词手动搜索、预览候选、双击选中后
 * 拉取歌词并写入缓存，下次播放该曲即可命中。
 *
 * 必须在 AWT Event Dispatch Thread 上构造和显示。
 */
class ManualSearchDialog(
    private val query: TrackQuery,
    private val resolver: LyricsResolver,
    private val cache: LyricsCache,
) {
    /** 单例窗口锁：同一曲目只允许一个手动搜索窗口 */
    @Volatile private var frame: JFrame? = null

    fun show() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater { show() }
            return
        }
        if (frame != null) {
            frame?.toFront()
            return
        }
        try {
            javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName())
        } catch (_: Exception) {
            // 回退默认外观
        }

        val f = JFrame("MultiLyrics 手动搜索").apply {
            defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
            isAlwaysOnTop = true
            addWindowListener(object : WindowAdapter() {
                override fun windowClosed(e: WindowEvent) { frame = null }
            })
        }
        frame = f

        val searchField = JTextField(buildInitialQuery(), 32)
        val searchBtn = JButton("搜索")
        val statusLabel = JLabel("输入关键词后回车搜索，双击候选加载歌词")
        val listModel = javax.swing.DefaultListModel<DisplayCandidate>()
        val list = JList(listModel).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            visibleRowCount = 12
            cellRenderer = object : javax.swing.DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean,
                ) = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus).apply {
                    val dc = value as? DisplayCandidate ?: return@apply
                    val c = dc.candidate
                    val dur = c.durationMs?.let { " · ${it / 1000}s" } ?: ""
                    val album = c.album.takeIf(String::isNotBlank)?.let { " · $it" } ?: ""
                    text = "<html><b>[${c.source.displayName}]</b> ${c.title} — ${c.artists.joinToString(", ")}$dur$album</html>"
                }
            }
        }
        val scrollPane = JScrollPane(list)

        val useBtn = JButton("使用选中歌词")
        val closeBtn = JButton("关闭")

        fun doSearch() {
            val keywords = searchField.text.trim()
            if (keywords.isEmpty()) {
                statusLabel.text = "请输入关键词"
                return
            }
            statusLabel.text = "搜索中…"
            listModel.clear()
            searchBtn.isEnabled = false
            object : SwingWorker<List<DisplayCandidate>, DisplayCandidate>() {
                override fun doInBackground(): List<DisplayCandidate> {
                    val tmp = mutableListOf<DisplayCandidate>()
                    runCatching {
                        resolver.searchAll(query, keywords).forEach { c ->
                            tmp.add(DisplayCandidate(c))
                            publish(DisplayCandidate(c))
                        }
                    }
                    return tmp
                }
                override fun process(chunks: List<DisplayCandidate>) {
                    chunks.forEach { listModel.addElement(it) }
                }
                override fun done() {
                    searchBtn.isEnabled = true
                    val n = listModel.size()
                    statusLabel.text = if (n == 0) "未找到候选，换一组关键词试试" else "找到 $n 条候选，双击加载歌词"
                }
            }.execute()
        }

        searchField.addActionListener { doSearch() }
        searchBtn.addActionListener { doSearch() }
        list.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount >= 2) useBtn.doClick()
            }
        })
        useBtn.addActionListener {
            val idx = list.selectedIndex
            if (idx < 0) {
                statusLabel.text = "请先选中一条候选"
                return@addActionListener
            }
            val dc = listModel.getElementAt(idx)
            statusLabel.text = "拉取歌词中…"
            useBtn.isEnabled = false
            Thread {
                val resolved = runCatching { resolver.fetchManual(dc.candidate) }.getOrNull()
                SwingUtilities.invokeLater {
                    useBtn.isEnabled = true
                    if (resolved == null) {
                        statusLabel.text = "歌词拉取失败，换一条候选试试"
                    } else {
                        runCatching { cache.putLyrics(query.key, resolver.toCache(resolved)) }
                        statusLabel.text = "已保存歌词（来源：${dc.candidate.source.displayName}）。请重新播放本曲生效"
                        JOptionPane.showMessageDialog(f, "歌词已保存到缓存。\n请重新播放当前歌曲（切换上一曲/下一曲再切回）以加载歌词。", "完成", JOptionPane.INFORMATION_MESSAGE)
                    }
                }
            }.start()
        }
        closeBtn.addActionListener { f.dispose() }

        val topPanel = JPanel(BorderLayout(8, 8)).apply {
            border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                add(JLabel("关键词："))
                add(searchField)
                add(searchBtn)
            }, BorderLayout.NORTH)
            add(scrollPane, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                add(statusLabel)
                add(Box.createHorizontalStrut(16))
                add(useBtn)
                add(closeBtn)
            }, BorderLayout.SOUTH)
        }
        f.contentPane.add(topPanel, BorderLayout.CENTER)
        f.pack()
        f.setLocationRelativeTo(null)
        f.isVisible = true

        // 自动触发首次搜索
        doSearch()
    }

    private fun buildInitialQuery(): String {
        val parts = mutableListOf<String>()
        val t = TextNormalizer_clean(query.title)
        if (t.isNotBlank()) parts.add(t)
        query.artists.firstOrNull()?.takeIf(String::isNotBlank)?.let { parts.add(it) }
        if (parts.isEmpty()) {
            // 文件名兜底
            val p = query.path.trim()
            if (p.isNotEmpty()) {
                val slash = p.lastIndexOfAny(charArrayOf('/', '\\'))
                val name = if (slash >= 0) p.substring(slash + 1) else p
                val dot = name.lastIndexOf('.')
                val stem = if (dot > 0) name.substring(0, dot) else name
                return stem
            }
        }
        return parts.joinToString(" ")
    }

    /** 包装候选项用于 JList 显示（toString 用于搜索/调试，渲染器实际用 HTML）。 */
    private data class DisplayCandidate(val candidate: LyricsCandidate) {
        override fun toString(): String =
            "[${candidate.source.displayName}] ${candidate.title} — ${candidate.artists.joinToString(", ")}"
    }

    @Suppress("FunctionName")
    private fun TextNormalizer_clean(value: String): String =
        com.spw.multilyrics.domain.TextNormalizer.cleanSearchTitle(value)
}
