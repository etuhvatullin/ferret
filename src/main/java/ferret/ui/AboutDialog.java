package ferret.ui;

import ferret.App;
import ferret.core.I18n;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.event.KeyEvent;

// «Про Ferret» — у стилі заставки: ліворуч нора з тхором, праворуч знак, версія, кілька слів про те, навіщо це все,
// підпис і посилання (GitHub, ліцензія, сторонні компоненти).
final class AboutDialog {
    private AboutDialog() { }

    static void show(JFrame owner) {
        JDialog d = new JDialog(owner, I18n.t("main.menu.about"), true);
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Theme.PANEL);
        root.add(Splash.burrow(300), BorderLayout.WEST);

        Box r = Box.createVerticalBox();
        r.setBorder(BorderFactory.createEmptyBorder(32, 30, 20, 30));
        JComponent mark = Splash.picture(Theme.image("wordmark.png"), 26);
        mark.setAlignmentX(Component.LEFT_ALIGNMENT);
        r.add(mark);
        r.add(Box.createVerticalStrut(6));
        r.add(Splash.left(Ui.label(I18n.t("about.version", App.version()), Theme.alpha(Theme.MUTED, 190), false, 11)));
        r.add(Box.createVerticalStrut(18));
        r.add(Splash.left(text(I18n.t("about.text"))));
        r.add(Box.createVerticalStrut(12));
        r.add(Splash.left(Ui.label(I18n.t("about.signature"), Theme.TEXT, true, 13)));
        r.add(Box.createVerticalGlue());
        r.add(Splash.left(Ui.buttons(16, link(d, "GitHub", Help.REPO),
                link(d, I18n.t("about.license"), Help.REPO + "/blob/main/LICENSE"),
                link(d, I18n.t("about.thirdparty"), Help.REPO + "/blob/main/THIRD-PARTY.md"))));

        JPanel right = new JPanel(new BorderLayout());
        right.setOpaque(false);
        right.add(r, BorderLayout.CENTER);
        root.add(right, BorderLayout.CENTER);
        root.add(Help.footer(d), BorderLayout.SOUTH);
        d.setContentPane(root);
        d.getRootPane().registerKeyboardAction(e -> d.dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        d.setSize(660, 410);
        d.setResizable(false);
        d.setLocationRelativeTo(owner);
        d.setVisible(true);
    }

    // Текст повністю (поле заставки ріже довгі описи оновлень): ширина правої частини, висота — скільки треба
    static javax.swing.JTextArea text(String s) { return text(s, 380); }

    static javax.swing.JTextArea text(String s, int width) {
        javax.swing.JTextArea a = new javax.swing.JTextArea(s);
        ((javax.swing.text.DefaultCaret) a.getCaret()).setUpdatePolicy(javax.swing.text.DefaultCaret.NEVER_UPDATE);   // у прокрутці не тягне її до себе
        a.setLineWrap(true);
        a.setWrapStyleWord(true);
        a.setEditable(false);
        a.setFocusable(false);
        a.setOpaque(false);
        a.setForeground(Theme.MUTED);
        a.setFont(a.getFont().deriveFont(13f));
        a.setBorder(null);
        a.setSize(width, 1);
        java.awt.Dimension d = new java.awt.Dimension(width, a.getPreferredSize().height);
        a.setPreferredSize(d);
        a.setMaximumSize(d);
        return a;
    }

    // Посилання — медовий текст без рамки, рука під курсором
    static JButton link(JDialog d, String text, String url) {
        JButton b = new JButton(text);
        b.putClientProperty(com.formdev.flatlaf.FlatClientProperties.BUTTON_TYPE, com.formdev.flatlaf.FlatClientProperties.BUTTON_TYPE_BORDERLESS);
        b.setForeground(Theme.ACCENT);
        b.setFont(b.getFont().deriveFont(12.5f));
        b.setMargin(new java.awt.Insets(2, 0, 2, 0));
        b.setFocusable(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setToolTipText(url);
        b.addActionListener(e -> Help.open(d, url));
        return b;
    }
}
