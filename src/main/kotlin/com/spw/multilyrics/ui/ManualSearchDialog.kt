package com.spw.multilyrics.ui

import com.spw.multilyrics.domain.LyricsCandidate
import com.spw.multilyrics.domain.LyricsSource
import com.spw.multilyrics.domain.TrackQuery
import com.spw.multilyrics.search.LyricsResolver
import com.spw.multilyrics.storage.LyricsCache
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.Image
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.Icon
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JComponent
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
import javax.swing.UIManager
import javax.swing.border.Border
import javax.swing.BoxLayout
import javax.swing.plaf.basic.BasicScrollBarUI

/**
 * 手动搜索歌词对话框（独立 Swing 窗口）。
 *
 * 当自动搜索失败时弹出，用户可输入关键词手动搜索、预览候选、双击选中后
 * 拉取歌词并写入缓存，下次播放该曲即可命中。
 *
 * 视觉风格：亚克力半透明 + 微软雅黑 + 圆角按钮 + 内联绘制音乐图标。
 * 必须在 AWT Event Dispatch Thread 上构造和显示。
 */
class ManualSearchDialog(
    private val query: TrackQuery,
    private val resolver: LyricsResolver,
    private val cache: LyricsCache,
) {
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
        AcrylicTheme.setup()
        val f = JFrame("MultiLyrics 手动搜索").apply {
            defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
            isAlwaysOnTop = true
            contentPane = AcrylicPanel()
            addWindowListener(object : WindowAdapter() {
                override fun windowClosed(e: WindowEvent) { frame = null }
            })
        }
        frame = f

        // —— 顶部标题栏 ——
        val iconLabel = JLabel(AcousticTheme.musicIcon(28))
        val titleLabel = JLabel("手动搜索歌词").apply {
            font = AcrylicTheme.titleFont
            foreground = AcrylicTheme.textPrimary
        }
        val subtitleLabel = JLabel("跨所有已启用来源搜索 · 双击候选加载").apply {
            font = AcrylicTheme.captionFont
            foreground = AcrylicTheme.textSecondary
        }
        val headerLeft = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(2, 8, 0, 0)
            add(titleLabel, BorderLayout.NORTH)
            add(subtitleLabel, BorderLayout.SOUTH)
        }
        val header = JPanel(BorderLayout(12, 0)).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(18, 20, 14, 20)
            add(iconLabel, BorderLayout.WEST)
            add(headerLeft, BorderLayout.CENTER)
        }

        // —— 搜索栏 ——
        val searchField = object : JTextField(buildInitialQuery(), 28) {
            init {
                font = AcrylicTheme.bodyFont
                foreground = AcrylicTheme.textPrimary
                background = AcrylicTheme.inputBg
                caretColor = AcrylicTheme.textPrimary
                border = AcrylicTheme.inputBorder
                preferredSize = Dimension(420, 34)
            }
        }
        val searchBtn = AcrylicButton("搜索", primary = true)
        val searchBar = JPanel(BorderLayout(8, 0)).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(0, 20, 10, 20)
            add(searchField, BorderLayout.CENTER)
            add(searchBtn, BorderLayout.EAST)
        }

        // —— 候选列表 ——
        val listModel = DefaultListModel<DisplayCandidate>()
        val list = JList(listModel).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            visibleRowCount = 12
            font = AcrylicTheme.bodyFont
            background = Color(0, 0, 0, 0)
            selectionBackground = AcrylicTheme.tintedAccent(0x33)
            selectionForeground = AcrylicTheme.textPrimary
            foreground = AcrylicTheme.textPrimary
            cellRenderer = CandidateRenderer()
            fixedCellHeight = 56
        }
        val scrollPane = JScrollPane(list).apply {
            isOpaque = false
            viewport.isOpaque = false
            border = BorderFactory.createEmptyBorder(0, 12, 0, 12)
            verticalScrollBar.setUI(AcousticScrollBarUI())
            horizontalScrollBar.setUI(AcousticScrollBarUI())
        }

        // —— 状态栏 + 按钮 ——
        val statusLabel = JLabel("准备就绪").apply {
            font = AcrylicTheme.captionFont
            foreground = AcrylicTheme.textSecondary
        }
        val useBtn = AcrylicButton("使用选中歌词", primary = true)
        val closeBtn = AcrylicButton("关闭", primary = false)
        val footer = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(12, 20, 18, 20)
            add(statusLabel, BorderLayout.WEST)
            val btnPanel = JPanel().apply {
                isOpaque = false
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                add(useBtn)
                add(Box.createHorizontalStrut(10))
                add(closeBtn)
            }
            add(btnPanel, BorderLayout.EAST)
        }

        // —— 事件 ——
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
                    statusLabel.text = if (n == 0) "未找到候选，换一组关键词试试" else "找到 $n 条候选，双击或选中后点“使用选中歌词”"
                }
            }.execute()
        }

        searchField.addActionListener { doSearch() }
        searchBtn.addActionListener { doSearch() }
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
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
                        statusLabel.text = "已保存歌词（来源：${dc.candidate.source.displayName}）"
                        JOptionPane.showMessageDialog(f, "歌词已保存到缓存。\n请重新播放当前歌曲（切换上一曲/下一曲再切回）以加载歌词。", "完成", JOptionPane.INFORMATION_MESSAGE)
                    }
                }
            }.start()
        }
        closeBtn.addActionListener { f.dispose() }

        // —— 装配 ——
        val contentPane = f.contentPane as AcrylicPanel
        contentPane.setLayout(BorderLayout())
        contentPane.add(header, BorderLayout.NORTH)
        contentPane.add(searchBar, BorderLayout.SOUTH)
        val center = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(0, 0, 0, 0)
            add(scrollPane, BorderLayout.CENTER)
            add(footer, BorderLayout.SOUTH)
        }
        contentPane.add(center, BorderLayout.CENTER)

        f.setSize(620, 540)
        f.minimumSize = Dimension(520, 420)
        f.setLocationRelativeTo(null)
        f.isVisible = true

        // 自动触发首次搜索
        doSearch()
    }

    private fun buildInitialQuery(): String {
        val parts = mutableListOf<String>()
        val t = com.spw.multilyrics.domain.TextNormalizer.cleanSearchTitle(query.title)
        if (t.isNotBlank()) parts.add(t)
        query.artists.firstOrNull()?.takeIf(String::isNotBlank)?.let { parts.add(it) }
        if (parts.isEmpty()) {
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
}

/** 候选项包装：供渲染器与 SwingWorker 共享。 */
internal data class DisplayCandidate(val candidate: LyricsCandidate) {
    override fun toString(): String =
        "[${candidate.source.displayName}] ${candidate.title} — ${candidate.artists.joinToString(", ")}"
}

/** 候选项渲染器：来源色块 + 标题/艺术家/时长/专辑。 */
private class CandidateRenderer : DefaultListCellRenderer() {
    override fun getListCellRendererComponent(
        list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean,
    ): Component {
        val dc = value as? DisplayCandidate
            ?: return super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
        return CandidateCard(dc.candidate, isSelected).also {
            it.preferredSize = Dimension(list?.width ?: 560, 56)
        }
    }
}

/** 单条候选卡片：左侧来源色块 + 右侧标题/副信息。 */
private class CandidateCard(
    candidate: LyricsCandidate,
    private val selected: Boolean,
) : JPanel(BorderLayout(10, 0)) {
    init {
        isOpaque = false
        border = BorderFactory.createEmptyBorder(6, 12, 6, 12)
        val badge = SourceBadge(candidate.source)
        add(badge, BorderLayout.WEST)
        val title = JLabel(candidate.title).apply {
            font = AcrylicTheme.bodyFont.deriveFont(Font.PLAIN, 13f)
            foreground = AcrylicTheme.textPrimary
        }
        val dur = candidate.durationMs?.let { "${it / 1000}s" } ?: ""
        val album = candidate.album.takeIf(String::isNotBlank)?.let { " · $it" } ?: ""
        val artists = candidate.artists.joinToString(", ")
        val sub = JLabel("$artists · $dur$album").apply {
            font = AcrylicTheme.captionFont
            foreground = AcrylicTheme.textSecondary
        }
        val text = JPanel(BorderLayout(0, 2)).apply {
            isOpaque = false
            add(title, BorderLayout.NORTH)
            add(sub, BorderLayout.SOUTH)
        }
        add(text, BorderLayout.CENTER)
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val w = width
        val h = height
        if (selected) {
            g2.color = AcrylicTheme.tintedAccent(0x33)
        } else {
            g2.color = Color(255, 255, 255, 10)
        }
        g2.fill(RoundRectangle2D.Double(2.0, 2.0, (w - 4).toDouble(), (h - 4).toDouble(), 8.0, 8.0))
        g2.dispose()
    }
}

/** 来源图标徽章：圆角底 + 官方平台图标（从 classpath 加载）。 */
private class SourceBadge(private val source: LyricsSource) : JComponent() {
    private val icon: ImageIcon? = loadIcon(source)
    init { preferredSize = Dimension(36, 36) }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        // 透明背景：图标本身已含品牌色与形状，仅做圆角裁剪
        val clip = RoundRectangle2D.Double(0.0, 0.0, 36.0, 36.0, 10.0, 10.0)
        g2.clip = clip
        icon?.paintIcon(this, g2, 0, 0)
        g2.dispose()
    }

    private fun loadIcon(s: LyricsSource): ImageIcon? {
        val fileName = when (s) {
            LyricsSource.APPLE_MUSIC -> "applemusic.png"
            LyricsSource.QQ -> "qq.png"
            LyricsSource.NETEASE -> "netease.png"
            LyricsSource.KUGOU -> "kugou.png"
            LyricsSource.KUWO -> "kuwo.png"
            LyricsSource.SPOTIFY -> "spotify.png"
            LyricsSource.LOCAL -> "local.png"
        }
        val url = javaClass.getResource("/icons/$fileName") ?: return null
        val scaled = ImageIcon(url).image.getScaledInstance(36, 36, Image.SCALE_SMOOTH)
        return ImageIcon(scaled)
    }
}

/** 亚克力风格按钮：圆角、悬停渐变、主/次样式。 */
private class AcrylicButton(text: String, private val primary: Boolean) : JButton(text) {
    private var hover = false

    init {
        isContentAreaFilled = false
        isFocusPainted = false
        isBorderPainted = false
        font = AcrylicTheme.bodyFont.deriveFont(Font.PLAIN, 13f)
        foreground = if (primary) Color.WHITE else AcrylicTheme.textPrimary
        border = BorderFactory.createEmptyBorder(8, 18, 8, 18)
        cursor = Cursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) { hover = true; repaint() }
            override fun mouseExited(e: MouseEvent) { hover = false; repaint() }
        })
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val w = width
        val h = height
        val radius = 8.0
        if (primary) {
            val base = if (hover) AcrylicTheme.accentBrighter else AcrylicTheme.accent
            g2.paint = GradientPaint(0f, 0f, base, 0f, h.toFloat(), AcrylicTheme.accentDarker)
            g2.fill(RoundRectangle2D.Double(0.0, 0.0, w - 1.0, h - 1.0, radius, radius))
            g2.color = AcrylicTheme.accentDarker
            g2.stroke = BasicStroke(1f)
            g2.draw(RoundRectangle2D.Double(0.0, 0.0, (w - 1).toDouble(), (h - 1).toDouble(), radius, radius))
        } else {
            g2.color = if (hover) Color(255, 255, 255, 30) else Color(255, 255, 255, 18)
            g2.fill(RoundRectangle2D.Double(0.0, 0.0, (w - 1).toDouble(), (h - 1).toDouble(), radius, radius))
            g2.color = Color(255, 255, 255, 60)
            g2.stroke = BasicStroke(1f)
            g2.draw(RoundRectangle2D.Double(0.0, 0.0, (w - 1).toDouble(), (h - 1).toDouble(), radius, radius))
        }
        g2.dispose()
        super.paintComponent(g)
    }
}

/** 细滚动条。 */
private class AcousticScrollBarUI : BasicScrollBarUI() {
    override fun configureScrollBarColors() {
        trackColor = Color(0, 0, 0, 0)
        thumbColor = Color(255, 255, 255, 60)
        thumbDarkShadowColor = Color(0, 0, 0, 0)
        thumbHighlightColor = Color(0, 0, 0, 0)
        thumbLightShadowColor = Color(0, 0, 0, 0)
        trackHighlightColor = Color(0, 0, 0, 0)
    }
    override fun createDecreaseButton(orientation: Int) = emptyButton()
    override fun createIncreaseButton(orientation: Int) = emptyButton()
    private fun emptyButton(): JButton = JButton().apply {
        preferredSize = Dimension(0, 0)
        isVisible = false
    }
    override fun setThumbBounds(x: Int, y: Int, w: Int, h: Int) {
        super.setThumbBounds(x, y, w, h)
        scrollbar.repaint()
    }
}

/** 亚克力半透明背景面板：模拟毛玻璃 + 顶部光晕。 */
private class AcrylicPanel : JPanel() {
    init { isOpaque = false }
    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        // 底色：深色半透明，模拟亚克力
        g2.color = AcrylicTheme.background
        g2.fillRect(0, 0, width, height)
        // 顶部光晕（强调色淡渐变）
        g2.paint = GradientPaint(
            0f, 0f, AcrylicTheme.tintedAccent(0x22),
            0f, 120f, (AcrylicTheme.background translucent 0x00),
        )
        g2.fillRect(0, 0, width, 120)
        g2.dispose()
    }
}

/** 主题配置：字体、颜色、亚克力底色。 */
private object AcrylicTheme {
    val titleFont: Font = bestFont(Font.BOLD, 18)
    val bodyFont: Font = bestFont(Font.PLAIN, 13)
    val captionFont: Font = bestFont(Font.PLAIN, 11)

    // 亚克力深色主题
    val background: Color = Color(0x1F, 0x1F, 0x23, 235)       // 近黑半透明
    val textPrimary: Color = Color(0xF2, 0xF2, 0xF2)
    val textSecondary: Color = Color(0xB0, 0xB0, 0xB8)
    val accent: Color = Color(0x6C, 0xB4, 0xF7)                // 亚克力蓝
    val accentBrighter: Color = Color(0x8F, 0xCB, 0xF9)
    val accentDarker: Color = Color(0x4A, 0x90, 0xD9)
    val inputBg: Color = Color(0x2A, 0x2A, 0x30, 200)
    val inputBorder: Border = BorderFactory.createEmptyBorder(7, 10, 7, 10)

    fun setup() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
        } catch (_: Exception) {}
        // 统一默认字体
        val keys = UIManager.getDefaults().keys()
        while (keys.hasMoreElements()) {
            val k = keys.nextElement()
            val v = UIManager.get(k)
            if (v is Font) UIManager.put(k, bodyFont)
        }
    }

    /** 亚克力蓝按 alpha 叠加（属性同名函数桥接）。 */
    fun tintedAccent(alpha: Int): Color = accent translucent alpha

    private fun bestFont(style: Int, size: Int): Font {
        val available = GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
        val candidates = listOf("Microsoft YaHei", "微软雅黑", "PingFang SC", "Noto Sans CJK SC", Font.SANS_SERIF)
        val family = candidates.firstOrNull { c -> available.any { it.equals(c, true) } } ?: Font.SANS_SERIF
        return Font(family, style, size)
    }
}

/** Color 扩展：按 alpha 分量叠加。 */
private infix fun Color.translucent(alpha: Int): Color =
    Color(red, green, blue, alpha.coerceIn(0, 255))

/**
 * 绘制内联音乐图标（双八分音符），无需外部资源。
 */
private object AcousticTheme {
    fun musicIcon(size: Int): Icon {
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        val s = size.toFloat()
        // 渐变填充
        g.paint = GradientPaint(0f, 0f, Color(0x8F, 0xCB, 0xF9), s, s, Color(0x4A, 0x90, 0xD9))
        g.fill(RoundRectangle2D.Float(0f, 0f, s, s, s * 0.28f, s * 0.28f))
        // 音符（白色）
        g.color = Color.WHITE
        g.stroke = BasicStroke(s * 0.09f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        // 左音符
        val lx = s * 0.28f
        val ly = s * 0.68f
        g.fillOval((lx - s * 0.12f).toInt(), (ly - s * 0.07f).toInt(), (s * 0.24f).toInt(), (s * 0.18f).toInt())
        g.drawLine((lx + s * 0.12f).toInt(), (ly - s * 0.05f).toInt(), (lx + s * 0.12f).toInt(), (s * 0.28f).toInt())
        // 右音符
        val rx = s * 0.58f
        val ry = s * 0.60f
        g.fillOval((rx - s * 0.12f).toInt(), (ry - s * 0.07f).toInt(), (s * 0.24f).toInt(), (s * 0.18f).toInt())
        g.drawLine((rx + s * 0.12f).toInt(), (ry - s * 0.05f).toInt(), (rx + s * 0.12f).toInt(), (s * 0.20f).toInt())
        // 连接横梁
        val beamY = s * 0.22f
        g.drawLine((lx + s * 0.12f).toInt(), beamY.toInt(), (rx + s * 0.12f).toInt(), beamY.toInt())
        g.drawLine((lx + s * 0.12f).toInt(), (beamY + s * 0.10f).toInt(), (rx + s * 0.12f).toInt(), (beamY + s * 0.10f).toInt())
        g.dispose()
        return ImageIcon(img)
    }
}
