package ferret.ui;

import ferret.core.I18n;
import ferret.core.Json;
import ferret.core.McpAccess;
import ferret.core.Model.Connection;
import ferret.core.Store;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.Timer;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

// «Доступ для Claude» (Підключення → Доступ для Claude…): яким підключенням Claude має доступ і з якими правами, як
// підключити Claude Code / Claude Desktop (команда з ключем) і журнал його запитів. Сам сервер — core/Mcp.
final class ClaudeDialog {
    private ClaudeDialog() { }

    static final List<String> RIGHTS = List.of(McpAccess.OFF, McpAccess.READ, McpAccess.WRITE);
    static final int JOURNAL_ROWS = 500;

    static void show(JFrame owner, AppState state) {
        JDialog d = new JDialog(owner, I18n.t("claude.title"), true);
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab(I18n.t("claude.tab.access"), scroll(access(d, state)));
        Journal journal = new Journal();
        tabs.addTab(I18n.t("claude.tab.journal"), journal.view());
        Timer refresh = new Timer(2000, e -> journal.reloadIfChanged());   // Claude пише в журнал, поки вікно відкрите
        refresh.start();
        d.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent e) { refresh.stop(); }
        });
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Theme.BG);
        root.add(tabs, BorderLayout.CENTER);
        root.add(Help.footer(d), BorderLayout.SOUTH);
        d.setContentPane(root);
        d.getRootPane().registerKeyboardAction(e -> d.dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        d.setSize(760, 620);
        d.setLocationRelativeTo(owner);
        d.setVisible(true);
    }

    // Права одного підключення — однаково тут і у вікні «Підключення»
    static JComboBox<String> rightBox(String right, Consumer<String> changed) {
        JComboBox<String> box = new JComboBox<>(RIGHTS.stream().map(r -> I18n.t("claude.right." + r)).toArray(String[]::new));
        box.setSelectedIndex(Math.max(0, RIGHTS.indexOf(right)));
        box.addActionListener(e -> changed.accept(RIGHTS.get(box.getSelectedIndex())));
        return box;
    }

    // MARK: доступ

    static JPanel access(JDialog d, AppState state) {
        Box p = Box.createVerticalBox();
        p.setBorder(BorderFactory.createEmptyBorder(18, 22, 18, 22));
        p.add(left(AboutDialog.text(I18n.t("claude.intro"), 700)));
        p.add(Box.createVerticalStrut(18));
        p.add(left(Ui.label(I18n.t("claude.connections"), Theme.TEXT, true, 14)));
        p.add(Box.createVerticalStrut(8));
        Map<UUID, String> rights = new LinkedHashMap<>(McpAccess.rights());
        if (state.connections.isEmpty()) p.add(left(Ui.label(I18n.t("claude.noconnections"), Theme.MUTED, false, 13)));
        for (Connection c : state.connections) {
            JPanel row = new JPanel();
            row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
            row.setOpaque(false);
            row.add(Ui.dot(Theme.mark(c)));
            row.add(Ui.gap(8));
            row.add(Ui.label(c.name, Theme.TEXT, false, 13));
            row.add(Ui.gap(8));
            row.add(Ui.label(c.kind.title, Theme.MUTED, false, 11));
            row.add(Box.createHorizontalGlue());
            JLabel guarded = Ui.label(I18n.t("claude.guarded"), Theme.MUTED, false, 11);
            JComboBox<String> box = rightBox(rights.getOrDefault(c.id, McpAccess.OFF), r -> {
                rights.put(c.id, r);
                try { McpAccess.save(rights); } catch (IOException ex) { Ask.inform(d, I18n.t("claude.title"), ex.getMessage()); }
                guarded.setVisible(McpAccess.effective(c, r).equals(McpAccess.READ) && r.equals(McpAccess.WRITE));
            });
            box.setMaximumSize(new Dimension(190, 30));
            row.add(box);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
            p.add(left(row));
            guarded.setVisible(c.readOnly && McpAccess.WRITE.equals(rights.get(c.id)));
            p.add(left(guarded));
            p.add(Box.createVerticalStrut(4));
        }
        p.add(Box.createVerticalStrut(18));
        p.add(left(Ui.label(I18n.t("claude.setup"), Theme.TEXT, true, 14)));
        p.add(Box.createVerticalStrut(8));
        String exe = System.getProperty("jpackage.app-path");   // запускач установленого Ferret
        if (exe == null) {
            p.add(left(Ui.label(I18n.t("claude.setup.nopackage"), Theme.MUTED, false, 13)));
        } else {
            p.add(left(copyRow(I18n.t("claude.setup.code"), I18n.t("claude.copy.command"), () -> codeCommand(exe, key(), false))));
            if (Store.WINDOWS) p.add(left(copyRow(I18n.t("claude.setup.wsl"), I18n.t("claude.copy.command"), () -> codeCommand(exe, key(), true))));
            p.add(left(copyRow(I18n.t("claude.setup.desktop"), I18n.t("claude.copy.config"), () -> desktopConfig(exe, key()))));
            p.add(Box.createVerticalStrut(6));
            p.add(left(AboutDialog.text(I18n.t("claude.setup.note"), 700)));
            p.add(Box.createVerticalStrut(12));
            JLabel shown = Ui.label(masked(key()), Theme.MUTED, false, 13);
            shown.setFont(Theme.mono(12));
            JButton fresh = Ui.plainButton(I18n.t("claude.key.new"), null);
            fresh.addActionListener(e -> {
                if (!Ask.confirm(d, I18n.t("claude.key.new"), I18n.t("claude.key.confirm"), I18n.t("claude.key.create"), I18n.t("common.cancel"))) return;
                shown.setText(masked(McpAccess.newKey()));
            });
            JPanel row = line(I18n.t("claude.key"), shown, fresh);
            p.add(left(row));
        }
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setBackground(Theme.BG);
        wrap.add(p, BorderLayout.NORTH);
        return wrap;
    }

    // Ключ створюється, коли він уперше потрібен
    static String key() {
        String k = McpAccess.key();
        return k != null ? k : McpAccess.newKey();
    }

    static String masked(String k) { return k.substring(0, 3) + "••••••••" + k.substring(k.length() - 4); }

    // Claude Code: на Windows — і варіант для WSL (там Ferret.exe видно як /mnt/c/…, а змінну передає WSLENV)
    static String codeCommand(String exe, String key, boolean wsl) {
        String path = wsl ? "/mnt/" + Character.toLowerCase(exe.charAt(0)) + exe.substring(2).replace('\\', '/') : exe;
        String quoted = Store.WINDOWS && !wsl ? "\"" + path + "\"" : "'" + path.replace("'", "'\\''") + "'";
        return "claude mcp add ferret --scope user -e FERRET_KEY=" + key + (wsl ? " -e WSLENV=FERRET_KEY" : "") + " -- " + quoted + " --mcp";
    }

    static String desktopConfig(String exe, String key) {
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("command", exe);
        server.put("args", List.of("--mcp"));
        server.put("env", Map.of("FERRET_KEY", key));
        return Json.pretty(Map.of("mcpServers", Map.of("ferret", server)));
    }

    static JPanel copyRow(String what, String button, java.util.function.Supplier<String> text) {
        JButton b = Ui.plainButton(button, null);
        b.addActionListener(e -> {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text.get()), null);
            b.setText(I18n.t("claude.copied"));
            Timer back = new Timer(1500, x -> b.setText(button));
            back.setRepeats(false);
            back.start();
        });
        return line(what, null, b);
    }

    static JPanel line(String what, Component middle, JButton b) {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setOpaque(false);
        JLabel l = Ui.label(what, Theme.TEXT, false, 13);
        l.setPreferredSize(new Dimension(170, 28));
        row.add(l);
        if (middle != null) row.add(middle);
        row.add(Box.createHorizontalGlue());
        row.add(b);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        return row;
    }

    static JComponent left(JComponent c) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        return c;
    }

    static JScrollPane scroll(JComponent c) {
        JScrollPane sp = new JScrollPane(c);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.getVerticalScrollBar().setUnitIncrement(16);
        return sp;
    }

    // MARK: журнал

    static final class Journal extends AbstractTableModel {
        static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm:ss").withZone(ZoneId.systemDefault());
        List<McpAccess.Entry> rows = List.of();
        long stamp = -1;
        final JLabel empty = Ui.label(I18n.t("claude.journal.empty"), Theme.MUTED, false, 13);

        JComponent view() {
            JTable t = new JTable(this) {
                @Override public String getToolTipText(java.awt.event.MouseEvent e) {   // повний запит; текст — лише як текст
                    int r = rowAtPoint(e.getPoint());
                    if (r < 0) return null;
                    McpAccess.Entry x = rows.get(r);
                    String s = x.sql() + (x.error() == null ? "" : "\n\n" + x.error());
                    return "<html>" + s.replace("&", "&amp;").replace("<", "&lt;").replace("\n", "<br>") + "</html>";
                }
            };
            t.setDefaultRenderer(Object.class, Ui.noHtml(new DefaultTableCellRenderer() {
                @Override public Component getTableCellRendererComponent(JTable tb, Object v, boolean sel, boolean focus, int row, int col) {
                    Component c = super.getTableCellRendererComponent(tb, v, sel, false, row, col);
                    if (!sel) c.setForeground(col == 4 && rows.get(row).error() != null ? Theme.ERROR : col == 3 || col == 4 ? Theme.TEXT : Theme.MUTED);
                    return c;
                }
            }));
            t.setFillsViewportHeight(true);
            t.setShowVerticalLines(false);
            t.getTableHeader().setReorderingAllowed(false);
            int[] widths = {110, 100, 130, 300, 160};
            for (int i = 0; i < widths.length; i++) t.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
            JPanel p = new JPanel(new BorderLayout());
            p.setBackground(Theme.BG);
            p.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
            p.add(scroll(t), BorderLayout.CENTER);
            empty.setBorder(BorderFactory.createEmptyBorder(8, 2, 0, 0));
            p.add(empty, BorderLayout.SOUTH);
            reloadIfChanged();
            return p;
        }

        void reloadIfChanged() {
            long now;
            try {
                now = Files.exists(Store.file("mcp-journal.jsonl")) ? Files.getLastModifiedTime(Store.file("mcp-journal.jsonl")).toMillis() : 0;
            } catch (IOException e) {
                return;
            }
            if (now == stamp) return;
            stamp = now;
            rows = McpAccess.journal(JOURNAL_ROWS);
            empty.setText(rows.isEmpty() ? I18n.t("claude.journal.empty") : I18n.t("claude.journal.note", JOURNAL_ROWS));
            fireTableDataChanged();
        }

        @Override public int getRowCount() { return rows.size(); }

        @Override public int getColumnCount() { return 5; }

        @Override public String getColumnName(int c) {
            return I18n.t(new String[] {"claude.journal.time", "claude.journal.client", "claude.journal.connection", "claude.journal.sql", "claude.journal.result"}[c]);
        }

        @Override public Object getValueAt(int r, int c) {
            McpAccess.Entry x = rows.get(r);
            return switch (c) {
                case 0 -> TIME.format(x.at());
                case 1 -> x.client();
                case 2 -> x.connection();
                case 3 -> x.sql().replaceAll("\\s+", " ");
                default -> x.error() != null ? x.error().lines().findFirst().orElse("")
                        : x.rows() != null ? ResultPanel.rowsLabel(x.rows()) : I18n.t("claude.journal.done");
            };
        }
    }
}
