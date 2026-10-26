package ferret.core;

import ferret.core.Model.Connection;
import ferret.core.Model.DBError;
import ferret.core.Model.DBKind;
import ferret.core.Model.QueryResult;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

// Робота з базами через драйвери JDBC — у самій програмі (у Swift-Ferret це був окремий процес-міст, тут Java своя).
// Драйвер шукається ServiceLoader-ом у його власному файлі (.jar), тож назва класу не потрібна. Пул — до 4 зʼєднань на
// підключення (дерево й запити паралельно). «Лише читання»: запобіжник у тексті (кожне слово), setReadOnly, а де бази
// мають транзакції — autoCommit off і rollback після кожного запиту. Зʼєднання повертається в пул зі своєю базою й
// схемою: «use інша_база» в запиті не переносить наступні запити й дерево.
public final class Jdbc {
    private Jdbc() { }

    static final int POOL_MAX = 4;
    static final int CELL_MAX = 100_000;   // символів у клітинці — далі обрізаємо

    private static final Map<String, URLClassLoader> loaders = new ConcurrentHashMap<>();
    private static final Map<String, Deque<java.sql.Connection>> idle = new ConcurrentHashMap<>();
    private static final Map<java.sql.Connection, String[]> home = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<String, List<String>> shapes = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> depths = new ConcurrentHashMap<>();

    static { DriverManager.setLoginTimeout(20); }

    // Запит, який можна зупинити («Зупинити» / ⌘.): скасовує й на сервері.
    public static final class Running {
        private volatile Statement st;
        private volatile boolean cancelled;

        public void cancel() {
            cancelled = true;
            Statement s = st;
            if (s != null) new Thread(() -> { try { s.cancel(); } catch (Throwable ignored) { } }, "ferret-cancel").start();
        }

        public boolean isCancelled() { return cancelled; }
    }

    // Налаштування зʼєднання: підключення + пароль → адреса, драйвер, властивості.
    record Conf(Connection c, String url, List<Path> jars, Properties props) {
        String pool() { return c.id + "|" + url + "|" + jars + "|" + c.readOnly + "|" + c.schema + "|" + props.hashCode(); }
    }

    static Conf conf(Connection c, String password) throws DBError {
        Properties p = new Properties();
        p.putAll(Drivers.props(c, password));
        return new Conf(c, Drivers.url(c), Drivers.jars(c), p);
    }

    // MARK: драйвер і зʼєднання

    static URLClassLoader loader(List<Path> jars) throws DBError {
        String key = jars.toString();
        URLClassLoader l = loaders.get(key);
        if (l != null) return l;
        List<URL> urls = new ArrayList<>();
        for (Path j : jars) {
            if (!Files.isRegularFile(j)) throw new DBError(I18n.t("jdbc.driver.nofile", j));
            try { urls.add(j.toUri().toURL()); } catch (Exception e) { throw new DBError(I18n.t("jdbc.driver.badpath", j)); }
        }
        l = new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader());
        URLClassLoader prev = loaders.putIfAbsent(key, l);
        return prev != null ? prev : l;
    }

    static Driver driver(Conf c) throws Exception {
        URLClassLoader l = loader(c.jars());
        Iterator<Driver> it = ServiceLoader.load(Driver.class, l).iterator();
        while (true) {
            Driver d;
            try {
                if (!it.hasNext()) break;
                d = it.next();
            } catch (ServiceConfigurationError e) {
                continue;   // зламаний сусідній драйвер у тому ж файлі — не наша справа
            }
            if (d.getClass().getClassLoader() == l && d.acceptsURL(c.url())) return d;
        }
        int colon = c.url().indexOf(':', 5);
        throw new DBError(I18n.t("jdbc.driver.nourl", colon > 0 ? c.url().substring(0, colon + 1) : c.url()));
    }

    static java.sql.Connection open(Conf c) throws Exception {
        Thread.currentThread().setContextClassLoader(loader(c.jars()));   // Snowflake, BigQuery шукають ресурси через нього
        java.sql.Connection x = driver(c).connect(c.url(), (Properties) c.props().clone());
        if (x == null) throw new DBError(I18n.t("jdbc.driver.rejected"));
        if (c.c().readOnly) {
            try { x.setReadOnly(true); } catch (Throwable ignored) { }
            if (Drivers.rollbackGuard(c.c().kind)) {
                try { x.setAutoCommit(false); } catch (Throwable ignored) { }
            }
        }
        if (!c.c().schema.isEmpty() && c.c().kind != DBKind.TRINO) {
            try { x.setSchema(c.c().schema); } catch (Throwable ignored) { }
        }
        String[] h = new String[2];
        try { h[0] = x.getCatalog(); } catch (Throwable ignored) { }
        try { h[1] = x.getSchema(); } catch (Throwable ignored) { }
        home.put(x, h);
        return x;
    }

    static java.sql.Connection borrow(Conf c) throws Exception {
        Deque<java.sql.Connection> q = idle.computeIfAbsent(c.pool(), k -> new ConcurrentLinkedDeque<>());
        java.sql.Connection x;
        while ((x = q.pollFirst()) != null) {
            try {
                if (x.isValid(3)) return x;
            } catch (Throwable notSupported) {
                return x;
            }
            closeQuietly(x);
        }
        return open(c);
    }

    static void release(Conf c, java.sql.Connection x, boolean broken) {
        if (x == null) return;
        if (c.c().readOnly && !broken) {
            try { if (!x.getAutoCommit()) x.rollback(); } catch (Throwable ignored) { }
        }
        String[] h = home.get(x);
        if (h != null && !broken) {
            try { if (h[0] != null && !h[0].equals(x.getCatalog())) x.setCatalog(h[0]); } catch (Throwable ignored) { }
            try { if (h[1] != null && !h[1].equals(x.getSchema())) x.setSchema(h[1]); } catch (Throwable ignored) { }
        }
        Deque<java.sql.Connection> q = idle.computeIfAbsent(c.pool(), k -> new ConcurrentLinkedDeque<>());
        if (broken || q.size() >= POOL_MAX) closeQuietly(x); else q.offerFirst(x);
    }

    // Підключення змінили чи видалили — закрити його зʼєднання.
    public static void forget(UUID id) {
        String p = id + "|";
        for (Iterator<Map.Entry<String, Deque<java.sql.Connection>>> it = idle.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Deque<java.sql.Connection>> e = it.next();
            if (!e.getKey().startsWith(p)) continue;
            for (java.sql.Connection x : e.getValue()) closeQuietly(x);
            it.remove();
        }
        shapes.keySet().removeIf(k -> k.startsWith(p));
    }

    static boolean broken(Throwable e) {
        if (e instanceof SQLRecoverableException || e instanceof SQLNonTransientConnectionException) return true;
        return e instanceof SQLException s && s.getSQLState() != null && s.getSQLState().startsWith("08");
    }

    static void closeQuietly(java.sql.Connection x) {
        try { x.close(); } catch (Throwable ignored) { }
    }

    // Текст помилки: повідомлення першої причини, без стека; пароль, якщо драйвер його повторив, — прибрано.
    static DBError error(Throwable e, String password, Running r) {
        if (r != null && r.isCancelled()) return new DBError(I18n.t("jdbc.stopped"));
        if (e instanceof DBError d) return new DBError(Drivers.redact(d.getMessage(), password));
        Throwable t = e;
        while (t.getMessage() == null && t.getCause() != null && t.getCause() != t) t = t.getCause();
        String m = t.getMessage();
        if (m == null || m.isEmpty()) m = t.getClass().getName();
        if (t instanceof SQLException s && s.getSQLState() != null && !m.contains(s.getSQLState())) m += " [" + s.getSQLState() + "]";
        String h = hint(e);
        return new DBError(Drivers.redact(h == null ? m : h + "\n" + m, password));
    }

    // Людське речення для мережевих збоїв — першим рядком над текстом драйвера («java.net.UnknownHostException» колегам
    // нічого не каже). Шукаємо в ланцюжку причин, а як драйвер загорнув причину лише в текст — у тексті.
    public static String hint(Throwable e) {
        StringBuilder all = new StringBuilder();
        for (Throwable t = e; t != null && all.length() < 20_000; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof java.net.UnknownHostException && t.getMessage() != null)   // Linux дописує «: Name or service not known»
                return I18n.t("jdbc.hint.host", t.getMessage().split("[\\s:]", 2)[0]);
            all.append(t).append('\n');
        }
        String s = all.toString();
        java.util.regex.Matcher host = java.util.regex.Pattern.compile("UnknownHostException: ([^\\s:;,)]+)").matcher(s);
        if (host.find()) return I18n.t("jdbc.hint.host", host.group(1));
        if (s.contains("ConnectException") || s.contains("Connection refused")) return I18n.t("jdbc.hint.refused");
        if (s.contains("SocketTimeoutException") || s.contains("HttpTimeoutException") || s.contains("connect timed out"))
            return I18n.t("jdbc.hint.timeout");
        if (s.contains("Authentication failed") || s.contains("Unauthorized") || s.contains("password authentication failed")
                || s.contains("Login failed for user") || s.contains("Access denied for user"))
            return I18n.t("jdbc.hint.auth");
        if (s.contains("SSLException") || s.contains("SSLHandshakeException")) return I18n.t("jdbc.hint.tls");
        return null;
    }

    static String password(Connection c, String given) {
        if (given != null) return given;
        String p = Passwords.get(c.id);
        return p == null ? "" : p;
    }

    // MARK: запит

    // Одна точка запуску: спершу запобіжник «лише читання» (до мережі), потім драйвер. password null — з файлу паролів.
    public static QueryResult run(Connection c, String sql, String password, int limit, Running r) throws DBError {
        if (c.readOnly) {
            String why = SqlText.readOnlyViolation(sql, true);
            if (why != null) throw new DBError(why);
        }
        String pw = password(c, password);
        long t0 = System.nanoTime();
        Conf conf = conf(c, pw);
        java.sql.Connection x = null;
        boolean bad = false;
        try {
            x = borrow(conf);
            Thread.currentThread().setContextClassLoader(loader(conf.jars()));
            try (Statement st = x.createStatement()) {
                if (r != null) {
                    r.st = st;
                    if (r.isCancelled()) throw new DBError(I18n.t("jdbc.stopped"));
                }
                try { st.setMaxRows(limit + 1); } catch (Throwable ignored) { }
                try { st.setFetchSize(Math.min(limit + 1, 1000)); } catch (Throwable ignored) { }
                QueryResult q = new QueryResult();
                boolean isRows = st.execute(sql);
                long changed = -1;
                while (true) {
                    if (isRows) {
                        try (ResultSet rs = st.getResultSet()) { rows(rs, limit, q, st); }
                        break;
                    }
                    int n = st.getUpdateCount();
                    if (n == -1) break;
                    changed = Math.max(changed, 0) + n;
                    isRows = st.getMoreResults();
                }
                if (q.columns.isEmpty()) q.message = changed >= 0 ? I18n.t("jdbc.done.changed", I18n.num(changed)) : I18n.t("jdbc.done");
                q.elapsedMs = (System.nanoTime() - t0) / 1_000_000;
                return q;
            }
        } catch (Throwable e) {
            bad = broken(e);
            throw error(e, pw, r);
        } finally {
            if (r != null) r.st = null;
            release(conf, x, bad);
        }
    }

    static void rows(ResultSet rs, int limit, QueryResult q, Statement st) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int n = md.getColumnCount();
        boolean[] binary = new boolean[n + 1];
        for (int i = 1; i <= n; i++) {
            q.columns.add(md.getColumnLabel(i));
            q.types.add(md.getColumnTypeName(i));
            int t = md.getColumnType(i);
            binary[i] = t == Types.BINARY || t == Types.VARBINARY || t == Types.LONGVARBINARY || t == Types.BLOB;
        }
        while (rs.next()) {
            if (q.rows.size() >= limit) { q.truncated = true; break; }
            String[] row = new String[n];
            for (int i = 1; i <= n; i++) {
                String v;
                if (binary[i]) {
                    byte[] b = rs.getBytes(i);
                    v = b == null ? null : hex(b);
                } else {
                    v = rs.getString(i);
                }
                row[i - 1] = v != null && v.length() > CELL_MAX ? v.substring(0, CELL_MAX) + "…" : v;
            }
            q.rows.add(row);
        }
        if (q.truncated) {
            try { st.cancel(); } catch (Throwable ignored) { }   // драйвер, що не зважає на setMaxRows, не тягне решту
        }
    }

    static String hex(byte[] b) {
        int n = Math.min(b.length, 2048);
        StringBuilder s = new StringBuilder("0x");
        for (int i = 0; i < n; i++) s.append(String.format("%02X", b[i]));
        if (b.length > n) s.append('…');
        return s.toString();
    }

    // «Перевірити зʼєднання»: що за сервер відповів.
    public static String test(Connection c, String password) throws DBError {
        String pw = password(c, password);
        Conf conf = conf(c, pw);
        java.sql.Connection x = null;
        boolean bad = true;
        try {
            x = borrow(conf);
            DatabaseMetaData md = x.getMetaData();
            String out = md.getDatabaseProductName() + " " + md.getDatabaseProductVersion();
            bad = false;
            return out;
        } catch (Throwable e) {
            throw error(e, pw, null);
        } finally {
            release(conf, x, bad);
        }
    }

    // MARK: дерево схем

    // Скільки рівнів до колонок у цього підключення: Trino — каталог/схема/таблиця/колонка (4), MS SQL — так само,
    // MySQL — база/таблиця/колонка (3), Oracle — схема/таблиця/колонка (3), SQLite — таблиця/колонка (2).
    public static int depth(Connection c) {
        Integer d = depths.get(c.id);
        return d != null ? d : (c.kind == DBKind.TRINO ? 4 : 3);
    }

    public static void setDepth(UUID id, int d) { depths.put(id, d); }

    // Рівні над таблицями: рівень показується, лише коли в ньому є вибір (одна база — рівня немає).
    static List<String> shape(Conf c, java.sql.Connection x) throws SQLException {
        String k = c.c().id + "|" + c.url();
        List<String> s = shapes.get(k);
        if (s != null) return s;
        DatabaseMetaData md = x.getMetaData();
        s = new ArrayList<>();
        if (names(md.getCatalogs(), "TABLE_CAT").size() > 1) s.add("catalog");
        if (!names(md.getSchemas(), "TABLE_SCHEM").isEmpty()) s.add("schema");
        shapes.put(k, s);
        setDepth(c.c().id, s.size() + 2);
        return s;
    }

    static List<String> names(ResultSet rs, String col) throws SQLException {
        List<String> out = new ArrayList<>();
        try (ResultSet r = rs) {
            while (r.next()) {
                String v = r.getString(col);
                if (v != null && !v.isEmpty() && !out.contains(v)) out.add(v);
            }
        }
        return out;
    }

    static String level(List<String> shape, List<String> path, String name) {
        int i = shape.indexOf(name);
        return i >= 0 && i < path.size() ? path.get(i) : null;
    }

    static String escape(DatabaseMetaData md, String s) throws SQLException {
        if (s == null) return null;
        String e = md.getSearchStringEscape();
        if (e == null || e.isEmpty()) return s;
        return s.replace(e, e + e).replace("_", e + "_").replace("%", e + "%");
    }

    static boolean tableType(String t) {
        String u = t == null ? "TABLE" : t.toUpperCase(Locale.ROOT);
        return u.contains("TABLE") || u.contains("VIEW");
    }

    // Діти вузла дерева (каталоги, схеми, таблиці чи колонки з типом) — завжди в режимі читання.
    public static List<SchemaItem> children(Connection c0, String password, List<String> path) throws DBError {
        Connection c = c0.copy();
        c.readOnly = true;
        String pw = password(c, password);
        Conf conf = conf(c, pw);
        java.sql.Connection x = null;
        boolean bad = false;
        try {
            x = borrow(conf);
            DatabaseMetaData md = x.getMetaData();
            List<String> shape = shape(conf, x);
            String cat = shape.contains("catalog") ? level(shape, path, "catalog") : x.getCatalog();
            String sch = level(shape, path, "schema");
            List<SchemaItem> items = new ArrayList<>();
            int lv = path.size();
            if (lv < shape.size() && shape.get(lv).equals("catalog")) {
                for (String n : names(md.getCatalogs(), "TABLE_CAT")) items.add(item(path, n, null));
            } else if (lv < shape.size()) {
                List<String> ns;
                try {
                    ns = names(md.getSchemas(cat, null), "TABLE_SCHEM");
                } catch (SQLFeatureNotSupportedException | AbstractMethodError e) {
                    ns = names(md.getSchemas(), "TABLE_SCHEM");
                }
                for (String n : ns) items.add(item(path, n, null));
            } else if (lv == shape.size()) {
                try (ResultSet rs = md.getTables(cat, escape(md, sch), "%", null)) {
                    while (rs.next()) if (tableType(rs.getString("TABLE_TYPE"))) items.add(item(path, rs.getString("TABLE_NAME"), null));
                }
            } else if (lv == shape.size() + 1) {
                try (ResultSet rs = md.getColumns(cat, escape(md, sch), escape(md, path.get(lv - 1)), "%")) {
                    while (rs.next()) items.add(item(path, rs.getString("COLUMN_NAME"), rs.getString("TYPE_NAME")));
                }
            }
            return items;
        } catch (Throwable e) {
            bad = broken(e);
            throw error(e, pw, null);
        } finally {
            release(conf, x, bad);
        }
    }

    static SchemaItem item(List<String> path, String name, String detail) {
        List<String> p = new ArrayList<>(path);
        p.add(name);
        return new SchemaItem(p, detail);
    }

    // Усі таблиці поточної бази шляхами за рівнями — для підказки після FROM і пошуку. До 50 000. Перелік усього
    // каталогу одним запитом буває зламаний (Trino lake: «Failed to list views» через схему system) — тоді по схемах,
    // зламані пропускаються.
    public static List<List<String>> tables(Connection c0, String password) throws DBError {
        Connection c = c0.copy();
        c.readOnly = true;
        String pw = password(c, password);
        Conf conf = conf(c, pw);
        java.sql.Connection x = null;
        boolean bad = false;
        try {
            x = borrow(conf);
            DatabaseMetaData md = x.getMetaData();
            List<String> shape = shape(conf, x);
            String cat = x.getCatalog();
            if (cat != null && cat.isEmpty()) cat = null;
            List<List<String>> paths = new ArrayList<>();
            try {
                // Trino: перелік усього каталогу ~7 с (заміряно на lake), а схема system його ламає — одразу по схемах
                if (c.kind == DBKind.TRINO) throw new SQLException("по схемах");
                collect(md, shape, cat, null, paths);
            } catch (SQLException whole) {
                paths.clear();
                List<String> schemas;
                try { schemas = names(md.getSchemas(cat, null), "TABLE_SCHEM"); } catch (SQLException | AbstractMethodError e) { schemas = names(md.getSchemas(), "TABLE_SCHEM"); }
                schemas.removeIf(s -> s.equalsIgnoreCase("information_schema"));
                paths.addAll(bySchema(conf, shape, cat, schemas));
            }
            return paths;
        } catch (Throwable e) {
            bad = broken(e);
            throw error(e, pw, null);
        } finally {
            release(conf, x, bad);
        }
    }

    // По схемах — одночасно, до POOL_MAX зʼєднань (у Trino lake по одній схемі ~0,4 с; підряд 15 схем — 6,5 с, разом — ~2 с).
    static List<List<String>> bySchema(Conf conf, List<String> shape, String cat, List<String> schemas) throws InterruptedException {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(POOL_MAX, r -> {
            Thread t = new Thread(r, "ferret-tables");
            t.setDaemon(true);
            return t;
        });
        try {
            List<java.util.concurrent.Future<List<List<String>>>> parts = new ArrayList<>();
            for (String s : schemas) {
                parts.add(pool.submit(() -> {
                    List<List<String>> out = new ArrayList<>();
                    java.sql.Connection x = null;
                    boolean bad = false;
                    try {
                        x = borrow(conf);
                        collect(x.getMetaData(), shape, cat, escape(x.getMetaData(), s), out);
                    } catch (Throwable skip) {
                        bad = broken(skip);   // зламана схема просто пропускається
                    } finally {
                        release(conf, x, bad);
                    }
                    return out;
                }));
            }
            List<List<String>> all = new ArrayList<>();
            for (var f : parts) {
                try { all.addAll(f.get()); } catch (java.util.concurrent.ExecutionException ignored) { }
            }
            return all;
        } finally {
            pool.shutdownNow();
        }
    }

    static void collect(DatabaseMetaData md, List<String> shape, String cat, String schemaPattern, List<List<String>> out) throws SQLException {
        try (ResultSet rs = md.getTables(cat, schemaPattern, "%", null)) {
            while (rs.next() && out.size() < 50_000) {
                if (!tableType(rs.getString("TABLE_TYPE"))) continue;
                List<String> p = new ArrayList<>();
                if (shape.contains("catalog")) p.add(rs.getString("TABLE_CAT"));
                if (shape.contains("schema")) p.add(rs.getString("TABLE_SCHEM"));
                p.add(rs.getString("TABLE_NAME"));
                if (!p.contains(null)) out.add(p);
            }
        }
    }
}
