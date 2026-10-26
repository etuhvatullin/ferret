package ferret.ui;

import com.formdev.flatlaf.FlatClientProperties;
import ferret.core.Model.Connection;
import ferret.ui.AppState.ConsoleTab;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Панель вкладок: лише ті, що вміщаються (поточна — завжди), решта — у «усі вкладки» (кнопка з числом: пошук, групи
// за серверами). Вкладка: крапка кольору сервера, назва, сервер дрібно (якщо назва про нього не каже), × ; поточна —
// медове підкреслення, інші — смужка кольору свого сервера.
public final class TabBar extends JPanel {
    private final AppState state;
    final JPanel strip = new JPanel();
    private final JButton all = Ui.plainButton("", null);
    private final JButton plus = Ui.iconButton(Ui.icon(Ui.Glyph.PLUS, Theme.ACCENT), "Нова вкладка (" + Theme.CMD + "T)");

    public TabBar(AppState state) {
        super(new BorderLayout());
        this.state = state;
        setBackground(Theme.PANEL);
        setBorder(BorderFactory.createMatteBorder(1, 0, 1, 0, Theme.LINE));
        setPreferredSize(new Dimension(10, 36));
        strip.setLayout(new BoxLayout(strip, BoxLayout.X_AXIS));
        strip.setOpaque(false);
        add(strip, BorderLayout.CENTER);
        JPanel right = new JPanel();
        right.setLayout(new BoxLayout(right, BoxLayout.X_AXIS));
        right.setOpaque(false);
        all.setIcon(Ui.icon(Ui.Glyph.COPY, Theme.MUTED));
        all.setToolTipText("Усі вкладки");
        all.addActionListener(e -> allTabs().show(all, all.getWidth() - 340, all.getHeight()));
        plus.addActionListener(e -> state.newTab("", null, state.currentConnectionID));
        right.add(all);
        right.add(plus);
        right.add(Ui.gap(6));
        add(right, BorderLayout.EAST);
        addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) { rebuild(); }
        });
        state.on(e -> { if (e.equals("tabs") || e.equals("connections") || e.equals("consoles")) rebuild(); });
    }

    String server(ConsoleTab t) {
        Connection c = state.connection(t);
        return c == null || t.title.startsWith(c.name) ? null : c.name;
    }

    int width(ConsoleTab t, FontMetrics body, FontMetrics small) {
        String s = server(t);
        return 10 + 11 + 6 + body.stringWidth(t.title) + (s == null ? 0 : 6 + small.stringWidth(s)) + 6 + 16 + 10;
    }

    // Які вкладки стають на панель: по порядку, поки вміщаються; поточна — завжди (на місце останніх, що вміщались).
    static List<Integer> fitting(List<Integer> widths, int room, int current) {
        List<Integer> shown = new ArrayList<>();
        int used = 0;
        for (int i = 0; i < widths.size(); i++) {
            if (used + widths.get(i) > room) break;
            shown.add(i);
            used += widths.get(i);
        }
        if (current >= 0 && !shown.contains(current)) {
            while (!shown.isEmpty() && used + widths.get(current) > room) used -= widths.get(shown.remove(shown.size() - 1));
            shown.add(current);
        }
        return shown;
    }

    void rebuild() {
        strip.removeAll();
        FontMetrics body = getFontMetrics(getFont().deriveFont(13f)), small = getFontMetrics(getFont().deriveFont(10f));
        List<Integer> widths = new ArrayList<>();
        for (ConsoleTab t : state.tabs) widths.add(width(t, body, small));
        int room = Math.max(120, getWidth() - 110);
        List<Integer> shown = fitting(widths, room, state.tabs.indexOf(state.current));
        for (int i : shown) strip.add(tab(state.tabs.get(i)));
        strip.add(Box.createHorizontalGlue());
        int hidden = state.tabs.size() - shown.size();
        all.setText(String.valueOf(state.tabs.size()));
        all.putClientProperty(FlatClientProperties.STYLE, hidden > 0 ? "foreground: #F2A33A; borderWidth: 0; focusWidth: 0; arc: 8"
                : "foreground: #B3A390; borderWidth: 0; focusWidth: 0; arc: 8");
        all.setToolTipText(hidden > 0 ? "Усі вкладки — ще " + hidden + " не вміщаються" : "Усі вкладки");
        strip.revalidate();
        strip.repaint();
    }

    JComponent tab(ConsoleTab t) {
        boolean on = t == state.current;
        Connection c = state.connection(t);
        Color mark = Theme.mark(c);
        JPanel p = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                g.setColor(on ? Theme.ACCENT : Theme.alpha(mark, 110));
                g.fillRect(0, getHeight() - 2, getWidth(), 2);
            }
        };
        p.setLayout(new BoxLayout(p, BoxLayout.X_AXIS));
        p.setBackground(on ? Theme.RAISED : Theme.PANEL);
        p.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 6));
        p.add(Ui.dot(mark));
        p.add(Ui.gap(6));
        JLabel title = Ui.label(t.title, on ? Theme.TEXT : Theme.MUTED, on, 13);
        p.add(title);
        String s = server(t);
        if (s != null) {
            p.add(Ui.gap(6));
            p.add(Ui.label(s, Theme.alpha(Theme.MUTED, 170), false, 10));
        }
        p.add(Ui.gap(6));
        JButton x = Ui.iconButton(Ui.icon(Ui.Glyph.CLOSE, Theme.MUTED, 11), "Закрити вкладку — консоль лишиться в «Консолях»");
        x.setPreferredSize(new Dimension(18, 18));
        x.setMaximumSize(new Dimension(18, 18));
        x.addActionListener(e -> state.closeTab(t));
        p.add(x);
        p.setMaximumSize(new Dimension(p.getPreferredSize().width + 4, 34));
        p.setToolTipText(c == null ? t.title : t.title + " · " + c.name);
        p.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                if (javax.swing.SwingUtilities.isMiddleMouseButton(e)) state.closeTab(t); else state.selectTab(t);
            }
        });
        return p;
    }

    // «Усі вкладки»: пошук, групи за серверами.
    JPopupMenu allTabs() {
        JPopupMenu m = new JPopupMenu();
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        p.setBackground(Theme.RAISED);
        JTextField q = new JTextField();
        q.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Знайти вкладку");
        JPanel list = new JPanel();
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setOpaque(false);
        Runnable fill = () -> {
            list.removeAll();
            String s = q.getText().trim().toLowerCase();
            Map<String, List<ConsoleTab>> groups = new LinkedHashMap<>();
            for (ConsoleTab t : state.tabs) {
                if (!s.isEmpty() && !t.title.toLowerCase().contains(s)) continue;
                Connection c = state.connection(t);
                groups.computeIfAbsent(c == null ? "без сервера" : c.name, k -> new ArrayList<>()).add(t);
            }
            groups.forEach((name, tabs) -> {
                JLabel h = Ui.label(name.toUpperCase(), Theme.MUTED, true, 10);
                h.setBorder(BorderFactory.createEmptyBorder(6, 4, 2, 4));
                list.add(h);
                for (ConsoleTab t : tabs) {
                    JButton b = Ui.plainButton(t.title, new Sidebar.DotIcon(Theme.mark(state.connection(t))));
                    b.setHorizontalAlignment(JButton.LEFT);
                    b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
                    if (t == state.current) b.putClientProperty(FlatClientProperties.STYLE, "background: #F2A33A; foreground: #1F1A15; borderWidth: 0; arc: 8");
                    b.addActionListener(e -> { m.setVisible(false); state.selectTab(t); });
                    list.add(b);
                }
            });
            list.revalidate();
            list.repaint();
        };
        q.getDocument().addDocumentListener(Sidebar.onChange(fill));
        fill.run();
        javax.swing.JScrollPane sp = new javax.swing.JScrollPane(list);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.setPreferredSize(new Dimension(320, Math.min(420, state.tabs.size() * 30 + 60)));
        p.add(q, BorderLayout.NORTH);
        p.add(sp, BorderLayout.CENTER);
        m.add(p);
        javax.swing.SwingUtilities.invokeLater(q::requestFocusInWindow);
        return m;
    }
}
