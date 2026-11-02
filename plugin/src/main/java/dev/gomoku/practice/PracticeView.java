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
    record Row(String text, BufferedImage image, String action, int height, int imageWidth) {}
    record Button(String label, String action, boolean enabled, Rectangle bounds) {}
    private final Editor editor;
    private final PracticeSession session;
    private Inlay<PracticeView> inlay;
    private Font font;
    private FontMetrics metrics;
    private int width, height, lineHeight, bodyHeight, bodyTop, page, footerTop;
    private int answerRow;
    private final List<List<Row>> pages = new ArrayList<>();
    private final List<Button> buttons = new ArrayList<>();
    private final List<Button> optionHits = new ArrayList<>();
    private final Timer resize = new Timer(500, e -> checkLayout());
    private String layoutKey = "";
    private boolean disposed;

    PracticeView(Editor editor, PracticeSession session) { this.editor = editor; this.session = session; layout(); }
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
    void firstPage() { page = 0; refresh(); }
    void answerPage() {
        refresh();
        int rows = 0;
        for (int i = 0; i < pages.size(); i++) {
            rows += pages.get(i).size();
            if (answerRow < rows) { page = i; break; }
        }
        refresh();
    }
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
            case "page-" -> movePage(-1);
            case "page+" -> movePage(1);
            case "previous" -> session.move(-1);
            case "next" -> session.move(1);
            case "submit" -> session.submit();
            case "retry" -> session.retry();
            case "filter" -> session.filter();
            case "hide" -> session.hide();
            default -> session.choose(action);
        }
    }
    // 按正文行数分页；隐藏辅助区时同时移除占位和按钮命中区域。
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
            if (!q.material().isEmpty()) text(rows, "材料：" + q.material(), null);
            for (var image : q.materialImages()) picture(rows, image, null);
            text(rows, q.stem(), null);
            for (var image : q.images()) picture(rows, image, null);
            for (int i = 0; i < q.options().size(); i++) {
                String letter = String.valueOf((char) ('A' + i));
                var option = q.options().get(i);
                boolean selected = progress.choices.getOrDefault(q.id(), "").contains(letter);
                text(rows, (selected ? "[" + letter + "] " : letter + ". ") + option.text(), letter);
                if (option.image() != null) picture(rows, option.image(), letter);
            }
            answerRow = rows.size();
            if (progress.submitted.containsKey(q.id())) {
                text(rows, progress.result(q), null);
                text(rows, "解析：" + (q.explanation().isEmpty() ? "未提供解析" : q.explanation()), null);
            }
        }
        pages.clear();
        var current = new ArrayList<Row>();
        int used = 0;
        for (var row : rows) {
            if (used + row.height > bodyHeight && !current.isEmpty()) { pages.add(current); current = new ArrayList<>(); used = 0; }
            current.add(row);
            used += row.height;
        }
        if (!current.isEmpty() || pages.isEmpty()) pages.add(current);
        page = Math.max(0, Math.min(page, pages.size() - 1));
        bodyTop = (session.isDetailsVisible() ? 2 * lineHeight : 0) + 4;
        footerTop = bodyTop + bodyHeight + 4;
        buttons.clear();
        var available = progress.indices(session.paper());
        int at = available.indexOf(progress.index);
        boolean submitted = !empty && progress.submitted.containsKey(session.question().id());
        String selected = empty ? "" : progress.choices.getOrDefault(session.question().id(), "");
        if (session.isDetailsVisible()) addButtons(footerTop, new String[][] {
                {"上一页", "page-", Boolean.toString(page > 0)}, {"下一页", "page+", Boolean.toString(page + 1 < pages.size())},
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
            if (row.action != null) optionHits.add(new Button(row.action, row.action, !submitted, new Rectangle(6, y, width - 12, row.height)));
            y += row.height;
        }
    }
    private void addButtons(int top, String[][] values) {
        int x = 8, y = top;
        for (var value : values) {
            if (!session.empty() && value[1].matches("[A-D]") && !session.question().validChoice(value[1])) continue;
            String label = "[" + value[0] + "]";
            int w = metrics.stringWidth(label) + 10;
            if (x + w > width - 8 && x > 8) { x = 8; y += lineHeight + 3; }
            buttons.add(new Button(label, value[1], Boolean.parseBoolean(value[2]), new Rectangle(x, y, w, lineHeight + 3)));
            x += w;
        }
        height = y + lineHeight + 10;
    }
    private void text(List<Row> rows, String text, String action) {
        int max = Math.max(20, width - 18 - metrics.stringWidth("// "));
        for (String paragraph : text.replace("\t", "    ").split("\\R", -1)) {
            StringBuilder line = new StringBuilder();
            int pixels = 0;
            for (int offset = 0; offset < paragraph.length();) {
                int cp = paragraph.codePointAt(offset);
                String part = new String(Character.toChars(cp));
                int advance = metrics.stringWidth(part);
                if (pixels + advance > max && !line.isEmpty()) {
                    rows.add(new Row("// " + line, null, action, lineHeight, 0));
                    line.setLength(0); pixels = 0;
                }
                line.append(part); pixels += advance; offset += Character.charCount(cp);
            }
            rows.add(new Row("// " + line, null, action, lineHeight, 0));
        }
    }
    private void picture(List<Row> rows, BufferedImage image, String action) {
        double scale = Math.min(1.0, Math.min((width - 24.0) / image.getWidth(), (bodyHeight - 8.0) / image.getHeight()));
        rows.add(new Row(null, image, action, Math.max(1, (int) (image.getHeight() * scale)) + 8,
                Math.max(1, (int) (image.getWidth() * scale))));
    }
    @Override public int calcWidthInPixels(Inlay inlay) { return width; }
    @Override public int calcHeightInPixels(Inlay inlay) { return height; }
    @Override public String getContextMenuGroupId(Inlay inlay) { return "Moyu.Practice.Menu"; }
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
                g.drawString(shorten("// " + status + " · 页 " + (page + 1) + "/" + pages.size()
                        + (progress.onlyWrong ? " · 错题" : "")), 8, lineHeight + metrics.getAscent() + 2);
            }
            int y = bodyTop;
            String selected = session.empty() ? "" : progress.choices.getOrDefault(session.question().id(), "");
            for (var row : pages.get(page)) {
                g.setColor(row.action != null && selected.contains(row.action) ? new Color(0xD99545) : normal);
                if (row.image != null) g.drawImage(row.image, 10, y + 4, row.imageWidth, row.height - 8, null);
                else g.drawString(row.text, 8, y + metrics.getAscent());
                y += row.height;
            }
            for (var button : buttons) {
                g.setColor(!button.enabled ? normal : (button.action.matches("[A-D]") && selected.contains(button.action)) ? new Color(0xD99545) : scheme.getDefaultForeground());
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
