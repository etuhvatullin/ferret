package ferret.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

// Оновлення: `latest.json` (GitHub Releases — release.sh) — номер версії, що нового, адреси архівів для Mac і Windows і
// їхні sha256. Адреса (і, для закритого сховища, ключ лише на читання — заголовок Deploy-Token) вшивається при збиранні
// випуску в `ferret/assets/update.json`; у git цього файла немає. Встановлення: архів →
// перевірка sha256 → розпакування поруч → маленький скрипт чекає, доки Ferret закриється, підміняє програму й
// запускає нову. Працює лише в установленій програмі (jpackage), не з ferret.jar.
public final class Updater {
    private Updater() { }

    public record Release(String version, String notes, String url, String sha256) { }

    record Config(String url, String token) { }

    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build();

    static Config config() {
        try (InputStream in = Updater.class.getResourceAsStream("/ferret/assets/update.json")) {
            if (in == null) return null;
            Map<String, Object> m = Json.map(Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
            String url = Json.str(m.get("url"));
            return url == null || url.isEmpty() ? null : new Config(url, Json.str(m.get("token"), ""));
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean configured() { return config() != null; }

    static HttpRequest.Builder request(Config c, String url) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(10));
        if (!c.token().isEmpty()) b.header("Deploy-Token", c.token());
        return b;
    }

    // Найновіший реліз для цієї системи; null — оновлень немає (або не налаштовано).
    public static Release check(String current) throws Model.DBError {
        Config c = config();
        if (c == null) return null;
        try {
            HttpResponse<String> r = HTTP.send(request(c, c.url()).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) throw new Model.DBError(I18n.t("updater.status", r.statusCode()));
            return pick(Json.map(Json.parse(r.body())), current, Store.MAC ? "mac" : Store.WINDOWS ? "windows" : "linux");
        } catch (IOException | InterruptedException e) {
            throw new Model.DBError(I18n.t("updater.offline", e.getMessage()));
        }
    }

    static Release pick(Map<String, Object> latest, String current, String platform) {
        String v = Json.str(latest.get("version"));
        Map<String, Object> p = Json.map(latest.get(platform));
        if (v == null || p.get("url") == null || p.get("sha256") == null) return null;
        if (!"dev".equals(current) && Drivers.compareVersions(v, current) <= 0) return null;
        return new Release(v, Json.str(latest.get("notes"), ""), Json.str(p.get("url")), Json.str(p.get("sha256")).toLowerCase());
    }

    // Тека встановленої програми: Mac — …/Ferret.app, Windows — тека з Ferret.exe. null — запущено не з інсталятора.
    public static Path installDir() {
        String launcher = System.getProperty("jpackage.app-path");
        if (launcher == null) return null;
        Path p = Path.of(launcher).toAbsolutePath();
        if (Store.MAC) {
            for (Path x = p; x != null; x = x.getParent()) if (x.getFileName() != null && x.getFileName().toString().endsWith(".app")) return x;
            return null;
        }
        return p.getParent();
    }

    // Завантажити, перевірити, розпакувати, запустити підміну. Після цього програму треба закрити (System.exit).
    public static void install(Release r) throws Exception { install(r, done -> { }); }

    // progress — частка завантаженого архіву 0…1 (з потоку завантаження; невідомий розмір — не викликається).
    public static void install(Release r, java.util.function.DoubleConsumer progress) throws Exception {
        Config c = config();
        Path dir = installDir();
        if (c == null || dir == null) throw new Model.DBError(I18n.t("updater.notinstalled"));
        if (!Files.isWritable(dir.getParent())) throw new Model.DBError(I18n.t("updater.noaccess", dir.getParent()));
        Path work = Files.createTempDirectory("ferret-update");
        Path zip = work.resolve("ferret.zip");
        HttpResponse<InputStream> resp = HTTP.send(request(c, r.url()).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) { resp.body().close(); throw new Model.DBError(I18n.t("updater.status.archive", resp.statusCode())); }
        long total = resp.headers().firstValueAsLong("Content-Length").orElse(-1);
        try (InputStream in = resp.body(); var out = Files.newOutputStream(zip)) {
            byte[] buf = new byte[1 << 16];
            long got = 0;
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
                got += n;
                if (total > 0) progress.accept(Math.min(1.0, got / (double) total));
            }
        }
        if (!Drivers.hash(zip, "SHA-256").equals(r.sha256())) throw new Model.DBError(I18n.t("updater.checksum"));
        Path unpacked = work.resolve("new");
        if (Store.MAC) {
            // ditto зберігає права, посилання й підпис .app — розпакування засобами Java їх губить
            Files.createDirectories(unpacked);
            Process p = new ProcessBuilder("ditto", "-x", "-k", zip.toString(), unpacked.toString()).inheritIO().start();
            if (p.waitFor() != 0) throw new Model.DBError(I18n.t("updater.unpack"));
        } else {
            unzip(zip, unpacked);
        }
        Path fresh;
        try (var s = Files.list(unpacked)) { fresh = s.filter(Files::isDirectory).findFirst().orElse(unpacked); }
        long pid = ProcessHandle.current().pid();
        if (Store.MAC) {
            Path sh = work.resolve("update.sh");
            Files.writeString(sh, "#!/bin/sh\n"
                    + "while kill -0 " + pid + " 2>/dev/null; do sleep 0.3; done\n"
                    + "rm -rf " + q(dir) + "\n"
                    + "ditto " + q(fresh) + " " + q(dir) + "\n"
                    + "xattr -dr com.apple.quarantine " + q(dir) + " 2>/dev/null\n"
                    + "open " + q(dir) + "\n"
                    + "rm -rf " + q(work) + "\n");   // архів і розпаковане — ≈100 МБ
            sh.toFile().setExecutable(true);
            new ProcessBuilder("/bin/sh", sh.toString()).directory(work.getParent().toFile()).start();
        } else {
            Path cmd = work.resolve("update.cmd");
            // chcp 65001 — інакше cmd читає шлях у своїй кодовій сторінці, і C:\Users\Олена\… перетворюється на сміття
            Files.writeString(cmd, "@echo off\r\nchcp 65001 >nul\r\n"
                    + ":wait\r\ntasklist /FI \"PID eq " + pid + "\" | find \"" + pid + "\" >nul && (timeout /t 1 /nobreak >nul & goto wait)\r\n"
                    + "robocopy \"" + fresh + "\" \"" + dir + "\" /MIR /NFL /NDL /NJH /NJS >nul\r\n"
                    + "start \"\" \"" + dir.resolve("Ferret.exe") + "\"\r\n"
                    // скрипт видаляє власну теку: (goto) спершу виходить зі скрипту, тож rd не обриває його посередині
                    + "cd /d \"" + work.getParent() + "\"\r\n(goto) 2>nul & rd /s /q \"" + work + "\"\r\n", StandardCharsets.UTF_8);
            // cmd /c — щоб вікно закрилось саме (start з .cmd відкриває cmd /k, і чорне вікно лишалось у панелі задач);
            // тека запуску — не тека програми, інакше вікно тримало б її
            new ProcessBuilder("cmd.exe", "/c", "start", "\"\"", "/min", "cmd.exe", "/c", cmd.toString())
                    .directory(work.getParent().toFile()).start();
        }
    }

    // Залишки оновлень, які не прибрали самі (вимкнули компʼютер посередині тощо): теки ferret-update* старші за годину.
    public static void cleanupOld() {
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        long hourAgo = System.currentTimeMillis() - 3_600_000L;
        try (var s = Files.newDirectoryStream(tmp, "ferret-update*")) {
            for (Path d : s) {
                if (Files.getLastModifiedTime(d).toMillis() > hourAgo) continue;
                try (var all = Files.walk(d)) {
                    all.sorted(java.util.Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
                } catch (IOException ignored) { }
            }
        } catch (IOException ignored) { }
    }

    static String q(Path p) { return "'" + p.toString().replace("'", "'\\''") + "'"; }

    static void unzip(Path zip, Path to) throws IOException {
        Files.createDirectories(to);
        try (ZipInputStream z = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                Path out = to.resolve(e.getName()).normalize();
                if (!out.startsWith(to)) throw new IOException(I18n.t("updater.badpath", e.getName()));   // захист від ../
                if (e.isDirectory()) { Files.createDirectories(out); continue; }
                Files.createDirectories(out.getParent());
                Files.copy(z, out, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
