package ferret.ui;

import ferret.core.I18n;
import ferret.core.Json;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

// «Налаштування» (Ctrl+, / ⌘,): мова і перевірка оновлень під час запуску. Мова — після перезапуску (вікна вже
// побудовані старою мовою), решта — одразу. Кількість останніх серверів — у бічній панелі, там, де видно список.
final class SettingsDialog {
    private SettingsDialog() { }

    static void show(MainWindow w, AppState state) {
        JDialog d = new JDialog(w, I18n.t("settings.title"), true);
        List<String> codes = new ArrayList<>(I18n.NAMES.keySet());
        JComboBox<String> lang = new JComboBox<>(I18n.NAMES.values().toArray(new String[0]));
        Object saved = state.settings.get("language");
        lang.setSelectedIndex(Math.max(0, codes.indexOf(saved instanceof String s && codes.contains(s) ? s : I18n.lang())));
        JCheckBox updates = new JCheckBox(I18n.t("settings.updates"), Json.bool(state.settings.get("checkUpdates"), true));
        updates.setOpaque(false);

        JPanel form = new JPanel(new GridBagLayout());
        form.setBackground(Theme.BG);
        form.setBorder(BorderFactory.createEmptyBorder(22, 24, 22, 24));
        row(form, 0, I18n.t("main.language"), lang);
        row(form, 1, "", Ui.label(I18n.t("settings.language.note"), Theme.MUTED, false, 11));
        row(form, 2, "", updates);
        GridBagConstraints end = new GridBagConstraints();
        end.gridy = 3;
        end.weighty = 1;
        form.add(new JPanel() { { setOpaque(false); } }, end);

        JPanel foot = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        foot.setBackground(Theme.PANEL);
        foot.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.LINE), BorderFactory.createEmptyBorder(10, 14, 10, 14)));
        JButton cancel = Ui.plainButton(I18n.t("settings.cancel"), null);
        JButton save = Ui.accentButton(I18n.t("settings.save"), null);
        cancel.addActionListener(e -> d.dispose());
        save.addActionListener(e -> {
            String code = codes.get(lang.getSelectedIndex());
            state.settings.put("checkUpdates", updates.isSelected());
            d.dispose();
            w.chooseLanguage(code);   // зберігає налаштування; інша мова — пропозиція перезапуститись
        });
        foot.add(cancel);
        foot.add(save);

        JPanel root = new JPanel(new BorderLayout());
        root.add(form, BorderLayout.CENTER);
        root.add(foot, BorderLayout.SOUTH);
        d.setContentPane(root);
        d.getRootPane().setDefaultButton(save);
        d.getRootPane().registerKeyboardAction(e -> d.dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        d.setSize(520, 250);
        d.setLocationRelativeTo(w);
        d.setVisible(true);
    }

    static void row(JPanel p, int y, String label, JComponent c) {
        GridBagConstraints l = new GridBagConstraints(), v = new GridBagConstraints();
        l.gridy = v.gridy = y;
        l.gridx = 0;
        v.gridx = 1;
        l.anchor = GridBagConstraints.EAST;
        v.anchor = GridBagConstraints.WEST;
        v.weightx = 1;
        l.insets = new Insets(4, 0, 4, 14);
        v.insets = new Insets(4, 0, 4, 0);
        p.add(Ui.label(label, Theme.MUTED, false, 13), l);
        p.add(c, v);
    }
}
