package ferret.ui;

import ferret.core.I18n;
import ferret.core.Model.QueryResult;
import ferret.core.Values;
import ferret.ui.AppState.ConsoleTab;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.RowFilter;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagLayout;
import java.awt.Image;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

// Результат запиту: порожній стан (значок і підказки), «Шукаю…» зі значком, що підстрибує, і секундоміром, помилка
// (текст можна скопіювати), повідомлення команди або таблиця: номер рядка, ширина колонок за вмістом, NULL — сірим,
// копіювання таблицею (вставляється в Google Таблиці / Excel клітинками), CSV з BOM (кирилиця в Excel), «Показати всі».
// Клік по заголовку сортує (↑ → ↓ → як прийшло; числа — як числа), «Пошук у результаті» лишає рядки з текстом у будь-якій
// колонці й підсвічує збіги. Копіювання й CSV беруть те, що видно, — з сортуванням і пошуком.
public final class ResultPanel extends JPanel {
    private final AppState state;
    private final CardLayout cards = new CardLayout();
    private final JLabel summary = Ui.label("", Theme.MUTED, false, 12);
    private final JLabel truncated = Ui.label("", Theme.ACCENT, false, 12);
    private final JButton showAll = Ui.plainButton(I18n.t("result.showall"), null);
    private final Model model = new Model();
    private final JTable table = new JTable(model);
    private final JTextField filter = new JTextField();
    private String needle = "";
    private final TableRowSorter<Model> sorter = new TableRowSorter<>(model) {
        @Override public void toggleSortOrder(int column) {   // третій клік — назад до порядку з бази
            List<? extends RowSorter.SortKey> keys = getSortKeys();
            if (!keys.isEmpty() && keys.get(0).getColumn() == column && keys.get(0).getSortOrder() == SortOrder.DESCENDING) setSortKeys(null);
            else super.toggleSortOrder(column);
        }
    };
    private final JTextArea errorText = new JTextArea();
    private final JLabel errorTitle = Ui.label("", Theme.ERROR, true, 14);
    private final JTextArea messageText = new JTextArea();
    private final JLabel runningText = Ui.label("", Theme.TEXT, true, 15);
    private final Timer ticker;
    private final Image icon = Theme.image("icon.png");
    private int bounce;
    private ConsoleTab shown;
    private QueryResult shownResult;

    public ResultPanel(AppState state) {
        this.state = state;
        setLayout(cards);
        setBackground(Theme.BG);
        add(emptyView(), "empty");
        add(runningView(), "running");
        add(errorView(), "error");
        add(messageView(), "message");
        add(gridView(), "grid");
        ticker = new Timer(100, e -> tick());
        state.on(e -> { if (e.equals("result") || e.equals("tabs")) refresh(); });
        refresh();
    }

    // «з 21 рядка», «з 4 рядків» — після «з» родовий відмінок (result.rowsof)
    static String rowsOf(int n) { return I18n.plural("result.rowsof", n); }

    public static String rowsLabel(int n) { return I18n.plural("result.rows", n); }

    void refresh() {
        ConsoleTab t = state.current;
        shown = t;
        if (t == null) { cards.show(this, "empty"); return; }
        if (t.running) {
            ticker.start();
            tick();
            cards.show(this, "running");
            return;
        }
        ticker.stop();
        if (t.error != null) {
            boolean stopped = t.error.equals(I18n.t("jdbc.stopped"));   // та сама позначка, що в Jdbc і AppState
            errorTitle.setText(I18n.t(stopped ? "result.stopped" : "result.failed"));
            errorTitle.setForeground(stopped ? Theme.MUTED : Theme.ERROR);
            errorText.setText(stopped ? "" : t.error);
            errorText.setCaretPosition(0);
            cards.show(this, "error");
            return;
        }
        QueryResult r = t.result;
        if (r == null) { cards.show(this, "empty"); return; }
        if (r.columns.isEmpty()) {
            messageText.setText(r.message == null ? I18n.t("result.done") : r.message);
            cards.show(this, "message");
            return;
        }
        if (r != shownResult) {
            // той самий набір колонок («Показати всі», повторний запуск) — сортування й пошук лишаються; інший — скидаються
            boolean same = shownResult != null && shownResult.columns.equals(r.columns);
            List<? extends RowSorter.SortKey> keys = same ? List.copyOf(sorter.getSortKeys()) : List.of();
            shownResult = r;
            model.set(r);
            sorter.setSortable(0, false);
            for (int c = 1; c <= r.columns.size(); c++)
                sorter.setComparator(c, Values.numericType(c - 1 < r.types.size() ? r.types.get(c - 1) : null) ? Values.NUMBERS : Values.TEXT);
            sorter.setSortKeys(keys);
            if (!same) filter.setText("");
            applyFilter();
            fitColumns(r);
        }
        updateSummary();
        truncated.setText(r.truncated ? I18n.t("result.truncated", rowsLabel(r.rows.size())) : "");
        truncated.setVisible(r.truncated);   // інакше значок ⚠ лишався сам, без тексту
        showAll.setVisible(r.truncated && r.rows.size() < AppState.ALL_ROWS_LIMIT);
        showAll.setToolTipText(I18n.t("result.showall.tooltip", rowsLabel(AppState.ALL_ROWS_LIMIT)));
        cards.show(this, "grid");
    }

    void applyFilter() {
        needle = filter.getText().trim().toLowerCase(Locale.ROOT);
        sorter.setRowFilter(needle.isEmpty() ? null : new RowFilter<Model, Integer>() {
            @Override public boolean include(Entry<? extends Model, ? extends Integer> e) {
                for (String v : e.getModel().r.rows.get(e.getIdentifier())) if (Values.contains(v, needle)) return true;
                return false;
            }
        });
        updateSummary();
        table.repaint();
    }

    void updateSummary() {
        QueryResult r = model.r;
        String rows = needle.isEmpty() ? rowsLabel(r.rows.size())
                : I18n.t("result.filtered", I18n.num(table.getRowCount()), rowsOf(r.rows.size()));
        summary.setText(rows + " · " + I18n.t("result.ms", String.valueOf(r.elapsedMs)));   // мс — без розрядів, як було
    }

    public void focusFilter() {
        filter.requestFocusInWindow();
        filter.selectAll();
    }

    // Рядки моделі в тому порядку, як їх видно (сортування й пошук).
    int[] viewRows() {
        int[] out = new int[table.getRowCount()];
        for (int i = 0; i < out.length; i++) out[i] = table.convertRowIndexToModel(i);
        return out;
    }

    void tick() {
        ConsoleTab t = shown;
        if (t == null || !t.running || t.startedAt == null) return;
        double s = Duration.between(t.startedAt, Instant.now()).toMillis() / 1000.0;
        runningText.setText(I18n.t("result.running", String.format(Locale.US, "%.1f", s)));
        bounce++;
        repaint();
    }

    // MARK: вигляди

    private JComponent emptyView() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBackground(Theme.BG);
        Box b = Box.createHorizontalBox();
        JLabel img = new JLabel();
        if (icon != null) img.setIcon(new javax.swing.ImageIcon(icon.getScaledInstance(96, 96, Image.SCALE_SMOOTH)));
        b.add(img);
        b.add(Box.createHorizontalStrut(24));
        Box txt = Box.createVerticalBox();
        txt.add(Ui.label(I18n.t("result.empty.title"), Theme.TEXT, true, 20));
        txt.add(Box.createVerticalStrut(8));
        txt.add(Ui.label(I18n.t("result.empty.run", Theme.CMD + (Theme.MAC ? "↵" : "Enter")), Theme.MUTED, false, 13));
        txt.add(Box.createVerticalStrut(4));
        txt.add(Ui.label(I18n.t("result.empty.preview"), Theme.MUTED, false, 13));
        txt.add(Box.createVerticalStrut(4));
        txt.add(Ui.label(I18n.t("result.empty.complete"), Theme.MUTED, false, 13));
        b.add(txt);
        p.add(b);
        return p;
    }

    private JComponent runningView() {
        JPanel p = new JPanel(new GridBagLayout()) {
            @Override protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                // проста анімація процесу: значок підстрибує, медова смужка біжить угорі
                int w = getWidth();
                int x = (int) ((bounce * 0.04 % 1.35 - 0.35) * w);
                g.setColor(Theme.ACCENT);
                g.fillRect(x, 0, (int) (w * 0.35), 2);
            }
        };
        p.setBackground(Theme.BG);
        Box b = Box.createVerticalBox();
        JLabel img = new JLabel() {
            @Override protected void paintComponent(Graphics g) {
                if (icon == null) return;
                int dy = (int) Math.round(Math.sin(bounce * 0.5) * 4);
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g2.drawImage(icon, 0, 6 + dy, 72, 72, null);
                g2.dispose();
            }
        };
        java.awt.Dimension size = new java.awt.Dimension(72, 84);
        img.setPreferredSize(size);
        img.setMinimumSize(size);   // BoxLayout бере й найменший/найбільший розмір: порожня мітка без них — 0×0, значка не було
        img.setMaximumSize(size);
        img.setAlignmentX(Component.CENTER_ALIGNMENT);
        runningText.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel hint = Ui.label(I18n.t("result.running.hint", Theme.CMD + "."), Theme.MUTED, false, 13);
        hint.setAlignmentX(Component.CENTER_ALIGNMENT);
        b.add(img);
        b.add(Box.createVerticalStrut(10));
        b.add(runningText);
        b.add(Box.createVerticalStrut(6));
        b.add(hint);
        p.add(b);
        return p;
    }

    private JComponent errorView() {
        JPanel p = new JPanel(new BorderLayout(0, 10));
        p.setBackground(Theme.BG);
        p.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        errorTitle.setIcon(Ui.icon(Ui.Glyph.WARN, Theme.ERROR, 16));
        errorTitle.setIconTextGap(8);
        p.add(errorTitle, BorderLayout.NORTH);
        errorText.setEditable(false);
        errorText.setLineWrap(true);
        errorText.setWrapStyleWord(true);
        errorText.setFont(Theme.mono(12.5f));
        errorText.setBackground(Theme.BG);
        errorText.setForeground(Theme.TEXT);
        JScrollPane sp = new JScrollPane(errorText);
        sp.setBorder(BorderFactory.createEmptyBorder());
        p.add(sp, BorderLayout.CENTER);
        JButton copy = Ui.plainButton(I18n.t("result.copyerror"), Ui.icon(Ui.Glyph.COPY, Theme.TEXT));
        copy.addActionListener(e -> clip(errorText.getText()));
        JPanel south = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        south.setOpaque(false);
        south.add(copy);
        p.add(south, BorderLayout.SOUTH);
        return p;
    }

    private JComponent messageView() {
        messageText.setEditable(false);
        messageText.setFont(Theme.mono(12.5f));
        messageText.setBackground(Theme.BG);
        messageText.setForeground(Theme.TEXT);
        messageText.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        JScrollPane sp = new JScrollPane(messageText);
        sp.setBorder(BorderFactory.createEmptyBorder());
        return sp;
    }

    private JComponent gridView() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(Theme.BG);
        JPanel head = new JPanel();
        head.setLayout(new BoxLayout(head, BoxLayout.X_AXIS));
        head.setBackground(Theme.PANEL);
        head.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.LINE), BorderFactory.createEmptyBorder(5, 12, 5, 12)));
        truncated.setIcon(Ui.icon(Ui.Glyph.WARN, Theme.ACCENT, 12));
        head.add(summary);
        head.add(Ui.gap(12));
        head.add(truncated);
        head.add(Ui.gap(8));
        head.add(showAll);
        head.add(Box.createHorizontalGlue());
        JButton copyAll = Ui.plainButton(I18n.t("result.copyall"), Ui.icon(Ui.Glyph.COPY, Theme.TEXT));
        copyAll.setToolTipText(I18n.t("result.copyall.tooltip"));
        copyAll.addActionListener(e -> clip(tsv(model.r, viewRows())));
        filter.putClientProperty(com.formdev.flatlaf.FlatClientProperties.PLACEHOLDER_TEXT, I18n.t("result.filter"));
        filter.putClientProperty(com.formdev.flatlaf.FlatClientProperties.TEXT_FIELD_LEADING_ICON, Ui.icon(Ui.Glyph.SEARCH, Theme.MUTED));
        filter.putClientProperty(com.formdev.flatlaf.FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
        filter.setToolTipText(I18n.t("result.filter.tooltip"));
        java.awt.Dimension fs = new java.awt.Dimension(240, filter.getPreferredSize().height);
        filter.setPreferredSize(fs);
        filter.setMaximumSize(fs);
        Timer filterDelay = new Timer(150, e -> applyFilter());   // 200 000 рядків — не на кожну літеру
        filterDelay.setRepeats(false);
        filter.getDocument().addDocumentListener(Sidebar.onChange(filterDelay::restart));
        EditorPanel.bind(filter, KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0), "clear-filter", () -> { filter.setText(""); table.requestFocusInWindow(); });
        head.add(filter);
        head.add(Ui.gap(8));
        JButton csv = Ui.plainButton(I18n.t("result.savecsv"), Ui.icon(Ui.Glyph.DOWNLOAD, Theme.TEXT));
        csv.addActionListener(e -> saveCSV());
        showAll.addActionListener(e -> state.showAll(state.current));
        head.add(copyAll);
        head.add(Ui.gap(6));
        head.add(csv);
        p.add(head, BorderLayout.NORTH);

        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setFont(Theme.mono(12.5f));
        table.setRowHeight(22);
        table.setShowHorizontalLines(false);
        table.setShowVerticalLines(true);
        table.setFillsViewportHeight(true);
        table.setCellSelectionEnabled(true);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.getTableHeader().setReorderingAllowed(false);
        table.setRowSorter(sorter);
        // заголовок — назви колонок із бази (у SELECT * — як їх назвали в таблиці): теж лише текстом, див. Ui.noHtml
        javax.swing.table.TableCellRenderer names = table.getTableHeader().getDefaultRenderer();
        table.getTableHeader().setDefaultRenderer((t, v, sel, focus, row, col) ->
                names.getTableCellRendererComponent(t, v instanceof String s ? Ui.plain(s) : v, sel, focus, row, col));
        table.setDefaultRenderer(Object.class, Ui.noHtml(new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int row, int col) {
                Component c = super.getTableCellRendererComponent(t, v == null ? "NULL" : v, sel, false, row, col);
                if (col == 0) { setText(String.valueOf(row + 1)); setForeground(Theme.MUTED); setHorizontalAlignment(SwingConstants.RIGHT); }   // № — як видно
                else {
                    boolean hit = !needle.isEmpty() && v != null && Values.contains(v.toString(), needle);
                    setForeground(v == null ? Theme.alpha(Theme.MUTED, 150) : hit && !sel ? Theme.ACCENT : Theme.TEXT);
                    setHorizontalAlignment(SwingConstants.LEFT);
                }
                if (!sel) setBackground(row % 2 == 0 ? Theme.BG : (java.awt.Color) UIManager.get("Table.alternateRowColor"));
                return c;
            }
        }));
        // ⌘C — виділені клітинки таблицею з заголовком
        EditorPanel.bind(table, KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_C, Theme.MENU_MASK), "copy-tsv", () -> clip(tsvSelected()));
        JScrollPane sp = new JScrollPane(table);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.getViewport().setBackground(Theme.BG);
        p.add(sp, BorderLayout.CENTER);
        return p;
    }

    // MARK: таблиця

    static final class Model extends AbstractTableModel {
        QueryResult r = new QueryResult();

        void set(QueryResult r) {
            this.r = r;
            fireTableStructureChanged();
        }

        @Override public int getRowCount() { return r.rows.size(); }

        @Override public int getColumnCount() { return r.columns.size() + 1; }

        @Override public String getColumnName(int c) { return c == 0 ? "#" : r.columns.get(c - 1); }

        @Override public Object getValueAt(int row, int c) { return c == 0 ? String.valueOf(row + 1) : r.rows.get(row)[c - 1]; }
    }

    // Ширина колонки за назвою і першими 200 значеннями, у межах 60…480.
    void fitColumns(QueryResult r) {
        java.awt.FontMetrics fm = table.getFontMetrics(table.getFont());
        for (int c = 0; c < table.getColumnCount(); c++) {
            TableColumn col = table.getColumnModel().getColumn(c);
            if (c == 0) { col.setPreferredWidth(Math.max(40, fm.stringWidth(String.valueOf(r.rows.size())) + 18)); continue; }
            int w = fm.stringWidth(r.columns.get(c - 1)) + 24;
            for (int i = 0; i < Math.min(200, r.rows.size()); i++) {
                String v = r.rows.get(i)[c - 1];
                w = Math.max(w, fm.stringWidth(v == null ? "NULL" : v.length() > 80 ? v.substring(0, 80) : v) + 18);
            }
            col.setPreferredWidth(Math.max(60, Math.min(480, w)));
        }
    }

    static String clean(String s) { return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' '); }

    static String tsv(QueryResult r, int[] rows) {
        StringBuilder b = new StringBuilder(String.join("\t", r.columns.stream().map(ResultPanel::clean).toList()));
        if (rows == null) for (String[] row : r.rows) b.append('\n').append(String.join("\t", java.util.Arrays.stream(row).map(ResultPanel::clean).toList()));
        else for (int i : rows) b.append('\n').append(String.join("\t", java.util.Arrays.stream(r.rows.get(i)).map(ResultPanel::clean).toList()));
        return b.toString();
    }

    String tsvSelected() {
        int[] rows = table.getSelectedRows();
        int[] cols = table.getSelectedColumns();
        if (rows.length == 1 && cols.length == 1) {   // одна клітинка — лише значення
            Object v = table.getValueAt(rows[0], cols[0]);
            return v == null ? "" : String.valueOf(v);
        }
        if (rows.length == 0) return tsv(model.r, viewRows());
        for (int i = 0; i < rows.length; i++) rows[i] = table.convertRowIndexToModel(rows[i]);
        return tsv(model.r, rows);
    }

    static String csv(QueryResult r, int[] rows) {
        StringBuilder b = new StringBuilder();
        b.append(String.join(",", r.columns.stream().map(ResultPanel::field).toList())).append("\r\n");
        for (int i : rows) b.append(String.join(",", java.util.Arrays.stream(r.rows.get(i)).map(ResultPanel::field).toList())).append("\r\n");
        return b.toString();
    }

    static String field(String s) {
        if (s == null) return "";
        return s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    static void clip(String s) {
        java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(s), null);
    }

    void saveCSV() {
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new FileNameExtensionFilter("CSV", "csv"));
        fc.setSelectedFile(new File((state.current == null ? I18n.t("result.csvname") : state.current.title) + ".csv"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File f = fc.getSelectedFile();
        if (!f.getName().toLowerCase().endsWith(".csv")) f = new File(f.getPath() + ".csv");
        try {
            // BOM — щоб Excel відкрив кирилицю без «кракозябр»
            Files.write(f.toPath(), ("﻿" + csv(model.r, viewRows())).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Ask.inform(this, "Ferret", I18n.t("result.savefailed", e.getMessage()));
        }
    }

    public void focusTable() { table.requestFocusInWindow(); }
}
