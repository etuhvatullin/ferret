package ferret.ui;

import ferret.App;
import ferret.core.I18n;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.datatransfer.StringSelection;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

// Меню «Довідка»: посилання на GitHub (браузер, а без нього — посилання в буфер), заявка про помилку з уже вписаними
// версією й системою, і вікно гарячих клавіш — зібране з самого меню, тож завжди збігається з програмою.
final class Help {
    private Help() { }

    static final String REPO = "https://github.com/etuhvatullin/ferret";

    static void open(java.awt.Component parent, String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (Exception ignored) { }
        java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(url), null);
        JOptionPane.showMessageDialog(parent, I18n.t("help.nobrowser", url), "Ferret", JOptionPane.INFORMATION_MESSAGE);
    }

    // Заявка — англійською (репозиторій публічний), з версією, системою й Java, і нагадуванням про ferret.log
    static void reportBug(java.awt.Component parent) {
        String body = "**Ferret:** " + App.version() + "\n**OS:** " + System.getProperty("os.name") + " " + System.getProperty("os.version")
                + " (" + System.getProperty("os.arch") + ")\n**Java:** " + System.getProperty("java.version")
                + "\n\n**What happened:**\n\n\n**Steps to reproduce:**\n\n\n_Please attach `ferret.log` from the data folder "
                + "(Windows: `%APPDATA%\\Ferret\\v2`, macOS: `~/Library/Application Support/Ferret/v2`)._";
        open(parent, REPO + "/issues/new?body=" + URLEncoder.encode(body, StandardCharsets.UTF_8));
    }

    // Гарячі клавіші: усе з меню (назва + клавіша) і кілька клавіш поза меню
    static void shortcuts(JFrame owner, JMenuBar bar) {
        JPanel list = new JPanel(new GridBagLayout());
        list.setBackground(Theme.PANEL);
        list.setBorder(BorderFactory.createEmptyBorder(14, 18, 14, 18));
        int[] row = {0};
        for (int i = 0; i < bar.getMenuCount(); i++) {
            JMenu m = bar.getMenu(i);
            if (m == null) continue;
            boolean header = false;
            for (int j = 0; j < m.getItemCount(); j++) {
                JMenuItem it = m.getItem(j);
                if (it == null || it.getAccelerator() == null) continue;
                if (!header) { section(list, row, m.getText()); header = true; }
                line(list, row, it.getText(), keys(it.getAccelerator()));
            }
        }
        section(list, row, I18n.t("help.keys.more"));
        if (Theme.MAC) line(list, row, I18n.t("main.menu.settings"), "⌘,");
        line(list, row, I18n.t("help.keys.suggest"), "Tab");
        line(list, row, I18n.t("help.keys.list"), "Esc");
        line(list, row, I18n.t("help.keys.copy"), Theme.MAC ? "⌘C" : "Ctrl+C");
        line(list, row, I18n.t("help.keys.preview"), I18n.t("help.keys.doubleclick"));
        GridBagConstraints end = new GridBagConstraints();
        end.gridy = row[0];
        end.weighty = 1;
        list.add(new JPanel() { { setOpaque(false); } }, end);
        JDialog d = new JDialog(owner, I18n.t("main.menu.shortcuts"), true);
        JScrollPane sp = new JScrollPane(list);
        sp.setBorder(BorderFactory.createEmptyBorder());
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Theme.PANEL);
        root.add(sp, BorderLayout.CENTER);
        root.add(footer(d), BorderLayout.SOUTH);
        d.setContentPane(root);
        d.getRootPane().registerKeyboardAction(e -> d.dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        d.setSize(520, 620);
        d.setLocationRelativeTo(owner);
        d.setVisible(true);
    }

    static void section(JPanel p, int[] row, String title) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = row[0]++;
        c.gridwidth = 2;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(row[0] == 1 ? 0 : 14, 0, 6, 0);
        p.add(Ui.label(title.toUpperCase(I18n.locale()), Theme.MUTED, true, 11), c);
    }

    static void line(JPanel p, int[] row, String action, String keys) {
        GridBagConstraints a = new GridBagConstraints(), k = new GridBagConstraints();
        a.gridy = k.gridy = row[0]++;
        a.gridx = 0;
        k.gridx = 1;
        a.anchor = GridBagConstraints.WEST;
        k.anchor = GridBagConstraints.EAST;
        a.weightx = 1;
        a.insets = k.insets = new Insets(3, 0, 3, 0);
        p.add(Ui.label(action, Theme.TEXT, false, 13), a);
        javax.swing.JLabel key = Ui.label(keys, Theme.ACCENT, false, 12);
        key.setFont(Theme.mono(12));
        p.add(key, k);
    }

    // Клавіша так, як її пишуть на цій системі: ⌃⌥⇧⌘ на Mac, «Ctrl+Shift+…» деінде
    static String keys(KeyStroke ks) {
        int m = ks.getModifiers();
        String key = switch (ks.getKeyCode()) {
            case KeyEvent.VK_ENTER -> Theme.MAC ? "↵" : "Enter";
            case KeyEvent.VK_PERIOD -> ".";
            case KeyEvent.VK_COMMA -> ",";
            case KeyEvent.VK_SLASH -> "/";
            case KeyEvent.VK_OPEN_BRACKET -> "[";
            case KeyEvent.VK_CLOSE_BRACKET -> "]";
            default -> KeyEvent.getKeyText(ks.getKeyCode());
        };
        StringBuilder b = new StringBuilder();
        if (Theme.MAC) {
            if ((m & InputEvent.CTRL_DOWN_MASK) != 0) b.append('⌃');
            if ((m & InputEvent.ALT_DOWN_MASK) != 0) b.append('⌥');
            if ((m & InputEvent.SHIFT_DOWN_MASK) != 0) b.append('⇧');
            if ((m & InputEvent.META_DOWN_MASK) != 0) b.append('⌘');
            return b + key;
        }
        if ((m & InputEvent.CTRL_DOWN_MASK) != 0) b.append("Ctrl+");
        if ((m & InputEvent.ALT_DOWN_MASK) != 0) b.append("Alt+");
        if ((m & InputEvent.SHIFT_DOWN_MASK) != 0) b.append("Shift+");
        return b + key;
    }

    static JPanel footer(JDialog d) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        p.setBackground(Theme.PANEL);
        p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.LINE), BorderFactory.createEmptyBorder(10, 14, 10, 14)));
        javax.swing.JButton close = Ui.plainButton(I18n.t("help.close"), null);
        close.addActionListener(e -> d.dispose());
        p.add(close);
        d.getRootPane().setDefaultButton(close);
        return p;
    }
}
