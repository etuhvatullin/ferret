package ferret.ui;

import com.formdev.flatlaf.FlatClientProperties;
import ferret.App;
import ferret.core.I18n;
import ferret.core.Model.Connection;
import ferret.core.Model.DBKind;
import ferret.ui.AppState.ConsoleTab;
import ferret.ui.AppState.HistoryItem;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JRootPane;
import javax.swing.JSplitPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.event.ActionListener;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;

// Головне вікно: бічна панель ліворуч; праворуч — заголовок зі знаком FERRET (по центру правої частини), вкладки,
// панель запуску, смуга сервера, редактор зверху і результат знизу. Межі між частинами памʼятаються. Меню «Запит» і
// «Правка» з клавішами: ⌘ на Mac, Ctrl на Windows.
public final class MainWindow extends JFrame {
    final AppState state = new AppState();
    final Sidebar sidebar;
    final TabBar tabBar;
    final EditorPanel editor;
    final ResultPanel result;
    final JSplitPane split, main;
    private final JLabel bandName = Ui.label("", Theme.TEXT, true, 12);
    private final JLabel bandHost = Ui.label("", Theme.MUTED, false, 12);
    private final JLabel bandLock = Ui.label("", Theme.MUTED, false, 11);
    private final JButton runBtn = Ui.accentButton(I18n.t("main.run"), Ui.icon(Ui.Glyph.PLAY, Theme.ON_ACCENT, 12));
    private final JButton stopBtn = Ui.plainButton(I18n.t("main.stop"), Ui.icon(Ui.Glyph.STOP, Theme.TEXT, 12));
    public final UpdateBanner update;

    public static MainWindow open() {
        MainWindow w = new MainWindow();
        if (System.getenv("FERRET_SNAPSHOT_PAINT") != null) w.setLocation(-6000, -6000);   // знімок — поза екраном людини
        w.setVisible(true);
        return w;
    }

    MainWindow() {
        super("Ferret");
        state.load();
        Image icon = Theme.image("icon.png");
        if (icon != null) setIconImage(icon);
        JRootPane rp = getRootPane();
        rp.putClientProperty(FlatClientProperties.TITLE_BAR_SHOW_TITLE, false);
        rp.putClientProperty(FlatClientProperties.TITLE_BAR_SHOW_ICON, false);
        if (Theme.MAC) {
            rp.putClientProperty(FlatClientProperties.FULL_WINDOW_CONTENT, true);
            rp.putClientProperty("apple.awt.fullWindowContent", true);
            rp.putClientProperty("apple.awt.transparentTitleBar", true);
            rp.putClientProperty("apple.awt.windowTitleVisible", false);
        }

        sidebar = new Sidebar(state);
        tabBar = new TabBar(state);
        editor = new EditorPanel(state);
        result = new ResultPanel(state);
        update = new UpdateBanner();
        update.onQuit = this::quit;
        editor.onRun = sql -> state.run(state.current, sql, AppState.DEFAULT_ROW_LIMIT);
        sidebar.onEditConnection = c -> openConnections(c == null ? null : c.id, null);
        sidebar.onNewConnection = () -> openConnections(null, DBKind.TRINO);
        sidebar.onInsert = editor::insertText;

        split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, editor, result);
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setDividerSize(6);
        split.setResizeWeight(0.4);
        split.setContinuousLayout(true);

        JPanel right = new JPanel(new BorderLayout());
        right.setBackground(Theme.BG);
        // Mac: меню — угорі екрана, знак — у смузі над вкладками (заголовок прозорий). Windows: меню й знак — у рідному
        // заголовку FlatLaf (вбудоване меню). Смуга з FULL_WINDOW_CONTENT на Windows не годиться: окремо від меню вона
        // давала два ряди, а меню в ній клікалось через раз — FlatLaf приймав клік за перетягування вікна (1.0.3).
        JMenuBar bar = menus();
        Box north = Box.createVerticalBox();
        if (Theme.MAC) north.add(titleStrip());
        north.add(update);
        north.add(tabBar);
        north.add(runBar());
        north.add(band());
        for (java.awt.Component c : north.getComponents()) ((JComponent) c).setAlignmentX(LEFT_ALIGNMENT);
        right.add(north, BorderLayout.NORTH);
        right.add(split, BorderLayout.CENTER);

        main = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sidebar, right);
        main.setBorder(BorderFactory.createEmptyBorder());
        main.setDividerSize(4);
        main.setContinuousLayout(true);
        main.setDividerLocation(state.setting("sidebarWidth", 290));
        setContentPane(main);
        if (!Theme.MAC) bar.add(titleMark(right));
        setJMenuBar(bar);

        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        // ⌘Q на Mac типово закриває Java одразу, повз windowClosing: розмір вікна й налаштування не зберігались
        if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.APP_QUIT_HANDLER))
            java.awt.Desktop.getDesktop().setQuitHandler((e, response) -> quit());
        if (Theme.MAC && java.awt.Desktop.isDesktopSupported()) {   // меню «Ferret» на Mac: «Про Ferret» і «Налаштування… ⌘,»
            java.awt.Desktop.getDesktop().setAboutHandler(e -> AboutDialog.show(this));
            java.awt.Desktop.getDesktop().setPreferencesHandler(e -> openSettings());
        }
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { quit(); }
        });
        int w = state.setting("windowWidth", 1400), h = state.setting("windowHeight", 880);
        setSize(w, h);
        setMinimumSize(new Dimension(900, 560));
        setLocationRelativeTo(null);
        state.on(e -> { if (e.equals("connection") || e.equals("tabs") || e.equals("connections")) refreshBand(); if (e.equals("result") || e.equals("tabs")) refreshRun(); });
        refreshBand();
        refreshRun();
        SwingUtilities.invokeLater(() -> {
            split.setDividerLocation(state.setting("editorFraction", 40) / 100.0);
            tabBar.rebuild();
            if (state.connections.isEmpty()) openConnections(null, DBKind.TRINO);
            update.checkLater();
        });
        split.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, e -> {
            if (split.getHeight() > 0) state.settings.put("editorFraction", (long) Math.round(split.getDividerLocation() * 100.0 / split.getHeight()));
        });
        main.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, e -> {
            state.settings.put("sidebarWidth", (long) main.getDividerLocation());
            bar.repaint();   // знак у заголовку Windows — по центру правої частини, тож іде за межею
        });
    }

    public void quit() {
        saveState();
        dispose();
        System.exit(0);
    }

    // Вкладки, розмір вікна й налаштування — на диск (вихід і перезапуск після зміни мови)
    void saveState() {
        state.saveTabs();
        state.settings.put("windowWidth", (long) getWidth());
        state.settings.put("windowHeight", (long) getHeight());
        state.saveSettings();
    }

    // MARK: заголовок, панель запуску, смуга сервера

    JComponent titleStrip() {
        Image mark = Theme.image("wordmark.png");
        JPanel t = new JPanel(new BorderLayout()) {
            @Override protected void paintComponent(Graphics g) {
                g.setColor(Theme.PANEL);
                g.fillRect(0, 0, getWidth(), getHeight());
                if (mark == null) return;
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                int h = 20, w = mark.getWidth(null) * h / mark.getHeight(null);
                g2.drawImage(mark, (getWidth() - w) / 2, (getHeight() - h) / 2, w, h, null);
                g2.dispose();
            }
        };
        t.setPreferredSize(new Dimension(10, 40));
        t.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        t.putClientProperty(FlatClientProperties.COMPONENT_TITLE_BAR_CAPTION, true);
        t.setToolTipText("Ferret " + App.version());
        return t;
    }

    // Знак FERRET у вбудованому меню Windows: «клей» (Box.Filler на всю ширину — FlatLaf тоді розтягує меню на весь
    // заголовок), який малює знак по центру правої частини вікна, як на Mac. За ним вікно перетягується.
    JComponent titleMark(JComponent right) {
        Image mark = Theme.image("wordmark.png");
        Box.Filler m = new Box.Filler(new Dimension(0, 0), new Dimension(0, 0), new Dimension(Short.MAX_VALUE, Short.MAX_VALUE)) {
            @Override protected void paintComponent(Graphics g) {
                if (mark == null || !right.isShowing()) return;
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                int h = 18, w = mark.getWidth(null) * h / mark.getHeight(null);
                int cx = SwingUtilities.convertPoint(right, right.getWidth() / 2, 0, this).x;
                if (w > getWidth()) { g2.dispose(); return; }
                g2.drawImage(mark, Math.max(0, Math.min(cx - w / 2, getWidth() - w)), (getHeight() - h) / 2, w, h, null);
                g2.dispose();
            }
        };
        m.putClientProperty(FlatClientProperties.COMPONENT_TITLE_BAR_CAPTION, true);
        return m;
    }

    JComponent runBar() {
        JPanel r = Ui.row(6, 10, 6, 10);
        r.setOpaque(true);
        r.setBackground(Theme.PANEL);
        runBtn.addActionListener(e -> editor.requestRun());
        stopBtn.addActionListener(e -> state.stop(state.current));
        r.add(runBtn);
        r.add(Ui.gap(8));
        r.add(Ui.label(Theme.MAC ? "⌘↵" : "Ctrl+Enter", Theme.MUTED, false, 11));
        r.add(Ui.gap(10));
        r.add(stopBtn);
        r.add(Box.createHorizontalGlue());
        r.add(Ui.label(I18n.t("main.eschint"), Theme.MUTED, false, 11));
        r.add(Ui.gap(10));
        JButton hist = Ui.plainButton(I18n.t("main.history"), Ui.icon(Ui.Glyph.HISTORY, Theme.TEXT));
        hist.addActionListener(e -> historyMenu().show(hist, hist.getWidth() - 520, hist.getHeight()));
        r.add(hist);
        r.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        return r;
    }

    void refreshRun() {
        ConsoleTab t = state.current;
        boolean running = t != null && t.running;
        runBtn.setEnabled(!running);
        stopBtn.setEnabled(running);
    }

    JPopupMenu historyMenu() {
        JPopupMenu m = new JPopupMenu();
        Connection c = state.currentConnection();
        int n = 0;
        for (HistoryItem h : state.history) {
            if (c == null || !h.connection().equals(c.id)) continue;
            String first = Sidebar.firstLine(h.sql());
            String info = h.error() != null ? I18n.t("main.history.error") : h.rows() == null ? "" : ResultPanel.rowsLabel(h.rows());
            JMenuItem i = new JMenuItem("<html><b>" + esc(first.length() > 70 ? first.substring(0, 70) + "…" : first) + "</b>&nbsp;&nbsp;<font color='#B3A390'>"
                    + esc(info + " · " + Sidebar.ago(h.date())) + "</font></html>");
            i.setToolTipText(I18n.t("main.history.insert"));
            i.addActionListener(e -> editor.insertText(h.sql()));
            m.add(i);
            if (++n >= 30) break;
        }
        if (n == 0) m.add(new JMenuItem(I18n.t("main.history.empty"))).setEnabled(false);
        return m;
    }

    static String esc(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }

    JComponent band() {
        JPanel b = Ui.row(4, 12, 4, 12);
        b.setOpaque(true);
        b.setBackground(Theme.BAND);
        b.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(2, 0, 0, 0, Theme.ACCENT), BorderFactory.createEmptyBorder(4, 12, 4, 12)));
        JComponent dot = new JComponent() {
            @Override protected void paintComponent(Graphics g) {
                var g2 = Ui.smooth(g);
                g2.setColor(Theme.mark(state.currentConnection()));
                g2.fillOval(1, (getHeight() - 9) / 2, 9, 9);
                g2.dispose();
            }
        };
        dot.setPreferredSize(new Dimension(11, 11));
        dot.setMaximumSize(new Dimension(11, 11));
        b.add(dot);
        b.add(Ui.gap(7));
        b.add(bandName);
        b.add(Ui.gap(8));
        b.add(bandHost);
        b.add(Ui.gap(6));
        JButton move = Ui.iconButton(Ui.icon(Ui.Glyph.SWAP, Theme.MUTED, 12), I18n.t("main.move"));
        move.addActionListener(e -> moveMenu().show(move, 0, move.getHeight()));
        b.add(move);
        b.add(Box.createHorizontalGlue());
        b.add(bandLock);
        b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        return b;
    }

    void refreshBand() {
        Connection c = state.connection(state.current);
        bandName.setText(c == null ? I18n.t("main.band.none") : c.name);
        bandHost.setText(c == null ? "" : (c.host.isEmpty() ? c.kind.title : c.host) + (c.database.isEmpty() ? "" : " · " + c.database));
        if (c == null) { bandLock.setText(""); bandLock.setIcon(null); }
        else if (c.readOnly) { bandLock.setText(I18n.t("main.band.readonly")); bandLock.setIcon(Ui.icon(Ui.Glyph.LOCK, Theme.MUTED, 11)); bandLock.setForeground(Theme.MUTED); }
        else { bandLock.setText(I18n.t("main.band.writable")); bandLock.setIcon(Ui.icon(Ui.Glyph.WARN, Theme.ERROR, 11)); bandLock.setForeground(Theme.ERROR); }
        repaint();
    }

    JPopupMenu moveMenu() {
        JPopupMenu m = new JPopupMenu();
        for (Connection c : state.connections) {
            JMenuItem i = new JMenuItem(c.name, new Sidebar.DotIcon(Theme.mark(c)));
            i.addActionListener(e -> state.moveTab(state.current, c.id));
            m.add(i);
        }
        return m;
    }

    // MARK: меню

    // Меню: Файл · Редагування · Запит · Підключення · Довідка. На Mac «Про Ferret» і «Налаштування… ⌘,» — у меню
    // «Ferret» (Java дає туди лише ці системні пункти), а «Вийти» там же від системи.
    JMenuBar menus() {
        JMenuBar bar = new JMenuBar();
        int M = Theme.MENU_MASK;
        JMenu file = new JMenu(I18n.t("main.menu.file"));
        file.add(item(I18n.t("main.menu.newtab"), KeyStroke.getKeyStroke(KeyEvent.VK_T, M), e -> state.newTab("", null, state.currentConnectionID)));
        file.add(item(I18n.t("main.menu.reopen"), KeyStroke.getKeyStroke(KeyEvent.VK_T, M | InputEvent.SHIFT_DOWN_MASK), e -> state.reopenTab()));
        file.add(item(I18n.t("main.menu.closetab"), KeyStroke.getKeyStroke(KeyEvent.VK_W, M), e -> state.closeTab(state.current)));
        if (!Theme.MAC) {
            file.addSeparator();
            file.add(item(I18n.t("main.menu.settings"), KeyStroke.getKeyStroke(KeyEvent.VK_COMMA, M), e -> openSettings()));
            file.addSeparator();
            file.add(item(I18n.t("main.menu.quit"), KeyStroke.getKeyStroke(KeyEvent.VK_Q, M), e -> quit()));
        }

        JMenu ed = new JMenu(I18n.t("main.menu.edit"));
        ed.add(item(I18n.t("main.menu.find"), KeyStroke.getKeyStroke(KeyEvent.VK_F, M), e -> {   // курсор у результаті — пошук у результаті
            java.awt.Component f = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            if (f != null && SwingUtilities.isDescendingFrom(f, result)) result.focusFilter(); else editor.showFind();
        }));
        ed.add(item(I18n.t("main.menu.findnext"), KeyStroke.getKeyStroke(KeyEvent.VK_G, M), e -> editor.find(true)));
        ed.add(item(I18n.t("main.menu.findprev"), KeyStroke.getKeyStroke(KeyEvent.VK_G, M | InputEvent.SHIFT_DOWN_MASK), e -> editor.find(false)));
        ed.addSeparator();
        ed.add(item(I18n.t("main.menu.format"), KeyStroke.getKeyStroke(KeyEvent.VK_L, M | InputEvent.ALT_DOWN_MASK),
                e -> editor.formatQuery(state.currentConnection() == null ? DBKind.TRINO : state.currentConnection().kind)));
        ed.add(item(I18n.t("main.menu.comment"), KeyStroke.getKeyStroke(KeyEvent.VK_SLASH, M), e -> editor.toggleComment()));
        ed.add(item(I18n.t("main.menu.indent"), KeyStroke.getKeyStroke(KeyEvent.VK_CLOSE_BRACKET, M), e -> editor.indent(false)));
        ed.add(item(I18n.t("main.menu.outdent"), KeyStroke.getKeyStroke(KeyEvent.VK_OPEN_BRACKET, M), e -> editor.indent(true)));

        JMenu q = new JMenu(I18n.t("main.menu.query"));
        q.add(item(I18n.t("main.run"), KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, M), e -> editor.requestRun()));
        q.add(item(I18n.t("main.stop"), KeyStroke.getKeyStroke(KeyEvent.VK_PERIOD, M), e -> state.stop(state.current)));

        JMenu conn = new JMenu(I18n.t("main.menu.conn"));
        conn.add(item(I18n.t("main.menu.connections"), KeyStroke.getKeyStroke(KeyEvent.VK_COMMA, M | InputEvent.SHIFT_DOWN_MASK),
                e -> openConnections(state.currentConnectionID, null)));
        conn.add(item(I18n.t("main.menu.newconnection"), null, e -> openConnections(null, DBKind.TRINO)));
        conn.add(item(I18n.t("main.menu.refreshschema"), KeyStroke.getKeyStroke(KeyEvent.VK_R, M), e -> state.refreshSchema()));
        conn.add(item(I18n.t("main.menu.claude"), null, e -> ClaudeDialog.show(this, state)));
        conn.addSeparator();
        for (int i = 0; i < 9; i++) {
            int n = i;
            conn.add(item(I18n.t("main.menu.recentserver", i + 1), KeyStroke.getKeyStroke(KeyEvent.VK_1 + i, InputEvent.CTRL_DOWN_MASK), e -> {
                List<Connection> r = state.recentConnections();
                if (n < r.size()) state.goToServer(r.get(n).id);
            }));
        }

        JMenu help = new JMenu(I18n.t("main.menu.help"));
        help.add(item(I18n.t("main.menu.shortcuts"), null, e -> Help.shortcuts(this, getJMenuBar())));
        help.add(item(I18n.t("main.menu.checkupdates"), null, e -> update.checkNow(true)));
        help.add(item(I18n.t("main.menu.reportbug"), null, e -> Help.reportBug(this)));
        if (!Theme.MAC) {
            help.addSeparator();
            help.add(item(I18n.t("main.menu.about"), null, e -> AboutDialog.show(this)));
        }
        for (JMenu m : new JMenu[] {file, ed, q, conn, help}) bar.add(m);
        return bar;
    }

    void openSettings() { SettingsDialog.show(this, state); }

    void chooseLanguage(String code) {
        state.settings.put("language", code);
        state.saveSettings();
        if (code.equals(I18n.lang())) return;   // повернули ту, якою вікно вже говорить
        if (Ask.confirm(this, I18n.t("main.restart.title"), I18n.t("main.restart.text"), I18n.t("main.restart.now"), I18n.t("main.restart.later")))
            App.restart(this, this::saveState);
    }

    static JMenuItem item(String title, KeyStroke ks, ActionListener a) {
        JMenuItem i = new JMenuItem(title);
        if (ks != null) i.setAccelerator(ks);
        i.addActionListener(a);
        return i;
    }

    // MARK: підключення

    void openConnections(java.util.UUID select, DBKind newKind) {
        ConnectionsDialog d = new ConnectionsDialog(this, state, select, newKind);
        d.setVisible(true);
    }
}
