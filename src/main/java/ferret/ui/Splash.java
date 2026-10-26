package ferret.ui;

import ferret.App;
import ferret.core.I18n;
import ferret.core.Updater;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagLayout;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

// Заставка запуску (варіант B «Розворот» із трьох макетів): ліворуч нора з тхором, праворуч знак і стан —
// «Перевіряю оновлення…» → нова версія з «Оновити / Пізніше» → завантаження з відсотком → перезапуск. Перевірка — не
// довше 3 с (без VPN не тримає), заставка — не менше 0,7 с (щоб не блимала). «Пізніше» відкриває Ferret, а нова версія
// лишається в смузі вгорі вікна. Лише в установленій програмі з налаштованими оновленнями; інакше Ferret відкривається одразу.
public final class Splash extends JFrame {
    static final int W = 620, H = 300, ARC = 16;
    static final long CHECK_MS = 3000, MIN_MS = 700;

    private final Consumer<Updater.Release> openApp;   // null — оновлень немає; інакше — відкладена «Пізніше»
    private final Box state = Box.createVerticalBox();
    private final long shownAt = System.currentTimeMillis();
    private final int pad;   // поле під намальовану тінь (Windows); на Mac тінь рідна
    private Bar bar;
    private Timer anim;

    public static void start(Consumer<Updater.Release> openApp) {
        if (!Updater.configured() || !checksAtStart()) { openApp.accept(null); return; }
        Splash s = new Splash(openApp);
        s.setVisible(true);
        s.check();
    }

    // «Налаштування → Перевіряти оновлення під час запуску» (за замовчуванням так); вимкнено — ні заставки, ні щоденної
    // перевірки у фоні, лише «Довідка → Перевірити оновлення»
    public static boolean checksAtStart() {
        return ferret.core.Json.bool(ferret.core.Json.map(ferret.core.Store.loadJSON("settings.json")).get("checkUpdates"), true);
    }

    private Splash(Consumer<Updater.Release> openApp) {
        super("Ferret");
        this.openApp = openApp;
        Image icon = Theme.image("icon.png");
        if (icon != null) setIconImage(icon);
        setUndecorated(true);
        GraphicsDevice gd = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        boolean clear = gd.isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT);
        pad = clear && !Theme.MAC ? 22 : 0;
        if (clear) setBackground(new Color(0, 0, 0, 0));
        JPanel root = new JPanel(new BorderLayout()) {
            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                for (int i = pad; i > 0; i--) {   // мʼяка тінь зі зсувом донизу
                    g.setColor(new Color(0, 0, 0, 5));
                    g.fill(new RoundRectangle2D.Double(pad - i * 0.7, pad + 6 - i * 0.7, W + i * 1.4, H + i * 1.4, ARC + i, ARC + i));
                }
                g.setColor(Theme.PANEL);
                g.fill(new RoundRectangle2D.Double(pad, pad, W, H, ARC, ARC));
                g.dispose();
            }

            @Override protected void paintChildren(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                g.clip(new RoundRectangle2D.Double(pad, pad, W, H, ARC, ARC));
                super.paintChildren(g);
                g.setClip(null);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(Theme.LINE);
                g.draw(new RoundRectangle2D.Double(pad + 0.5, pad + 0.5, W - 1, H - 1, ARC, ARC));
                g.dispose();
            }
        };
        root.setOpaque(false);
        root.setBorder(BorderFactory.createEmptyBorder(pad, pad, pad, pad));
        root.add(burrow(H), BorderLayout.WEST);
        root.add(side(), BorderLayout.CENTER);
        setContentPane(root);
        setSize(W + pad * 2, H + pad * 2);
        setLocationRelativeTo(null);
        showChecking();
    }

    // Ліворуч — нора з тхором (її ж бере вікно «Про Ferret»)
    static JComponent burrow(int height) {
        JPanel left = new JPanel(new GridBagLayout()) {
            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                g.setPaint(new GradientPaint(0, 0, new Color(0x241D16), 0, getHeight(), Theme.BG));
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(Theme.LINE);
                g.fillRect(getWidth() - 1, 0, 1, getHeight());
                g.dispose();
            }
        };
        left.setPreferredSize(new Dimension(210, height));
        left.add(picture(Theme.image("icon.png"), 116));
        return left;
    }

    // Праворуч — знак, версія, стан унизу
    private JComponent side() {
        Box r = Box.createVerticalBox();
        r.setBorder(BorderFactory.createEmptyBorder(38, 34, 28, 34));
        JComponent mark = picture(Theme.image("wordmark.png"), 26);
        mark.setAlignmentX(Component.LEFT_ALIGNMENT);
        r.add(mark);
        r.add(Box.createVerticalStrut(6));
        r.add(left(Ui.label(I18n.t("splash.version", App.version()), Theme.alpha(Theme.MUTED, 190), false, 11)));
        r.add(Box.createVerticalGlue());
        state.setAlignmentX(Component.LEFT_ALIGNMENT);
        r.add(state);
        return r;
    }

    // MARK: стани

    private void showChecking() {
        state.removeAll();
        state.add(left(Ui.label(I18n.t("splash.checking"), Theme.MUTED, false, 13)));
        state.add(Box.createVerticalStrut(10));
        state.add(newBar(-1));
        refresh();
    }

    private void showFound(Updater.Release r) {
        state.removeAll();
        state.add(left(Ui.label(I18n.t("splash.found", r.version()), Theme.TEXT, true, 15)));
        if (!r.notes().isBlank()) {
            state.add(Box.createVerticalStrut(6));
            state.add(left(notes(r.notes())));
        }
        state.add(Box.createVerticalStrut(14));
        JButton later = Ui.plainButton(I18n.t("splash.later"), null);
        JButton go = Ui.accentButton(I18n.t("splash.update"), Ui.icon(Ui.Glyph.DOWNLOAD, Theme.ON_ACCENT, 12));
        later.addActionListener(e -> finish(r));
        go.addActionListener(e -> download(r));
        state.add(left(Ui.buttons(8, later, go)));
        getRootPane().setDefaultButton(go);
        EditorPanel.bind(getRootPane(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "later", () -> { if (later.isShowing()) later.doClick(); });
        refresh();
    }

    private void showDownload(Updater.Release r, double done) {
        if (bar == null || bar.p < 0) {
            state.removeAll();
            state.add(left(Ui.label("", Theme.TEXT, true, 14)));
            state.add(Box.createVerticalStrut(10));
            state.add(newBar(0));
            state.add(Box.createVerticalStrut(10));
            state.add(left(Ui.label(I18n.t("splash.restartnote"), Theme.MUTED, false, 12)));
            refresh();
        }
        ((JLabel) state.getComponent(0)).setText(I18n.t("splash.downloading", r.version(), Math.round(done * 100)));
        bar.p = done;
        bar.repaint();
    }

    private void showError(Updater.Release r, String message) {
        state.removeAll();
        state.add(left(Ui.label(I18n.t("splash.failed"), Theme.ERROR, true, 15)));
        state.add(Box.createVerticalStrut(6));
        state.add(left(notes(message)));
        state.add(Box.createVerticalStrut(14));
        JButton later = Ui.plainButton(I18n.t("splash.later"), null);
        JButton again = Ui.accentButton(I18n.t("splash.retry"), null);
        later.addActionListener(e -> finish(r));
        again.addActionListener(e -> download(r));
        state.add(left(Ui.buttons(8, later, again)));
        getRootPane().setDefaultButton(again);
        refresh();
    }

    // MARK: дії

    private void check() {
        CompletableFuture.supplyAsync(() -> {
                    try {
                        return Updater.check(App.version());
                    } catch (Exception e) {
                        return null;   // немає мережі чи сервер не відповів — відкриваємо Ferret, смуга спробує пізніше
                    }
                })
                .completeOnTimeout(null, CHECK_MS, TimeUnit.MILLISECONDS)
                .thenAccept(r -> SwingUtilities.invokeLater(() -> {
                    Timer t = new Timer((int) Math.max(1, MIN_MS - (System.currentTimeMillis() - shownAt)), e -> {
                        if (r == null) finish(null); else showFound(r);
                    });
                    t.setRepeats(false);
                    t.start();
                }));
    }

    private void download(Updater.Release r) {
        showDownload(r, 0);
        new Thread(() -> {
            try {
                Updater.install(r, done -> SwingUtilities.invokeLater(() -> showDownload(r, done)));
                SwingUtilities.invokeLater(() -> {
                    ((JLabel) state.getComponent(0)).setText(I18n.t("splash.restarting"));
                    new Timer(150, e -> System.exit(0)).start();   // скрипт оновлення чекає, доки цей процес закриється
                });
            } catch (Exception e) {
                String m = e.getMessage() == null ? e.toString() : e.getMessage();
                SwingUtilities.invokeLater(() -> showError(r, m));
            }
        }, "ferret-update").start();
    }

    private void finish(Updater.Release later) {
        if (anim != null) anim.stop();
        dispose();
        openApp.accept(later);
    }

    // MARK: дрібниці

    private Bar newBar(double p) {
        bar = new Bar(p);
        bar.setAlignmentX(Component.LEFT_ALIGNMENT);
        if (anim == null) {
            anim = new Timer(33, e -> { if (bar != null && bar.p < 0) { bar.t = (bar.t + 0.024) % 1; bar.repaint(); } });
            anim.start();
        }
        return bar;
    }

    private void refresh() {
        state.revalidate();
        state.repaint();
    }

    static JComponent left(JComponent c) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        return c;
    }

    // Що нового — до трьох рядків по ширині правої частини
    static JTextArea notes(String s) {
        JTextArea a = new JTextArea(s.length() > 220 ? s.substring(0, 219) + "…" : s);
        a.setLineWrap(true);
        a.setWrapStyleWord(true);
        a.setEditable(false);
        a.setFocusable(false);
        a.setOpaque(false);
        a.setForeground(Theme.MUTED);
        a.setFont(a.getFont().deriveFont(12.5f));
        a.setBorder(null);
        a.setSize(330, 1);   // ширина відома до компонування — тоді й висота з переносами правильна
        Dimension d = new Dimension(330, a.getPreferredSize().height);
        a.setPreferredSize(d);
        a.setMaximumSize(d);
        return a;
    }

    static JComponent picture(Image im, int h) {
        int w = im == null ? h : im.getWidth(null) * h / im.getHeight(null);
        JComponent c = new JComponent() {
            @Override protected void paintComponent(Graphics g0) {
                if (im == null) return;
                Graphics2D g = (Graphics2D) g0.create();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g.drawImage(im, 0, 0, w, h, null);
                g.dispose();
            }
        };
        Dimension d = new Dimension(w, h);
        c.setPreferredSize(d);
        c.setMinimumSize(d);
        c.setMaximumSize(d);
        return c;
    }

    // Смужка: p < 0 — невизначена (медовий відрізок пробігає), інакше частка 0…1
    static final class Bar extends JComponent {
        double p, t;

        Bar(double p) {
            this.p = p;
            setPreferredSize(new Dimension(10, 3));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 3));
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            RoundRectangle2D track = new RoundRectangle2D.Double(0, 0, w, h, h, h);
            g.setColor(Theme.LINE);
            g.fill(track);
            if (p >= 0) {
                g.setPaint(new GradientPaint(0, 0, new Color(0xE0882A), (float) Math.max(1, w * p), 0, Theme.ACCENT));
                g.fill(new RoundRectangle2D.Double(0, 0, Math.max(h, w * p), h, h, h));
            } else {
                double e = t < 0.5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2;   // плавно на краях, швидше посередині
                double seg = w * 0.32, x = (e * 1.32 - 0.32) * w;
                g.clip(track);
                g.setPaint(new GradientPaint((float) x, 0, Theme.alpha(Theme.ACCENT, 0), (float) (x + seg / 2), 0, Theme.ACCENT, true));
                g.fill(new RoundRectangle2D.Double(x, 0, seg, h, h, h));
            }
            g.dispose();
        }
    }
}
