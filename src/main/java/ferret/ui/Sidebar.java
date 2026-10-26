package ferret.ui;

import com.formdev.flatlaf.FlatClientProperties;
import ferret.core.I18n;
import ferret.core.Jdbc;
import ferret.core.Model.Connection;
import ferret.core.SchemaItem;
import ferret.core.SqlText;
import ferret.ui.AppState.ConsoleTab;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

// Бічна панель: вибір сервера (меню) і шестерня підключень, перемикач «Таблиці | Консолі». Таблиці — пошук і дерево
// схем (плоским списком рядків з відступами): клік — розгорнути, подвійний клік по таблиці — перші 100 рядків у новій
// вкладці, правий клік — вставити імʼя чи скопіювати. Консолі — усі консолі сервера, відкриті й закриті; видалити
// остаточно — лише тут і з підтвердженням. Унизу — «Останні сервери» (скільки — число в заголовку).
public final class Sidebar extends JPanel {
    private final AppState state;
    private final JLabel serverName = Ui.label("", Theme.TEXT, true, 13);
    private JButton serverPick;
    private final JComponent serverDot = new DotHolder();
    private final JLabel lock = Ui.label("", Theme.MUTED, false, 11);
    private final JToggleButton tablesBtn = new JToggleButton(I18n.t("side.tables"));
    private final JToggleButton consolesBtn = new JToggleButton(I18n.t("side.consoles"));
    private final CardLayout modeCards = new CardLayout();
    private final JPanel modes = new JPanel(modeCards);
    private final JTextField search = new JTextField();
    private final JLabel schemaInfo = Ui.label("", Theme.MUTED, false, 11);
    private final DefaultListModel<TreeRow> treeModel = new DefaultListModel<>();
    private final JList<TreeRow> treeList = new JList<>(treeModel);
    private final DefaultListModel<ConsoleTab> consolesModel = new DefaultListModel<>();
    private final JList<ConsoleTab> consolesList = new JList<>(consolesModel);
    private final JTextField consoleSearch = new JTextField();
    private final JPanel recentRows = new JPanel();
    private final JScrollPane recentScroll;
    private final JSpinner recentCount;
    public Consumer<Connection> onEditConnection = c -> { };
    public Runnable onNewConnection = () -> { };
    public Consumer<String> onInsert = s -> { };

    record TreeRow(SchemaItem item, int level, boolean leaf, boolean open, String note) { }

    public Sidebar(AppState state) {
        super(new BorderLayout());
        this.state = state;
        setBackground(Theme.PANEL);
        setPreferredSize(new Dimension(290, 0));
        setMinimumSize(new Dimension(220, 0));

        Box top = Box.createVerticalBox();
        JPanel lights = new JPanel();   // місце під кнопки вікна macOS (заголовок прозорий, вміст — під ним)
        lights.setOpaque(false);
        lights.setPreferredSize(new Dimension(10, Theme.MAC ? 40 : 6));
        lights.setMaximumSize(new Dimension(Integer.MAX_VALUE, Theme.MAC ? 40 : 6));
        lights.setAlignmentX(LEFT_ALIGNMENT);
        lights.putClientProperty(FlatClientProperties.COMPONENT_TITLE_BAR_CAPTION, true);
        top.add(lights);
        top.add(header());
        top.add(modeSwitch());
        add(top, BorderLayout.NORTH);

        modes.setOpaque(false);
        modes.add(tablesView(), "tables");
        modes.add(consolesView(), "consoles");
        add(modes, BorderLayout.CENTER);

        recentRows.setLayout(new BoxLayout(recentRows, BoxLayout.Y_AXIS));
        recentRows.setBackground(Theme.PANEL);
        recentScroll = new JScrollPane(recentRows);
        recentScroll.setBorder(BorderFactory.createEmptyBorder());
        recentScroll.getViewport().setBackground(Theme.PANEL);
        recentCount = new JSpinner(new SpinnerNumberModel(state.recentCount(), 1, 50, 1));
        recentCount.setToolTipText(I18n.t("side.recentCountTip"));
        recentCount.addChangeListener(e -> { state.setSetting("recentCount", ((Number) recentCount.getValue()).longValue()); rebuildRecent(); });
        add(recentSection(), BorderLayout.SOUTH);

        String mode = String.valueOf(state.settings.getOrDefault("sidebarMode", "tables"));
        (mode.equals("consoles") ? consolesBtn : tablesBtn).setSelected(true);
        modeCards.show(modes, mode);

        state.on(e -> {
            switch (e) {
                case "tree", "connection", "connections" -> { rebuildHeader(); rebuildTree(); rebuildConsoles(); rebuildRecent(); }
                case "tabs", "consoles" -> { rebuildConsoles(); rebuildHeader(); }
                default -> { }
            }
        });
        rebuildHeader();
        rebuildTree();
        rebuildConsoles();
        rebuildRecent();
    }

    // MARK: шапка й перемикач

    final class DotHolder extends JComponent {
        DotHolder() { setPreferredSize(new Dimension(11, 11)); setMaximumSize(new Dimension(11, 11)); }

        @Override protected void paintComponent(Graphics g) {
            var g2 = Ui.smooth(g);
            g2.setColor(Theme.mark(state.currentConnection()));
            g2.fillOval(1, (getHeight() - 9) / 2, 9, 9);
            g2.dispose();
        }
    }

    // Назва сервера стискається з «…» (повна — у підказці), а замок і шестерня праворуч видно завжди: довга назва
    // («ClickHouse BI · .190») колись виштовхувала шестерню під праву частину вікна.
    private JComponent header() {
        JPanel r = new JPanel(new BorderLayout(6, 0)) {
            @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
        };
        r.setOpaque(false);
        r.setBorder(BorderFactory.createEmptyBorder(4, 12, 6, 8));
        r.setAlignmentX(LEFT_ALIGNMENT);
        JButton pick = new JButton() {
            @Override public Dimension getMaximumSize() { return getPreferredSize(); }
        };
        serverName.setMinimumSize(new Dimension(24, 0));
        pick.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_BORDERLESS);
        pick.setLayout(new BoxLayout(pick, BoxLayout.X_AXIS));
        pick.add(serverDot);
        pick.add(Ui.gap(7));
        pick.add(serverName);
        pick.add(Ui.gap(4));
        pick.add(new JLabel(Ui.icon(Ui.Glyph.CHEVRON, Theme.MUTED, 12)));
        pick.setFocusable(false);
        pick.addActionListener(e -> serverMenu().show(pick, 0, pick.getHeight()));
        serverPick = pick;
        Box left = Box.createHorizontalBox();
        left.add(pick);
        left.add(Box.createHorizontalGlue());
        JPanel right = Ui.row(0, 0, 0, 0);
        right.add(lock);
        JButton gear = Ui.iconButton(Ui.icon(Ui.Glyph.GEAR, Theme.MUTED), I18n.t("side.connectionSettingsTip", Theme.CMD));
        gear.addActionListener(e -> onEditConnection.accept(state.currentConnection()));
        right.add(gear);
        r.add(left, BorderLayout.CENTER);
        r.add(right, BorderLayout.EAST);
        return r;
    }

    private JPopupMenu serverMenu() {
        JPopupMenu m = new JPopupMenu();
        for (Connection c : state.connections) {
            JMenuItem i = new JMenuItem(c.name, new DotIcon(Theme.mark(c)));
            if (c.id.equals(state.currentConnectionID)) i.setFont(i.getFont().deriveFont(java.awt.Font.BOLD));
            i.addActionListener(e -> state.goToServer(c.id));
            m.add(i);
        }
        if (!state.connections.isEmpty()) m.addSeparator();
        JMenuItem add = new JMenuItem(I18n.t("side.newConnection"));
        add.addActionListener(e -> onNewConnection.run());
        m.add(add);
        Connection cur = state.currentConnection();
        if (cur != null) {
            JMenuItem edit = new JMenuItem(I18n.t("side.editConnection", cur.name));
            edit.addActionListener(e -> onEditConnection.accept(cur));
            m.add(edit);
        }
        return m;
    }

    static final class DotIcon implements javax.swing.Icon {
        final Color c;

        DotIcon(Color c) { this.c = c; }

        @Override public void paintIcon(Component comp, Graphics g, int x, int y) {
            var g2 = Ui.smooth(g);
            g2.setColor(c);
            g2.fillOval(x + 1, y + 1, 9, 9);
            g2.dispose();
        }

        @Override public int getIconWidth() { return 11; }

        @Override public int getIconHeight() { return 11; }
    }

    private JComponent modeSwitch() {
        JPanel seg = new JPanel(new GridLayout(1, 2, 2, 0));
        seg.setBackground(Theme.BG);
        seg.setBorder(BorderFactory.createEmptyBorder(3, 3, 3, 3));
        ButtonGroup g = new ButtonGroup();
        for (JToggleButton b : List.of(tablesBtn, consolesBtn)) {
            b.putClientProperty(FlatClientProperties.STYLE, "selectedBackground: #F2A33A; selectedForeground: #1F1A15; background: #17130F; "
                    + "borderWidth: 0; focusWidth: 0; arc: 8");
            b.setFocusable(false);
            g.add(b);
            seg.add(b);
        }
        tablesBtn.addActionListener(e -> setMode("tables"));
        consolesBtn.addActionListener(e -> setMode("consoles"));
        JPanel wrap = Ui.row(2, 10, 6, 10);
        wrap.add(seg);
        return wrap;
    }

    void setMode(String mode) {
        modeCards.show(modes, mode);
        state.setSetting("sidebarMode", mode);
    }

    void rebuildHeader() {
        Connection c = state.currentConnection();
        serverName.setText(c == null ? I18n.t("side.connection") : c.name);
        if (serverPick != null) serverPick.setToolTipText(c == null ? null : c.name + " · " + c.host);
        if (c == null) { lock.setText(""); lock.setIcon(null); }
        else if (c.readOnly) { lock.setText(I18n.t("side.readOnly")); lock.setIcon(Ui.icon(Ui.Glyph.LOCK, Theme.MUTED, 11)); lock.setForeground(Theme.MUTED);
            lock.setToolTipText(I18n.t("side.readOnlyTip")); }
        else { lock.setText(I18n.t("side.writeAllowed")); lock.setIcon(Ui.icon(Ui.Glyph.WARN, Theme.ERROR, 11)); lock.setForeground(Theme.ERROR);
            lock.setToolTipText(I18n.t("side.writeAllowedTip")); }
        consolesBtn.setText(I18n.t("side.consolesCount", state.consoles(state.currentConnectionID).size()));
        serverDot.repaint();
    }

    // MARK: таблиці

    private JComponent tablesView() {
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        Box top = Box.createVerticalBox();
        search.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, I18n.t("side.searchTables"));
        search.putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON, Ui.icon(Ui.Glyph.SEARCH, Theme.MUTED));
        search.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
        search.getDocument().addDocumentListener(onChange(this::rebuildTree));
        JPanel sw = Ui.row(0, 10, 4, 10);
        sw.add(search);
        top.add(sw);
        JPanel bar = Ui.row(2, 12, 4, 8);
        bar.add(schemaInfo);
        bar.add(Box.createHorizontalGlue());
        JButton refresh = Ui.plainButton(I18n.t("side.refresh"), Ui.icon(Ui.Glyph.REFRESH, Theme.MUTED, 12));
        refresh.putClientProperty(FlatClientProperties.STYLE, "font: -1; borderWidth: 0; background: null; margin: 1,4,1,4");
        refresh.setToolTipText(I18n.t("side.refreshTip", Theme.CMD));
        refresh.addActionListener(e -> state.refreshSchema());
        bar.add(refresh);
        top.add(bar);
        p.add(top, BorderLayout.NORTH);

        treeList.setBackground(Theme.PANEL);
        treeList.setFixedCellHeight(22);
        treeList.setCellRenderer((l, row, i, sel, focus) -> treeCell(row, sel));
        treeList.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                int i = treeList.locationToIndex(e.getPoint());
                if (i < 0 || !treeList.getCellBounds(i, i).contains(e.getPoint())) return;
                treeList.setSelectedIndex(i);
                TreeRow r = treeModel.get(i);
                if (SwingUtilities.isRightMouseButton(e) || e.isPopupTrigger()) { treeMenu(r).show(treeList, e.getX(), e.getY()); return; }
                if (r.item() == null) return;
                if (e.getClickCount() == 2 && isTable(r)) { preview(r.item().path()); return; }
                if (!r.leaf() && e.getClickCount() == 1) state.toggle(r.item().path());
            }
        });
        JScrollPane sp = new JScrollPane(treeList);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.getViewport().setBackground(Theme.PANEL);
        p.add(sp, BorderLayout.CENTER);
        return p;
    }

    boolean isTable(TreeRow r) {
        Connection c = state.currentConnection();
        return c != null && r.item() != null && r.item().path().size() == Jdbc.depth(c) - 1;
    }

    Component treeCell(TreeRow r, boolean sel) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.X_AXIS));
        p.setBackground(sel ? Theme.RAISED : Theme.PANEL);
        p.setBorder(BorderFactory.createEmptyBorder(0, 8 + r.level() * 16, 0, 8));
        if (r.item() == null) {
            // помилка — першим рядком і трикрапкою по ширині панелі (а не горизонтальною прокруткою), повністю — в підказці
            String first = r.note().lines().findFirst().orElse("");
            p.setLayout(new BorderLayout());   // у BoxLayout мітка не стискається й не ставить «…»
            p.add(Ui.label(first, r.note().startsWith("⚠") ? Theme.ERROR : Theme.MUTED, false, 12), BorderLayout.CENTER);
            p.setPreferredSize(new Dimension(40, 22));
            if (!first.equals(r.note())) p.setToolTipText("<html>" + r.note().replace("&", "&amp;").replace("<", "&lt;").replace("\n", "<br>") + "</html>");
            else if (r.note().startsWith("⚠")) p.setToolTipText(r.note());
            return p;
        }
        JLabel chevron = new JLabel(r.leaf() ? null : Ui.icon(Ui.Glyph.CHEVRON, Theme.MUTED, 12));
        if (!r.leaf() && !r.open()) chevron.setIcon(rotated(Ui.icon(Ui.Glyph.CHEVRON, Theme.MUTED, 12)));
        chevron.setPreferredSize(new Dimension(14, 14));
        p.add(chevron);
        p.add(Ui.gap(4));
        Connection c = state.currentConnection();
        int depth = c == null ? 3 : Jdbc.depth(c);
        boolean column = r.item().path().size() == depth, table = r.item().path().size() == depth - 1;
        p.add(new JLabel(Ui.icon(column ? Ui.Glyph.COLUMN : table ? Ui.Glyph.TABLE : Ui.Glyph.FOLDER, column ? Theme.alpha(Theme.MUTED, 150) : Theme.MUTED, 13)));
        p.add(Ui.gap(6));
        p.add(Ui.label(r.item().name(), Theme.TEXT, false, 13));
        if (r.item().detail() != null) {
            p.add(Ui.gap(8));
            p.add(Ui.label(r.item().detail().toLowerCase(), Theme.MUTED, false, 11));
        }
        if (r.note() != null) {
            p.add(Ui.gap(8));
            p.add(Ui.label(r.note(), Theme.MUTED, false, 11));
        }
        return p;
    }

    static javax.swing.Icon rotated(javax.swing.Icon i) {
        return new javax.swing.Icon() {
            @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                var g2 = (java.awt.Graphics2D) g.create();
                g2.rotate(-Math.PI / 2, x + i.getIconWidth() / 2.0, y + i.getIconHeight() / 2.0);
                i.paintIcon(c, g2, x, y);
                g2.dispose();
            }

            @Override public int getIconWidth() { return i.getIconWidth(); }

            @Override public int getIconHeight() { return i.getIconHeight(); }
        };
    }

    // Розгорнуте дерево — плоским списком рядків; пошук — серед завантаженого й серед усіх таблиць сервера.
    void rebuildTree() {
        treeModel.clear();
        Connection c = state.currentConnection();
        if (c == null) { schemaInfo.setText(""); return; }
        Instant upd = state.schemaUpdated.get(c.id);
        schemaInfo.setText(state.refreshing.contains(c.id) ? I18n.t("side.refreshing") : upd == null ? "" : I18n.t("side.schemaAge", ago(upd)));
        String q = search.getText().trim().toLowerCase();
        if (!q.isEmpty()) {
            List<SchemaItem> hits = new ArrayList<>();
            for (List<String> p : state.tables.getOrDefault(c.id, List.of())) {
                if (p.get(p.size() - 1).toLowerCase().contains(q) && hits.size() < 300) hits.add(new SchemaItem(p, null));
            }
            String pre = c.id + "/";
            state.tree.forEach((k, items) -> {
                if (!k.startsWith(pre)) return;
                for (SchemaItem i : items) if (i.detail() != null && i.name().toLowerCase().contains(q) && hits.size() < 300) hits.add(i);
            });
            for (SchemaItem i : hits) treeModel.addElement(new TreeRow(i, 0, true, false, String.join(".", i.path().subList(0, i.path().size() - 1))));
            if (hits.isEmpty()) treeModel.addElement(new TreeRow(null, 0, true, false, state.tables.containsKey(c.id) ? I18n.t("side.nothingFound") : I18n.t("side.tablesLoading")));
            return;
        }
        walk(List.of(), 0);
        String rootErr = state.treeErrors.get(state.treeKey(List.of()));
        if (rootErr != null) treeModel.addElement(new TreeRow(null, 0, true, false, "⚠ " + rootErr));
        if (state.loading.contains(state.treeKey(List.of()))) treeModel.addElement(new TreeRow(null, 0, true, false, I18n.t("side.loading")));
    }

    void walk(List<String> path, int level) {
        for (SchemaItem i : state.tree.getOrDefault(state.treeKey(path), List.of())) {
            String key = state.treeKey(i.path());
            boolean open = state.expanded.contains(key);
            boolean leaf = state.isLeaf(i.path());
            String note = state.loading.contains(key) ? "…" : null;
            treeModel.addElement(new TreeRow(i, level, leaf, open, note));
            if (state.treeErrors.containsKey(key)) treeModel.addElement(new TreeRow(null, level + 1, true, false, "⚠ " + state.treeErrors.get(key)));
            if (open) walk(i.path(), level + 1);
        }
    }

    static String ago(Instant t) {
        long m = Duration.between(t, Instant.now()).toMinutes();
        if (m < 1) return I18n.t("side.justNow");
        if (m < 60) return I18n.t("side.minutesAgo", m);
        long h = m / 60;
        if (h < 24) return I18n.t("side.hoursAgo", h);
        return I18n.t("side.daysAgo", h / 24);
    }

    JPopupMenu treeMenu(TreeRow r) {
        JPopupMenu m = new JPopupMenu();
        Connection c = state.currentConnection();
        if (r.item() == null || c == null) return m;
        JMenuItem ins = new JMenuItem(I18n.t("side.insertName"));
        ins.addActionListener(e -> onInsert.accept(r.leaf() && r.item().detail() != null ? r.item().name() : SqlText.qualifiedName(c.kind, r.item().path())));
        m.add(ins);
        JMenuItem copy = new JMenuItem(I18n.t("side.copyName"));
        copy.addActionListener(e -> ResultPanel.clip(r.item().detail() != null ? r.item().name() : SqlText.qualifiedName(c.kind, r.item().path())));
        m.add(copy);
        if (isTable(r) || r.note() != null && r.item().detail() == null && r.leaf()) {
            JMenuItem sel = new JMenuItem(I18n.t("side.copySelectAll", SqlText.selectAllSQL(c.kind, List.of("…")).replace("…", I18n.t("side.tablePlaceholder"))));
            sel.addActionListener(e -> ResultPanel.clip(SqlText.selectAllSQL(c.kind, r.item().path())));
            m.add(sel);
            JMenuItem prev = new JMenuItem(I18n.t("side.first100"));
            prev.addActionListener(e -> preview(r.item().path()));
            m.add(prev);
        }
        return m;
    }

    void preview(List<String> path) {
        Connection c = state.currentConnection();
        if (c == null) return;
        ConsoleTab t = state.newTab(SqlText.previewSQL(c.kind, path), path.get(path.size() - 1), c.id);
        state.run(t, t.sql, AppState.DEFAULT_ROW_LIMIT);
    }

    // MARK: консолі

    private JComponent consolesView() {
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        consoleSearch.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, I18n.t("side.searchConsoles"));
        consoleSearch.putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON, Ui.icon(Ui.Glyph.SEARCH, Theme.MUTED));
        consoleSearch.getDocument().addDocumentListener(onChange(this::rebuildConsoles));
        JPanel top = new JPanel(new BorderLayout(6, 0));
        top.setOpaque(false);
        top.setBorder(BorderFactory.createEmptyBorder(0, 10, 6, 10));
        top.add(consoleSearch, BorderLayout.CENTER);
        JButton add = Ui.iconButton(Ui.icon(Ui.Glyph.PLUS, Theme.TEXT), I18n.t("side.newConsoleTip", Theme.CMD));
        add.addActionListener(e -> state.newTab("", null, state.currentConnectionID));
        top.add(add, BorderLayout.EAST);
        p.add(top, BorderLayout.NORTH);
        consolesList.setBackground(Theme.PANEL);
        consolesList.setCellRenderer((l, t, i, sel, focus) -> consoleCell(t, sel));
        consolesList.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                int i = consolesList.locationToIndex(e.getPoint());
                if (i < 0 || !consolesList.getCellBounds(i, i).contains(e.getPoint())) return;
                ConsoleTab t = consolesModel.get(i);
                if (SwingUtilities.isRightMouseButton(e) || e.isPopupTrigger()) { consoleMenu(t).show(consolesList, e.getX(), e.getY()); return; }
                state.openConsole(t.id);
            }
        });
        JScrollPane sp = new JScrollPane(consolesList);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.getViewport().setBackground(Theme.PANEL);
        p.add(sp, BorderLayout.CENTER);
        return p;
    }

    Component consoleCell(ConsoleTab t, boolean sel) {
        boolean open = t.closedAt == null, current = t == state.current;
        JPanel p = new JPanel(new BorderLayout(8, 0));
        p.setBorder(BorderFactory.createEmptyBorder(5, 12, 5, 10));
        p.setBackground(current ? Theme.ACCENT : sel ? Theme.RAISED : Theme.PANEL);
        Color fg = current ? Theme.ON_ACCENT : Theme.TEXT, mut = current ? Theme.alpha(Theme.ON_ACCENT, 190) : Theme.MUTED;
        Box b = Box.createVerticalBox();
        b.add(Ui.label((open ? "● " : "") + t.title, fg, open, 13));
        String first = firstLine(t.sql);
        b.add(Ui.label(first.isEmpty() ? I18n.t("side.emptyConsole") : first, mut, false, 11));
        p.add(b, BorderLayout.CENTER);
        p.add(Ui.label(Sidebar.ago(t.modified), mut, false, 10), BorderLayout.EAST);
        return p;
    }

    // Перший змістовний рядок запиту — підпис консолі (зупиняється на першому: консолі бувають по 26 тис. рядків).
    static String firstLine(String sql) {
        int i = 0;
        while (i < sql.length()) {
            int j = sql.indexOf('\n', i);
            String line = (j < 0 ? sql.substring(i) : sql.substring(i, j)).trim();
            if (!line.isEmpty() && !line.startsWith("--") && !line.equals(";")) return line.length() > 80 ? line.substring(0, 80) : line;
            if (j < 0) break;
            i = j + 1;
        }
        return "";
    }

    void rebuildConsoles() {
        consolesModel.clear();
        String q = consoleSearch.getText().trim().toLowerCase();
        for (ConsoleTab t : state.consoles(state.currentConnectionID)) {
            if (q.isEmpty() || t.title.toLowerCase().contains(q) || t.sql.toLowerCase().contains(q)) consolesModel.addElement(t);
        }
    }

    JPopupMenu consoleMenu(ConsoleTab t) {
        JPopupMenu m = new JPopupMenu();
        JMenuItem open = new JMenuItem(I18n.t("side.open"));
        open.addActionListener(e -> state.openConsole(t.id));
        m.add(open);
        if (t.closedAt == null) {
            JMenuItem close = new JMenuItem(I18n.t("side.closeTab"));
            close.addActionListener(e -> state.closeTab(t));
            m.add(close);
        }
        JMenuItem rename = new JMenuItem(I18n.t("side.rename"));
        rename.addActionListener(e -> {
            String s = (String) JOptionPane.showInputDialog(this, I18n.t("side.renamePrompt"), I18n.t("side.renameTitle"), JOptionPane.PLAIN_MESSAGE, null, null, t.title);
            state.renameConsole(t, s);
        });
        m.add(rename);
        m.addSeparator();
        JMenuItem del = new JMenuItem(I18n.t("side.delete"));
        del.setForeground(Theme.ERROR);
        del.addActionListener(e -> {
            int a = JOptionPane.showConfirmDialog(this, I18n.t("side.deleteConfirm", t.title),
                    I18n.t("side.deleteTitle"), JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            if (a == JOptionPane.OK_OPTION) state.deleteConsole(t);
        });
        m.add(del);
        return m;
    }

    // MARK: останні сервери

    private JComponent recentSection() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(Theme.PANEL);
        p.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.LINE));
        JPanel h = Ui.row(8, 12, 4, 10);
        h.add(Ui.label(I18n.t("side.recentServers"), Theme.MUTED, true, 11));
        h.add(Box.createHorizontalGlue());
        recentCount.setMaximumSize(new Dimension(64, 24));
        recentCount.setPreferredSize(new Dimension(64, 24));
        h.add(recentCount);
        p.add(h, BorderLayout.NORTH);
        p.add(recentScroll, BorderLayout.CENTER);
        return p;
    }

    void rebuildRecent() {
        recentRows.removeAll();
        List<Connection> list = state.recentConnections();
        for (int i = 0; i < list.size(); i++) recentRows.add(recentRow(list.get(i), i));
        recentRows.add(Box.createVerticalStrut(6));
        int h = list.size() * 40 + 6;
        int max = Math.max(120, (int) (getHeight() * 0.45));
        recentScroll.setPreferredSize(new Dimension(10, Math.min(h, max)));
        revalidate();
        repaint();
    }

    JComponent recentRow(Connection c, int index) {
        boolean on = c.id.equals(state.currentConnectionID);
        JPanel r = new JPanel(new BorderLayout(8, 0)) {
            @Override protected void paintComponent(Graphics g) {
                if (on) Ui.fillRounded(g, Theme.ACCENT, 6, 1, getWidth() - 12, getHeight() - 2, 10);
            }
        };
        r.setOpaque(false);
        r.setBorder(BorderFactory.createEmptyBorder(4, 16, 4, 16));
        Box names = Box.createVerticalBox();
        names.add(Ui.label(c.name, on ? Theme.ON_ACCENT : Theme.TEXT, on, 12));
        names.add(Ui.label(c.host.isEmpty() ? c.kind.title : c.host, on ? Theme.alpha(Theme.ON_ACCENT, 190) : Theme.MUTED, false, 10));
        r.add(Ui.dot(Theme.mark(c)), BorderLayout.WEST);
        r.add(names, BorderLayout.CENTER);
        r.add(Ui.label(index < 9 ? (Theme.MAC ? "⌃" : "Ctrl+") + (index + 1) : "", on ? Theme.alpha(Theme.ON_ACCENT, 190) : Theme.MUTED, false, 10), BorderLayout.EAST);
        r.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        r.setPreferredSize(new Dimension(10, 40));
        r.setAlignmentX(LEFT_ALIGNMENT);
        r.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { state.goToServer(c.id); }
        });
        r.setToolTipText(c.kind.title + " · " + c.host);
        return r;
    }

    static DocumentListener onChange(Runnable r) {
        return new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { r.run(); }
            @Override public void removeUpdate(DocumentEvent e) { r.run(); }
            @Override public void changedUpdate(DocumentEvent e) { r.run(); }
        };
    }
}
