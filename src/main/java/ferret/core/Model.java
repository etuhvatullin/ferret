package ferret.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

// Ядро Ferret: типи баз, підключення, результат запиту, помилка. Імена полів у JSON — ті самі, що в Swift-Ferret:
// connections.json і tabs.json читаються обома програмами (перехід зі Swift-версії — без повторного введення).
public final class Model {
    private Model() { }

    public enum DBKind {
        TRINO("trino", "Trino", 443), CLICKHOUSE("clickhouse", "ClickHouse", 8123), POSTGRES("postgres", "PostgreSQL", 5432),
        MSSQL("mssql", "Microsoft SQL Server", 1433), MYSQL("mysql", "MySQL", 3306), MARIADB("mariadb", "MariaDB", 3306),
        ORACLE("oracle", "Oracle", 1521), SQLITE("sqlite", "SQLite", 0), DUCKDB("duckdb", "DuckDB", 0),
        SNOWFLAKE("snowflake", "Snowflake", 443), REDSHIFT("redshift", "Amazon Redshift", 5439), ATHENA("athena", "Amazon Athena", 443),
        BIGQUERY("bigquery", "Google BigQuery", 443), DATABRICKS("databricks", "Databricks", 443), VERTICA("vertica", "Vertica", 5433),
        DB2("db2", "IBM Db2", 50000), HANA("hana", "SAP HANA", 30015), TERADATA("teradata", "Teradata", 1025),
        EXASOL("exasol", "Exasol", 8563), FIREBIRD("firebird", "Firebird", 3050), H2("h2", "H2", 0),
        JDBC("jdbc", I18n.t("model.kind.jdbc"), 0);   // знімок мови на момент завантаження класу

        public final String raw;
        public final String title;
        public final int defaultPort;

        DBKind(String raw, String title, int port) {
            this.raw = raw;
            this.title = title;
            this.defaultPort = port;
        }

        public static DBKind of(String raw) {
            for (DBKind k : values()) if (k.raw.equals(raw)) return k;
            return null;
        }

        public boolean isFile() { return this == SQLITE || this == DUCKDB || this == H2; }

        public boolean alwaysTLS() { return this == SNOWFLAKE || this == ATHENA || this == BIGQUERY || this == DATABRICKS; }

        // MS SQL — шифровано (драйвер з 10-ї версії інакше не пускає), але без перевірки сертифіката внутрішнього сервера
        public boolean defaultTLS() { return this == TRINO || this == MSSQL || alwaysTLS(); }

        // Найуживаніші — у меню «+» одразу, решта — у «Інші бази».
        public boolean popular() {
            return switch (this) {
                case TRINO, CLICKHOUSE, POSTGRES, MSSQL, MYSQL, MARIADB, ORACLE, SQLITE, DUCKDB, SNOWFLAKE, REDSHIFT -> true;
                default -> false;
            };
        }
    }

    public static final int COLOR_COUNT = 8;

    public static final class Connection {
        public UUID id = UUID.randomUUID();
        public String name = "";
        public DBKind kind = DBKind.TRINO;
        public String host = "";
        public int port;
        public boolean useTLS;
        public String user = "";
        public String database = "";   // Trino — каталог; решта — база (у SQLite / DuckDB / H2 — шлях до файлу)
        public String schema = "";
        public boolean readOnly = true; // запобіжник: лише читання (за замовчуванням — увімкнено)
        public Integer color;           // мітка на вкладках — номер кольору з палітри (null — ще не роздано)
        public String jdbcURL;          // адреса JDBC, задана вручну (null — складається з полів)
        public List<String> driverJars; // «Інша база»: файли драйвера (.jar), скопійовані в теку даних

        public static Connection blank(DBKind k) {
            Connection c = new Connection();
            c.kind = k;
            c.port = k.defaultPort;
            c.useTLS = k.defaultTLS();
            return c;
        }

        public Connection copy() { return fromJson(toJson()); }

        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id.toString().toUpperCase());
            m.put("name", name);
            m.put("kind", kind.raw);
            m.put("host", host);
            m.put("port", (long) port);
            m.put("useTLS", useTLS);
            m.put("user", user);
            m.put("database", database);
            m.put("schema", schema);
            m.put("readOnly", readOnly);
            if (color != null) m.put("color", (long) color);
            if (jdbcURL != null) m.put("jdbcURL", jdbcURL);
            if (driverJars != null) m.put("driverJars", new ArrayList<>(driverJars));
            return m;
        }

        // null — запис незрозумілий (невідомий тип бази тощо): пропускаємо його, а не весь файл
        public static Connection fromJson(Object o) {
            Map<String, Object> m = Json.map(o);
            DBKind k = DBKind.of(Json.str(m.get("kind"), ""));
            String id = Json.str(m.get("id"));
            if (k == null || id == null) return null;
            Connection c = new Connection();
            try { c.id = UUID.fromString(id); } catch (IllegalArgumentException e) { return null; }
            c.kind = k;
            c.name = Json.str(m.get("name"), "");
            c.host = Json.str(m.get("host"), "");
            Integer port = Json.integer(m.get("port"));
            c.port = port != null ? port : k.defaultPort;
            c.useTLS = Json.bool(m.get("useTLS"), k.defaultTLS());
            c.user = Json.str(m.get("user"), "");
            c.database = Json.str(m.get("database"), "");
            c.schema = Json.str(m.get("schema"), "");
            c.readOnly = Json.bool(m.get("readOnly"), true);
            c.color = Json.integer(m.get("color"));
            c.jdbcURL = Json.str(m.get("jdbcURL"));
            c.driverJars = m.containsKey("driverJars") ? Json.strings(m.get("driverJars")) : null;
            return c;
        }

        @Override public boolean equals(Object o) {
            return o instanceof Connection c && Objects.equals(Json.write(toJson()), Json.write(c.toJson()));
        }

        @Override public int hashCode() { return id.hashCode(); }

        @Override public String toString() { return name.isEmpty() ? I18n.t("model.unnamed") : name; }
    }

    // Найменш уживаний колір (за рівності — менший номер): нове підключення не повторює наявних, доки є вільні.
    public static int leastUsedColor(List<Connection> conns) {
        int best = 0, bestCount = Integer.MAX_VALUE;
        for (int i = 0; i < COLOR_COUNT; i++) {
            int n = 0;
            for (Connection c : conns) if (c.color != null && c.color == i) n++;
            if (n < bestCount) { best = i; bestCount = n; }
        }
        return best;
    }

    public static final class QueryResult {
        public List<String> columns = new ArrayList<>();
        public List<String> types = new ArrayList<>();
        public List<String[]> rows = new ArrayList<>();
        public boolean truncated;       // рядків більше, ніж показано (досягли ліміту)
        public long elapsedMs;
        public String message;          // для команд без рядків
    }

    public static final class DBError extends Exception {
        private static final long serialVersionUID = 1L;

        public DBError(String message) { super(message); }
    }
}
