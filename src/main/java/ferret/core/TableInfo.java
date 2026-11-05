package ferret.core;

import ferret.core.Model.Connection;
import ferret.core.Model.DBError;
import ferret.core.Model.DBKind;
import ferret.core.Model.QueryResult;

import java.sql.Types;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Вікно «Таблиця» і describe_table для Claude: DDL і картка таблиці. Усі запити йдуть через Jdbc.run з підключенням
// у «лише читання» — той самий запобіжник, що й для людини, без обходу. Лише метадані: саму таблицю не сканує ніхто,
// тож кількість рядків — така, як її знає база (оцінка позначена). DDL: Trino, ClickHouse, MySQL — SHOW CREATE,
// BigQuery — INFORMATION_SCHEMA.TABLES.ddl, Postgres і MS SQL — текст view від бази, а таблицю (як і в решти баз)
// складає Ferret з опису драйвера: без check-обмежень, партицій і тригерів (Ddl.built).
public final class TableInfo {
    private TableInfo() { }

    static final int TIMEOUT_S = 15;   // на кожен запит; далі — «не встигла», запит скасовано й на сервері

    public record Ddl(String text, String kind, boolean built) { }   // kind — table / view / materialized view / dictionary

    // Опис таблиці від драйвера (Jdbc.tableMeta) — сировина для складеного DDL
    public record Column(String name, String type, int jdbcType, int size, int digits, boolean nullable, String def, String comment) { }

    public record Key(String name, List<String> columns) { }

    public record ForeignKey(String name, List<String> columns, List<String> refPath, List<String> refColumns) { }

    public record Index(String name, boolean unique, List<String> columns) { }

    public record Meta(String type, String comment, List<Column> columns, Key pk, List<ForeignKey> fks, List<Index> indexes) { }

    // Картка: рядок на факт. value — як віддала база (дати — «рррр-мм-дд гг:хх:сс», числа — цифрами, розмір — у байтах);
    // note — колонка, схема партицій, причина збою або одна з позначок NOTE_*.
    public enum Fact { DATA_UNTIL, LAST_WRITE, ROWS, SIZE, PARTITIONS, COMMENT }

    public enum Mark { EXACT, ESTIMATE, UNKNOWN, NONE, FAILED, TIMEOUT }

    public record Item(Fact fact, Mark mark, String value, String note) { }

    public static final String NOTE_PARTITION = "partition";          // «дані до» — це остання партиція, не колонка дати
    public static final String NOTE_DELETES = "deletes";              // у таблиці є файли видалень: рядків менше
    public static final String NOTE_SINCE_RESTART = "since-restart";  // MS SQL памʼятає записи лише з перезапуску сервера
    public static final String NOTE_NO_CARD = "no-card";              // для цієї бази картки немає

    // Зупинка всього, що робить вікно: закрили — запити скасовуються й на сервері.
    public static final class Stop {
        private final List<Jdbc.Running> all = new CopyOnWriteArrayList<>();
        private volatile boolean stopped;

        Jdbc.Running add() {
            Jdbc.Running r = new Jdbc.Running();
            all.add(r);
            if (stopped) r.cancel();
            return r;
        }

        public void cancel() {
            stopped = true;
            for (Jdbc.Running r : all) r.cancel();
        }

        boolean stopped() { return stopped; }
    }

    // Запит скасовано («Зупинити», закрили вікно, межа часу) — це не відповідь бази, з нього не роблять «таблиця не така»
    static boolean isStop(DBError e) { return I18n.t("jdbc.stopped").equals(e.getMessage()); }

    // Запит картки чи DDL: у справжньому вікні — Jdbc.run, у перевірках — підставні відповіді.
    public interface Run { QueryResult q(String sql) throws DBError; }

    static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "ferret-table-timer"));
    static final ExecutorService POOL = Executors.newCachedThreadPool(r -> daemon(r, "ferret-table"));

    static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    // Запуск з межею часу: після TIMEOUT_S запит скасовується, а timedOut[0] каже, що це межа, а не «Зупинити».
    static Run runner(Connection c0, String password, Stop stop, boolean[] timedOut) {
        Connection c = c0.copy();
        c.readOnly = true;
        Jdbc.Running r = stop.add();
        return sql -> {
            var f = TIMER.schedule(() -> { timedOut[0] = true; r.cancel(); }, TIMEOUT_S, TimeUnit.SECONDS);
            try {
                return Jdbc.run(c, sql, password, 1000, r);
            } finally {
                f.cancel(false);
            }
        };
    }

    // MARK: DDL

    // password null — з файлу паролів (як у Jdbc)
    public static Ddl ddl(Connection c, String password, List<String> path, Stop stop) throws DBError {
        boolean[] late = {false};
        Run run = runner(c, password, stop, late);
        DBError first = null;
        for (String sql : ddlQueries(c.kind, path)) {
            try {
                String text = createText(run.q(sql));
                if (text != null && !text.isBlank()) return new Ddl(text.strip(), kindOf(text), false);
            } catch (DBError e) {
                if (late[0]) throw new DBError(I18n.t("table.timeout", TIMEOUT_S));
                if (isStop(e)) throw e;
                if (first == null) first = e;
            }
        }
        if (nativeOnly(c.kind)) throw first != null ? first : new DBError(I18n.t("table.notfound", String.join(".", path)));
        Meta m = meta(c, password, path, stop);
        return new Ddl(build(c.kind, path, m), m.type().toUpperCase(Locale.ROOT).contains("VIEW") ? "view" : "table", true);
    }

    // Текст DDL у відповіді: перша клітинка, що починається з CREATE (MySQL на view дає ще кодування й collation
    // останніми колонками), інакше — остання (ClickHouse «statement», Trino «Create Table»).
    public static String createText(QueryResult q) {
        if (q.rows.isEmpty()) return null;
        String[] r = q.rows.get(0);
        for (String v : r) if (v != null && v.strip().regionMatches(true, 0, "CREATE", 0, 6)) return v;
        return r[r.length - 1];
    }

    // Опис від драйвера — окремим потоком з межею часу: метадані драйвер не скасовує, а Oracle на великому словнику
    // віддає ключі й індекси хвилинами. Закрили вікно чи Claude скасував — не чекаємо (драйвер доробить і віддасть зʼєднання).
    static Meta meta(Connection c, String password, List<String> path, Stop stop) throws DBError {
        if (stop.stopped()) throw new DBError(I18n.t("jdbc.stopped"));
        Future<Meta> f = POOL.submit(() -> Jdbc.tableMeta(c, password, path));
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_S * 2L);
        while (true) {
            try {
                return f.get(200, TimeUnit.MILLISECONDS);
            } catch (TimeoutException wait) {
                if (stop.stopped()) { f.cancel(true); throw new DBError(I18n.t("jdbc.stopped")); }
                if (System.nanoTime() > end) { f.cancel(true); throw new DBError(I18n.t("table.timeout", TIMEOUT_S * 2)); }
            } catch (ExecutionException e) {
                if (e.getCause() instanceof DBError d) throw d;
                throw new DBError(String.valueOf(e.getCause()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DBError(I18n.t("jdbc.stopped"));
            }
        }
    }

    // Бази, що віддають DDL самі: складати за них не треба (і складене було б біднішим за їхній текст).
    static boolean nativeOnly(DBKind k) { return k == DBKind.TRINO || k == DBKind.CLICKHOUSE || k == DBKind.MYSQL || k == DBKind.MARIADB || k == DBKind.BIGQUERY; }

    // Запити DDL по черзі: перший, що віддав текст, — відповідь. Postgres і MS SQL так беруть лише view (для таблиці
    // рядків немає — тоді складальник).
    public static List<String> ddlQueries(DBKind kind, List<String> path) {
        // поза рядком імʼя — завжди в лапках: каталог «backup» чи схема «copy» без них — слово запису для запобіжника
        String q = SqlText.qualifiedName(kind, named(kind, path)), qq = quoted(kind, path), t = path.get(path.size() - 1);
        List<String> up = path.subList(0, path.size() - 1);
        return switch (kind) {
            case TRINO -> List.of("SHOW CREATE TABLE " + qq, "SHOW CREATE VIEW " + qq, "SHOW CREATE MATERIALIZED VIEW " + qq);
            case CLICKHOUSE -> List.of("SHOW CREATE TABLE " + qq, "SHOW CREATE DICTIONARY " + qq);
            case MYSQL, MARIADB -> List.of("SHOW CREATE TABLE " + qq);
            case BIGQUERY -> List.of("SELECT ddl FROM " + bq(up, "INFORMATION_SCHEMA.TABLES") + " WHERE table_name = " + SqlText.quoteLiteral(t, kind));
            case POSTGRES -> List.of("SELECT CASE c.relkind WHEN 'm' THEN 'CREATE MATERIALIZED VIEW ' ELSE 'CREATE VIEW ' END || "
                    + SqlText.quoteLiteral(q, kind) + " || ' AS' || chr(10) || pg_get_viewdef(c.oid, true) FROM pg_class c WHERE c.oid = to_regclass("
                    + SqlText.quoteLiteral(q, kind) + ") AND c.relkind IN ('v', 'm')");
            case MSSQL -> List.of("SELECT m.definition FROM " + mssqlSys(path, "sql_modules") + " m WHERE m.object_id = OBJECT_ID(N"
                    + SqlText.quoteLiteral(q, kind) + ")");
            default -> List.of();
        };
    }

    // Вид обʼєкта — перше ключове слово після CREATE і його уточнень (OR REPLACE, ALGORITHM=…, DEFINER=…, SQL SECURITY):
    // слова до нього без лапок і дужок, тож «"daily view"» у назві таблиці вид не змінює.
    static final Pattern KIND = Pattern.compile("^\\s*CREATE((?:\\s+(?!TABLE\\b|VIEW\\b|MATERIALIZED\\b|DICTIONARY\\b)[^\\s\"(]+)*)"
            + "\\s+(MATERIALIZED\\s+VIEW|VIEW|DICTIONARY|TABLE)\\b", Pattern.CASE_INSENSITIVE);

    public static String kindOf(String ddl) {
        Matcher m = KIND.matcher(ddl);
        return m.find() ? m.group(2).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ") : "table";
    }

    // Імʼя для службового запиту поза рядком: кожна частина в лапках бази
    static String quoted(DBKind kind, List<String> path) {
        return String.join(".", named(kind, path).stream().map(p -> SqlText.quoteIdent(p, kind)).toList());
    }

    // BigQuery: «проєкт.датасет.ОБʼЄКТ» — службові таблиці пишуться як є, назви — у зворотних лапках
    static String bq(List<String> up, String object) { return (up.isEmpty() ? "" : quoted(DBKind.BIGQUERY, up) + ".") + object; }

    // MS SQL: системні подання тієї бази, де таблиця (шлях з базою — «база.sys.x», без неї — поточна)
    static String mssqlSys(List<String> path, String view) {
        return (path.size() >= 3 ? SqlText.quoteIdent(path.get(0), DBKind.MSSQL) + "." : "") + "sys." + view;
    }

    // DDL з опису драйвера. Імена — як для вставки (прості без лапок), типи — як їх назвав драйвер, з розміром.
    public static String build(DBKind kind, List<String> path, Meta m) {
        String q = SqlText.qualifiedName(kind, named(kind, path));
        boolean view = m.type() != null && m.type().toUpperCase(Locale.ROOT).contains("VIEW");
        List<String> lines = new ArrayList<>();
        for (Column c : m.columns()) {
            StringBuilder b = new StringBuilder("    ").append(name(kind, c.name())).append(' ').append(type(kind, c));
            if (!view && !c.nullable()) b.append(" NOT NULL");
            if (!view && c.def() != null && !c.def().isBlank() && !type(kind, c).toLowerCase(Locale.ROOT).endsWith("serial")) b.append(" DEFAULT ").append(c.def().strip());
            lines.add(b.toString());
        }
        if (!view && m.pk() != null) lines.add("    " + constraint(kind, m.pk().name()) + "PRIMARY KEY (" + names(kind, m.pk().columns()) + ")");
        if (!view) for (ForeignKey f : m.fks())
            lines.add("    " + constraint(kind, f.name()) + "FOREIGN KEY (" + names(kind, f.columns()) + ") REFERENCES "
                    + SqlText.qualifiedName(kind, named(kind, f.refPath())) + " (" + names(kind, f.refColumns()) + ")");
        StringBuilder out = new StringBuilder("CREATE " + (view ? "VIEW " : "TABLE ") + q + " (\n" + String.join(",\n", lines) + "\n);");
        if (!view) for (Index i : m.indexes()) {
            if (m.pk() != null && i.columns().equals(m.pk().columns()) && i.unique()) continue;   // індекс самого ключа
            if (m.fks().stream().anyMatch(f -> f.columns().equals(i.columns()) && !i.unique())) continue;   // його база робить сама
            out.append("\nCREATE ").append(i.unique() ? "UNIQUE " : "").append("INDEX ").append(name(kind, i.name())).append(" ON ").append(q)
                    .append(" (").append(names(kind, i.columns())).append(");");
        }
        if (kind != DBKind.MSSQL) {   // у MS SQL коментарі — розширені властивості, не COMMENT ON
            if (m.comment() != null) out.append("\nCOMMENT ON ").append(view ? "VIEW " : "TABLE ").append(q).append(" IS ").append(SqlText.quoteLiteral(m.comment(), kind)).append(';');
            for (Column c : m.columns()) if (c.comment() != null)
                out.append("\nCOMMENT ON COLUMN ").append(q).append('.').append(name(kind, c.name())).append(" IS ").append(SqlText.quoteLiteral(c.comment(), kind)).append(';');
        }
        return out.toString();
    }

    // Імʼя для DDL і службових запитів: Postgres не знає «база.схема.таблиця» (у дереві база — окремий рівень, коли їх
    // кілька), тож база відкидається; решта — шлях як є.
    static List<String> named(DBKind kind, List<String> path) {
        return kind == DBKind.POSTGRES && path.size() >= 3 ? path.subList(path.size() - 2, path.size()) : path;
    }

    static String name(DBKind kind, String n) { return SqlText.qualifiedName(kind, List.of(n)); }

    static String names(DBKind kind, List<String> ns) { return String.join(", ", ns.stream().map(n -> name(kind, n)).toList()); }

    static String constraint(DBKind kind, String n) { return n == null || n.isBlank() ? "" : "CONSTRAINT " + name(kind, n) + " "; }

    // Тип з розміром: varchar(20), decimal(18, 2); «без межі» (Postgres text / varchar, MS SQL max) — без числа.
    static String type(DBKind kind, Column c) {
        String t = c.type() == null ? "" : c.type();
        if (t.contains("(")) return t;
        return switch (c.jdbcType()) {
            case Types.CHAR, Types.VARCHAR, Types.NCHAR, Types.NVARCHAR, Types.BINARY, Types.VARBINARY -> {
                if (c.size() <= 0) yield t;
                if (kind == DBKind.MSSQL && c.size() > 8000) yield t + "(max)";
                yield c.size() >= 1_000_000_000 ? t : t + "(" + c.size() + ")";
            }
            case Types.DECIMAL, Types.NUMERIC -> c.size() <= 0 || c.size() >= 1000 ? t : t + "(" + c.size() + ", " + Math.max(0, c.digits()) + ")";
            default -> t;
        };
    }

    // MARK: картка

    // Факти, які приносить один запит (або кілька поспіль) — збій чи межа часу позначають саме їх.
    record Group(List<Fact> facts, Step step) { }

    interface Step { void fill(Run run, Map<Fact, Item> out) throws DBError; }

    public static List<Item> card(Connection c, String password, List<String> path, Stop stop) {
        List<Group> groups = groups(c.kind, path, c.database);
        Map<Fact, Item> out = new EnumMap<>(Fact.class);
        List<Future<?>> jobs = new ArrayList<>();
        for (Group g : groups) jobs.add(POOL.submit(() -> fill(g, runnerFor(c, password, stop), out)));
        // одна межа на всю картку (група — до трьох запитів поспіль по TIMEOUT_S); що не встигло — «не встигла»,
        // а не «база не веде»; збій поза базою (помилка в самому Ferret) — «не вдалося» з причиною
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_S * 3L);
        Map<Fact, Item> late = new EnumMap<>(Fact.class);
        for (int k = 0; k < jobs.size(); k++) {
            try {
                jobs.get(k).get(Math.max(0, end - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                for (Fact f : groups.get(k).facts()) late.put(f, new Item(f, Mark.TIMEOUT, null, String.valueOf(TIMEOUT_S * 3)));
            } catch (ExecutionException e) {
                for (Fact f : groups.get(k).facts()) late.put(f, new Item(f, Mark.FAILED, null, String.valueOf(e.getCause())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        Map<Fact, Item> got;
        synchronized (out) { got = new EnumMap<>(out); }
        late.forEach(got::putIfAbsent);
        return complete(got, groups.isEmpty());
    }

    // Окремий запускач на групу: межа часу — своя, а «Зупинити» (закрили вікно) — спільне
    private record Timed(Run run, boolean[] late) { }

    private static Timed runnerFor(Connection c, String password, Stop stop) {
        boolean[] late = {false};
        return new Timed(runner(c, password, stop, late), late);
    }

    private static void fill(Group g, Timed t, Map<Fact, Item> out) {
        Map<Fact, Item> mine = new EnumMap<>(Fact.class);
        try {
            g.step().fill(t.run(), mine);
        } catch (DBError e) {
            for (Fact f : g.facts()) if (!mine.containsKey(f))
                mine.put(f, t.late()[0] ? new Item(f, Mark.TIMEOUT, null, String.valueOf(TIMEOUT_S)) : new Item(f, Mark.FAILED, null, e.getMessage()));
        }
        synchronized (out) { out.putAll(mine); }
    }

    // Те, чого жоден запит не приніс, — «база не веде»; бази без картки — так і сказано.
    static List<Item> complete(Map<Fact, Item> got, boolean noCard) {
        List<Item> all = new ArrayList<>();
        for (Fact f : Fact.values()) {
            Item i = got.get(f);
            all.add(i != null ? i : new Item(f, Mark.UNKNOWN, null, noCard && f != Fact.COMMENT ? NOTE_NO_CARD : null));
        }
        return all;
    }

    // Те саме без потоків і межі часу — для перевірок з підставними відповідями.
    public static List<Item> card(DBKind kind, List<String> path, String database, Run run) {
        List<Group> groups = groups(kind, path, database);
        Map<Fact, Item> out = new EnumMap<>(Fact.class);
        for (Group g : groups) {
            Map<Fact, Item> mine = new EnumMap<>(Fact.class);
            try { g.step().fill(run, mine); } catch (DBError e) {
                for (Fact f : g.facts()) if (!mine.containsKey(f)) mine.put(f, new Item(f, Mark.FAILED, null, e.getMessage()));
            }
            out.putAll(mine);
        }
        return complete(out, groups.isEmpty());
    }

    static List<Group> groups(DBKind kind, List<String> path, String database) {
        String t = path.get(path.size() - 1);
        List<String> up = path.subList(0, path.size() - 1);
        return switch (kind) {
            case TRINO -> trino(path, database);
            case CLICKHOUSE -> List.of(new Group(List.of(Fact.ROWS, Fact.SIZE, Fact.COMMENT, Fact.PARTITIONS, Fact.LAST_WRITE, Fact.DATA_UNTIL), (run, out) -> {
                String db = up.isEmpty() ? "currentDatabase()" : SqlText.quoteLiteral(up.get(up.size() - 1), kind), name = SqlText.quoteLiteral(t, kind);
                QueryResult a = run.q("SELECT engine, total_rows, total_bytes, partition_key, comment FROM system.tables WHERE database = " + db + " AND name = " + name);
                if (a.rows.isEmpty()) throw new DBError(I18n.t("table.notfound", String.join(".", path)));
                String[] r = a.rows.get(0);
                put(out, Fact.ROWS, r[1], Mark.EXACT, null);
                put(out, Fact.SIZE, r[2], Mark.EXACT, null);
                put(out, Fact.COMMENT, r[4], Mark.EXACT, null);
                QueryResult b = run.q("SELECT count(DISTINCT partition), max(partition), max(modification_time), max(max_date), max(max_time) FROM system.parts"
                        + " WHERE database = " + db + " AND table = " + name + " AND active");
                String[] p = first(b);
                long parts = number(p[0]);
                if (parts <= 0) return;   // view, Distributed, Memory — частин немає, решта «база не веде»
                boolean split = r[3] != null && !r[3].isBlank();
                if (split) put(out, Fact.PARTITIONS, p[0], Mark.EXACT, r[3]); else out.put(Fact.PARTITIONS, new Item(Fact.PARTITIONS, Mark.NONE, null, null));
                put(out, Fact.LAST_WRITE, p[2], Mark.EXACT, null);
                if (p[4] != null && !p[4].startsWith("1970-01-01")) put(out, Fact.DATA_UNTIL, p[4], Mark.EXACT, r[3]);
                else if (p[3] != null && !p[3].startsWith("1970-01-01")) put(out, Fact.DATA_UNTIL, p[3], Mark.EXACT, r[3]);
                else if (split) put(out, Fact.DATA_UNTIL, p[1], Mark.ESTIMATE, NOTE_PARTITION);
            }));
            case POSTGRES -> List.of(new Group(List.of(Fact.ROWS, Fact.SIZE, Fact.COMMENT, Fact.PARTITIONS), (run, out) -> {
                String reg = "to_regclass(" + SqlText.quoteLiteral(SqlText.qualifiedName(kind, named(kind, path)), kind) + ")";
                QueryResult a = run.q("SELECT c.reltuples::bigint, pg_total_relation_size(c.oid), obj_description(c.oid, 'pg_class'),"
                        + " (SELECT count(*) FROM pg_inherits i WHERE i.inhparent = c.oid) FROM pg_class c WHERE c.oid = " + reg);
                if (a.rows.isEmpty()) throw new DBError(I18n.t("table.notfound", String.join(".", path)));
                String[] r = a.rows.get(0);
                if (number(r[0]) >= 0) put(out, Fact.ROWS, r[0], Mark.ESTIMATE, null);   // -1 — таблицю ще не аналізували
                put(out, Fact.SIZE, r[1], Mark.EXACT, null);
                put(out, Fact.COMMENT, r[2], Mark.EXACT, null);
                if (number(r[3]) > 0) put(out, Fact.PARTITIONS, r[3], Mark.EXACT, null); else out.put(Fact.PARTITIONS, new Item(Fact.PARTITIONS, Mark.NONE, null, null));
            }));
            case MYSQL, MARIADB -> List.of(new Group(List.of(Fact.ROWS, Fact.SIZE, Fact.LAST_WRITE, Fact.COMMENT, Fact.PARTITIONS), (run, out) -> {
                String db = up.isEmpty() ? "DATABASE()" : SqlText.quoteLiteral(up.get(up.size() - 1), kind);
                QueryResult a = run.q("SELECT t.TABLE_ROWS, t.DATA_LENGTH + t.INDEX_LENGTH, t.UPDATE_TIME, t.TABLE_COMMENT, t.TABLE_TYPE,"
                        + " (SELECT count(*) FROM information_schema.PARTITIONS p WHERE p.TABLE_SCHEMA = t.TABLE_SCHEMA AND p.TABLE_NAME = t.TABLE_NAME"
                        + " AND p.PARTITION_NAME IS NOT NULL) FROM information_schema.TABLES t WHERE t.TABLE_SCHEMA = " + db
                        + " AND t.TABLE_NAME = " + SqlText.quoteLiteral(t, kind));
                if (a.rows.isEmpty()) throw new DBError(I18n.t("table.notfound", String.join(".", path)));
                String[] r = a.rows.get(0);
                boolean view = "VIEW".equalsIgnoreCase(r[4]);
                if (!view) put(out, Fact.ROWS, r[0], Mark.ESTIMATE, null);
                if (!view) put(out, Fact.SIZE, r[1], Mark.EXACT, null);
                put(out, Fact.LAST_WRITE, r[2], Mark.EXACT, null);
                if (!"VIEW".equals(r[3])) put(out, Fact.COMMENT, r[3], Mark.EXACT, null);   // view: MySQL пише в коментар «VIEW»
                if (number(r[5]) > 0) put(out, Fact.PARTITIONS, r[5], Mark.EXACT, null); else if (!view) out.put(Fact.PARTITIONS, new Item(Fact.PARTITIONS, Mark.NONE, null, null));
            }));
            case MSSQL -> {
                String id = "OBJECT_ID(N" + SqlText.quoteLiteral(SqlText.qualifiedName(kind, path), kind) + ")";
                String dbId = path.size() >= 3 ? "DB_ID(N" + SqlText.quoteLiteral(path.get(0), kind) + ")" : "DB_ID()";
                yield List.of(
                        new Group(List.of(Fact.ROWS, Fact.SIZE, Fact.PARTITIONS), (run, out) -> {
                            QueryResult a = run.q("SELECT SUM(CASE WHEN ps.index_id IN (0, 1) THEN ps.row_count ELSE 0 END), SUM(ps.reserved_page_count) * 8192,"
                                    + " COUNT(DISTINCT ps.partition_number) FROM " + mssqlSys(path, "dm_db_partition_stats") + " ps WHERE ps.object_id = " + id);
                            String[] r = first(a);
                            if (r[0] == null) return;   // view або нема такої — «база не веде»
                            put(out, Fact.ROWS, r[0], Mark.ESTIMATE, null);
                            put(out, Fact.SIZE, r[1], Mark.EXACT, null);
                            if (number(r[2]) > 1) put(out, Fact.PARTITIONS, r[2], Mark.EXACT, null); else out.put(Fact.PARTITIONS, new Item(Fact.PARTITIONS, Mark.NONE, null, null));
                        }),
                        new Group(List.of(Fact.LAST_WRITE), (run, out) -> {
                            QueryResult a = run.q("SELECT MAX(last_user_update) FROM sys.dm_db_index_usage_stats WHERE database_id = " + dbId + " AND object_id = " + id);
                            String v = first(a)[0];
                            out.put(Fact.LAST_WRITE, new Item(Fact.LAST_WRITE, v == null ? Mark.UNKNOWN : Mark.EXACT, v, NOTE_SINCE_RESTART));
                        }),
                        new Group(List.of(Fact.COMMENT), (run, out) -> {
                            QueryResult a = run.q("SELECT CAST(ep.value AS nvarchar(4000)) FROM " + mssqlSys(path, "extended_properties")
                                    + " ep WHERE ep.class = 1 AND ep.major_id = " + id + " AND ep.minor_id = 0 AND ep.name = N'MS_Description'");
                            put(out, Fact.COMMENT, first(a)[0], Mark.EXACT, null);
                        }));
            }
            case BIGQUERY -> {
                String name = SqlText.quoteLiteral(t, kind);
                yield List.of(
                        new Group(List.of(Fact.ROWS, Fact.SIZE, Fact.LAST_WRITE), (run, out) -> {
                            QueryResult a = run.q("SELECT row_count, size_bytes, last_modified_time FROM " + bq(up, "__TABLES__") + " WHERE table_id = " + name);
                            if (a.rows.isEmpty()) return;   // view — у __TABLES__ рядків і розміру немає
                            String[] r = a.rows.get(0);
                            put(out, Fact.ROWS, r[0], Mark.EXACT, null);
                            put(out, Fact.SIZE, r[1], Mark.EXACT, null);
                            if (r[2] != null) put(out, Fact.LAST_WRITE, local(Instant.ofEpochMilli(number(r[2]))), Mark.EXACT, null);
                        }),
                        new Group(List.of(Fact.PARTITIONS, Fact.DATA_UNTIL), (run, out) -> {
                            QueryResult a = run.q("SELECT count(*), max(partition_id) FROM " + bq(up, "INFORMATION_SCHEMA.PARTITIONS") + " WHERE table_name = " + name
                                    + " AND partition_id NOT IN ('__NULL__', '__UNPARTITIONED__')");
                            String[] r = first(a);
                            if (number(r[0]) <= 0) { out.put(Fact.PARTITIONS, new Item(Fact.PARTITIONS, Mark.NONE, null, null)); return; }
                            put(out, Fact.PARTITIONS, r[0], Mark.EXACT, null);
                            put(out, Fact.DATA_UNTIL, bqPartition(r[1]), Mark.ESTIMATE, NOTE_PARTITION);
                        }),
                        new Group(List.of(Fact.COMMENT), (run, out) -> {
                            QueryResult a = run.q("SELECT option_value FROM " + bq(up, "INFORMATION_SCHEMA.TABLE_OPTIONS") + " WHERE table_name = " + name
                                    + " AND option_name = 'description'");
                            put(out, Fact.COMMENT, unquote(first(a)[0]), Mark.EXACT, null);
                        }));
            }
            default -> List.of();
        };
    }

    // Trino: Iceberg — з метаданих ($partitions, $files, $snapshots); інший каталог (Hive тощо) — SHOW STATS (оцінка).
    // Коментар таблиці — system.metadata.table_comments: драйвер Trino у REMARKS його не віддає.
    static List<Group> trino(List<String> path, String database) {
        DBKind k = DBKind.TRINO;
        String t = path.get(path.size() - 1);
        String cat = path.size() >= 3 ? path.get(0) : database, sch = path.size() >= 2 ? path.get(path.size() - 2) : null;
        List<String> up = path.subList(0, path.size() - 1);
        java.util.function.Function<String, String> meta = suffix -> {
            List<String> p = new ArrayList<>(up);
            p.add(t + suffix);
            return quoted(k, p);
        };
        List<Group> g = new ArrayList<>();
        g.add(new Group(List.of(Fact.PARTITIONS, Fact.DATA_UNTIL), (run, out) -> {
            QueryResult d;
            try {
                d = run.q("DESCRIBE " + meta.apply("$partitions"));
            } catch (DBError notIceberg) {
                if (isStop(notIceberg)) throw notIceberg;
                return;   // ні Iceberg, ні Hive — партицій і свіжості з метаданих немає
            }
            if (d.rows.stream().noneMatch(r -> "record_count".equals(r[0]))) {   // Hive: у «$partitions» самі ключі, рядок на партицію
                put(out, Fact.PARTITIONS, first(run.q("SELECT count(*) FROM " + meta.apply("$partitions")))[0], Mark.EXACT,
                        String.join(", ", d.rows.stream().map(r -> r[0]).toList()));
                return;
            }
            String partType = null, dataType = "";
            for (String[] r : d.rows) {
                if ("partition".equals(r[0])) partType = r[1];
                if ("data".equals(r[0])) dataType = r[1];
            }
            Partitioning p = partitioning(partType, dataType);
            String sql = "SELECT count(*)" + (p.column() == null ? "" : ", max(data." + SqlText.quoteIdent(p.column(), k) + ".max)")
                    + (p.column() == null && partType != null ? ", json_format(CAST(max(partition) AS json))" : "") + " FROM " + meta.apply("$partitions");
            String[] r = first(run.q(sql));
            if (partType == null) out.put(Fact.PARTITIONS, new Item(Fact.PARTITIONS, Mark.NONE, null, null));
            else put(out, Fact.PARTITIONS, r[0], Mark.EXACT, p.scheme());
            if (p.column() != null) put(out, Fact.DATA_UNTIL, r[1], Mark.EXACT, p.column());
            else if (partType != null) put(out, Fact.DATA_UNTIL, r[1], Mark.ESTIMATE, NOTE_PARTITION);
        }));
        g.add(new Group(List.of(Fact.ROWS, Fact.SIZE), (run, out) -> {
            String[] f;
            try {
                f = first(run.q("SELECT sum(CASE WHEN content = 0 THEN record_count ELSE 0 END), sum(CASE WHEN content = 1 THEN record_count ELSE 0 END),"
                        + " count_if(content = 2), sum(file_size_in_bytes) FROM " + meta.apply("$files")));
            } catch (DBError notIceberg) {
                if (isStop(notIceberg)) throw notIceberg;
                QueryResult st;
                try {
                    st = run.q("SHOW STATS FOR " + quoted(k, path));
                } catch (DBError noStats) {
                    if (isStop(noStats)) throw noStats;
                    return;   // view чи каталог без статистики — «база не веде», а не збій
                }
                int rc = st.columns.indexOf("row_count"), ds = st.columns.indexOf("data_size"), cn = st.columns.indexOf("column_name");
                long size = 0;
                boolean anySize = false;
                for (String[] r : st.rows) {
                    if (cn >= 0 && r[cn] == null) { if (rc >= 0) put(out, Fact.ROWS, integer(r[rc]), Mark.ESTIMATE, null); }
                    else if (ds >= 0 && r[ds] != null) { size += (long) Double.parseDouble(r[ds]); anySize = true; }
                }
                if (anySize) put(out, Fact.SIZE, String.valueOf(size), Mark.ESTIMATE, null);
                return;
            }
            long rows = Math.max(0, number(f[0])) - Math.max(0, number(f[1]));   // порожня таблиця — файлів немає, сума null
            boolean deletes = number(f[1]) > 0 || number(f[2]) > 0;
            put(out, Fact.ROWS, String.valueOf(rows), deletes ? Mark.ESTIMATE : Mark.EXACT, deletes ? NOTE_DELETES : null);
            put(out, Fact.SIZE, f[3] == null ? "0" : f[3], Mark.EXACT, null);
        }));
        g.add(new Group(List.of(Fact.LAST_WRITE), (run, out) -> {
            try {
                put(out, Fact.LAST_WRITE, first(run.q("SELECT max(committed_at) FROM " + meta.apply("$snapshots")))[0], Mark.EXACT, null);
            } catch (DBError notIceberg) {
                if (isStop(notIceberg)) throw notIceberg;   // інакше не Iceberg — «база не веде»
            }
        }));
        if (cat != null && !cat.isEmpty() && sch != null) g.add(new Group(List.of(Fact.COMMENT), (run, out) -> {
            QueryResult a = run.q("SELECT comment FROM system.metadata.table_comments WHERE catalog_name = " + SqlText.quoteLiteral(cat, k)
                    + " AND schema_name = " + SqlText.quoteLiteral(sch, k) + " AND table_name = " + SqlText.quoteLiteral(t, k));
            put(out, Fact.COMMENT, first(a)[0], Mark.EXACT, null);
        }));
        return g;
    }

    // Партиції Iceberg з типу колонки partition у «$partitions»: row("created_at_month" integer, "region" varchar).
    // Колонка дати — поле з перетворенням у часі (_year / _month / _day / _hour) або поле типу date / timestamp, і
    // лише якщо для неї є статистика файлів (у колонці data). scheme — для підпису: month(created_at), region.
    public record Partitioning(String column, String scheme) { }

    static final Pattern FIELD = Pattern.compile("\"((?:[^\"]|\"\")+)\"\\s+([a-z][a-z0-9 ]*(?:\\([^)]*\\))?)");
    static final Pattern TIME = Pattern.compile("(.+)_(year|month|day|hour)");

    public static Partitioning partitioning(String partType, String dataType) {
        if (partType == null) return new Partitioning(null, null);
        List<String> scheme = new ArrayList<>();
        String column = null;
        Matcher m = FIELD.matcher(partType);
        while (m.find()) {
            String f = m.group(1).replace("\"\"", "\""), type = m.group(2);
            Matcher tm = TIME.matcher(f);
            String src = null;
            if (tm.matches() && hasStats(dataType, tm.group(1))) { src = tm.group(1); scheme.add(tm.group(2) + "(" + src + ")"); }
            else {
                scheme.add(f);
                if ((type.startsWith("date") || type.startsWith("timestamp")) && hasStats(dataType, f)) src = f;
            }
            if (column == null) column = src;
        }
        return new Partitioning(column, String.join(", ", scheme));
    }

    static boolean hasStats(String dataType, String column) { return dataType != null && dataType.contains("\"" + column.replace("\"", "\"\"") + "\" row("); }

    // MARK: дрібниці

    static void put(Map<Fact, Item> out, Fact f, String value, Mark mark, String note) {
        if (value == null || value.isBlank()) return;
        out.put(f, new Item(f, mark, value.strip(), note));
    }

    // Перший рядок відповіді; порожня — рядок з null (агрегат без рядків, підставна відповідь у перевірці)
    static String[] first(QueryResult q) { return q.rows.isEmpty() ? new String[Math.max(1, q.columns.size())] : q.rows.get(0); }

    static long number(String s) {
        if (s == null) return -1;
        try { return new java.math.BigDecimal(s.trim()).longValue(); } catch (NumberFormatException e) { return -1; }
    }

    static String integer(String s) { long n = number(s); return n < 0 ? null : String.valueOf(n); }

    static String local(Instant i) { return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()).format(i); }

    // BigQuery: партиція «20261105» / «2026110516» / «202611» / «2026» — датою, решта — як є
    static String bqPartition(String id) {
        if (id == null) return null;
        if (id.matches("\\d{10}")) return id.substring(0, 4) + "-" + id.substring(4, 6) + "-" + id.substring(6, 8) + " " + id.substring(8) + ":00";
        if (id.matches("\\d{8}")) return id.substring(0, 4) + "-" + id.substring(4, 6) + "-" + id.substring(6);
        if (id.matches("\\d{6}")) return id.substring(0, 4) + "-" + id.substring(4);
        return id;
    }

    // BigQuery віддає опис у лапках, як рядок SQL: "текст"
    static String unquote(String s) {
        if (s == null || s.length() < 2 || s.charAt(0) != '"' || s.charAt(s.length() - 1) != '"') return s;
        return s.substring(1, s.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
