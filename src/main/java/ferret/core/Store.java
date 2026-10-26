package ferret.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
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
        try { Files.createDirectories(d); } catch (IOException ignored) { }
        dir = d;
        return d;
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

    public static Object loadJSON(String name) {
        try {
            Path p = file(name);
            return Files.exists(p) ? Json.parse(Files.readString(p, StandardCharsets.UTF_8)) : null;
        } catch (Exception e) {
            return null;
        }
    }

    public static void saveJSON(String name, Object value) throws IOException {
        writeAtomic(file(name), Json.pretty(value).getBytes(StandardCharsets.UTF_8), false);
    }

    // Запис через тимчасовий файл поруч і атомарну заміну; secret — лише для власника (600 там, де є права POSIX).
    public static void writeAtomic(Path target, byte[] data, boolean secret) throws IOException {
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
