package ferret.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

// Тека даних: Mac — ~/Library/Application Support/Ferret/v2, Windows — %APPDATA%\Ferret\v2, інше — ~/.local/share/ferret/v2
// (FERRET_DATA_DIR — для перевірок). Підтека v2 — щоб не заважати Swift-Ferret, який живе в теці рівнем вище: при першому
// запуску його дані (підключення, консолі, історія, паролі, драйвери, кеш схем) КОПІЮЮТЬСЯ сюди, і далі програми не
// перетинаються.
public final class Store {
    private Store() { }

    public static final boolean MAC = System.getProperty("os.name", "").toLowerCase().contains("mac");
    public static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");

    private static Path dir;

    public static synchronized Path dir() {
        if (dir != null) return dir;
        String custom = System.getenv("FERRET_DATA_DIR");
        Path d;
        if (custom != null && !custom.isEmpty()) {
            d = Path.of(custom);
        } else {
            Path home = Path.of(System.getProperty("user.home"));
            Path base = MAC ? home.resolve("Library/Application Support/Ferret")
                    : WINDOWS ? Path.of(System.getenv().getOrDefault("APPDATA", home.resolve("AppData/Roaming").toString()), "Ferret")
                    : home.resolve(".local/share/ferret");
            d = base.resolve("v2");
            try {
                Files.createDirectories(d);
                migrateFrom(base, d);
            } catch (IOException ignored) { }
        }
        try { Files.createDirectories(d); } catch (IOException e) { report(new Problem(true, d, reason(e), null)); }
        dir = d;
        return d;
    }

    // MARK: збої запису й читання

    // Раніше кожен збій тут ковтався мовчки. У колеги на Windows після перезапуску зникали підключення, а ключ Claude
    // щоразу був новий — і Ferret ні словом не сказав чому, тож причину не було видно навіть нам.
    // write — запис (інакше читання); aside — куди відкладено файл, що не читається (null — не відкладали).
    public record Problem(boolean write, Path file, String reason, Path aside) { }

    private static final List<Problem> pending = new ArrayList<>();
    private static Consumer<Problem> listener;

    // Слухач збоїв (вікно Ferret); ті, що сталися до нього (під час завантаження), віддаються одразу.
    public static void onProblem(Consumer<Problem> l) {
        List<Problem> before;
        synchronized (Store.class) {
            listener = l;
            before = new ArrayList<>(pending);
            pending.clear();
        }
        before.forEach(l);
    }

    public static void report(Problem p) {
        System.err.println("Ferret: " + (p.write() ? "не записано " : "не прочитано ") + p.file() + " — " + p.reason());
        Consumer<Problem> l;
        synchronized (Store.class) {
            l = listener;
            if (l == null && pending.size() < 20) pending.add(p);
        }
        if (l != null) l.accept(p);
    }

    // Назва помилки + текст: для AccessDeniedException текст — лише шлях, і без назви незрозуміло, що сталось.
    public static String reason(Exception e) {
        String m = e.getMessage();
        return e.getClass().getSimpleName() + (m == null || m.isEmpty() ? "" : ": " + m);
    }

    // Файл, що не читається, — вбік із міткою часу, щоб наступне збереження не затерло його порожнім.
    public static Path setAside(Path f) {
        Path aside = f.resolveSibling(f.getFileName() + ".unreadable-" + System.currentTimeMillis() / 1000);
        try {
            Files.move(f, aside);
            return aside;
        } catch (IOException e) {
            return null;
        }
    }

    // Для перевірок: інша тека даних у межах одного процесу.
    public static synchronized void useDir(Path d) {
        dir = d;
        try { Files.createDirectories(d); } catch (IOException ignored) { }
    }

    // Дані Swift-Ferret — один раз, копією (оригінал не чіпаємо).
    static void migrateFrom(Path old, Path to) throws IOException {
        if (Files.exists(to.resolve("connections.json")) || Files.exists(to.resolve(".migrated")) || !Files.exists(old.resolve("connections.json"))) return;
        for (String f : List.of("connections.json", "tabs.json", "history.json", "passwords.enc", "passwords.key", "drivers.json")) {
            Path src = old.resolve(f);
            if (Files.exists(src)) Files.copy(src, to.resolve(f), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        }
        for (String sub : List.of("drivers", "schema-cache")) {
            Path src = old.resolve(sub);
            if (!Files.isDirectory(src)) continue;
            try (Stream<Path> walk = Files.walk(src)) {
                for (Path p : (Iterable<Path>) walk::iterator) {
                    Path target = to.resolve(sub).resolve(src.relativize(p).toString());
                    if (Files.isDirectory(p)) Files.createDirectories(target);
                    else Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        Files.writeString(to.resolve(".migrated"), "дані Swift-Ferret скопійовано з " + old + "\n");
    }

    public static Path file(String name) { return dir().resolve(name); }

    // Немає файлу — null мовчки; не читається — null і збій; не розбирається як JSON — файл вбік і збій.
    public static Object loadJSON(String name) {
        Path p = file(name);
        if (!Files.exists(p)) return null;
        String text;
        try {
            text = Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            report(new Problem(false, p, reason(e), null));
            return null;
        }
        try {
            return Json.parse(text);
        } catch (Exception e) {
            report(new Problem(false, p, reason(e), setAside(p)));
            return null;
        }
    }

    public static void saveJSON(String name, Object value) throws IOException {
        writeAtomic(file(name), Json.pretty(value).getBytes(StandardCharsets.UTF_8), false);
    }

    // Запис через тимчасовий файл поруч і атомарну заміну; secret — лише для власника (600 там, де є права POSIX).
    // Збій — і виняток тому, хто пише, і повідомлення слухачу: виклики самі його ковтають.
    public static void writeAtomic(Path target, byte[] data, boolean secret) throws IOException {
        try {
            write(target, data, secret);
        } catch (IOException e) {
            report(new Problem(true, target, reason(e), null));
            throw e;
        }
    }

    private static void write(Path target, byte[] data, boolean secret) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling("." + target.getFileName() + ".tmp");
        Files.deleteIfExists(tmp);
        if (secret && !WINDOWS) {
            Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.write(tmp, data);
        } else {
            Files.write(tmp, data);
        }
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
