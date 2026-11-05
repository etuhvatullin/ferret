package ferret.tests;

import ferret.core.Model.DBError;
import ferret.core.Model.DBKind;
import ferret.core.Model.QueryResult;
import ferret.core.SqlText;
import ferret.core.TableInfo;
import ferret.core.TableInfo.Fact;
import ferret.core.TableInfo.Item;
import ferret.core.TableInfo.Mark;

import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import static ferret.tests.T.check;

// DDL і картка таблиці без бази: тексти запитів для кожної бази (і що всі вони проходять суворий запобіжник «лише
// читання»), розбір відповідей на підставних даних — за формою справжніх відповідей Trino і ClickHouse —
// і складальник DDL з опису драйвера.
final class TableInfoTests {
    static void run() {
        T.section("DDL: запити під кожну базу");
        List<String> tr = ddlQueries(DBKind.TRINO, List.of("lake", "sales", "order_items"));
        String oi = "\"lake\".\"sales\".\"order_items\"";
        check(tr.equals(List.of("SHOW CREATE TABLE " + oi, "SHOW CREATE VIEW " + oi, "SHOW CREATE MATERIALIZED VIEW " + oi)),
                "Trino: таблиця, далі view, далі materialized view; кожна частина імені в лапках");
        check(ddlQueries(DBKind.CLICKHOUSE, List.of("db", "t`x")).get(0).equals("SHOW CREATE TABLE `db`.`t``x`"), "ClickHouse: SHOW CREATE, імʼя в лапках");
        List<String> words = new ArrayList<>();
        for (DBKind k : List.of(DBKind.TRINO, DBKind.CLICKHOUSE, DBKind.MYSQL, DBKind.BIGQUERY)) words.addAll(ddlQueries(k, List.of("backup", "copy", "merge")));
        check(words.stream().allMatch(x -> SqlText.readOnlyViolation(x, true) == null),
                "каталог «backup», схема «copy», таблиця «merge» — власні запити Ferret не впираються в запобіжник");
        String bq = ddlQueries(DBKind.BIGQUERY, List.of("my-proj", "Sales", "orders")).get(0);
        check(bq.equals("SELECT ddl FROM `my-proj`.`Sales`.INFORMATION_SCHEMA.TABLES WHERE table_name = 'orders'"), "BigQuery: ddl з INFORMATION_SCHEMA, назви у `…`: " + bq);
        String pg = ddlQueries(DBKind.POSTGRES, List.of("public", "Orders")).get(0);
        check(pg.contains("to_regclass('public.\"Orders\"')") && pg.contains("pg_get_viewdef"), "Postgres: view — pg_get_viewdef за точним імʼям");
        QueryResult myView = q(List.of("View", "Create View", "character_set_client", "collation_connection"),
                new String[] {"v", "CREATE ALGORITHM=UNDEFINED DEFINER=`u`@`%` SQL SECURITY DEFINER VIEW `v` AS select 1", "utf8mb4", "utf8mb4_0900_ai_ci"});
        check(TableInfo.createText(myView).startsWith("CREATE ALGORITHM") && TableInfo.createText(q(List.of("statement"), new String[] {"CREATE TABLE t"})).equals("CREATE TABLE t"),
                "MySQL view: DDL — з колонки «Create View», а не collation з останньої");
        check(ddlQueries(DBKind.POSTGRES, List.of("shop", "public", "orders")).get(0).contains("to_regclass('public.orders')"),
                "Postgres: рівень бази з дерева не потрапляє в імʼя (Postgres не знає «база.схема.таблиця»)");
        String ms = ddlQueries(DBKind.MSSQL, List.of("shop", "dbo", "Orders")).get(0);
        check(ms.equals("SELECT m.definition FROM \"shop\".sys.sql_modules m WHERE m.object_id = OBJECT_ID(N'shop.dbo.Orders')"), "MS SQL: view — sql_modules тієї бази: " + ms);
        check(ddlQueries(DBKind.ORACLE, List.of("HR", "EMP")).isEmpty(), "Oracle та інші — одразу складальник");
        List<String> all = new ArrayList<>();
        for (DBKind k : List.of(DBKind.TRINO, DBKind.CLICKHOUSE, DBKind.MYSQL, DBKind.BIGQUERY, DBKind.POSTGRES, DBKind.MSSQL))
            all.addAll(ddlQueries(k, List.of("a", "b", "c")));
        check(all.stream().allMatch(s -> SqlText.readOnlyViolation(s, true) == null), "усі запити DDL проходять суворий запобіжник «лише читання»");
        check(kindOf("CREATE MATERIALIZED VIEW x AS SELECT 1").equals("materialized view") && kindOf("CREATE VIEW v AS SELECT 1").equals("view")
                        && kindOf("CREATE ALGORITHM=UNDEFINED DEFINER=`u`@`%` SQL SECURITY DEFINER VIEW `v` AS select 1").equals("view")
                        && kindOf("CREATE TABLE t (a int) COMMENT 'view of x'").equals("table")
                        && kindOf("CREATE TABLE lake.reports.\"daily view\" (a int)").equals("table")
                        && kindOf("CREATE OR REPLACE VIEW v AS SELECT 1").equals("view") && kindOf("CREATE EXTERNAL TABLE t (a int)").equals("table"),
                "вид обʼєкта з тексту DDL: таблиця, view, materialized view (MySQL — з DEFINER)");

        T.section("Картка: Trino Iceberg (відповіді — як lake.sales.order_items)");
        List<String> seen = new ArrayList<>();
        List<Item> c = card(DBKind.TRINO, List.of("lake", "sales", "order_items"), "lake", seen, sql -> {
            if (sql.startsWith("DESCRIBE")) return q(List.of("Column", "Type"),
                    new String[] {"partition", "row(\"created_at_month\" integer)"}, new String[] {"record_count", "bigint"},
                    new String[] {"data", "row(\"id\" row(\"min\" bigint, \"max\" bigint), \"created_at\" row(\"min\" timestamp(6), \"max\" timestamp(6)))"});
            if (sql.contains("$partitions")) return q(List.of("_col0", "_col1"), new String[] {"78", "2026-11-05 16:30:44.000000"});
            if (sql.contains("$files")) return q(List.of("a", "b", "c", "d"), new String[] {"120000000", "0", "0", "13400000000"});
            if (sql.contains("$snapshots")) return q(List.of("x"), new String[] {"2026-11-05 16:40:35.013 Europe/Kiev"});
            if (sql.contains("table_comments")) return q(List.of("comment"), new String[] {"Товари в замовленні."});
            throw new DBError("unexpected: " + sql);
        });
        check(item(c, Fact.DATA_UNTIL).equals(new Item(Fact.DATA_UNTIL, Mark.EXACT, "2026-11-05 16:30:44.000000", "created_at")),
                "дані до — максимум created_at зі статистики файлів (не номер партиції 682)");
        check(item(c, Fact.PARTITIONS).equals(new Item(Fact.PARTITIONS, Mark.EXACT, "78", "month(created_at)")), "партицій 78, по місяцях created_at");
        check(item(c, Fact.ROWS).mark() == Mark.EXACT && "120000000".equals(item(c, Fact.ROWS).value()) && "13400000000".equals(item(c, Fact.SIZE).value()),
                "рядків — точно (файлів видалень немає), розмір у байтах");
        check(item(c, Fact.LAST_WRITE).value().startsWith("2026-11-05 16:40") && item(c, Fact.COMMENT).value().startsWith("Товари"),
                "останній запис — зі $snapshots, коментар — з system.metadata.table_comments");
        check(seen.stream().anyMatch(s -> s.contains("max(data.\"created_at\".max)")) && seen.stream().anyMatch(s -> s.contains("\"lake\".\"sales\".\"order_items$files\"")),
                "службові таблиці Iceberg — у лапках («$» не проходить без них)");
        check(seen.stream().allMatch(s -> SqlText.readOnlyViolation(s, true) == null), "усі запити картки Trino — лише читання");

        List<Item> del = card(DBKind.TRINO, List.of("lake", "sales", "t"), "lake", new ArrayList<>(), sql -> {
            if (sql.startsWith("DESCRIBE")) return q(List.of("Column", "Type"), new String[] {"record_count", "bigint"}, new String[] {"data", "row()"});
            if (sql.contains("$partitions")) return q(List.of("_col0"), new String[] {"1"});
            if (sql.contains("$files")) return q(List.of("a", "b", "c", "d"), new String[] {"1000", "40", "0", "5000"});
            return q(List.of("x"));
        });
        check(item(del, Fact.ROWS).equals(new Item(Fact.ROWS, Mark.ESTIMATE, "960", TableInfo.NOTE_DELETES)) && item(del, Fact.PARTITIONS).mark() == Mark.NONE
                        && item(del, Fact.DATA_UNTIL).mark() == Mark.UNKNOWN,
                "файли видалень — рядків менше й «оцінка»; без партицій — «не поділена», дані до — невідомо");

        List<Item> hive = card(DBKind.TRINO, List.of("hive", "s", "t"), "lake", new ArrayList<>(), sql -> {
            if (sql.startsWith("DESCRIBE") || sql.contains("$snapshots") || sql.contains("$files")) throw new DBError("Table 'hive.s.t$partitions' does not exist");
            if (sql.startsWith("SHOW STATS")) return q(List.of("column_name", "data_size", "row_count"),
                    new String[] {"a", "1200.0", null}, new String[] {"b", "300.0", null}, new String[] {null, null, "52.0"});
            return q(List.of("comment"));
        });
        check(item(hive, Fact.ROWS).equals(new Item(Fact.ROWS, Mark.ESTIMATE, "52", null)) && "1500".equals(item(hive, Fact.SIZE).value())
                        && item(hive, Fact.LAST_WRITE).mark() == Mark.UNKNOWN,
                "не Iceberg — SHOW STATS (оцінка), останній запис — «база не веде»");

        List<Item> hp = card(DBKind.TRINO, List.of("hive", "s", "t"), "lake", new ArrayList<>(), sql -> {
            if (sql.startsWith("DESCRIBE")) return q(List.of("Column", "Type"), new String[] {"dt", "varchar"}, new String[] {"region", "varchar"});
            if (sql.contains("$partitions")) return q(List.of("_col0"), new String[] {"412"});
            throw new DBError("Table 'hive.s.t$files' does not exist");
        });
        check(item(hp, Fact.PARTITIONS).equals(new Item(Fact.PARTITIONS, Mark.EXACT, "412", "dt, region")),
                "Hive: «$partitions» — самі ключі, рядок на партицію: 412 партицій за dt, region (а не «не поділена»)");
        String stopped = ferret.core.I18n.t("jdbc.stopped");
        List<Item> st = card(DBKind.TRINO, List.of("lake", "sales", "t"), "lake", new ArrayList<>(), sql -> {
            if (sql.contains("$files") || sql.contains("$snapshots")) throw new DBError(stopped);
            return q(List.of("Column", "Type"), new String[] {"record_count", "bigint"});
        });
        check(item(st, Fact.ROWS).mark() == Mark.FAILED && item(st, Fact.LAST_WRITE).mark() == Mark.FAILED,
                "скасований запит (межа часу, закрили вікно) — не «база не веде» і не перехід на SHOW STATS");

        T.section("Картка: ClickHouse (відповіді — як shop.purchases)");
        List<String> chSeen = new ArrayList<>();
        List<Item> ch = card(DBKind.CLICKHOUSE, List.of("shop", "purchases"), "", chSeen, sql -> {
            if (sql.contains("system.tables")) return q(List.of("engine", "total_rows", "total_bytes", "partition_key", "comment"),
                    new String[] {"MergeTree", "50000000", "9000000000", "toYYYYMM(order_created)", ""});
            return q(List.of("a", "b", "c", "d", "e"), new String[] {"136", "202611", "2026-11-05 03:48:33", "1970-01-01", "2026-11-05 01:59:59"});
        });
        check(item(ch, Fact.DATA_UNTIL).equals(new Item(Fact.DATA_UNTIL, Mark.EXACT, "2026-11-05 01:59:59", "toYYYYMM(order_created)"))
                        && "136".equals(item(ch, Fact.PARTITIONS).value()) && "2026-11-05 03:48:33".equals(item(ch, Fact.LAST_WRITE).value()),
                "дані до — max_time частин (max_date 1970 — не дата), партицій 136, останній запис");
        check(item(ch, Fact.ROWS).mark() == Mark.EXACT && item(ch, Fact.COMMENT).mark() == Mark.UNKNOWN, "рядків — точно; порожній коментар — немає");
        check(chSeen.get(0).contains("database = 'shop' AND name = 'purchases'"), "назви — рядками в лапках");
        List<Item> chView = card(DBKind.CLICKHOUSE, List.of("v"), "", new ArrayList<>(), sql ->
                sql.contains("system.tables") ? q(List.of("a", "b", "c", "d", "e"), new String[] {"View", null, null, "", "вітрина"})
                        : q(List.of("a", "b", "c", "d", "e"), new String[] {"0", "", "1970-01-01 03:00:00", "1970-01-01", "1970-01-01 03:00:00"}));
        check(item(chView, Fact.ROWS).mark() == Mark.UNKNOWN && item(chView, Fact.PARTITIONS).mark() == Mark.UNKNOWN
                        && "вітрина".equals(item(chView, Fact.COMMENT).value()),
                "ClickHouse view: рядків і частин немає — «база не веде», коментар є; база — поточна");

        T.section("Картка: решта баз — запити й позначки");
        List<String> s2 = new ArrayList<>();
        List<Item> pgc = card(DBKind.POSTGRES, List.of("public", "orders"), "", s2, sql -> q(List.of("a", "b", "c", "d"), new String[] {"-1", "8192", null, "0"}));
        check(item(pgc, Fact.ROWS).mark() == Mark.UNKNOWN && item(pgc, Fact.LAST_WRITE).mark() == Mark.UNKNOWN && item(pgc, Fact.PARTITIONS).mark() == Mark.NONE,
                "Postgres: ще не аналізована (-1) — рядків не знаємо; останній запис база не веде");
        List<Item> pg2 = card(DBKind.POSTGRES, List.of("public", "orders"), "", s2, sql -> q(List.of("a", "b", "c", "d"), new String[] {"1204311", "431996928", "Замовлення", "12"}));
        check(item(pg2, Fact.ROWS).mark() == Mark.ESTIMATE && "12".equals(item(pg2, Fact.PARTITIONS).value()), "Postgres: reltuples — оцінка, 12 партицій");
        List<String> s3 = new ArrayList<>();
        card(DBKind.MSSQL, List.of("shop", "dbo", "Orders"), "", s3, sql -> q(List.of("a", "b", "c"), new String[] {null, null, null}));
        check(s3.stream().anyMatch(s -> s.contains("\"shop\".sys.dm_db_partition_stats")) && s3.stream().anyMatch(s -> s.contains("DB_ID(N'shop')"))
                        && s3.stream().anyMatch(s -> s.contains("OBJECT_ID(N'shop.dbo.Orders')")),
                "MS SQL: системні подання тієї ж бази, OBJECT_ID з повним імʼям");
        List<String> s4 = new ArrayList<>();
        List<Item> bqc = card(DBKind.BIGQUERY, List.of("proj", "ds", "events"), "", s4, sql -> {
            if (sql.contains("__TABLES__")) return q(List.of("a", "b", "c"), new String[] {"500", "4096", "1762353600000"});
            if (sql.contains("PARTITIONS")) return q(List.of("a", "b"), new String[] {"30", "20261104"});
            return q(List.of("v"), new String[] {"\"Події \\\"застосунку\\\"\""});
        });
        check(item(bqc, Fact.DATA_UNTIL).equals(new Item(Fact.DATA_UNTIL, Mark.ESTIMATE, "2026-11-04", TableInfo.NOTE_PARTITION))
                        && "Події \"застосунку\"".equals(item(bqc, Fact.COMMENT).value()) && item(bqc, Fact.LAST_WRITE).value().startsWith("2025-11-05"),
                "BigQuery: остання партиція датою, опис без лапок, час — з мілісекунд");
        check(s4.stream().allMatch(s -> s.contains("`proj`.`ds`.")), "BigQuery: службові таблиці датасету — `proj`.`ds`.__TABLES__ / INFORMATION_SCHEMA");
        List<String> s5 = new ArrayList<>();
        card(DBKind.MYSQL, List.of("shop", "orders"), "", s5, sql -> q(List.of("a", "b", "c", "d", "e", "f"), new String[] {"10", "16384", null, "", "BASE TABLE", "0"}));
        List<String> every = new ArrayList<>();
        every.addAll(seen); every.addAll(chSeen); every.addAll(s2); every.addAll(s3); every.addAll(s4); every.addAll(s5);
        check(every.stream().allMatch(s -> SqlText.readOnlyViolation(s, true) == null), "усі запити картки всіх баз проходять суворий запобіжник");
        List<Item> none = card(DBKind.ORACLE, List.of("HR", "EMP"), "", new ArrayList<>(), sql -> { throw new DBError("не мало бути запиту"); });
        check(none.stream().filter(i -> i.fact() != Fact.COMMENT).allMatch(i -> i.mark() == Mark.UNKNOWN && TableInfo.NOTE_NO_CARD.equals(i.note())),
                "база без картки — жодного запиту, усе «картки немає»");
        List<Item> failed = card(DBKind.POSTGRES, List.of("public", "t"), "", new ArrayList<>(), sql -> { throw new DBError("permission denied"); });
        check(item(failed, Fact.ROWS).mark() == Mark.FAILED && "permission denied".equals(item(failed, Fact.ROWS).note())
                        && item(failed, Fact.LAST_WRITE).mark() == Mark.UNKNOWN,
                "збій запиту — лише в його фактах, з причиною");

        T.section("Партиції Iceberg");
        check(partitioning("row(\"region\" varchar, \"ts_day\" date)", "row(\"ts\" row(\"min\" timestamp(6)), \"region\" row(\"min\" varchar))")
                        .equals(newPartitioning("ts", "region, day(ts)")), "дата — з поля day(ts), схема — усі поля");
        check(partitioning("row(\"dt\" date)", "row(\"dt\" row(\"min\" date))").equals(newPartitioning("dt", "dt")), "поле-дата без перетворення");
        check(partitioning("row(\"id_bucket\" integer)", "row(\"id\" row(\"min\" bigint))").column() == null, "bucket — не дата");

        T.section("Складальник DDL з опису драйвера");
        TableInfo.Meta m = new TableInfo.Meta("TABLE", "Замовлення", List.of(
                new TableInfo.Column("id", "int4", Types.INTEGER, 10, 0, false, "nextval('orders_id_seq'::regclass)", null),
                new TableInfo.Column("title", "varchar", Types.VARCHAR, 200, 0, true, null, "Назва'"),
                new TableInfo.Column("note", "text", Types.VARCHAR, Integer.MAX_VALUE, 0, true, null, null),
                new TableInfo.Column("Price", "numeric", Types.NUMERIC, 18, 2, false, "0", null),
                new TableInfo.Column("client_id", "int4", Types.INTEGER, 10, 0, true, null, null)),
                new TableInfo.Key("orders_pkey", List.of("id")),
                List.of(new TableInfo.ForeignKey("orders_client_fk", List.of("client_id"), List.of("public", "clients"), List.of("id"))),
                List.of(new TableInfo.Index("orders_pkey", true, List.of("id")), new TableInfo.Index("orders_title_ix", false, List.of("title", "Price"))));
        String ddl = build(DBKind.POSTGRES, List.of("public", "orders"), m);
        check(ddl.equals("CREATE TABLE public.orders (\n"
                + "    id int4 NOT NULL DEFAULT nextval('orders_id_seq'::regclass),\n"
                + "    title varchar(200),\n"
                + "    note text,\n"
                + "    \"Price\" numeric(18, 2) NOT NULL DEFAULT 0,\n"
                + "    client_id int4,\n"
                + "    CONSTRAINT orders_pkey PRIMARY KEY (id),\n"
                + "    CONSTRAINT orders_client_fk FOREIGN KEY (client_id) REFERENCES public.clients (id)\n"
                + ");\n"
                + "CREATE INDEX orders_title_ix ON public.orders (title, \"Price\");\n"
                + "COMMENT ON TABLE public.orders IS 'Замовлення';\n"
                + "COMMENT ON COLUMN public.orders.title IS 'Назва''';"), "Postgres: колонки, ключі, індекс (без індексу ключа), коментарі:\n" + ddl);
        TableInfo.Column max = new TableInfo.Column("body", "nvarchar", Types.NVARCHAR, 1073741823, 0, true, null, "x");
        String msDdl = build(DBKind.MSSQL, List.of("shop", "dbo", "Docs"), new TableInfo.Meta("TABLE", "y", List.of(max), null, List.of(), List.of()));
        check(msDdl.contains("body nvarchar(max)") && !msDdl.contains("COMMENT ON"), "MS SQL: nvarchar(max), без COMMENT ON");
        check(build(DBKind.POSTGRES, List.of("public", "v"), new TableInfo.Meta("VIEW", null, List.of(max), null, List.of(), List.of())).startsWith("CREATE VIEW public.v ("),
                "view без тексту від бази — лише колонки, без NOT NULL і ключів");
    }

    // MARK: помічники

    static List<String> ddlQueries(DBKind k, List<String> path) { return TableInfo.ddlQueries(k, path); }

    static String kindOf(String ddl) { return TableInfo.kindOf(ddl); }

    static String build(DBKind k, List<String> path, TableInfo.Meta m) { return TableInfo.build(k, path, m); }

    static TableInfo.Partitioning partitioning(String p, String d) { return TableInfo.partitioning(p, d); }

    static TableInfo.Partitioning newPartitioning(String column, String scheme) { return new TableInfo.Partitioning(column, scheme); }

    // картка з підставними відповідями; усі запити — у seen
    static List<Item> card(DBKind k, List<String> path, String db, List<String> seen, TableInfo.Run answer) {
        return TableInfo.card(k, path, db, sql -> {
            seen.add(sql);
            return answer.q(sql);
        });
    }

    static Item item(List<Item> items, Fact f) { return items.stream().filter(i -> i.fact() == f).findFirst().orElseThrow(); }

    static QueryResult q(List<String> cols, String[]... rows) {
        QueryResult r = new QueryResult();
        r.columns.addAll(cols);
        for (int i = 0; i < cols.size(); i++) r.types.add("varchar");
        r.rows.addAll(List.of(rows));
        return r;
    }
}
