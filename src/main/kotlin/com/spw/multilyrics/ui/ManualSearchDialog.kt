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

/** 来源图标徽章：彩色圆角底 + 白色品牌图标（内联绘制，无外部资源）。 */
private class SourceBadge(private val source: LyricsSource) : JComponent() {
    private val color: Color = sourceColor(source)
    init { preferredSize = Dimension(36, 36) }
    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        // 品牌色圆角底
        g2.color = color
        g2.fill(RoundRectangle2D.Double(0.0, 0.0, 36.0, 36.0, 10.0, 10.0))
        // 白色品牌图标
        SourceIconPainter.paint(g2, source)
        g2.dispose()
    }
    private fun sourceColor(s: LyricsSource): Color = when (s) {
        LyricsSource.APPLE_MUSIC -> Color(0xFA, 0x57, 0x5C)
        LyricsSource.QQ -> Color(0x31, 0xC8, 0x88)
        LyricsSource.NETEASE -> Color(0xE6, 0x3E, 0x3E)
        LyricsSource.KUGOU -> Color(0x2E, 0x9C, 0xF6)
        LyricsSource.KUWO -> Color(0xFF, 0xA5, 0x00)
        LyricsSource.SPOTIFY -> Color(0x1D, 0xB9, 0x54)
        LyricsSource.LOCAL -> Color(0x8B, 0x95, 0xA1)
    }
}

/**
 * 各来源品牌图标绘制（白色，绘制在 36x36 徽章中央约 22x22 区域）。
 * 全部用 Java2D 几何图形内联绘制，无图片资源依赖。
 */
private object SourceIconPainter {
    private const val S = 36.0

    fun paint(g2: Graphics2D, source: LyricsSource) {
        g2.color = Color.WHITE
        when (source) {
            LyricsSource.APPLE_MUSIC -> apple(g2)
            LyricsSource.QQ -> note(g2)
            LyricsSource.NETEASE -> cloud(g2)
            LyricsSource.KUGOU -> dog(g2)
            LyricsSource.KUWO -> mic(g2)
            LyricsSource.SPOTIFY -> waves(g2)
            LyricsSource.LOCAL -> folder(g2)
        }
    }

    /** Apple Music：咬过的苹果剪影 + 叶子。 */
    private fun apple(g2: Graphics2D) {
        val p = java.awt.geom.Path2D.Double()
        // 苹果主体（左侧圆 + 右侧带咬口）
        p.moveTo(S * 0.62, S * 0.18)
        p.curveTo(S * 0.42, S * 0.06, S * 0.16, S * 0.16, S * 0.14, S * 0.40)
        p.curveTo(S * 0.12, S * 0.62, S * 0.22, S * 0.86, S * 0.36, S * 0.86)
        p.curveTo(S * 0.44, S * 0.86, S * 0.48, S * 0.80, S * 0.54, S * 0.80)
        p.curveTo(S * 0.60, S * 0.80, S * 0.64, S * 0.86, S * 0.72, S * 0.86)
        p.curveTo(S * 0.86, S * 0.86, S * 0.92, S * 0.62, S * 0.86, S * 0.40)
        p.curveTo(S * 0.83, S * 0.28, S * 0.74, S * 0.22, S * 0.62, S * 0.18)
        p.closePath()
        // 咬口（反向小圆弧切除右侧）
        val bite = java.awt.geom.Ellipse2D.Double(S * 0.80, S * 0.40, S * 0.14, S * 0.14)
        val area = java.awt.geom.Area(p).apply { subtract(java.awt.geom.Area(bite)) }
        g2.fill(area)
        // 叶子
        val leaf = java.awt.geom.Path2D.Double()
        leaf.moveTo(S * 0.52, S * 0.20)
        leaf.curveTo(S * 0.58, S * 0.08, S * 0.72, S * 0.06, S * 0.74, S * 0.16)
        leaf.curveTo(S * 0.70, S * 0.22, S * 0.60, S * 0.24, S * 0.52, S * 0.20)
        leaf.closePath()
        g2.fill(leaf)
    }

    /** QQ 音乐：八分音符（音头 + 竖杆 + 旗）。 */
    private fun note(g2: Graphics2D) {
        g2.stroke = BasicStroke((S * 0.10).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        // 竖杆
        g2.drawLine((S * 0.64).toInt(), (S * 0.24).toInt(), (S * 0.64).toInt(), (S * 0.66).toInt())
        // 旗
        val flag = java.awt.geom.Path2D.Double()
        flag.moveTo(S * 0.64, S * 0.24)
        flag.curveTo(S * 0.80, S * 0.30, S * 0.82, S * 0.42, S * 0.74, S * 0.52)
        flag.lineTo(S * 0.74, S * 0.36)
        flag.curveTo(S * 0.72, S * 0.32, S * 0.68, S * 0.28, S * 0.64, S * 0.28)
        flag.closePath()
        g2.fill(flag)
        // 音头
        g2.fill(java.awt.geom.Ellipse2D.Double(S * 0.24, S * 0.58, S * 0.40, S * 0.26))
    }

    /** 网易云：云朵（三个凸圆 + 平底）。 */
    private fun cloud(g2: Graphics2D) {
        val p = java.awt.geom.Path2D.Double()
        val baseY = S * 0.68
        p.moveTo(S * 0.20, baseY)
        // 左凸
        p.curveTo(S * 0.08, baseY, S * 0.06, S * 0.44, S * 0.22, S * 0.44)
        p.curveTo(S * 0.22, S * 0.28, S * 0.44, S * 0.24, S * 0.50, S * 0.38)
        // 中凸
        p.curveTo(S * 0.54, S * 0.22, S * 0.78, S * 0.26, S * 0.78, S * 0.44)
        // 右凸
        p.curveTo(S * 0.94, S * 0.44, S * 0.92, baseY, S * 0.80, baseY)
        p.closePath()
        g2.fill(p)
    }

    /** 酷狗：狗头（圆脸 + 两只耳朵 + 鼻子）。 */
    private fun dog(g2: Graphics2D) {
        // 左耳
        val earL = java.awt.geom.Path2D.Double()
        earL.moveTo(S * 0.20, S * 0.40)
        earL.lineTo(S * 0.12, S * 0.16)
        earL.lineTo(S * 0.34, S * 0.30)
        earL.closePath()
        g2.fill(earL)
        // 右耳
        val earR = java.awt.geom.Path2D.Double()
        earR.moveTo(S * 0.80, S * 0.40)
        earR.lineTo(S * 0.88, S * 0.16)
        earR.lineTo(S * 0.66, S * 0.30)
        earR.closePath()
        g2.fill(earR)
        // 脸（圆）
        g2.fill(java.awt.geom.Ellipse2D.Double(S * 0.20, S * 0.30, S * 0.60, S * 0.54))
        // 鼻子（用底色小圆点突出）
        g2.color = SourceBadgeColorForDog
        g2.fill(java.awt.geom.Ellipse2D.Double(S * 0.44, S * 0.50, S * 0.12, S * 0.09))
        g2.color = Color.WHITE
    }

    /** 酷我：麦克风（圆头 + 杆 + 底座）。 */
    private fun mic(g2: Graphics2D) {
        g2.stroke = BasicStroke((S * 0.10).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        // 麦克风头（圆角矩形）
        g2.fill(java.awt.geom.RoundRectangle2D.Double(S * 0.32, S * 0.16, S * 0.36, S * 0.40, S * 0.18, S * 0.18))
        // 杆
        g2.drawLine((S * 0.50).toInt(), (S * 0.56).toInt(), (S * 0.50).toInt(), (S * 0.76).toInt())
        // 底座横杆
        g2.drawLine((S * 0.34).toInt(), (S * 0.76).toInt(), (S * 0.66).toInt(), (S * 0.76).toInt())
    }

    /** Spotify：三条同心声波弧线。 */
    private fun waves(g2: Graphics2D) {
        g2.stroke = BasicStroke((S * 0.11).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        val cx = S * 0.50
        val cy = S * 0.54
        g2.draw(java.awt.geom.Arc2D.Double(
            cx - S * 0.40, cy - S * 0.40, S * 0.80, S * 0.80,
            30.0, 120.0, java.awt.geom.Arc2D.OPEN))
        g2.draw(java.awt.geom.Arc2D.Double(
            cx - S * 0.28, cy - S * 0.28, S * 0.56, S * 0.56,
            30.0, 120.0, java.awt.geom.Arc2D.OPEN))
        g2.draw(java.awt.geom.Arc2D.Double(
            cx - S * 0.16, cy - S * 0.16, S * 0.32, S * 0.32,
            30.0, 120.0, java.awt.geom.Arc2D.OPEN))
    }

    /** 本地：文件夹。 */
    private fun folder(g2: Graphics2D) {
        val p = java.awt.geom.Path2D.Double()
        p.moveTo(S * 0.18, S * 0.30)
        p.lineTo(S * 0.40, S * 0.30)
        p.lineTo(S * 0.46, S * 0.38)
        p.lineTo(S * 0.82, S * 0.38)
        p.lineTo(S * 0.82, S * 0.74)
        p.lineTo(S * 0.18, S * 0.74)
        p.closePath()
        g2.fill(p)
    }
}

/** 酷狗徽章底色，用于狗鼻子的对比色。 */
private val SourceBadgeColorForDog: Color = Color(0x2E, 0x9C, 0xF6)

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
