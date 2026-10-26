package ferret.ui;

import com.formdev.flatlaf.FlatClientProperties;
import ferret.core.Drivers;
import ferret.core.I18n;
import ferret.core.Jdbc;
import ferret.core.Model;
import ferret.core.Model.Connection;
import ferret.core.Model.DBKind;
import ferret.core.Passwords;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// Вікно «Підключення» (як у DataGrip — усе в одному місці; варіант A з макетів): ліворуч підключення за типом бази з
// пошуком, «+» (вбудовані … через драйвер … своя .jar), «−», копія; праворуч — поля для типу бази, адреса JDBC (складена
// з полів або вручну), розділ «Драйвер» (версія, «Завантажити / Оновити»), «Лише читання»; унизу — «Перевірити
// зʼєднання». Правки, нові паролі й видалення живуть у чернетці вікна й лягають разом — «Застосувати» або «Готово».
public final class ConnectionsDialog extends JDialog {
    private final AppState state;
    private final List<Connection> drafts = new ArrayList<>();
    private final Map<UUID, String> passwords = new HashMap<>();
    private final Set<UUID> deleted = new HashSet<>();
    private final DefaultListModel<Object> listModel = new DefaultListModel<>();
    private final JList<Object> list = new JList<>(listModel);
    private final JTextField search = new JTextField();
    private final JPanel formHolder = new JPanel(new BorderLayout());
    private final JLabel testResult = Ui.label("", Theme.MUTED, false, 12);
    private final JButton testBtn = Ui.plainButton(I18n.t("conn.test"), null);
    private final JButton applyBtn = Ui.plainButton(I18n.t("conn.apply"), null);
    private final JButton doneBtn = Ui.accentButton(I18n.t("conn.done"), null);
    private Connection selected;
    private boolean rebuilding;

    public ConnectionsDialog(JFrame owner, AppState state, UUID select, DBKind newKind) {
        super(owner, I18n.t("conn.title"), true);
        this.state = state;
        for (Connection c : state.connections) drafts.add(c.copy());
        setSize(1040, 720);
        setLocationRelativeTo(owner);
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Theme.PANEL);
        root.add(sidebar(), BorderLayout.WEST);
        formHolder.setBackground(Theme.BG);
        root.add(formHolder, BorderLayout.CENTER);
        root.add(footer(), BorderLayout.SOUTH);
        setContentPane(root);
        rebuildList();
        if (newKind != null) add(newKind);
        else select(drafts.stream().filter(c -> c.id.equals(select)).findFirst().orElse(drafts.isEmpty() ? null : drafts.get(0)));
        getRootPane().registerKeyboardAction(e -> dispose(), javax.swing.KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
    }

    // MARK: список

    private JComponent sidebar() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(Theme.PANEL);
        p.setPreferredSize(new Dimension(290, 0));
        p.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Theme.LINE));
        JPanel tools = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 6));
        tools.setOpaque(false);
        JButton add = Ui.iconButton(Ui.icon(Ui.Glyph.PLUS, Theme.TEXT), I18n.t("conn.add"));
        add.addActionListener(e -> addMenu().show(add, 0, add.getHeight()));
        JButton remove = Ui.iconButton(Ui.icon(Ui.Glyph.MINUS, Theme.TEXT), I18n.t("conn.remove"));
        remove.addActionListener(e -> deleteSelected());
        JButton dup = Ui.iconButton(Ui.icon(Ui.Glyph.COPY, Theme.TEXT), I18n.t("conn.duplicate"));
        dup.addActionListener(e -> duplicate());
        tools.add(add);
        tools.add(remove);
        tools.add(dup);
        Box top = Box.createVerticalBox();
        top.add(tools);
        search.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, I18n.t("conn.search"));
        search.putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON, Ui.icon(Ui.Glyph.SEARCH, Theme.MUTED));
        search.getDocument().addDocumentListener(Sidebar.onChange(this::rebuildList));
        JPanel sw = Ui.row(0, 8, 6, 8);
        sw.add(search);
        top.add(sw);
        p.add(top, BorderLayout.NORTH);
        list.setBackground(Theme.PANEL);
        list.setCellRenderer((l, v, i, sel, f) -> {
            if (v instanceof String head) {
                JLabel h = Ui.label(head, Theme.MUTED, true, 11);
                h.setBorder(BorderFactory.createEmptyBorder(8, 10, 2, 10));
                return h;
            }
            Connection c = (Connection) v;
            boolean on = c == selected;
            JPanel r = new JPanel(new BorderLayout(7, 0));
            r.setBorder(BorderFactory.createEmptyBorder(3, 12, 3, 10));
            r.setBackground(on ? Theme.ACCENT : Theme.PANEL);
            r.add(Ui.dot(Theme.mark(c)), BorderLayout.WEST);
            r.add(Ui.label(c.name.isEmpty() ? I18n.t("conn.unnamed") : c.name, on ? Theme.ON_ACCENT : Theme.TEXT, on, 13), BorderLayout.CENTER);
            return r;
        });
        list.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                int i = list.locationToIndex(e.getPoint());
                if (i >= 0 && listModel.get(i) instanceof Connection c) select(c);
            }
        });
        JScrollPane sp = new JScrollPane(list);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.getViewport().setBackground(Theme.PANEL);
        p.add(sp, BorderLayout.CENTER);
        return p;
    }

    void rebuildList() {
        listModel.clear();
        String q = search.getText().trim().toLowerCase();
        for (DBKind k : DBKind.values()) {
            List<Connection> group = drafts.stream().filter(c -> c.kind == k && !deleted.contains(c.id)
                    && (q.isEmpty() || c.name.toLowerCase().contains(q) || c.host.toLowerCase().contains(q))).toList();
            if (group.isEmpty()) continue;
            listModel.addElement(k.title + " · " + group.size());
            for (Connection c : group) listModel.addElement(c);
        }
        list.repaint();
    }

    JPopupMenu addMenu() {
        JPopupMenu m = new JPopupMenu();
        JMenuItem h1 = new JMenuItem(I18n.t("conn.menu.popular"));
        h1.setEnabled(false);
        m.add(h1);
        for (DBKind k : DBKind.values()) {
            if (!k.popular()) continue;
            JMenuItem i = new JMenuItem(k.title + "   · " + Drivers.size(k));
            i.addActionListener(e -> add(k));
            m.add(i);
        }
        JMenu more = new JMenu(I18n.t("conn.menu.more"));
        for (DBKind k : DBKind.values()) {
            if (k.popular() || k == DBKind.JDBC) continue;
            JMenuItem i = new JMenuItem(k.title + "   · " + Drivers.size(k));
            i.addActionListener(e -> add(k));
            more.add(i);
        }
        m.add(more);
        m.addSeparator();
        JMenuItem own = new JMenuItem(I18n.t("conn.menu.own"));
        own.addActionListener(e -> add(DBKind.JDBC));
        m.add(own);
        return m;
    }

    void add(DBKind k) {
        Connection c = Connection.blank(k);
        c.name = I18n.t("conn.newname");
        c.color = Model.leastUsedColor(drafts);
        drafts.add(c);
        search.setText("");
        rebuildList();
        select(c);
    }

    void duplicate() {
        if (selected == null) return;
        Connection c = selected.copy();
        c.id = UUID.randomUUID();
        c.name = I18n.t("conn.copyname", c.name);
        c.color = Model.leastUsedColor(drafts);
        String pw = passwords.containsKey(selected.id) ? passwords.get(selected.id) : Passwords.get(selected.id);
        if (pw != null) passwords.put(c.id, pw);
        drafts.add(drafts.indexOf(selected) + 1, c);
        rebuildList();
        select(c);
    }

    void deleteSelected() {
        if (selected == null) return;
        if (!Ask.confirm(this, I18n.t("conn.delete.title"), I18n.t("conn.delete.confirm", selected.name), I18n.t("common.delete"), I18n.t("common.cancel"))) return;
        deleted.add(selected.id);
        rebuildList();
        select(drafts.stream().filter(c -> !deleted.contains(c.id)).findFirst().orElse(null));
    }

    void select(Connection c) {
        selected = c;
        testResult.setText("");
        list.repaint();
        rebuildForm();
    }

    // MARK: форма

    void rebuildForm() {
        if (rebuilding) return;
        rebuilding = true;
        try {
            formHolder.removeAll();
            if (selected == null) {
                JLabel l = Ui.label(I18n.t("conn.empty"), Theme.MUTED, false, 13);
                l.setHorizontalAlignment(JLabel.CENTER);
                formHolder.add(l, BorderLayout.CENTER);
            } else {
                JScrollPane sp = new JScrollPane(new Form(selected));
                sp.setBorder(BorderFactory.createEmptyBorder());
                sp.getViewport().setBackground(Theme.BG);
                sp.getVerticalScrollBar().setUnitIncrement(16);
                formHolder.add(sp, BorderLayout.CENTER);
            }
            formHolder.revalidate();
            formHolder.repaint();
            boolean valid = drafts.stream().filter(x -> !deleted.contains(x.id)).allMatch(Drivers::complete);
            applyBtn.setEnabled(valid);
            doneBtn.setEnabled(valid);
            doneBtn.setToolTipText(valid ? null : I18n.t("conn.done.invalid"));
            testBtn.setEnabled(selected != null && Drivers.complete(selected));
        } finally {
            rebuilding = false;
        }
    }

    // Пароль, скопійований із файлу, часто захоплює зайве (так 25.10.2026 не пройшов вхід у ClickHouse BI).
    static String passwordWarning(String p) {
        if (p == null || p.isEmpty()) return null;
        if (!p.equals(p.strip())) return I18n.t("conn.pw.spaces");
        if (p.length() >= 2 && p.charAt(0) == p.charAt(p.length() - 1) && "\"'`".indexOf(p.charAt(0)) >= 0) return I18n.t("conn.pw.quoted");
        return null;
    }

    final class Form extends JPanel {
        final Connection c;
        int row;

        Form(Connection c) {
            super(new GridBagLayout());
            this.c = c;
            setBackground(Theme.BG);
            setBorder(BorderFactory.createEmptyBorder(16, 20, 20, 20));
            DBKind k = c.kind;
            boolean manual = k == DBKind.JDBC || c.jdbcURL != null;
            boolean usesHost = !k.isFile() && k != DBKind.JDBC && k != DBKind.BIGQUERY && !manual;
            boolean usesLogin = k != DBKind.SQLITE && k != DBKind.DUCKDB && k != DBKind.BIGQUERY;
            boolean usesSchema = switch (k) {
                case TRINO, POSTGRES, ORACLE, SNOWFLAKE, REDSHIFT, DB2, HANA, EXASOL, ATHENA, VERTICA, H2, DUCKDB -> true;
                default -> false;
            };

            JTextField name = text(c.name, I18n.t("conn.name.hint"), v -> { c.name = v; rebuildList(); refreshButtons(); });
            field(I18n.t("conn.name"), name);
            JPanel colors = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            colors.setOpaque(false);
            for (int i = 0; i < Model.COLOR_COUNT; i++) {
                int idx = i;
                JComponent sw = new JComponent() {
                    @Override protected void paintComponent(java.awt.Graphics g) {
                        var g2 = Ui.smooth(g);
                        g2.setColor(Theme.MARKS[idx]);
                        g2.fillOval(4, 4, 16, 16);
                        if (c.color != null && c.color == idx) {
                            g2.setColor(Theme.TEXT);
                            g2.setStroke(new java.awt.BasicStroke(2));
                            g2.drawOval(1, 1, 22, 22);
                        }
                        g2.dispose();
                    }
                };
                sw.setPreferredSize(new Dimension(24, 24));
                sw.addMouseListener(new MouseAdapter() {
                    @Override public void mousePressed(MouseEvent e) { c.color = idx; colors.repaint(); list.repaint(); }
                });
                colors.add(sw);
            }
            colors.setToolTipText(I18n.t("conn.color.tip"));
            field(I18n.t("conn.color"), colors);
            JComboBox<DBKind> kind = new JComboBox<>(DBKind.values());
            kind.setSelectedItem(k);
            kind.setRenderer((l, v, i, sel, f) -> {
                JLabel x = Ui.label(v == null ? "" : v.title, sel ? Theme.ON_ACCENT : Theme.TEXT, false, 13);
                x.setOpaque(true);
                x.setBackground(sel ? Theme.ACCENT : Theme.RAISED);
                x.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
                return x;
            });
            kind.addActionListener(e -> {
                DBKind nk = (DBKind) kind.getSelectedItem();
                if (nk == null || nk == c.kind) return;
                c.kind = nk;
                c.port = nk.defaultPort;
                c.useTLS = nk.defaultTLS();
                c.jdbcURL = null;
                SwingUtilities.invokeLater(ConnectionsDialog.this::rebuildForm);
                rebuildList();
            });
            field(I18n.t("conn.kind"), kind);
            section();

            if (k.isFile()) {
                JPanel fp = new JPanel(new BorderLayout(6, 0));
                fp.setOpaque(false);
                JTextField file = text(c.database, k == DBKind.SQLITE ? I18n.t("conn.file.hint.sqlite") : I18n.t("conn.file.hint.memory"), v -> c.database = v);
                JButton pick = Ui.plainButton(I18n.t("conn.file.pick"), null);
                pick.addActionListener(e -> {
                    JFileChooser fc = new JFileChooser();
                    if (fc.showOpenDialog(ConnectionsDialog.this) == JFileChooser.APPROVE_OPTION) file.setText(fc.getSelectedFile().getPath());
                });
                fp.add(file, BorderLayout.CENTER);
                fp.add(pick, BorderLayout.EAST);
                field(I18n.t("conn.file"), fp);
            }
            if (usesHost) {
                field(k == DBKind.ATHENA ? I18n.t("conn.region") : I18n.t("conn.host"), text(c.host, hostPrompt(k), v -> { c.host = v; refreshButtons(); }));
                JTextField port = text(String.valueOf(c.port), "", v -> { try { c.port = Integer.parseInt(v.trim()); } catch (NumberFormatException ignored) { } });
                port.setColumns(6);
                field(I18n.t("conn.port"), port);
                if (!k.alwaysTLS()) {
                    JCheckBox tls = new JCheckBox(k == DBKind.MSSQL || k == DBKind.MARIADB ? I18n.t("conn.tls.nocheck")
                            : I18n.t("conn.tls"), c.useTLS);
                    tls.setOpaque(false);
                    tls.addActionListener(e -> { c.useTLS = tls.isSelected(); SwingUtilities.invokeLater(ConnectionsDialog.this::rebuildForm); });
                    field("", tls);
                }
            }
            if (usesLogin) {
                field(k == DBKind.DATABRICKS ? I18n.t("conn.user.token") : I18n.t("conn.user"), text(c.user, "", v -> c.user = v));
                JPasswordField pw = new JPasswordField(passwords.getOrDefault(c.id, ""));
                boolean stored = Passwords.get(c.id) != null;
                pw.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, stored ? I18n.t("conn.pw.stored") : "");
                JLabel warn = Ui.label("", Theme.ERROR, false, 11);
                pw.getDocument().addDocumentListener(Sidebar.onChange(() -> {
                    String v = new String(pw.getPassword());
                    passwords.put(c.id, v);
                    String w = passwordWarning(v);
                    warn.setText(w == null ? "" : w);
                    warn.setIcon(w == null ? null : Ui.icon(Ui.Glyph.WARN, Theme.ERROR, 11));
                }));
                field(k == DBKind.DATABRICKS ? I18n.t("conn.token") : I18n.t("conn.password"), pw);
                field("", warn);
            }
            if (usesHost || k == DBKind.BIGQUERY) {
                field(databaseLabel(k), text(c.database, k == DBKind.TRINO ? I18n.t("conn.catalog.hint") : "", v -> c.database = v));
                if (usesSchema) field(I18n.t("conn.schema"), text(c.schema, I18n.t("conn.schema.hint"), v -> c.schema = v));
            }
            jdbcRow(k);
            section();
            driverSection(k);
            section();
            JCheckBox ro = new JCheckBox(I18n.t("conn.readonly"), c.readOnly);
            ro.setOpaque(false);
            JTextArea roText = note(readOnlyText(c));
            ro.addActionListener(e -> { c.readOnly = ro.isSelected(); roText.setText(readOnlyText(c)); roText.setForeground(c.readOnly ? Theme.MUTED : Theme.ERROR); });
            roText.setForeground(c.readOnly ? Theme.MUTED : Theme.ERROR);
            field("", ro);
            field("", roText);
            GridBagConstraints filler = new GridBagConstraints();
            filler.gridy = row++;
            filler.weighty = 1;
            add(Box.createVerticalGlue(), filler);
        }

        void jdbcRow(DBKind k) {
            if (k == DBKind.JDBC) {
                JTextField url = text(c.jdbcURL == null ? "" : c.jdbcURL, "jdbc:…", v -> { c.jdbcURL = v; refreshButtons(); });
                url.setFont(Theme.mono(12));
                field(I18n.t("conn.jdbc"), url);
                return;
            }
            JPanel p = new JPanel(new BorderLayout(8, 0));
            p.setOpaque(false);
            JCheckBox manual = new JCheckBox(I18n.t("conn.jdbc.manual"), c.jdbcURL != null);
            manual.setOpaque(false);
            if (c.jdbcURL != null) {
                JTextField url = text(c.jdbcURL, "", v -> { c.jdbcURL = v; refreshButtons(); });
                url.setFont(Theme.mono(12));
                p.add(url, BorderLayout.CENTER);
            } else {
                JTextArea auto = note(Drivers.defaultURL(c));
                auto.setFont(Theme.mono(12));
                p.add(auto, BorderLayout.CENTER);
            }
            manual.addActionListener(e -> {
                c.jdbcURL = manual.isSelected() ? Drivers.defaultURL(c) : null;
                SwingUtilities.invokeLater(ConnectionsDialog.this::rebuildForm);
            });
            p.add(manual, BorderLayout.EAST);
            p.setToolTipText(I18n.t("conn.jdbc.tip"));
            field(I18n.t("conn.jdbc"), p);
        }

        void driverSection(DBKind k) {
            heading(I18n.t("conn.driver"));
            if (k == DBKind.JDBC) {
                JPanel jars = new JPanel();
                jars.setLayout(new BoxLayout(jars, BoxLayout.Y_AXIS));
                jars.setOpaque(false);
                for (String j : c.driverJars == null ? List.<String>of() : c.driverJars) {
                    JPanel r = new JPanel(new BorderLayout(6, 0));
                    r.setOpaque(false);
                    r.add(Ui.label(Path.of(j).getFileName().toString(), Theme.TEXT, false, 12), BorderLayout.CENTER);
                    JButton rm = Ui.iconButton(Ui.icon(Ui.Glyph.MINUS, Theme.MUTED), I18n.t("conn.driver.remove"));
                    rm.addActionListener(e -> { c.driverJars.remove(j); rebuildForm(); });
                    r.add(rm, BorderLayout.EAST);
                    jars.add(r);
                }
                JButton add = Ui.plainButton(I18n.t("conn.driver.addjar"), Ui.icon(Ui.Glyph.PLUS, Theme.TEXT));
                add.addActionListener(e -> {
                    JFileChooser fc = new JFileChooser();
                    fc.setFileFilter(new FileNameExtensionFilter(I18n.t("conn.driver.filter"), "jar"));
                    fc.setMultiSelectionEnabled(true);
                    if (fc.showOpenDialog(ConnectionsDialog.this) != JFileChooser.APPROVE_OPTION) return;
                    try {
                        if (c.driverJars == null) c.driverJars = new ArrayList<>();
                        for (var f : fc.getSelectedFiles()) {
                            String p = Drivers.adoptCustom(f.toPath());
                            if (!c.driverJars.contains(p)) c.driverJars.add(p);
                        }
                    } catch (Exception ex) {
                        Ask.inform(ConnectionsDialog.this, "Ferret", ex.getMessage());
                    }
                    rebuildForm();
                });
                jars.add(add);
                field(I18n.t("conn.driver.files"), jars);
                field("", note(I18n.t("conn.driver.jarnote")));
                return;
            }
            Drivers.Spec spec = Drivers.spec(k);
            field(I18n.t("conn.driver"), Ui.label(k.title + " (" + spec.artifact() + ")", Theme.TEXT, false, 13));
            JPanel ver = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            ver.setOpaque(false);
            Drivers.Installed inst = Drivers.installed(k);
            JLabel status = Ui.label(inst == null ? I18n.t("conn.driver.missing") : I18n.t("conn.driver.installed", Drivers.display(k, inst.version())), Theme.TEXT, false, 13);
            JComboBox<String> versions = new JComboBox<>();
            versions.setEnabled(false);
            JButton act = Ui.accentButton("…", null);
            act.setVisible(false);
            JLabel size = Ui.label("≈ " + Drivers.size(k), Theme.MUTED, false, 11);
            JLabel err = Ui.label("", Theme.ERROR, false, 11);
            ver.add(status);
            ver.add(versions);
            ver.add(act);
            ver.add(size);
            field(I18n.t("conn.driver.version"), ver);
            field("", err);
            field("", note(I18n.t("conn.driver.note", k.title)));
            new Thread(() -> {
                try {
                    List<String> vs = Drivers.versions(k, false);
                    SwingUtilities.invokeLater(() -> {
                        versions.removeAllItems();
                        for (String v : vs.subList(0, Math.min(40, vs.size()))) versions.addItem(v);
                        versions.setRenderer((l, v, i, sel, f) -> {
                            JLabel x = Ui.label(v == null ? "" : i == 0 || v.equals(vs.get(0)) ? I18n.t("conn.driver.latest", Drivers.display(k, v)) : Drivers.display(k, v),
                                    sel ? Theme.ON_ACCENT : Theme.TEXT, false, 12);
                            x.setOpaque(true);
                            x.setBackground(sel ? Theme.ACCENT : Theme.RAISED);
                            x.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
                            return x;
                        });
                        Drivers.Installed now = Drivers.installed(k);
                        versions.setSelectedItem(now != null ? now.version() : vs.isEmpty() ? null : vs.get(0));
                        versions.setEnabled(true);
                        Runnable label = () -> {
                            String chosen = (String) versions.getSelectedItem();
                            Drivers.Installed cur = Drivers.installed(k);
                            if (chosen == null) { act.setVisible(false); return; }
                            boolean same = cur != null && cur.version().equals(chosen);
                            act.setVisible(!same);
                            act.setText(I18n.t(cur == null ? "conn.driver.download" : "conn.driver.install", Drivers.display(k, chosen)));
                        };
                        versions.addActionListener(e -> label.run());
                        label.run();
                        act.addActionListener(e -> {
                            String v = (String) versions.getSelectedItem();
                            if (v == null) return;
                            act.setEnabled(false);
                            act.setText(I18n.t("conn.driver.downloading"));
                            err.setText("");
                            new Thread(() -> {
                                try {
                                    Drivers.install(k, v);
                                    SwingUtilities.invokeLater(() -> {
                                        status.setText(I18n.t("conn.driver.installed", Drivers.display(k, v)));
                                        act.setEnabled(true);
                                        label.run();
                                    });
                                } catch (Exception ex) {
                                    SwingUtilities.invokeLater(() -> { act.setEnabled(true); label.run(); err.setText(ex.getMessage()); });
                                }
                            }, "ferret-driver").start();
                        });
                    });
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> err.setText(I18n.t("conn.driver.versions.failed", ex.getMessage())));
                }
            }, "ferret-versions").start();
        }

        String hostPrompt(DBKind k) {
            return switch (k) {
                case TRINO -> I18n.t("conn.host.hint.trino");
                case SNOWFLAKE -> I18n.t("conn.host.hint.snowflake");
                case ATHENA -> I18n.t("conn.host.hint.athena");
                case DATABRICKS -> I18n.t("conn.host.hint.databricks");
                default -> I18n.t("conn.host.hint");
            };
        }

        String databaseLabel(DBKind k) {
            return switch (k) {
                case TRINO, ATHENA -> I18n.t("conn.db.catalog");
                case ORACLE -> I18n.t("conn.db.service");
                case BIGQUERY -> I18n.t("conn.db.project");
                case DATABRICKS -> I18n.t("conn.db.httppath");
                default -> I18n.t("conn.db");
            };
        }

        JTextField text(String value, String prompt, java.util.function.Consumer<String> set) {
            JTextField f = new JTextField(value);
            f.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, prompt);
            f.getDocument().addDocumentListener(Sidebar.onChange(() -> set.accept(f.getText())));
            return f;
        }

        JTextArea note(String s) {
            JTextArea a = new JTextArea(s);
            a.setEditable(false);
            a.setLineWrap(true);
            a.setWrapStyleWord(true);
            a.setOpaque(false);
            a.setForeground(Theme.MUTED);
            a.setFont(a.getFont().deriveFont(12f));
            a.setBorder(BorderFactory.createEmptyBorder());
            return a;
        }

        void field(String label, Component comp) {
            GridBagConstraints l = new GridBagConstraints();
            l.gridx = 0;
            l.gridy = row;
            l.anchor = GridBagConstraints.LINE_END;
            l.insets = new Insets(5, 0, 5, 14);
            add(Ui.label(label, Theme.MUTED, false, 13), l);
            GridBagConstraints c2 = new GridBagConstraints();
            c2.gridx = 1;
            c2.gridy = row++;
            c2.weightx = 1;
            c2.fill = GridBagConstraints.HORIZONTAL;
            c2.insets = new Insets(5, 0, 5, 0);
            add(comp, c2);
        }

        void heading(String s) {
            GridBagConstraints c2 = new GridBagConstraints();
            c2.gridx = 0;
            c2.gridy = row++;
            c2.gridwidth = 2;
            c2.anchor = GridBagConstraints.LINE_START;
            c2.insets = new Insets(4, 0, 4, 0);
            add(Ui.label(s, Theme.TEXT, true, 14), c2);
        }

        void section() {
            GridBagConstraints c2 = new GridBagConstraints();
            c2.gridx = 0;
            c2.gridy = row++;
            c2.gridwidth = 2;
            c2.fill = GridBagConstraints.HORIZONTAL;
            c2.insets = new Insets(10, 0, 10, 0);
            JPanel line = new JPanel();
            line.setBackground(Theme.LINE);
            line.setPreferredSize(new Dimension(10, 1));
            add(line, c2);
        }
    }

    static String readOnlyText(Connection c) {
        if (!c.readOnly) return I18n.t("conn.ro.off");
        // речення ціле в кожному варіанті: з відкатом змін чи без, з приміткою про MS SQL чи без
        boolean rollback = Drivers.rollbackGuard(c.kind);
        if (c.kind == DBKind.MSSQL) return I18n.t(rollback ? "conn.ro.on.rollback.mssql" : "conn.ro.on.mssql");
        return I18n.t(rollback ? "conn.ro.on.rollback" : "conn.ro.on");
    }

    void refreshButtons() {
        boolean valid = drafts.stream().filter(x -> !deleted.contains(x.id)).allMatch(Drivers::complete);
        applyBtn.setEnabled(valid);
        doneBtn.setEnabled(valid);
        testBtn.setEnabled(selected != null && Drivers.complete(selected));
    }

    // MARK: низ вікна

    private JComponent footer() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(Theme.PANEL);
        p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.LINE), BorderFactory.createEmptyBorder(10, 14, 10, 14)));
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        left.setOpaque(false);
        testBtn.addActionListener(e -> test());
        left.add(testBtn);
        left.add(testResult);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        JButton cancel = Ui.plainButton(I18n.t("conn.cancel"), null);
        cancel.addActionListener(e -> dispose());
        applyBtn.addActionListener(e -> apply());
        doneBtn.addActionListener(e -> { apply(); dispose(); });
        right.add(cancel);
        right.add(applyBtn);
        right.add(doneBtn);
        p.add(left, BorderLayout.CENTER);
        p.add(right, BorderLayout.EAST);
        getRootPane().setDefaultButton(doneBtn);
        return p;
    }

    void apply() {
        List<Connection> keep = drafts.stream().filter(c -> !deleted.contains(c.id)).map(Connection::copy).toList();
        state.applyConnections(new ArrayList<>(keep), new HashMap<>(passwords), new HashSet<>(deleted));
        drafts.removeIf(c -> deleted.contains(c.id));
        passwords.clear();
        deleted.clear();
        rebuildList();
        rebuildForm();
    }

    void test() {
        if (selected == null) return;
        Connection c = selected.copy();
        String typed = passwords.getOrDefault(c.id, "");
        String pw = typed.isEmpty() ? Passwords.get(c.id) : typed;
        boolean needsDriver = c.kind != DBKind.JDBC && Drivers.installed(c.kind) == null;
        testBtn.setEnabled(false);
        testBtn.setText(needsDriver ? I18n.t("conn.test.driver") : I18n.t("conn.test.running"));
        testResult.setText("");
        testResult.setIcon(null);
        new Thread(() -> {
            long t0 = System.nanoTime();
            String ok = null, err = null;
            try {
                Drivers.jars(c);
                SwingUtilities.invokeLater(() -> testBtn.setText(I18n.t("conn.test.running")));
                String product = Jdbc.test(c, pw == null ? "" : pw);
                ok = I18n.t("conn.test.ok", String.valueOf((System.nanoTime() - t0) / 1_000_000), product);
            } catch (Exception ex) {
                err = ex.getMessage();
            }
            String fo = ok, fe = err;
            SwingUtilities.invokeLater(() -> {
                testBtn.setEnabled(true);
                testBtn.setText(I18n.t("conn.test"));
                Color col = fo != null ? new Color(0x7CC7A8) : Theme.ERROR;
                testResult.setForeground(col);
                testResult.setIcon(Ui.icon(fo != null ? Ui.Glyph.PLAY : Ui.Glyph.WARN, col, 12));
                String s = fo != null ? fo : fe;
                // першим рядком — людське речення (Jdbc.hint), текст драйвера — у підказці
                String line = s == null ? "" : s.lines().findFirst().orElse("");
                testResult.setText(line.length() > 140 ? line.substring(0, 140) + "…" : line);
                testResult.setToolTipText(s == null ? null : "<html>" + s.replace("&", "&amp;").replace("<", "&lt;").replace("\n", "<br>") + "</html>");
            });
        }, "ferret-test").start();
    }
}
