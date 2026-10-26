package ferret.ui;

import ferret.core.SqlText.Suggestion;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JLayeredPane;
import javax.swing.JRootPane;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.Consumer;

// Список підказок під курсором — шар над вмістом головного вікна, а не окреме вікно: поки окреме вікно зʼявлялось чи
// ховалось, система (заміряно під Linux) губила натискання — «lake.sa» після Tab ставало «lake». Фокус лишається в
// редакторі: клавіші списку (↑ ↓ Tab ↵ Esc) перехоплює редактор, решта йде в текст, і список звужується під набране.
public final class SuggestionPopup {
    private final JComponent owner;
    private final JScrollPane box;
    private final DefaultListModel<Suggestion> model = new DefaultListModel<>();
    private final JList<Suggestion> list = new JList<>(model);
    public Consumer<Suggestion> onPick = s -> { };

    public SuggestionPopup(JComponent owner) {
        this.owner = owner;
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setBackground(Theme.RAISED);
        list.setFocusable(false);
        list.setCellRenderer((l, s, index, selected, focus) -> row(s, selected));
        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                int i = list.locationToIndex(e.getPoint());
                if (i >= 0) onPick.accept(model.get(i));
            }
        });
        box = new JScrollPane(list);
        box.setBorder(BorderFactory.createLineBorder(Theme.LINE));
        box.setVisible(false);
    }

    private Component row(Suggestion s, boolean on) {
        JPanel p = new JPanel(new BorderLayout(8, 0));
        p.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        p.setBackground(on ? Theme.ACCENT : Theme.RAISED);
        Color fg = on ? Theme.ON_ACCENT : Theme.TEXT;
        JLabel name = Ui.label(s.label(), fg, false, 13);
        name.setFont(Theme.mono(13));
        name.setIcon(Ui.icon(glyph(s), on ? Theme.ON_ACCENT : Theme.MUTED, 12));
        name.setIconTextGap(8);
        JLabel detail = Ui.label(s.detail(), on ? Theme.alpha(Theme.ON_ACCENT, 200) : Theme.MUTED, false, 11);
        p.add(name, BorderLayout.WEST);
        p.add(detail, BorderLayout.EAST);
        return p;
    }

    static Ui.Glyph glyph(Suggestion s) {
        return switch (s.kind()) {
            case TABLE -> Ui.Glyph.TABLE;
            case CONTAINER -> Ui.Glyph.FOLDER;
            case FUNCTION -> Ui.Glyph.CHEVRON;
            case KEYWORD -> Ui.Glyph.MINUS;
            case COLUMN -> Ui.Glyph.COLUMN;
            case QUERY -> Ui.Glyph.PLAY;
            case OTHER -> s.isTable() ? Ui.Glyph.TABLE : Ui.Glyph.FOLDER;
        };
    }

    public boolean isVisible() { return box.isVisible() && box.getParent() != null; }

    public Suggestion current() { int i = list.getSelectedIndex(); return i >= 0 ? model.get(i) : null; }

    public int selected() { return list.getSelectedIndex(); }

    public int size() { return model.size(); }

    // Під точкою at (координати редактора); кращий збіг — першим і вибраним. Не вміщається знизу — над рядком.
    public void show(List<Suggestion> items, Point at) {
        model.clear();
        for (Suggestion s : items) model.addElement(s);
        list.setSelectedIndex(0);
        list.ensureIndexIsVisible(0);
        JRootPane root = SwingUtilities.getRootPane(owner);
        if (root == null) return;
        JLayeredPane layers = root.getLayeredPane();
        if (box.getParent() != layers) {
            if (box.getParent() != null) box.getParent().remove(box);
            layers.add(box, JLayeredPane.POPUP_LAYER);
        }
        int w = Math.min(520, layers.getWidth() - 8), h = Math.min(items.size(), 12) * 22 + 6;
        Point p = SwingUtilities.convertPoint(owner, at, layers);
        int x = Math.max(4, Math.min(p.x - 12, layers.getWidth() - w - 4));
        int y = p.y + 2;
        if (y + h > layers.getHeight() - 4) y = Math.max(4, p.y - owner.getFontMetrics(owner.getFont()).getHeight() - h - 4);
        box.setBounds(x, y, w, h);
        box.setVisible(true);
        box.revalidate();
        box.repaint();
    }

    public void hide() {
        if (!box.isVisible()) return;
        box.setVisible(false);
        if (box.getParent() != null) box.getParent().repaint(box.getX(), box.getY(), box.getWidth(), box.getHeight());
    }

    public void move(int delta) {
        if (model.isEmpty()) return;
        int i = Math.max(0, Math.min(model.size() - 1, list.getSelectedIndex() + delta));
        list.setSelectedIndex(i);
        list.ensureIndexIsVisible(i);
    }
}
