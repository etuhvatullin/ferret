package ferret;

import ferret.core.Store;
import ferret.ui.MainWindow;
import ferret.ui.Splash;
import ferret.ui.Theme;

import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;

// Точка входу Ferret (Java-версія для Mac і Windows). Системний проксі — для корпоративних мереж; на Mac меню — угорі
// екрана й назва програми в ньому — Ferret.
public final class App {
    private App() { }

    public static String version() {
        try (var in = App.class.getResourceAsStream("/ferret/assets/VERSION")) {
            return in == null ? "dev" : new String(in.readAllBytes()).trim();
        } catch (Exception e) {
            return "dev";
        }
    }

    public static void main(String[] args) {
        System.setProperty("java.net.useSystemProxies", "true");
        if (Store.MAC) {
            System.setProperty("apple.laf.useScreenMenuBar", "true");
            System.setProperty("apple.awt.application.name", "Ferret");
            System.setProperty("apple.awt.application.appearance", "NSAppearanceNameDarkAqua");
        }
        System.setProperty("flatlaf.uiScale.allowScaleDown", "true");
        // журнал помилок — у теці даних (вікна не має бути видно з консоллю, а слід лишитись має)
        try {
            Path log = Store.file("ferret.log");
            Files.deleteIfExists(log);
            var out = new java.io.PrintStream(Files.newOutputStream(log), true, java.nio.charset.StandardCharsets.UTF_8);
            System.setErr(out);
        } catch (Exception ignored) { }
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> e.printStackTrace());
        SwingUtilities.invokeLater(() -> {
            Theme.install();
            String snap = System.getenv("FERRET_SNAPSHOT");   // для перевірок: знімок вікна через кілька секунд і вихід
            if (snap != null) { snapshot(MainWindow.open(), Path.of(snap)); return; }
            // заставка перевіряє оновлення; «Пізніше» — нова версія лишається в смузі вікна
            Splash.start(later -> MainWindow.open().update.afterSplash(later));
        });
    }

    static void snapshot(MainWindow w, Path out) {
        javax.swing.Timer t = new javax.swing.Timer(Integer.parseInt(System.getenv().getOrDefault("FERRET_SNAPSHOT_DELAY", "4000")), e -> {
            try {
                java.awt.Rectangle r = w.getBounds();
                java.awt.image.BufferedImage img;
                try {
                    // разом із рамкою вікна; на Mac без дозволу «Запис екрана» знімок мовчки чорний, а на заблокованому
                    // Windows — екран блокування: там малюємо вікно самі (FERRET_SNAPSHOT_PAINT)
                    if (Store.MAC || System.getenv("FERRET_SNAPSHOT_PAINT") != null) throw new IllegalStateException("paint");
                    img = new java.awt.Robot().createScreenCapture(r);
                } catch (Exception noRobot) {
                    img = new java.awt.image.BufferedImage(w.getWidth(), w.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
                    var g = img.createGraphics();
                    w.getRootPane().paint(g);
                    g.dispose();
                }
                javax.imageio.ImageIO.write(img, "png", out.toFile());
                System.out.println("знімок: " + out);
            } catch (Exception ex) {
                ex.printStackTrace(System.out);
            }
            w.quit();
        });
        t.setRepeats(false);
        t.start();
    }
}
