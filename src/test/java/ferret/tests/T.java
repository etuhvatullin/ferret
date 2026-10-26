package ferret.tests;

// Найпростіші перевірки, як у Swift-Ferret: ✓ / ✗ і підсумок; build.sh падає, якщо є ✗.
public final class T {
    private T() { }

    static int failures;

    public static void check(boolean ok, String what) {
        System.out.println((ok ? "✓ " : "✗ ") + what);
        if (!ok) failures++;
    }

    public static void section(String name) { System.out.println("\n— " + name); }

    // Тимчасові теки перевірок (з драйверами — десятки МБ) прибираються наприкінці прогону, а не накопичуються.
    static final java.util.List<java.nio.file.Path> temps = new java.util.ArrayList<>();

    public static java.nio.file.Path temp(String prefix) throws java.io.IOException {
        java.nio.file.Path p = java.nio.file.Files.createTempDirectory(prefix);
        temps.add(p);
        return p;
    }

    public static void cleanup() {
        for (java.nio.file.Path p : temps) {
            try (var all = java.nio.file.Files.walk(p)) {
                all.sorted(java.util.Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
            } catch (Exception ignored) { }
        }
    }
}
