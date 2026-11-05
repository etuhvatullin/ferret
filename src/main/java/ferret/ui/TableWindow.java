package ferret.ui;

import ferret.core.I18n;
import ferret.core.Model.Connection;
import ferret.core.Model.DBError;
import ferret.core.SqlText;
import ferret.core.TableInfo;
import ferret.core.TableInfo.Fact;
import ferret.core.TableInfo.Item;
import ferret.core.TableInfo.Mark;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.Gutter;
import org.fife.ui.rtextarea.RTextScrollPane;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.text.DefaultCaret;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.KeyEvent;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Вікно «Таблиця» (Перегляд → Показати таблицю, ⌘B на назві в редакторі, правий клік у дереві): зверху назва, коментар
// і пʼять фактів картки, під ними DDL з підсвіткою, лише для читання, з переносом рядків — коментарі колонок видно без
// прокрутки вбік. Не модальне: поруч пишеться запит. Одна таблиця — одне вікно (вдруге — виходить наперед). Закрили —
// запити картки й DDL скасовуються й на сервері. Дані — core/TableInfo; тут лише показ.
public final class TableWindow {
    private TableWindow() { }

    private static final Map<String, JDialog> open = new HashMap<>();

    static void show(JFrame owner, Connection c, List<String> path, Consumer<String> toConsole) {
        String key = c.id + "/" + String.join("\u001F", path);
        JDialog was = open.get(key);
        if (was != null && was.isDisplayable()) { was.toFront(); was.requestFocus(); return; }
        String name = path.get(path.size() - 1), where = String.join(".", path.subList(0, path.size() - 1));
        JDialog d = new JDialog(owner, where.isEmpty() ? name : name + " — " + where, false);
        d.setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);   // JDialog типово лише ховається: вікно й запити жили б далі
        TableInfo.Stop stop = new TableInfo.Stop();
        open.put(key, d);
        d.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent e) { stop.cancel(); open.remove(key, d); }
        });

        JLabel kind = Ui.label("", Theme.MUTED, false, 11);
        JTextArea comment = note(Theme.MUTED, 13);
        JPanel facts = new JPanel(new GridLayout(1, 5, 22, 0));
        facts.setOpaque(false);
        for (Fact f : FACTS) facts.add(fact(I18n.t("table.fact." + f.name()), Ui.label("…", Theme.MUTED, true, 15), Ui.label(" ", Theme.alpha(Theme.MUTED, 190), false, 11)));
        JPanel card = header(name, where, kind, comment, facts);

        RSyntaxTextArea ddl = new RSyntaxTextArea();
        ddl.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_SQL);
        EditorPanel.style(ddl);
        ddl.setEditable(false);
        ddl.setLineWrap(true);
        ddl.setWrapStyleWord(true);
        ddl.setText(I18n.t("table.loading"));
        ((DefaultCaret) ddl.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
        RTextScrollPane sp = new RTextScrollPane(ddl);
        sp.setBorder(BorderFactory.createEmptyBorder());
        Gutter g = sp.getGutter();
        g.setBackground(Theme.BG);
        g.setLineNumberColor(Theme.alpha(Theme.MUTED, 170));
        g.setLineNumberFont(Theme.mono(11));
        g.setBorderColor(Theme.LINE);
        JLabel built = Ui.label(I18n.t("table.built"), Theme.MUTED, false, 11);
        built.setBorder(BorderFactory.createEmptyBorder(6, 18, 6, 18));
        built.setVisible(false);
        JTextArea failed = note(Theme.ERROR, 13);
        failed.setBorder(BorderFactory.createEmptyBorder(14, 18, 14, 18));
        CardLayout cards = new CardLayout();
        JPanel body = new JPanel(cards);
        body.setBackground(Theme.BG);
        JPanel text = new JPanel(new BorderLayout());
        text.setBackground(Theme.BG);
        text.add(built, BorderLayout.NORTH);
        text.add(sp, BorderLayout.CENTER);
        body.add(text, "ddl");
        body.add(failed, "error");

        JButton copy = Ui.plainButton(I18n.t("table.copy"), null);
        JButton insert = Ui.accentButton(I18n.t("table.toConsole"), null);
        insert.setToolTipText(I18n.t("table.toConsoleTip"));
        copy.setEnabled(false);
        insert.setEnabled(false);
        String[] shown = {null};
        copy.addActionListener(e -> ResultPanel.clip(shown[0]));
        insert.addActionListener(e -> { toConsole.accept(shown[0]); owner.toFront(); });
        JPanel foot = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        foot.setBackground(Theme.PANEL);
        foot.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.LINE), BorderFactory.createEmptyBorder(10, 14, 10, 14)));
        foot.add(copy);
        foot.add(insert);

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Theme.BG);
        root.add(card, BorderLayout.NORTH);
        root.add(body, BorderLayout.CENTER);
        root.add(foot, BorderLayout.SOUTH);
        d.setContentPane(root);
        d.getRootPane().registerKeyboardAction(e -> d.dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        d.getRootPane().registerKeyboardAction(e -> d.dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_W, Theme.MENU_MASK), JComponent.WHEN_IN_FOCUSED_WINDOW);
        d.setSize(920, 640);
        d.setMinimumSize(new Dimension(560, 360));
        d.setLocationRelativeTo(owner);
        d.setVisible(true);

        // DDL і картка — паралельно, у фоні; що прийшло, те й показуємо (вікно могли вже закрити — тоді нічого)
        new Thread(() -> {
            try {
                TableInfo.Ddl r = TableInfo.ddl(c, null, path, stop);
                SwingUtilities.invokeLater(() -> {
                    if (!d.isDisplayable()) return;
                    shown[0] = r.text();
                    ddl.setText(r.text());
                    ddl.setCaretPosition(0);
                    built.setVisible(r.built());
                    kind.setText(I18n.t("table.kind." + r.kind().replace(' ', '_')));
                    copy.setEnabled(true);
                    insert.setEnabled(true);
                });
            } catch (DBError | RuntimeException e) {   // і збій у самому Ferret — сказати, а не лишити «Завантажую…» назавжди
                String why = e instanceof DBError ? e.getMessage() : e.toString();
                SwingUtilities.invokeLater(() -> {
                    if (!d.isDisplayable()) return;
                    failed.setText(why);
                    cards.show(body, "error");
                });
            }
        }, "ferret-ddl").start();
        new Thread(() -> {
            List<Item> items = TableInfo.card(c, null, path, stop);
            SwingUtilities.invokeLater(() -> {
                if (!d.isDisplayable()) return;
                fill(items, facts, comment);
            });
        }, "ferret-card").start();
    }

    static final List<Fact> FACTS = List.of(Fact.DATA_UNTIL, Fact.LAST_WRITE, Fact.ROWS, Fact.SIZE, Fact.PARTITIONS);

    // Шапка: рядок назви, коментар (переноситься по ширині вікна — тому BorderLayout, а не BoxLayout) і факти
    static JPanel header(String name, String where, JLabel kind, JTextArea comment, JPanel facts) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(Theme.PANEL);
        p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.LINE), BorderFactory.createEmptyBorder(14, 18, 14, 18)));
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setOpaque(false);
        row.add(new JLabel(Ui.icon(Ui.Glyph.TABLE, Theme.MUTED, 14)));
        row.add(Ui.gap(8));
        row.add(Ui.label(name, Theme.TEXT, true, 16));
        row.add(Ui.gap(8));
        row.add(Ui.label(where, Theme.MUTED, false, 13));
        row.add(Ui.gap(10));
        row.add(kind);
        row.add(Box.createHorizontalGlue());
        p.add(row, BorderLayout.NORTH);
        comment.setBorder(BorderFactory.createEmptyBorder(6, 22, 0, 0));
        comment.setVisible(false);
        p.add(comment, BorderLayout.CENTER);
        facts.setBorder(BorderFactory.createEmptyBorder(14, 22, 0, 0));
        p.add(facts, BorderLayout.SOUTH);
        return p;
    }

    // Текст, що переноситься (коментар таблиці, помилка): JTextArea без рамки й курсора — і без HTML узагалі
    static JTextArea note(java.awt.Color color, float size) {
        JTextArea a = new JTextArea();
        a.setEditable(false);
        a.setLineWrap(true);
        a.setWrapStyleWord(true);
        a.setOpaque(false);
        a.setForeground(color);
        a.setFont(a.getFont().deriveFont(size));
        a.setBorder(null);
        ((DefaultCaret) a.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
        return a;
    }

    static JPanel fact(String label, JLabel value, JLabel sub) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setOpaque(false);
        p.add(Ui.label(label, Theme.MUTED, false, 11));
        p.add(Box.createVerticalStrut(1));
        p.add(value);
        p.add(sub);
        return p;
    }

    static void fill(List<Item> items, JPanel facts, JTextArea comment) {
        Item c = items.stream().filter(i -> i.fact() == Fact.COMMENT).findFirst().orElse(null);
        if (c != null && c.value() != null) {
            comment.setText(c.value());
            comment.setToolTipText(Ui.plain(c.value()));
            comment.setVisible(true);
        }
        boolean noCard = items.stream().filter(i -> i.fact() != Fact.COMMENT).allMatch(i -> TableInfo.NOTE_NO_CARD.equals(i.note()));
        if (noCard) {
            facts.removeAll();
            facts.setLayout(new BorderLayout());
            facts.add(Ui.label(I18n.t("table.noCard"), Theme.MUTED, false, 13), BorderLayout.WEST);
            facts.revalidate();
            return;
        }
        for (int k = 0; k < FACTS.size(); k++) {
            Fact f = FACTS.get(k);
            Item i = items.stream().filter(x -> x.fact() == f).findFirst().orElse(new Item(f, Mark.UNKNOWN, null, null));
            JPanel cell = (JPanel) facts.getComponent(k);
            JLabel value = (JLabel) cell.getComponent(2), sub = (JLabel) cell.getComponent(3);
            String[] t = texts(i);
            value.setText(t[0]);
            value.setForeground(i.mark() == Mark.FAILED || i.mark() == Mark.TIMEOUT ? Theme.ERROR
                    : i.mark() == Mark.UNKNOWN || i.mark() == Mark.NONE ? Theme.MUTED : Theme.TEXT);
            value.setFont(value.getFont().deriveFont(i.mark() == Mark.EXACT || i.mark() == Mark.ESTIMATE ? java.awt.Font.BOLD : java.awt.Font.PLAIN,
                    i.mark() == Mark.EXACT || i.mark() == Mark.ESTIMATE ? 15f : 14f));
            sub.setText(t[1].isEmpty() ? " " : t[1]);
            cell.setToolTipText(i.mark() == Mark.FAILED ? Ui.plain(i.note()) : t[1].isEmpty() ? null : Ui.plain(t[1]));
        }
    }

    // Значення й підпис факту мовою вікна
    public static String[] texts(Item i) {
        return switch (i.mark()) {
            case UNKNOWN -> new String[] {I18n.t("table.unknown"), TableInfo.NOTE_SINCE_RESTART.equals(i.note()) ? I18n.t("table.sinceRestart") : ""};
            case NONE -> new String[] {I18n.t("table.none"), i.fact() == Fact.PARTITIONS ? I18n.t("table.notSplit") : ""};
            case FAILED -> new String[] {I18n.t("table.failed"), i.note() == null ? "" : i.note().lines().findFirst().orElse("")};
            case TIMEOUT -> new String[] {I18n.t("table.timedOut"), I18n.t("table.timedOutSub", i.note())};
            default -> new String[] {value(i), sub(i)};
        };
    }

    static String value(Item i) {
        return switch (i.fact()) {
            case ROWS -> (i.mark() == Mark.ESTIMATE ? "≈ " : "") + number(i.value());
            case PARTITIONS -> number(i.value());
            case SIZE -> size(i.value());
            case DATA_UNTIL, LAST_WRITE -> date(i.value());
            default -> i.value();
        };
    }

    static String sub(Item i) {
        String n = i.note();
        return switch (i.fact()) {
            case ROWS -> TableInfo.NOTE_DELETES.equals(n) ? I18n.t("table.deletes") : i.mark() == Mark.ESTIMATE ? I18n.t("table.estimate") : I18n.t("table.exact");
            case DATA_UNTIL -> TableInfo.NOTE_PARTITION.equals(n) ? I18n.t("table.lastPartition") : n == null ? "" : I18n.t("table.byColumn", n);
            case LAST_WRITE -> TableInfo.NOTE_SINCE_RESTART.equals(n) ? I18n.t("table.sinceRestart") : ago(i.value());
            case PARTITIONS -> scheme(n);
            default -> "";
        };
    }

    static String number(String v) {
        try { return I18n.num(Long.parseLong(v.trim())); } catch (Exception e) { return v; }
    }

    // Розмір за десятковими одиницями, як у Finder і Провіднику: 26 801 796 310 → «26,8 ГБ»
    public static String size(String v) {
        double b;
        try { b = Double.parseDouble(v.trim()); } catch (Exception e) { return v; }
        String[] units = {"b", "kb", "mb", "gb", "tb", "pb"};
        int u = 0;
        while (b >= 1000 && u < units.length - 1) { b /= 1000; u++; }
        String n = u == 0 ? String.valueOf((long) b) : String.format(I18n.locale(), b < 100 ? "%.1f" : "%.0f", b);
        return n + " " + I18n.t("table.size." + units[u]);
    }

    static final Pattern DATE = Pattern.compile("^(\\d{4})-(\\d{2})(?:-(\\d{2}))?(?:[ T](\\d{2}):(\\d{2}))?");

    // «2026-11-05 16:40:35.013 Europe/Kiev» → «05.11.2026 16:40»; «2026-11» → «11.2026»; інше — як є
    public static String date(String v) {
        Matcher m = DATE.matcher(v);
        if (!m.find()) return v;
        String d = m.group(3) == null ? m.group(2) + "." + m.group(1) : m.group(3) + "." + m.group(2) + "." + m.group(1);
        return m.group(4) == null ? d : d + " " + m.group(4) + ":" + m.group(5);
    }

    // «1 год тому» — лише для повного часу; пояс — з кінця значення (Trino: «… Europe/Kiev»), інакше свій
    static String ago(String v) {
        Matcher m = DATE.matcher(v);
        if (!m.find() || m.group(4) == null || m.group(3) == null) return "";
        ZoneId z = ZoneId.systemDefault();
        String[] parts = v.trim().split("\\s+");
        if (parts.length >= 3) try { z = ZoneId.of(parts[parts.length - 1]); } catch (Exception ignored) { }
        try {
            LocalDateTime t = LocalDateTime.parse(m.group(0).replace(' ', 'T'), DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            Instant at = t.atZone(z).toInstant();
            return at.isAfter(Instant.now().plusSeconds(60)) ? "" : Sidebar.ago(at);
        } catch (Exception e) {
            return "";
        }
    }

    static final Pattern TRANSFORM = Pattern.compile("(year|month|day|hour)\\((.+)\\)");

    // Схема партицій: «month(created_at)» → «по місяцях created_at»; ClickHouse «toYYYYMM(order_created)» — як є
    public static String scheme(String n) {
        if (n == null) return "";
        Matcher m = TRANSFORM.matcher(n);
        return m.matches() ? I18n.t("table.part." + m.group(1), m.group(2)) : n;
    }

    // Таблиці під курсором — шляхами дерева (⌘B): з назви в редакторі серед таблиць сервера
    static List<List<String>> resolve(String text, int cursor, List<List<String>> tables) {
        return SqlText.tablesNamed(tables, SqlText.nameAt(text, cursor));
    }
}
