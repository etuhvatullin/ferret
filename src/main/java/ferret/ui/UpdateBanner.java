package ferret.ui;

import ferret.App;
import ferret.core.Updater;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.Dimension;

// Смуга «Є нова версія — Оновити» під заголовком. Перевірка — при запуску (заставка, Splash) або за 5 с після нього й далі раз на добу; вручну —
// меню «Запит → Перевірити оновлення». «Оновити» завантажує й перевіряє архів, і Ferret перезапускається вже новим.
public final class UpdateBanner extends JPanel {
    private final JLabel text = Ui.label("", Theme.ON_ACCENT, true, 12);
    private final JButton go = Ui.plainButton("Оновити", Ui.icon(Ui.Glyph.DOWNLOAD, Theme.TEXT));
    private final JButton notes = Ui.plainButton("Що нового", null);
    private Updater.Release release;
    public Runnable onQuit = () -> System.exit(0);

    UpdateBanner() {
        setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
        setBackground(Theme.ACCENT);
        setBorder(BorderFactory.createEmptyBorder(5, 12, 5, 12));
        add(text);
        add(Box.createHorizontalGlue());
        add(notes);
        add(Ui.gap(6));
        add(go);
        setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        setVisible(false);
        notes.addActionListener(e -> JOptionPane.showMessageDialog(this, release == null ? "" : release.notes(), "Ferret " + (release == null ? "" : release.version()),
                JOptionPane.INFORMATION_MESSAGE));
        go.addActionListener(e -> install());
    }

    private boolean checkedAtStart;

    // Заставка вже перевірила оновлення при запуску: перша перевірка не потрібна, а відкладене «Пізніше» — у смузі.
    public void afterSplash(Updater.Release later) {
        checkedAtStart = true;
        if (later != null) offer(later);
    }

    void offer(Updater.Release r) {
        release = r;
        text.setText("Є нова версія Ferret " + r.version() + " (у тебе " + App.version() + ")");
        setVisible(true);
        revalidate();
    }

    void checkLater() {
        if (!Updater.configured()) return;
        new Thread(Updater::cleanupOld, "ferret-cleanup").start();
        if (!checkedAtStart) {
            Timer first = new Timer(5000, e -> checkNow(false));
            first.setRepeats(false);
            first.start();
        }
        new Timer(24 * 3600 * 1000, e -> checkNow(false)).start();
    }

    void checkNow(boolean manual) {
        if (!Updater.configured()) {
            if (manual) JOptionPane.showMessageDialog(this, "У цій збірці оновлення не налаштовано.", "Оновлення", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        new Thread(() -> {
            try {
                Updater.Release r = Updater.check(App.version());
                SwingUtilities.invokeLater(() -> {
                    if (r != null) offer(r);
                    else if (manual) {
                        JOptionPane.showMessageDialog(this, "У тебе найновіша версія — " + App.version() + ".", "Оновлення", JOptionPane.INFORMATION_MESSAGE);
                    }
                });
            } catch (Exception ex) {
                if (manual) SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, ex.getMessage(), "Оновлення", JOptionPane.WARNING_MESSAGE));
            }
        }, "ferret-update").start();
    }

    void install() {
        if (release == null) return;
        go.setEnabled(false);
        text.setText("Завантажую Ferret " + release.version() + "…");
        new Thread(() -> {
            try {
                Updater.install(release, done -> SwingUtilities.invokeLater(() ->
                        text.setText("Завантажую Ferret " + release.version() + "… " + Math.round(done * 100) + "%")));
                SwingUtilities.invokeLater(() -> onQuit.run());
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    go.setEnabled(true);
                    text.setText("Є нова версія Ferret " + release.version());
                    JOptionPane.showMessageDialog(this, ex.getMessage(), "Оновлення не вдалося", JOptionPane.WARNING_MESSAGE);
                });
            }
        }, "ferret-update").start();
    }
}
