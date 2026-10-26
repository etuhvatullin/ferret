package ferret.core;

import ferret.core.Model.Connection;
import ferret.core.Model.DBError;
import ferret.core.Model.QueryResult;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

// MCP-сервер Ferret: Claude Code / Claude Desktop запускають «Ferret --mcp» і говорять JSON-RPC через стандартні ввід і
// вивід (рядок — одне повідомлення). Запити виконує сам Ferret збереженими підключеннями й паролями — Claude їх не бачить.
// Ключ, права, підключення й паролі перечитуються на кожен виклик: зміни у вікні Ferret діють без перезапуску Claude.
// Тексти для Claude — англійською (це протокол, а не вікно); помилки бази — як їх показує Ferret.
public final class Mcp {
    static final List<String> VERSIONS = List.of("2025-06-18", "2025-03-26", "2024-11-05");
    static final int ROWS_DEFAULT = 100, ROWS_MAX = 1000, CELL = 500, TEXT_MAX = 60_000, ITEMS_MAX = 2000, FOUND_MAX = 200;

    static final String INSTRUCTIONS = "Ferret runs SQL against the user's databases with the user's own saved credentials. "
            + "Start with list_connections, find tables with find_tables or list_objects, then run_query with a small max_rows. "
            + "Write queries in the SQL dialect of the connection's database type.";

    private final OutputStream out;
    private final String key, version;
    private volatile String client = "";
    private final Map<String, Jdbc.Running> running = new ConcurrentHashMap<>();
    private final ExecutorService calls = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "ferret-mcp");
        t.setDaemon(true);
        return t;
    });

    public Mcp(OutputStream out, String key, String version) {
        this.out = out;
        this.key = key;
        this.version = version;
    }

    // «Ferret --mcp»: у стандартний вивід — лише протокол; усе інше, що пише в System.out / System.err, — у mcp.log
    public static void serve(String key, String version) {
        OutputStream proto = new FileOutputStream(FileDescriptor.out);
        try {
            Path log = Store.file("mcp.log");
            if (Files.exists(log) && Files.size(log) > 1_000_000) Files.delete(log);
            System.setErr(new PrintStream(Files.newOutputStream(log, StandardOpenOption.CREATE, StandardOpenOption.APPEND), true, StandardCharsets.UTF_8));
        } catch (IOException ignored) { }
        System.setOut(System.err);
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> e.printStackTrace());
        new Mcp(proto, key, version).loop(System.in, 5);
        System.exit(0);
    }

    // До кінця вводу (Claude закрив Ferret), тоді — ще трохи часу запитам, що вже йдуть
    public void loop(InputStream in, int waitSeconds) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                if (line.isBlank()) continue;
                Object msg;
                try {
                    msg = Json.parse(line);
                } catch (Exception e) {
                    send(error(null, -32700, "Parse error"));
                    continue;
                }
                if (msg instanceof List<?> batch) for (Object m : batch) handle(Json.map(m)); else handle(Json.map(msg));
            }
        } catch (IOException ignored) { }
        calls.shutdown();
        try { calls.awaitTermination(waitSeconds, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
    }

    void handle(Map<String, Object> m) {
        String method = Json.str(m.get("method"));
        Object id = m.get("id");
        if (method == null) return;   // відповідь клієнта: ми йому запитів не шлемо
        if (id == null) {             // сповіщення — без відповіді
            if (method.equals("notifications/cancelled")) {
                Jdbc.Running r = running.get(String.valueOf(Json.map(m.get("params")).get("requestId")));
                if (r != null) r.cancel();
            }
            return;
        }
        Map<String, Object> p = Json.map(m.get("params"));
        switch (method) {
            case "initialize" -> {
                client = Json.str(Json.map(p.get("clientInfo")).get("name"), "");
                String v = Json.str(p.get("protocolVersion"), VERSIONS.get(0));
                send(result(id, Map.of("protocolVersion", VERSIONS.contains(v) ? v : VERSIONS.get(0),
                        "capabilities", Map.of("tools", Map.of()),
                        "serverInfo", Map.of("name", "ferret", "version", version),
                        "instructions", INSTRUCTIONS)));
            }
            case "ping" -> send(result(id, Map.of()));
            case "tools/list" -> send(result(id, Map.of("tools", TOOLS)));
            // запит у базу може йти хвилинами — окремим потоком, щоб ping і скасування не чекали на нього
            case "tools/call" -> calls.submit(() -> send(result(id, callTool(id, Json.str(p.get("name"), ""), Json.map(p.get("arguments"))))));
            default -> send(error(id, -32601, "Method not found: " + method));
        }
    }

    // MARK: інструменти

    static final List<Object> TOOLS = List.of(
            tool("list_connections", "List the database connections the user has opened to you in Ferret: name, database type, "
                    + "database / catalog and your access (read — only reading statements such as SELECT, SHOW, DESCRIBE, EXPLAIN; "
                    + "read+write — any statement). Credentials stay inside Ferret. Call this first.", Map.of(), List.of()),
            tool("list_objects", "Browse the schema tree of a connection. An empty path lists the top level (catalogs or schemas); "
                    + "append a name to go one level deeper, e.g. [\"sales\"], then [\"sales\", \"orders\"]. "
                    + "Inside a table the list is its columns with their types.",
                    Map.of("connection", prop("string", "Connection name (or id) from list_connections"),
                            "path", Map.of("type", "array", "items", Map.of("type", "string"), "description", "Names from the top level down; empty for the top level")),
                    List.of("connection")),
            tool("find_tables", "Find tables whose full path (catalog.schema.table) contains all of the given words, case-insensitive. "
                    + "Returns up to " + FOUND_MAX + " paths.",
                    Map.of("connection", prop("string", "Connection name (or id) from list_connections"),
                            "words", prop("string", "Words separated by spaces, e.g. \"order item\"")),
                    List.of("connection", "words")),
            tool("run_query", "Run one SQL statement through the user's connection. Rows come back as tab-separated text: "
                    + "a summary line, column types, column names, then the rows (NULL for nulls, values longer than " + CELL
                    + " characters are cut). With read access only reading statements are allowed.",
                    Map.of("connection", prop("string", "Connection name (or id) from list_connections"),
                            "sql", prop("string", "One SQL statement in the database's own dialect"),
                            "max_rows", Map.of("type", "integer", "minimum", 1, "maximum", ROWS_MAX,
                                    "description", "Rows to return, default " + ROWS_DEFAULT + ", at most " + ROWS_MAX)),
                    List.of("connection", "sql")));

    static Map<String, Object> tool(String name, String description, Map<String, Object> props, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        if (!required.isEmpty()) schema.put("required", required);
        return Map.of("name", name, "description", description, "inputSchema", schema);
    }

    static Map<String, Object> prop(String type, String description) { return Map.of("type", type, "description", description); }

    // Відмова з поясненням для Claude (і для людини, якій він перекаже)
    static final class Refusal extends Exception {
        private static final long serialVersionUID = 1L;

        Refusal(String message) { super(message); }
    }

    public Map<String, Object> callTool(Object id, String tool, Map<String, Object> a) {
        try {
            String text = switch (tool) {
                case "list_connections" -> connections();
                case "list_objects" -> objects(a);
                case "find_tables" -> find(a);
                case "run_query" -> query(id, a);
                default -> throw new Refusal("Unknown tool: " + tool);
            };
            return content(text, false);
        } catch (Refusal | DBError e) {
            return content(e.getMessage(), true);
        } catch (Exception e) {
            e.printStackTrace();
            return content(e.getClass().getSimpleName() + ": " + e.getMessage(), true);
        }
    }

    static Map<String, Object> content(String text, boolean error) {
        return Map.of("content", List.of(Map.of("type", "text", "text", text)), "isError", error);
    }

    // MARK: доступ

    record Granted(Connection c, String right) { }   // right — з урахуванням запобіжника самого підключення

    // Ключ — на кожен виклик: «Новий ключ» у Ferret відключає вже запущеного Claude без перезапуску
    void checkKey() throws Refusal {
        String k = McpAccess.key();
        if (k == null) throw new Refusal("Access for Claude is not set up in Ferret. Ask the user to open Ferret → Connections → "
                + "Claude access… and copy the setup command from there.");
        if (key == null || !MessageDigest.isEqual(k.getBytes(StandardCharsets.UTF_8), key.getBytes(StandardCharsets.UTF_8)))
            throw new Refusal("The key (FERRET_KEY) does not match the one in Ferret: it was replaced or not passed. Ask the user to "
                    + "copy the setup command again in Ferret → Connections → Claude access….");
    }

    List<Granted> granted() throws Refusal {
        checkKey();
        Map<UUID, String> rights = McpAccess.rights();
        List<Granted> out = new ArrayList<>();
        for (Object o : Json.list(Store.loadJSON("connections.json"))) {
            Connection c = Connection.fromJson(o);
            String r = c == null ? null : rights.get(c.id);
            if (r != null) out.add(new Granted(c, McpAccess.effective(c, r)));
        }
        return out;
    }

    Granted pick(Map<String, Object> a) throws Refusal {
        String want = Json.str(a.get("connection"), "").trim();
        List<Granted> all = granted();
        if (all.isEmpty()) throw new Refusal(NONE);
        List<Granted> hit = all.stream().filter(g -> g.c().id.toString().equalsIgnoreCase(want)).toList();
        if (hit.isEmpty()) hit = all.stream().filter(g -> g.c().name.equals(want)).toList();
        if (hit.isEmpty()) hit = all.stream().filter(g -> g.c().name.equalsIgnoreCase(want)).toList();
        if (hit.size() == 1) return hit.get(0);
        if (hit.isEmpty()) throw new Refusal("No connection \"" + want + "\" is open to you. Available: "
                + all.stream().map(g -> "\"" + g.c().name + "\"").collect(Collectors.joining(", ")) + ".");
        throw new Refusal("Several connections are named \"" + want + "\" — pass the id instead: "
                + hit.stream().map(g -> g.c().id.toString().toUpperCase()).collect(Collectors.joining(", ")) + ".");
    }

    static final String NONE = "No connections are open to you yet. Ask the user to choose them in Ferret → Connections → Claude access….";

    // MARK: виклики

    String connections() throws Refusal {
        List<Object> out = new ArrayList<>();
        for (Granted g : granted()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", g.c().name);
            m.put("id", g.c().id.toString().toUpperCase());
            m.put("type", g.c().kind.title);
            if (!g.c().database.isEmpty()) m.put("database", g.c().database);
            if (!g.c().schema.isEmpty()) m.put("schema", g.c().schema);
            m.put("access", g.right().equals(McpAccess.WRITE) ? "read+write" : "read");
            out.add(m);
        }
        return out.isEmpty() ? NONE : Json.pretty(out);
    }

    String objects(Map<String, Object> a) throws Refusal, DBError {
        Granted g = pick(a);
        List<String> path = Json.strings(a.get("path"));
        List<SchemaItem> items = Jdbc.children(g.c(), null, path);
        String where = path.isEmpty() ? "the top level" : String.join(".", path);
        if (items.isEmpty()) return "Nothing under " + where + " (or no such path).";
        StringBuilder b = new StringBuilder(items.size() + " under " + where + ":\n");
        for (int i = 0; i < items.size() && i < ITEMS_MAX; i++) {
            SchemaItem s = items.get(i);
            b.append(s.name()).append(s.detail() == null ? "" : "\t" + s.detail()).append('\n');
        }
        if (items.size() > ITEMS_MAX) b.append("… and ").append(items.size() - ITEMS_MAX).append(" more — use find_tables to narrow down.");
        return b.toString().strip();
    }

    String find(Map<String, Object> a) throws Refusal, DBError {
        Granted g = pick(a);
        String[] words = Json.str(a.get("words"), "").toLowerCase(Locale.ROOT).trim().split("\\s+");
        List<String> hits = new ArrayList<>();
        int total = 0;
        for (List<String> t : Jdbc.tables(g.c(), null)) {
            String p = String.join(".", t);
            String low = p.toLowerCase(Locale.ROOT);
            if (!Arrays.stream(words).allMatch(low::contains)) continue;
            total++;
            if (hits.size() < FOUND_MAX) hits.add(p);
        }
        if (hits.isEmpty()) return "No tables match \"" + String.join(" ", words) + "\".";
        return total + (total == 1 ? " table" : " tables") + (total > hits.size() ? " (first " + hits.size() + ")" : "") + ":\n"
                + String.join("\n", hits);
    }

    String query(Object id, Map<String, Object> a) throws Refusal, DBError {
        long t0 = System.nanoTime();
        String sql = Json.str(a.get("sql"), "").strip();
        String name = Json.str(a.get("connection"), "");
        try {
            Granted g = pick(a);
            name = g.c().name;
            if (sql.isEmpty()) throw new Refusal("Empty SQL.");
            Integer want = Json.integer(a.get("max_rows"));
            int limit = Math.max(1, Math.min(ROWS_MAX, want == null ? ROWS_DEFAULT : want));
            // права Claude «читання» — так, ніби в підключенні увімкнено «лише читання»: той самий запобіжник і драйвер
            Connection run = g.c().copy();
            boolean mine = g.right().equals(McpAccess.READ) && !g.c().readOnly;   // обмеження від прав Claude, не від підключення
            if (g.right().equals(McpAccess.READ)) run.readOnly = true;
            Jdbc.Running r = new Jdbc.Running();
            running.put(String.valueOf(id), r);
            QueryResult q;
            try {
                q = Jdbc.run(run, sql, null, limit, r);
            } catch (DBError e) {
                if (mine && SqlText.readOnlyViolation(sql, true) != null)
                    throw new DBError("Your access to this connection is read-only (set by the user in Ferret → Claude access). " + e.getMessage());
                throw e;
            } finally {
                running.remove(String.valueOf(id));
            }
            McpAccess.log(new McpAccess.Entry(Instant.now(), client, name, cut(sql, 10_000), q.columns.isEmpty() ? null : q.rows.size(),
                    (System.nanoTime() - t0) / 1_000_000, null));
            return format(q, limit);
        } catch (Refusal | DBError e) {
            McpAccess.log(new McpAccess.Entry(Instant.now(), client, name, cut(sql, 10_000), null, (System.nanoTime() - t0) / 1_000_000, e.getMessage()));
            throw e;
        }
    }

    static String format(QueryResult q, int limit) {
        if (q.columns.isEmpty()) return (q.message == null ? "Done." : q.message) + " · " + q.elapsedMs + " ms";
        StringBuilder b = new StringBuilder();
        b.append(q.rows.size()).append(q.rows.size() == 1 ? " row" : " rows").append(" × ").append(q.columns.size())
                .append(q.columns.size() == 1 ? " column" : " columns").append(" · ").append(q.elapsedMs).append(" ms");
        if (q.truncated) b.append(" · more rows exist beyond max_rows=").append(limit);
        b.append("\ntypes: ").append(String.join(", ", q.types)).append('\n');
        b.append(q.columns.stream().map(Mcp::cell).collect(Collectors.joining("\t"))).append('\n');
        int shown = 0;
        for (String[] row : q.rows) {
            String line = Arrays.stream(row).map(v -> v == null ? "NULL" : cell(v)).collect(Collectors.joining("\t"));
            if (b.length() + line.length() > TEXT_MAX) {
                b.append("… output cut after ").append(shown).append(" of ").append(q.rows.size()).append(" rows — select fewer columns or rows");
                break;
            }
            b.append(line).append('\n');
            shown++;
        }
        return b.toString().strip();
    }

    // Клітинка в рядок таблиці: без табуляцій і переносів, довге — обрізано
    static String cell(String v) {
        return cut(v.replace("\t", " ").replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n"), CELL);
    }

    static String cut(String s, int max) { return s.length() > max ? s.substring(0, max) + "…" : s; }

    // MARK: JSON-RPC

    static Map<String, Object> result(Object id, Object result) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("result", result);
        return m;
    }

    static Map<String, Object> error(Object id, int code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("error", Map.of("code", code, "message", message));
        return m;
    }

    void send(Map<String, Object> m) {
        byte[] b = (Json.write(m) + "\n").getBytes(StandardCharsets.UTF_8);
        synchronized (out) {
            try {
                out.write(b);
                out.flush();
            } catch (IOException ignored) { }   // Claude уже закрив канал
        }
    }
}
