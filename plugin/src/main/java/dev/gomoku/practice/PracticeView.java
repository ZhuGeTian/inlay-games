package dev.gomoku.practice;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.editor.event.*;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.util.Disposer;
import com.intellij.util.ui.JBUI;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/** A painted inlay, never a document edit. Optional details surround the question body. */
final class PracticeView implements EditorCustomElementRenderer, Disposable {
    record Part(String text, String action, int x, int width) {}
    record Row(String text, BufferedImage image, String action, int height, int imageWidth, List<Part> parts) {
        Row(String text, BufferedImage image, String action, int height, int imageWidth) {
            this(text, image, action, height, imageWidth, List.of());
        }
    }
    record Button(String label, String action, boolean enabled, Rectangle bounds) {}
    private final Editor editor;
    private final PracticeSession session;
    private Inlay<PracticeView> inlay;
    private Font font;
    private FontMetrics metrics;
    private int width, height, lineHeight, bodyHeight, bodyTop, page, footerTop;
    private final List<List<Row>> pages = new ArrayList<>();
    private final List<Button> buttons = new ArrayList<>();
    private final List<Button> optionHits = new ArrayList<>();
    private final Timer resize = new Timer(500, e -> checkLayout());
    private String layoutKey = "";
    private boolean disposed;

    PracticeView(Editor editor, PracticeSession session) { this.editor = editor; this.session = session; page = session.progress().page; layout(); }
    // 绑定鼠标与局部快捷键，随刷题视图一起释放。
    void attach(int offset) {
        inlay = editor.getInlayModel().addBlockElement(offset, true, false, 0, this);
        if (inlay == null) { Disposer.dispose(this); return; }
        ActionManager.getInstance().getAction("Moyu.Practice.Details")
                .registerCustomShortcutSet(editor.getContentComponent(), this);
        editor.addEditorMouseListener(new EditorMouseListener() {
            @Override public void mousePressed(EditorMouseEvent event) {
                if (inlay == null || !inlay.isValid() || event.isConsumed() || event.getInlay() != inlay
                        || !SwingUtilities.isLeftMouseButton(event.getMouseEvent()) || event.getMouseEvent().isControlDown()) return;
                Rectangle bounds = inlay.getBounds();
                if (bounds == null) return;
                event.consume();
                Point point = event.getMouseEvent().getPoint();
                activateAt(point.x - bounds.x, point.y - bounds.y);
            }
        }, this);
        editor.getScrollingModel().addVisibleAreaListener(event -> checkLayout(), this);
        resize.start();
    }
    // 展示设置或编辑器尺寸变化时触发重新布局。
    private String key() {
        var area = editor.getScrollingModel().getVisibleArea();
        var scheme = editor.getColorsScheme();
        return area.width + ":" + area.height + ":" + scheme.getEditorFontName() + ":" + scheme.getEditorFontSize()
                + ":" + scheme.getDefaultForeground() + ":" + session.getFontSize() + ":" + session.getVisibleLines()
                + ":" + session.isDetailsVisible();
    }
    private void checkLayout() {
        if (disposed) return;
        if (editor.isDisposed() || inlay != null && !inlay.isValid()) { Disposer.dispose(this); return; }
        if (!key().equals(layoutKey)) refresh();
    }
    void refresh() {
        if (disposed || editor.isDisposed()) return;
        layout();
        if (inlay != null && inlay.isValid()) inlay.update();
    }
    void restorePage() { page = session.progress().page; refresh(); }
    void firstPage() { page = 0; refresh(); }
    int pageCount() { return pages.size(); }
    int pageIndex() { return page; }
    // 按边界翻页，供菜单与可见按钮共用。
    void movePage(int delta) {
        page = Math.max(0, Math.min(pages.size() - 1, page + delta));
        refresh();
    }
    List<Button> buttons() { return List.copyOf(buttons); }
    List<Button> optionHits() { return List.copyOf(optionHits); }
    String visibleText() {
        return pages.get(page).stream().map(r -> r.text == null ? "[图片]" : r.text).reduce("", (a, b) -> a + "\n" + b);
    }
    void activateAt(int x, int y) {
        for (Button button : buttons) if (button.enabled && button.bounds.contains(x, y)) { act(button.action); return; }
        for (Button button : optionHits) if (button.enabled && button.bounds.contains(x, y)) { act(button.action); return; }
    }
    // 分派正文和底部按钮动作，翻页复用同一边界逻辑。
    private void act(String action) {
        switch (action) {
            case "page-" -> session.movePage(-1);
            case "page+" -> session.movePage(1);
            case "segment-" -> movePage(-1);
            case "segment+" -> movePage(1);
            case "previous" -> session.move(-1);
            case "next" -> session.move(1);
            case "submit" -> session.submit();
            case "retry" -> session.retry();
            case "filter" -> session.filter();
            case "hide" -> session.hide();
            default -> session.choose(action);
        }
    }
    // 题目完整显示，答案按行数分页；辅助区隐藏时不保留空白或按钮命中区。
    private void layout() {
        layoutKey = key();
        Font preferred = editor.getColorsScheme().getFont(EditorFontType.PLAIN);
        font = session.getFontSize() == 0 ? preferred : preferred.deriveFont((float) JBUI.scale(session.getFontSize()));
        // Logical fonts provide CJK fallback even when the configured programming font lacks glyphs.
        if (font.canDisplayUpTo("行测题目解析") >= 0) font = new Font(Font.MONOSPACED, font.getStyle(), font.getSize()).deriveFont(font.getSize2D());
        metrics = editor.getContentComponent().getFontMetrics(font);
        lineHeight = Math.max(metrics.getHeight() + 2, editor.getLineHeight());
        var area = editor.getScrollingModel().getVisibleArea();
        width = Math.max(180, Math.min(JBUI.scale(760), (area.width > 0 ? area.width : 700) - 28));
        bodyHeight = lineHeight * Math.max(1, Math.min(session.getVisibleLines(),
                (area.height > 0 ? area.height : 600) / 2 / lineHeight));
        var rows = new ArrayList<Row>();
        var progress = session.progress();
        boolean empty = session.empty();
        if (empty) text(rows, "还没有错题。点击“全部题”返回试卷。", null);
        else {
            var q = session.question();
            if (progress.answerVisible) {
                String result = progress.result(q);
                text(rows, result.isEmpty() ? (q.answer().isEmpty() ? "未提供答案，暂不判分" : q.answerLabel() + "：" + q.answer()) : result, null);
                text(rows, "解析：" + (q.explanation().isEmpty() ? "未提供解析" : q.explanation()), null);
                if (!q.answerSource().isEmpty()) text(rows, "来源：" + q.answerSource(), null);
            } else {
                if (!q.material().isEmpty()) text(rows, "材料：" + q.material(), null);
                for (var image : q.materialImages()) picture(rows, image, null);
                text(rows, q.stem(), null);
                for (var image : q.images()) picture(rows, image, null);
                options(rows, q);
            }
        }
        pages.clear();
        var current = new ArrayList<Row>();
        int used = 0;
        for (var row : rows) {
            if (progress.answerVisible && used + row.height > bodyHeight && !current.isEmpty()) { pages.add(current); current = new ArrayList<>(); used = 0; }
            current.add(row);
            used += row.height;
        }
        if (!current.isEmpty() || pages.isEmpty()) pages.add(current);
        page = Math.max(0, Math.min(page, pages.size() - 1));
        progress.page = page;
        bodyTop = (session.isDetailsVisible() ? 2 * lineHeight : 0) + 4;
        footerTop = bodyTop + pages.get(page).stream().mapToInt(Row::height).sum() + 4;
        buttons.clear();
        var available = progress.indices(session.paper());
        int at = available.indexOf(progress.index);
        boolean submitted = !empty && (progress.submitted.containsKey(session.question().id()) || progress.answerVisible);
        String selected = empty ? "" : progress.choices.getOrDefault(session.question().id(), "");
        if (session.isDetailsVisible()) addButtons(footerTop, new String[][] {
                {"上一页", "page-", Boolean.toString(progress.canAdvance(session.paper(), -1))},
                {"下一页", "page+", Boolean.toString(progress.canAdvance(session.paper(), 1))},
                {"上段", "segment-", Boolean.toString(page > 0)}, {"下段", "segment+", Boolean.toString(page + 1 < pages.size())},
                {"A", "A", Boolean.toString(!empty && !submitted)}, {"B", "B", Boolean.toString(!empty && !submitted)},
                {"C", "C", Boolean.toString(!empty && !submitted && session.question().options().size() > 2)}, {"D", "D", Boolean.toString(!empty && !submitted && session.question().options().size() > 3)},
                {"提交", "submit", Boolean.toString(!empty && !submitted && !selected.isEmpty())},
                {"重做", "retry", Boolean.toString(!empty && submitted)},
                {"上一题", "previous", Boolean.toString(at > 0)}, {"下一题", "next", Boolean.toString(at >= 0 && at < available.size() - 1)},
                {progress.onlyWrong ? "全部题" : "只看错题", "filter", "true"}, {"收起", "hide", "true"}
        });
        else height = bodyTop + pages.get(page).stream().mapToInt(Row::height).sum() + 4;
        optionHits.clear();
        int y = bodyTop;
        for (var row : pages.get(page)) {
            for (var part : row.parts) optionHits.add(new Button(part.action, part.action, !submitted, new Rectangle(part.x, y, part.width, row.height)));
            if (row.action != null) optionHits.add(new Button(row.action, row.action, !submitted, new Rectangle(6, y, width - 12, row.height)));
            y += row.height;
        }
    }
    // 短选项按粗体最大宽度并排，保证选中后不跳行，各选项独立点击。
    private void options(List<Row> rows, PracticePaper.Question question) {
        var bold = editor.getContentComponent().getFontMetrics(font.deriveFont(Font.BOLD));
        int start = 8 + metrics.stringWidth("// "), available = width - 10 - start;
        boolean compact = question.options().stream().allMatch(o -> o.image() == null && !o.text().contains("\n")
                && bold.stringWidth("A. " + o.text()) <= available);
        if (!compact) {
            for (int i = 0; i < question.options().size(); i++) {
                var option = question.options().get(i); String letter = String.valueOf((char) ('A' + i));
                text(rows, letter + ". " + option.text(), letter);
                if (option.image() != null) picture(rows, option.image(), letter);
            }
            return;
        }
        var parts = new ArrayList<Part>();
        int x = start;
        for (int i = 0; i < question.options().size(); i++) {
            String letter = String.valueOf((char) ('A' + i)), label = letter + ". " + question.options().get(i).text();
            int w = bold.stringWidth(label);
            if (!parts.isEmpty() && x + w > width - 10) { optionRow(rows, parts); parts.clear(); x = start; }
            parts.add(new Part(label, letter, x, w)); x += w + metrics.stringWidth("  ");
        }
        if (!parts.isEmpty()) optionRow(rows, parts);
    }
    // 同行选项共享行高和注释前缀，保存片段用于绘制和命中测试。
    private void optionRow(List<Row> rows, List<Part> parts) {
        String label = "// " + String.join("  ", parts.stream().map(Part::text).toList());
        rows.add(new Row(label, null, null, lineHeight, 0, List.copyOf(parts)));
    }
    private void addButtons(int top, String[][] values) {
        int x = 8, y = top;
        for (var value : values) {
            if (value[1].startsWith("segment") && (pages.size() <= 1)) continue;
            if (!session.empty() && value[1].matches("[A-D]") && !session.question().validChoice(value[1])) continue;
            String label = "[" + value[0] + "]";
            int w = editor.getContentComponent().getFontMetrics(font.deriveFont(Font.BOLD)).stringWidth(label) + 10;
            if (x + w > width - 8 && x > 8) { x = 8; y += lineHeight + 3; }
            buttons.add(new Button(label, value[1], Boolean.parseBoolean(value[2]), new Rectangle(x, y, w, lineHeight + 3)));
            x += w;
        }
        height = y + lineHeight + 10;
    }
    private void text(List<Row> rows, String text, String action) {
        var textMetrics = editor.getContentComponent().getFontMetrics(optionFont(action));
        int max = Math.max(20, width - 18 - textMetrics.stringWidth("// "));
        for (String paragraph : text.replace("\t", "    ").split("\\R", -1)) {
            StringBuilder line = new StringBuilder();
            int pixels = 0;
            for (int offset = 0; offset < paragraph.length();) {
                int cp = paragraph.codePointAt(offset);
                String part = new String(Character.toChars(cp));
                int advance = textMetrics.stringWidth(part);
                if (pixels + advance > max && !line.isEmpty()) {
                    rows.add(new Row("// " + line, null, action, lineHeight, 0));
                    line.setLength(0); pixels = 0;
                }
                line.append(part); pixels += advance; offset += Character.charCount(cp);
            }
            rows.add(new Row("// " + line, null, action, lineHeight, 0));
        }
    }
    // 选中只改变字重，颜色及选项文字保持不变。
    Font optionFont(String action) {
        boolean selected = action != null && !session.empty() && session.progress().choices.getOrDefault(session.question().id(), "").contains(action);
        return selected ? font.deriveFont(Font.BOLD) : font;
    }
    private void picture(List<Row> rows, BufferedImage image, String action) {
        double scale = Math.min(1.0, (width - 24.0) / image.getWidth());
        rows.add(new Row(null, image, action, Math.max(1, (int) (image.getHeight() * scale)) + 8,
                Math.max(1, (int) (image.getWidth() * scale))));
    }
    @Override public int calcWidthInPixels(Inlay inlay) { return width; }
    @Override public int calcHeightInPixels(Inlay inlay) { return height; }
    @Override public String getContextMenuGroupId(Inlay inlay) { return "Moyu.Practice.ContextMenu"; }
    // 辅助信息按设置绘制，正文与选项使用统一的布局起点。
    @Override public void paint(Inlay inlay, Graphics graphics, Rectangle region, TextAttributes attributes) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.translate(region.x, region.y);
            g.setClip(0, 0, width, height);
            g.setFont(font);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            var scheme = editor.getColorsScheme();
            g.setColor(scheme.getDefaultBackground());
            g.fillRect(0, 0, width, height);
            var comment = scheme.getAttributes(DefaultLanguageHighlighterColors.LINE_COMMENT);
            Color normal = comment != null && comment.getForegroundColor() != null ? comment.getForegroundColor() : scheme.getDefaultForeground();
            g.setColor(normal);
            var progress = session.progress();
            if (session.isDetailsVisible()) {
                g.drawString(shorten("// " + session.paper().title()), 8, metrics.getAscent() + 2);
                String status = session.empty() ? "0 题" : "题 " + (progress.index + 1) + "/" + session.paper().questions().size() + " · " + session.question().typeLabel();
                g.drawString(shorten("// " + status + ((session.isSectionMode() ? " · " + session.getSection() : "") + (progress.answerVisible ? " · 答案 · 段 " : " · 题目 · 段 ")) + (page + 1) + "/" + pages.size()
                        + (progress.onlyWrong ? " · 错题" : "")), 8, lineHeight + metrics.getAscent() + 2);
            }
            int y = bodyTop;
            for (var row : pages.get(page)) {
                g.setColor(normal);
                g.setFont(optionFont(row.action));
                if (row.image != null) g.drawImage(row.image, 10, y + 4, row.imageWidth, row.height - 8, null);
                else if (!row.parts.isEmpty()) {
                    g.setFont(font); g.drawString("// ", 8, y + metrics.getAscent());
                    for (var part : row.parts) { g.setFont(optionFont(part.action)); g.drawString(part.text, part.x, y + metrics.getAscent()); }
                } else g.drawString(row.text, 8, y + metrics.getAscent());
                y += row.height;
            }
            for (var button : buttons) {
                g.setColor(!button.enabled ? normal : scheme.getDefaultForeground());
                g.setFont(optionFont(button.action.matches("[A-D]") ? button.action : null));
                g.drawString(button.label, button.bounds.x, button.bounds.y + metrics.getAscent());
            }
        } finally { g.dispose(); }
    }
    private String shorten(String text) {
        int limit = width - 20;
        if (metrics.stringWidth(text) <= limit) return text;
        int end = text.length();
        while (end > 0 && metrics.stringWidth(text.substring(0, end) + "…") > limit) end = text.offsetByCodePoints(end, -1);
        return text.substring(0, end) + "…";
    }
    @Override public void dispose() {
        disposed = true;
        resize.stop();
        if (inlay != null && inlay.isValid()) inlay.dispose();
        inlay = null;
        session.closed(this);
    }
}
