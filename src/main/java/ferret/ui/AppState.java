package ferret.ui;

import ferret.core.Dialect;
import ferret.core.Jdbc;
import ferret.core.Json;
import ferret.core.Model;
import ferret.core.Model.Connection;
import ferret.core.Model.DBError;
import ferret.core.Model.DBKind;
import ferret.core.Model.QueryResult;
import ferret.core.Passwords;
import ferret.core.SchemaItem;
import ferret.core.SqlText;
import ferret.core.Store;

import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.nio.file.Files;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

// Стан програми (як AppModel у Swift-Ferret): підключення, консолі (відкриті — вкладки, закриті — у бічній панелі),
// історія, дерево схем із кешем на диску, останні сервери. Змінюється лише в потоці вікна (EDT); запити й дерево —
// у фонових потоках, результат повертається в потік вікна. Підписники отримують назву події.
public final class AppState {
    public static final int DEFAULT_ROW_LIMIT = 500;     // запит без LIMIT — перші 500 рядків; сервер на цьому зупиняється
    public static final int ALL_ROWS_LIMIT = 200_000;    // «Показати всі» — до 200 000: більше таблиця в памʼяті не тримає
    static final int HISTORY_LIMIT = 500;

    public final List<Connection> connections = new ArrayList<>();
    public final List<ConsoleTab> tabs = new ArrayList<>();
    public final List<ConsoleTab> shelved = new ArrayList<>();
    public final List<HistoryItem> history = new ArrayList<>();
    public ConsoleTab current;
    public UUID currentConnectionID;

    // дерево схем: ключ — «id підключення/шлях через \u001F»
    public final Map<String, List<SchemaItem>> tree = new HashMap<>();
    public final Set<String> expanded = new HashSet<>();
    public final Set<String> loading = new HashSet<>();
    public final Map<String, String> treeErrors = new HashMap<>();
    public final Map<UUID, List<List<String>>> tables = new HashMap<>();
    public final Map<UUID, Instant> schemaUpdated = new HashMap<>();
    public final Set<UUID> refreshing = new HashSet<>();

    public final List<UUID> recentServers = new ArrayList<>();
    public final Map<String, Object> settings = new LinkedHashMap<>();

    private final List<Consumer<String>> listeners = new ArrayList<>();
    final ExecutorService bg = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "ferret-bg");
        t.setDaemon(true);
        return t;
    });
    private final Timer saveTabsLater = new Timer(1000, e -> saveTabs());
    private final Map<UUID, Timer> cacheSaves = new HashMap<>();

    public static final class ConsoleTab {
        public final UUID id;
        public String title;
        public String sql;
        public UUID connectionID;
        public QueryResult result;
        public String error;
        public boolean running;
        public Instant startedAt;
        public Jdbc.Running handle;
        public String lastSQL;
        public Instant modified;
        public Instant closedAt;
        public int cursor;

        ConsoleTab(UUID id, String title, String sql, UUID connection) {
            this.id = id;
            this.title = title;
            this.sql = sql;
            this.connectionID = connection;
            this.modified = Instant.now();
            this.cursor = sql.length();
        }
    }

    public record HistoryItem(UUID id, UUID connection, String sql, Instant date, Integer rows, long ms, String error) { }

    AppState() {
        saveTabsLater.setRepeats(false);
    }

    public void on(Consumer<String> l) { listeners.add(l); }

    void emit(String event) { for (Consumer<String> l : List.copyOf(listeners)) l.accept(event); }

    // MARK: завантаження й збереження

    void load() {
        for (Object o : Json.list(Store.loadJSON("connections.json"))) {
            Connection c = Connection.fromJson(o);
            if (c != null) connections.add(c);
        }
        for (Object o : Json.list(Store.loadJSON("tabs.json"))) {
            Map<String, Object> m = Json.map(o);
            try {
                ConsoleTab t = new ConsoleTab(UUID.fromString(Json.str(m.get("id"))), Json.str(m.get("title"), "Консоль"),
                        Json.str(m.get("sql"), ""), m.get("connection") == null ? null : UUID.fromString(Json.str(m.get("connection"))));
                t.modified = date(m.get("modified"), Instant.EPOCH);
                t.closedAt = date(m.get("closed"), null);
                Integer cur = Json.integer(m.get("cursor"));
                t.cursor = cur != null ? Math.min(cur, t.sql.length()) : t.sql.length();
                (t.closedAt == null ? tabs : shelved).add(t);
            } catch (Exception skip) { }
        }
        for (Object o : Json.list(Store.loadJSON("history.json"))) {
            Map<String, Object> m = Json.map(o);
            try {
                history.add(new HistoryItem(UUID.fromString(Json.str(m.get("id"))), UUID.fromString(Json.str(m.get("connection"))),
                        Json.str(m.get("sql"), ""), date(m.get("date"), Instant.EPOCH), Json.integer(m.get("rows")),
                        m.get("ms") instanceof Number n ? n.longValue() : 0, Json.str(m.get("error"))));
            } catch (Exception skip) { }
        }
        settings.putAll(Json.map(Store.loadJSON("settings.json")));
        for (String s : Json.strings(settings.get("recentServers"))) try { recentServers.add(UUID.fromString(s)); } catch (Exception ignored) { }
        if (recentServers.isEmpty()) {   // уперше — з історії запусків (найсвіжіші першими) і вкладок
            for (HistoryItem h : history) if (!recentServers.contains(h.connection())) recentServers.add(h.connection());
            for (ConsoleTab t : tabs) if (t.connectionID != null && !recentServers.contains(t.connectionID)) recentServers.add(t.connectionID);
        }
        boolean colored = false;
        for (Connection c : connections) if (c.color == null) { c.color = Model.leastUsedColor(connections); colored = true; }
        if (colored) saveConnections();
        if (tabs.isEmpty()) tabs.add(new ConsoleTab(UUID.randomUUID(), "Консоль 1", "", connections.isEmpty() ? null : connections.get(0).id));
        selectTab(tabs.get(0));
    }

    static Instant date(Object o, Instant fallback) {
        try { return o == null ? fallback : Instant.parse(String.valueOf(o)); } catch (Exception e) { return fallback; }
    }

    // ISO без долей секунди — так їх розуміє й Swift-Ferret
    static String iso(Instant i) { return i == null ? null : i.truncatedTo(ChronoUnit.SECONDS).toString(); }

    public void saveConnections() {
        List<Object> out = new ArrayList<>();
        for (Connection c : connections) out.add(c.toJson());
        try { Store.saveJSON("connections.json", out); } catch (Exception ignored) { }
    }

    public void scheduleSave() { saveTabsLater.restart(); }

    public void saveTabs() {
        List<Object> out = new ArrayList<>();
        List<ConsoleTab> all = new ArrayList<>(tabs);
        all.addAll(shelved);
        for (ConsoleTab t : all) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.id.toString().toUpperCase());
            m.put("title", t.title);
            m.put("sql", t.sql);
            if (t.connectionID != null) m.put("connection", t.connectionID.toString().toUpperCase());
            m.put("modified", iso(t.modified));
            if (t.closedAt != null) m.put("closed", iso(t.closedAt));
            m.put("cursor", (long) t.cursor);
            out.add(m);
        }
        try { Store.saveJSON("tabs.json", out); } catch (Exception ignored) { }
    }

    void saveHistory() {
        List<Object> out = new ArrayList<>();
        for (HistoryItem h : history) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", h.id().toString().toUpperCase());
            m.put("connection", h.connection().toString().toUpperCase());
            m.put("sql", h.sql());
            m.put("date", iso(h.date()));
            if (h.rows() != null) m.put("rows", (long) h.rows());
            m.put("ms", h.ms());
            if (h.error() != null) m.put("error", h.error());
            out.add(m);
        }
        try { Store.saveJSON("history.json", out); } catch (Exception ignored) { }
    }

    public void saveSettings() {
        List<Object> rs = new ArrayList<>();
        for (UUID u : recentServers) rs.add(u.toString().toUpperCase());
        settings.put("recentServers", rs);
        try { Store.saveJSON("settings.json", settings); } catch (Exception ignored) { }
    }

    public int setting(String key, int fallback) { return settings.get(key) instanceof Number n ? n.intValue() : fallback; }

    public void setSetting(String key, Object value) {
        settings.put(key, value);
        saveSettings();
    }

    // MARK: підключення

    public Connection connection(UUID id) {
        for (Connection c : connections) if (c.id.equals(id)) return c;
        return null;
    }

    public Connection currentConnection() { return connection(currentConnectionID); }

    public Connection connection(ConsoleTab t) { return t == null ? null : connection(t.connectionID); }

    // Зміни з вікна «Підключення» разом: список, нові паролі, видалені. Змінене — дерево перечитується, зʼєднання
    // зі старою адресою чи паролем закриваються.
    public void applyConnections(List<Connection> list, Map<UUID, String> passwords, Set<UUID> deleted) {
        Map<UUID, Connection> old = new HashMap<>();
        for (Connection c : connections) old.put(c.id, c);
        for (Connection c : list) if (c.color == null) c.color = Model.leastUsedColor(list);
        Set<UUID> changed = new HashSet<>(deleted);
        for (UUID id : deleted) Passwords.remove(id);
        for (Map.Entry<UUID, String> e : passwords.entrySet()) {
            if (e.getValue() != null && !e.getValue().isEmpty()) {
                Passwords.set(e.getKey(), e.getValue());
                changed.add(e.getKey());
            }
        }
        for (Connection c : list) if (old.containsKey(c.id) && !old.get(c.id).equals(c)) changed.add(c.id);
        for (UUID id : changed) {
            forgetTree(id);
            Jdbc.forget(id);
        }
        connections.clear();
        connections.addAll(list);
        saveConnections();
        if (currentConnectionID == null || connection(currentConnectionID) == null) currentConnectionID = connections.isEmpty() ? null : connections.get(0).id;
        emit("connections");
        connectionChanged();
    }

    // MARK: вкладки й консолі

    public void selectTab(ConsoleTab t) {
        if (t == null) return;
        current = t;
        if (t.connectionID != null && connection(t.connectionID) != null) {
            if (!t.connectionID.equals(currentConnectionID)) {
                currentConnectionID = t.connectionID;
                connectionChanged();
            }
        } else if (currentConnectionID != null) {
            t.connectionID = currentConnectionID;
        } else if (!connections.isEmpty()) {
            t.connectionID = connections.get(0).id;
            currentConnectionID = t.connectionID;
            connectionChanged();
        }
        emit("tabs");
        scheduleSave();
    }

    public ConsoleTab newTab(String sql, String title, UUID connection) {
        UUID conn = connection != null ? connection : currentConnectionID;
        int n = 1;
        Set<String> used = new HashSet<>();
        for (ConsoleTab t : tabs) used.add(t.title);
        for (ConsoleTab t : shelved) used.add(t.title);
        while (used.contains("Консоль " + n)) n++;
        ConsoleTab t = new ConsoleTab(UUID.randomUUID(), title != null ? title : "Консоль " + n, sql == null ? "" : sql, conn);
        int at = current == null ? tabs.size() : tabs.indexOf(current) + 1;
        tabs.add(Math.max(0, Math.min(at, tabs.size())), t);
        selectTab(t);
        return t;
    }

    // Закрита вкладка не зникає: консоль лежить у бічній панелі «Консолі». Порожня безіменна — зникає зовсім.
    public void closeTab(ConsoleTab t) {
        int i = tabs.indexOf(t);
        if (i < 0) return;
        if (t.handle != null) t.handle.cancel();
        tabs.remove(i);
        if (!(t.sql.isBlank() && t.title.startsWith("Консоль "))) {
            t.closedAt = Instant.now();
            shelved.add(0, t);
        }
        if (tabs.isEmpty()) tabs.add(new ConsoleTab(UUID.randomUUID(), "Консоль 1", "", currentConnectionID));
        selectTab(tabs.get(Math.max(0, Math.min(i, tabs.size() - 1))));
        emit("consoles");
        scheduleSave();
    }

    public void openConsole(UUID id) {
        for (ConsoleTab t : tabs) if (t.id.equals(id)) { selectTab(t); return; }
        for (ConsoleTab t : shelved) {
            if (!t.id.equals(id)) continue;
            shelved.remove(t);
            t.closedAt = null;
            tabs.add(t);
            selectTab(t);
            emit("consoles");
            return;
        }
    }

    // ⇧⌘T — остання закрита консоль.
    public void reopenTab() {
        shelved.stream().filter(t -> t.closedAt != null).max(Comparator.comparing(t -> t.closedAt)).ifPresent(t -> openConsole(t.id));
    }

    public List<ConsoleTab> consoles(UUID connection) {
        List<ConsoleTab> out = new ArrayList<>();
        for (ConsoleTab t : tabs) if (connection != null && connection.equals(t.connectionID)) out.add(t);
        for (ConsoleTab t : shelved) if (connection != null && connection.equals(t.connectionID)) out.add(t);
        out.sort(Comparator.comparing((ConsoleTab t) -> t.modified).reversed());
        return out;
    }

    public void renameConsole(ConsoleTab t, String title) {
        if (title == null || title.isBlank()) return;
        t.title = title.trim();
        emit("tabs");
        emit("consoles");
        scheduleSave();
    }

    // Видалити остаточно — лише з бічної панелі, з підтвердженням.
    public void deleteConsole(ConsoleTab t) {
        shelved.remove(t);
        if (tabs.contains(t)) closeTabForever(t);
        emit("consoles");
        scheduleSave();
    }

    private void closeTabForever(ConsoleTab t) {
        int i = tabs.indexOf(t);
        tabs.remove(t);
        if (tabs.isEmpty()) tabs.add(new ConsoleTab(UUID.randomUUID(), "Консоль 1", "", currentConnectionID));
        selectTab(tabs.get(Math.max(0, Math.min(i, tabs.size() - 1))));
    }

    // Перейти до сервера: його відкрита консоль, інакше остання закрита, інакше нова. Поточна вкладка не переїжджає.
    public void goToServer(UUID id) {
        List<ConsoleTab> mine = consoles(id);
        ConsoleTab open = mine.stream().filter(t -> t.closedAt == null).findFirst().orElse(null);
        if (open != null) selectTab(open);
        else if (!mine.isEmpty()) openConsole(mine.get(0).id);
        else newTab("", null, id);
    }

    // Перенести вкладку на інший сервер — лише явно (⇕ над редактором).
    public void moveTab(ConsoleTab t, UUID connection) {
        t.connectionID = connection;
        currentConnectionID = connection;
        connectionChanged();
        emit("tabs");
        scheduleSave();
    }

    // MARK: останні сервери

    void noteRecent(UUID id) {
        if (id == null || (!recentServers.isEmpty() && recentServers.get(0).equals(id))) return;
        recentServers.remove(id);
        recentServers.add(0, id);
        while (recentServers.size() > 50) recentServers.remove(recentServers.size() - 1);
        saveSettings();
    }

    public int recentCount() { return Math.max(1, Math.min(50, setting("recentCount", 4))); }

    // Попросили більше, ніж памʼятаємо, — далі решта підключень у порядку списку.
    public List<Connection> recentConnections() {
        List<Connection> out = new ArrayList<>();
        for (UUID id : recentServers) { Connection c = connection(id); if (c != null && !out.contains(c)) out.add(c); }
        for (Connection c : connections) if (!out.contains(c)) out.add(c);
        return out.subList(0, Math.min(out.size(), recentCount()));
    }

    // MARK: запуск

    // Виконати: текст обирає редактор (запит під курсором / підзапит / виділення). Без LIMIT — 500 рядків.
    public void run(ConsoleTab t, String sql, int limit) {
        if (t == null || t.running) return;
        Connection c = connection(t);
        if (c == null) {
            t.error = "Немає підключення — додай його ліворуч угорі.";
            emit("result");
            return;
        }
        if (sql == null || sql.isBlank()) return;
        t.running = true;
        t.error = null;
        t.startedAt = Instant.now();
        t.lastSQL = sql;
        Jdbc.Running h = new Jdbc.Running();
        t.handle = h;
        emit("result");
        noteRecent(c.id);
        bg.submit(() -> {
            long t0 = System.nanoTime();
            QueryResult r = null;
            String err = null;
            try {
                r = Jdbc.run(c, sql, null, limit, h);
            } catch (DBError e) {
                err = e.getMessage();
            } catch (Throwable e) {
                err = String.valueOf(e);
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            QueryResult fr = r;
            String fe = h.isCancelled() ? "Зупинено." : err;
            SwingUtilities.invokeLater(() -> {
                t.running = false;
                t.handle = null;
                if (fr != null && fe == null) { t.result = fr; t.error = null; } else t.error = fe;
                if (!"Зупинено.".equals(fe)) record(c, sql, fr == null ? null : fr.rows.size(), ms, fe);
                emit("result");
            });
        });
    }

    public void stop(ConsoleTab t) {
        if (t != null && t.handle != null) t.handle.cancel();
    }

    // «Показати всі»: той самий запит ще раз — без обрізання на 500 (до ALL_ROWS_LIMIT).
    public void showAll(ConsoleTab t) {
        if (t != null && t.lastSQL != null) run(t, t.lastSQL, ALL_ROWS_LIMIT);
    }

    void record(Connection c, String sql, Integer rows, long ms, String error) {
        history.add(0, new HistoryItem(UUID.randomUUID(), c.id, sql, Instant.now(), rows, ms, error));
        while (history.size() > HISTORY_LIMIT) history.remove(history.size() - 1);
        saveHistory();
        emit("history");
    }

    // MARK: дерево схем

    public String treeKey(UUID conn, List<String> path) { return conn + "/" + String.join("\u001F", path); }

    public String treeKey(List<String> path) { return treeKey(currentConnectionID, path); }

    void connectionChanged() {
        noteRecent(currentConnectionID);
        Connection c = currentConnection();
        if (c == null) { emit("connection"); return; }
        if (!tree.containsKey(treeKey(List.of())) && !loading.contains(treeKey(List.of())) && !restoreSchema(c)) {
            toggle(List.of());
            if (c.kind == DBKind.TRINO && !c.database.isEmpty()) toggle(List.of(c.database));   // каталог розгортається одразу
        }
        loadTables();
        emit("connection");
    }

    public boolean isLeaf(List<String> path) {
        Connection c = currentConnection();
        return c == null || path.size() >= Jdbc.depth(c);
    }

    public void toggle(List<String> path) {
        String key = treeKey(path);
        if (expanded.contains(key) && !path.isEmpty()) {
            expanded.remove(key);
            scheduleSchemaSave(currentConnectionID);
            emit("tree");
            return;
        }
        expanded.add(key);
        if (tree.containsKey(key)) { scheduleSchemaSave(currentConnectionID); emit("tree"); return; }
        Connection c = currentConnection();
        if (c == null || loading.contains(key)) return;
        loading.add(key);
        treeErrors.remove(key);
        emit("tree");
        bg.submit(() -> {
            try {
                List<SchemaItem> items = Jdbc.children(c, null, path);
                SwingUtilities.invokeLater(() -> {
                    tree.put(key, items);
                    loading.remove(key);
                    scheduleSchemaSave(c.id);
                    emit("tree");
                });
            } catch (DBError e) {
                SwingUtilities.invokeLater(() -> {
                    treeErrors.put(key, e.getMessage());
                    expanded.remove(key);
                    loading.remove(key);
                    emit("tree");
                });
            }
        });
    }

    // Усі таблиці сервера (раз на сервер; невдача не запамʼятовується).
    void loadTables() {
        Connection c = currentConnection();
        if (c == null || tables.containsKey(c.id) || loading.contains("tables/" + c.id)) return;
        loading.add("tables/" + c.id);
        bg.submit(() -> {
            List<List<String>> paths = null;
            try { paths = Jdbc.tables(c, null); } catch (DBError ignored) { }
            List<List<String>> fp = paths;
            SwingUtilities.invokeLater(() -> {
                loading.remove("tables/" + c.id);
                if (fp != null) { tables.put(c.id, fp); scheduleSchemaSave(c.id); }
            });
        });
    }

    // «↻ Оновити»: перечитати розкриті гілки й перелік таблиць; згорнуті — забути (підвантажаться свіжими).
    public void refreshSchema() {
        Connection c = currentConnection();
        if (c == null || refreshing.contains(c.id)) return;
        String p = c.id + "/";
        List<String> keys = new ArrayList<>();
        keys.add(p);
        for (String k : expanded) if (k.startsWith(p) && !k.equals(p)) keys.add(k);
        tree.keySet().removeIf(k -> k.startsWith(p) && !keys.contains(k));
        treeErrors.keySet().removeIf(k -> k.startsWith(p));
        refreshing.add(c.id);
        emit("tree");
        bg.submit(() -> {
            Map<String, List<SchemaItem>> fresh = new HashMap<>();
            Map<String, String> errs = new HashMap<>();
            for (String k : keys) {
                String rest = k.substring(p.length());
                List<String> path = rest.isEmpty() ? List.of() : List.of(rest.split("\u001F"));
                try { fresh.put(k, Jdbc.children(c, null, path)); } catch (DBError e) { errs.put(k, e.getMessage()); }
            }
            List<List<String>> paths = null;
            try { paths = Jdbc.tables(c, null); } catch (DBError ignored) { }
            List<List<String>> fp = paths;
            SwingUtilities.invokeLater(() -> {
                tree.putAll(fresh);
                treeErrors.putAll(errs);
                if (fp != null) tables.put(c.id, fp);
                schemaUpdated.put(c.id, Instant.now());
                refreshing.remove(c.id);
                saveSchemaCache(c.id);
                emit("tree");
            });
        });
    }

    void forgetTree(UUID id) {
        String p = id + "/";
        tree.keySet().removeIf(k -> k.startsWith(p));
        expanded.removeIf(k -> k.startsWith(p));
        treeErrors.keySet().removeIf(k -> k.startsWith(p));
        tables.remove(id);
        schemaUpdated.remove(id);
        try { Files.deleteIfExists(Store.file("schema-cache/" + id.toString().toUpperCase() + ".json")); } catch (Exception ignored) { }
    }

    // Кеш схем на диску (`schema-cache/<id>.json`, той самий формат, що в Swift-Ferret): дерево зʼявляється одразу.
    boolean restoreSchema(Connection c) {
        Map<String, Object> cache = Json.map(Store.loadJSON("schema-cache/" + c.id.toString().toUpperCase() + ".json"));
        Map<String, Object> nodes = Json.map(cache.get("nodes"));
        if (!nodes.containsKey("")) return false;
        String p = c.id + "/";
        for (Map.Entry<String, Object> e : nodes.entrySet()) {
            List<SchemaItem> items = new ArrayList<>();
            for (Object o : Json.list(e.getValue())) {
                Map<String, Object> m = Json.map(o);
                items.add(new SchemaItem(Json.strings(m.get("path")), Json.str(m.get("detail"))));
            }
            tree.put(p + e.getKey(), items);
        }
        expanded.add(p);
        for (String k : Json.strings(cache.get("expanded"))) expanded.add(p + k);
        if (!tables.containsKey(c.id) && cache.get("tables") != null) {
            List<List<String>> t = new ArrayList<>();
            for (Object o : Json.list(cache.get("tables"))) t.add(Json.strings(o));
            tables.put(c.id, t);
        }
        Integer d = Json.integer(cache.get("depth"));
        if (d != null) Jdbc.setDepth(c.id, d);
        schemaUpdated.put(c.id, date(cache.get("updated"), Instant.now()));
        return true;
    }

    void scheduleSchemaSave(UUID id) {
        if (id == null) return;
        Timer t = cacheSaves.computeIfAbsent(id, k -> {
            Timer x = new Timer(1000, e -> saveSchemaCache(k));
            x.setRepeats(false);
            return x;
        });
        t.restart();
    }

    void saveSchemaCache(UUID id) {
        String p = id + "/";
        Map<String, Object> nodes = new LinkedHashMap<>();
        for (Map.Entry<String, List<SchemaItem>> e : tree.entrySet()) {
            if (!e.getKey().startsWith(p)) continue;
            List<Object> items = new ArrayList<>();
            for (SchemaItem i : e.getValue()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("path", new ArrayList<Object>(i.path()));
                if (i.detail() != null) m.put("detail", i.detail());
                items.add(m);
            }
            nodes.put(e.getKey().substring(p.length()), items);
        }
        if (!nodes.containsKey("")) return;
        List<Object> open = new ArrayList<>();
        for (String k : expanded) if (k.startsWith(p) && !k.equals(p)) open.add(k.substring(p.length()));
        Instant updated = schemaUpdated.computeIfAbsent(id, k -> Instant.now());
        Map<String, Object> cache = new LinkedHashMap<>();
        cache.put("updated", iso(updated));
        cache.put("nodes", nodes);
        cache.put("expanded", open);
        if (tables.containsKey(id)) {
            List<Object> t = new ArrayList<>();
            for (List<String> path : tables.get(id)) t.add(new ArrayList<Object>(path));
            cache.put("tables", t);
        }
        Connection c = connection(id);
        if (c != null) cache.put("depth", (long) Jdbc.depth(c));
        try {
            Files.createDirectories(Store.file("schema-cache"));
            Store.saveJSON("schema-cache/" + id.toString().toUpperCase() + ".json", cache);
        } catch (Exception ignored) { }
    }

    // MARK: підказки

    // Для місця таблиці: таблиці сервера + каталоги, схеми й таблиці, уже відомі з дерева (у Trino каталогів багато).
    public List<SqlText.Suggestion> tableSuggestions(ConsoleTab t, String fragment, Runnable onLoaded) {
        Connection c = connection(t);
        if (c == null) return List.of();
        int depth = Jdbc.depth(c) - 1;
        String p = c.id + "/";
        List<List<String>> paths = new ArrayList<>(tables.getOrDefault(c.id, List.of()));
        for (Map.Entry<String, List<SchemaItem>> e : tree.entrySet()) {
            if (!e.getKey().startsWith(p)) continue;
            for (SchemaItem i : e.getValue()) if (i.path().size() <= depth) paths.add(i.path());
        }
        prefetchNames(c, fragment, depth, paths, onLoaded);
        return SqlText.tableSuggestions(paths, fragment, c.kind, depth, c.kind == DBKind.TRINO ? c.database : "", 200);
    }

    // Набрали «каталог.» чи «каталог.схема.», а їхніх імен ще немає — підвантажити тихо (як розгортання в дереві).
    void prefetchNames(Connection c, String fragment, int depth, List<List<String>> paths, Runnable onLoaded) {
        String f = fragment.replace("\"", "").replace("`", "");
        List<String> typed = new ArrayList<>(List.of(f.split("\\.", -1)));
        typed.remove(typed.size() - 1);
        if (typed.isEmpty() || typed.stream().anyMatch(String::isEmpty)) return;
        if (c.kind == DBKind.TRINO && !c.database.isEmpty() && paths.stream().noneMatch(pp -> !pp.isEmpty() && pp.get(0).equalsIgnoreCase(typed.get(0)))) {
            typed.add(0, c.database);
        }
        if (typed.size() >= depth) return;
        List<String> path = new ArrayList<>();
        for (String part : typed) {
            String found = null;
            for (List<String> pp : paths) {
                if (pp.size() > path.size() && pp.subList(0, path.size()).equals(path) && pp.get(path.size()).equalsIgnoreCase(part)) { found = pp.get(path.size()); break; }
            }
            if (found == null) return;
            path.add(found);
        }
        String key = treeKey(c.id, path);
        if (tree.containsKey(key) || loading.contains(key)) return;
        loading.add(key);
        bg.submit(() -> {
            List<SchemaItem> items = null;
            try { items = Jdbc.children(c, null, path); } catch (DBError ignored) { }
            List<SchemaItem> fi = items;
            SwingUtilities.invokeLater(() -> {
                loading.remove(key);
                if (fi != null) {
                    tree.put(key, fi);
                    scheduleSchemaSave(c.id);
                    if (onLoaded != null) onLoaded.run();
                }
            });
        });
    }

    // Під час набору: діалект сервера + імена з уже завантаженого дерева (таблиці, колонки).
    public List<SqlText.Suggestion> wordSuggestions(ConsoleTab t, String fragment) {
        Connection c = connection(t);
        if (c == null) return List.of();
        String p = c.id + "/";
        List<SchemaItem> names = new ArrayList<>();
        for (Map.Entry<String, List<SchemaItem>> e : tree.entrySet()) if (e.getKey().startsWith(p)) names.addAll(e.getValue());
        return Dialect.sqlSuggestions(fragment, c.kind, names, Jdbc.depth(c), 40);
    }
}
