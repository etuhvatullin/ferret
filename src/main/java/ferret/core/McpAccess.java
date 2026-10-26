package ferret.core;

import ferret.core.Model.Connection;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Доступ Claude до баз через Ferret (MCP): які підключення він бачить і з якими правами, ключ і журнал його запитів.
// Права — mcp.json, журнал — mcp-journal.jsonl у теці даних; ключ — у passwords.enc під окремим id (зашифрований, але
// його можна показати ще раз). Читають їх і вікно, і процес «ferret --mcp» — кожен виклик заново, тож зміни діють одразу.
public final class McpAccess {
    private McpAccess() { }

    public static final String OFF = "off", READ = "read", WRITE = "write";
    static final UUID KEY_ID = UUID.nameUUIDFromBytes("ferret-mcp-key".getBytes(StandardCharsets.UTF_8));
    static final long JOURNAL_MAX = 1_000_000;   // байтів; більше — лишається новіша половина

    // MARK: права

    // Підключення, яким дано доступ (вимкнені тут не зберігаються)
    public static Map<UUID, String> rights() {
        Map<UUID, String> out = new LinkedHashMap<>();
        Json.map(Json.map(Store.loadJSON("mcp.json")).get("connections")).forEach((id, v) -> {
            String r = Json.str(v, OFF);
            if (!r.equals(READ) && !r.equals(WRITE)) return;
            try { out.put(UUID.fromString(id), r); } catch (IllegalArgumentException ignored) { }
        });
        return out;
    }

    public static void save(Map<UUID, String> rights) throws IOException {
        Map<String, Object> list = new LinkedHashMap<>();
        rights.forEach((id, r) -> { if (r.equals(READ) || r.equals(WRITE)) list.put(id.toString().toUpperCase(), r); });
        Map<String, Object> all = new LinkedHashMap<>(Json.map(Store.loadJSON("mcp.json")));
        all.put("connections", list);
        Store.saveJSON("mcp.json", all);
    }

    // Що Claude справді може: власний запобіжник підключення сильніший за його права — «зміни» при увімкненому
    // «лише читання» лишаються читанням.
    public static String effective(Connection c, String right) {
        return right.equals(WRITE) && c.readOnly ? READ : right;
    }

    // MARK: ключ

    public static String key() {
        Passwords.reload();   // ключ могли щойно змінити у вікні Ferret
        String k = Passwords.get(KEY_ID);
        return k == null || k.isEmpty() ? null : k;
    }

    // Новий ключ: попередні налаштування Claude перестають працювати
    public static String newKey() {
        byte[] b = new byte[24];
        new SecureRandom().nextBytes(b);
        String k = "fk_" + Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        Passwords.set(KEY_ID, k);
        return k;
    }

    // MARK: журнал

    public record Entry(Instant at, String client, String connection, String sql, Integer rows, long ms, String error) {
        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("at", at.toString());
            m.put("client", client);
            m.put("connection", connection);
            m.put("sql", sql);
            if (rows != null) m.put("rows", (long) rows);
            m.put("ms", ms);
            if (error != null) m.put("error", error);
            return m;
        }

        static Entry fromJson(Object o) {
            Map<String, Object> m = Json.map(o);
            Integer rows = Json.integer(m.get("rows"));
            return new Entry(Instant.parse(Json.str(m.get("at"))), Json.str(m.get("client"), ""), Json.str(m.get("connection"), ""),
                    Json.str(m.get("sql"), ""), rows, m.get("ms") instanceof Number n ? n.longValue() : 0, Json.str(m.get("error")));
        }
    }

    static Path journalFile() { return Store.file("mcp-journal.jsonl"); }

    // Дописати рядок. Пишуть кілька процесів (Claude Code і Claude Desktop) — під блокуванням файлу.
    public static void log(Entry e) {
        byte[] line = (Json.write(e.toJson()) + "\n").getBytes(StandardCharsets.UTF_8);
        try (FileChannel ch = FileChannel.open(journalFile(), StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
             FileLock ignored = ch.lock()) {
            if (ch.size() + line.length > JOURNAL_MAX) {   // лишити новішу половину, з початку рядка
                ByteBuffer all = ByteBuffer.allocate((int) ch.size());
                while (all.hasRemaining() && ch.read(all, all.position()) > 0) { }   // read може віддати менше, ніж просили
                byte[] b = all.array();
                int from = b.length / 2;
                while (from < b.length && b[from - 1] != '\n') from++;
                ch.truncate(0);
                ch.write(ByteBuffer.wrap(b, from, b.length - from), 0);
            }
            ch.write(ByteBuffer.wrap(line), ch.size());
        } catch (IOException ex) {
            ex.printStackTrace();   // журнал не має зупиняти запит
        }
    }

    // Останні записи, новіші першими. Недописаний рядок (саме пишеться) пропускається.
    public static List<Entry> journal(int max) {
        List<Entry> out = new ArrayList<>();
        try {
            if (!Files.exists(journalFile())) return out;
            for (String l : Files.readAllLines(journalFile(), StandardCharsets.UTF_8)) {
                try { out.add(Entry.fromJson(Json.parse(l))); } catch (Exception skip) { }
            }
        } catch (IOException ignored) { }
        Collections.reverse(out);
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }
}
