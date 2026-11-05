package ferret.tests;

import ferret.core.Drivers;
import ferret.core.Jdbc;
import ferret.core.Model.Connection;
import ferret.core.Model.DBError;
import ferret.core.Model.DBKind;
import ferret.core.Model.QueryResult;
import ferret.core.SchemaItem;
import ferret.core.TableInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static ferret.tests.T.check;

// Бази наживо — H2 у памʼяті (2,6 МБ, без сервера): драйвер з Maven Central з перевіркою суми; без мережі — з кешу
// попереднього збирання (FERRET_TEST_CACHE). Немає ні того, ні іншого — пропуск.
final class JdbcTests {
    static void run() throws Exception {
        T.section("Мережеві збої — людським реченням");
        check(("Сервер «trino.invalid» не знайдено — перевір адресу або VPN.").equals(Jdbc.hint(
                new java.sql.SQLException("Error executing query", new java.net.UnknownHostException("trino.invalid: Name or service not known")))), "невідомий сервер — у причині");
        check(("Сервер «ch.invalid» не знайдено — перевір адресу або VPN.").equals(Jdbc.hint(
                new java.sql.SQLException("Error executing query: java.net.UnknownHostException: ch.invalid: Name or service not known"))), "невідомий сервер — лише в тексті драйвера");
        check(Jdbc.hint(new java.sql.SQLException("x", new java.net.ConnectException("Connection refused"))).startsWith("Сервер не приймає"), "порт закрито");
        check(Jdbc.hint(new java.sql.SQLException("Authentication failed: Unauthorized")).startsWith("Невірний логін"), "Trino: невірний пароль");
        check(Jdbc.hint(new java.sql.SQLException("Login failed for user 'x'.")).startsWith("Невірний логін"), "MS SQL: невірний пароль");
        check(Jdbc.hint(new java.sql.SQLException("Table 'x' not found")) == null, "помилка запиту — без підказки");
        T.section("Бази наживо (H2 у памʼяті)");
        Path cache = Path.of(System.getenv().getOrDefault("FERRET_TEST_CACHE", ".cache"));
        Path cached = cache.resolve("h2-2.5.252.jar");
        try {
            Path jar = Drivers.install(DBKind.H2, "2.5.252");
            check(Drivers.installed(DBKind.H2) != null && "2.5.252".equals(Drivers.installed(DBKind.H2).version()),
                    "драйвер H2 завантажено з Maven Central і перевірено контрольною сумою");
            Files.createDirectories(cache);
            Files.copy(jar, cached, StandardCopyOption.REPLACE_EXISTING);
        } catch (DBError e) {
            if (!Files.exists(cached)) { System.out.println("⚠︎ ні Maven Central, ні кешу — живу перевірку пропущено"); return; }
            System.out.println("⚠︎ Maven Central недоступний — H2 з кешу");
            Files.createDirectories(Drivers.folder(DBKind.H2));
            Files.copy(cached, Drivers.folder(DBKind.H2).resolve("h2-2.5.252.jar"), StandardCopyOption.REPLACE_EXISTING);
            ferret.core.Store.saveJSON("drivers.json", Map.of("h2", "2.5.252"));
        }
        Connection h2 = Connection.blank(DBKind.H2);
        h2.name = "h2";
        h2.user = "sa";
        h2.jdbcURL = "jdbc:h2:mem:ferret_test;DB_CLOSE_DELAY=-1";
        Connection w = h2.copy();
        w.readOnly = false;
        Jdbc.run(w, "create table t(a int, b varchar(20))", "", 10, null);
        Jdbc.run(w, "insert into t select x, 'рядок ' || x from system_range(1, 5)", "", 10, null);
        QueryResult r = Jdbc.run(h2, "select * from t order by a", "", 3, null);
        check(r.columns.equals(List.of("A", "B")) && r.rows.size() == 3 && "рядок 1".equals(r.rows.get(0)[1]) && r.truncated,
                "запит через драйвер: кирилиця, ліміт рядків");
        String guard = "";
        try { Jdbc.run(h2, "delete from t", "", 10, null); } catch (DBError e) { guard = e.getMessage(); }
        check(guard.contains("лише читання"), "лише читання: запобіжник не пустив delete");
        check(Jdbc.run(h2, "select count(*) from t", "", 10, null).rows.get(0)[0].equals("5"), "дані цілі");
        String guard2 = "";
        try { Jdbc.run(h2, "select 1 delete from t", "", 10, null); } catch (DBError e) { guard2 = e.getMessage(); }
        check(guard2.contains("DELETE"), "«select 1 delete from t» без «;» — заблоковано (MS SQL виконав би обидві)");
        List<SchemaItem> root = Jdbc.children(h2, "", List.of());
        List<SchemaItem> cols = Jdbc.children(h2, "", List.of("PUBLIC", "T"));
        List<List<String>> all = Jdbc.tables(h2, "");
        check(root.stream().anyMatch(i -> i.name().equals("PUBLIC")) && Jdbc.depth(h2) == 3 && cols.size() == 2
                && "INTEGER".equals(cols.get(0).detail()) && all.contains(List.of("PUBLIC", "T")),
                "дерево: схеми, колонки з типами, усі таблиці для підказки; глибина 3");
        Jdbc.Running long1 = new Jdbc.Running();
        CompletableFuture<String> f = CompletableFuture.supplyAsync(() -> {
            try { Jdbc.run(h2, "select count(*) from system_range(1, 100000) a, system_range(1, 100000) b", "", 10, long1); return "дійшов до кінця"; }
            catch (DBError e) { return e.getMessage(); }
        });
        Thread.sleep(700);
        long t0 = System.nanoTime();
        long1.cancel();
        String stopped = f.get();
        check(stopped.equals("Зупинено.") && (System.nanoTime() - t0) / 1_000_000 < 3000
                && Jdbc.run(h2, "select 42", "", 10, null).rows.get(0)[0].equals("42"), "«Зупинити» — на сервері теж, і далі все працює");
        Jdbc.run(w, "create schema if not exists s2", "", 10, null);
        Jdbc.run(w, "set schema s2", "", 10, null);
        check(Jdbc.run(w, "select schema()", "", 10, null).rows.get(0)[0].equals("PUBLIC"),
                "«set schema» / «use» у запиті не переносить наступні запити в іншу базу");
        String err = "";
        try { Jdbc.run(w, "selec 1", "", 10, null); } catch (DBError e) { err = e.getMessage(); }
        check(err.contains("Syntax error"), "помилка бази — як є");
        check(Jdbc.test(h2, "").startsWith("H2 2.5.252"), "«Перевірити зʼєднання» — назва й версія сервера");
        QueryResult limited = Jdbc.run(h2, "select x from system_range(1, 600)", "", 500, null);
        QueryResult all600 = Jdbc.run(h2, "select x from system_range(1, 600)", "", 200_000, null);
        check(limited.rows.size() == 500 && limited.truncated && all600.rows.size() == 600 && !all600.truncated,
                "без LIMIT — перші 500 рядків; «Показати всі» — усі 600");
        Jdbc.forget(h2.id);
        check(Jdbc.run(h2, "select 1", "", 10, null).rows.size() == 1, "після «забути» зʼєднання відкривається знову");
        T.section("Таблиця: коментарі й DDL, складений з опису драйвера (H2)");
        Jdbc.run(w, "create table clients(id int primary key, name varchar(50))", "", 10, null);
        Jdbc.run(w, "create table orders(id int not null primary key, client_id int references clients(id), title varchar(200) default 'без назви',"
                + " price decimal(18, 2) not null, note varchar)", "", 10, null);
        Jdbc.run(w, "create index orders_title on orders(title, price)", "", 10, null);
        Jdbc.run(w, "comment on table orders is 'Замовлення ''тест'''", "", 10, null);
        Jdbc.run(w, "comment on column orders.title is 'Назва'", "", 10, null);
        List<SchemaItem> oc = Jdbc.children(h2, "", List.of("PUBLIC", "ORDERS"));
        List<SchemaItem> tl = Jdbc.children(h2, "", List.of("PUBLIC"));
        check(oc.stream().anyMatch(i -> i.name().equals("TITLE") && "Назва".equals(i.comment()))
                        && tl.stream().anyMatch(i -> i.name().equals("ORDERS") && "Замовлення 'тест'".equals(i.comment()))
                        && oc.stream().anyMatch(i -> i.name().equals("ID") && i.comment() == null),
                "дерево: коментарі колонок і таблиці з метаданих драйвера, без коментаря — null");
        TableInfo.Ddl d = TableInfo.ddl(h2, "", List.of("PUBLIC", "ORDERS"), new TableInfo.Stop());
        check(d.built() && d.kind().equals("table") && d.text().contains("REFERENCES PUBLIC.CLIENTS (ID)") && d.text().contains("CREATE INDEX ORDERS_TITLE")
                        && d.text().contains("TITLE CHARACTER VARYING(200) DEFAULT ") && d.text().contains("PRICE DECIMAL(18, 2) NOT NULL") && d.text().contains("NOTE CHARACTER VARYING,"),
                "DDL складено з опису драйвера (підключення «лише читання»):\n" + d.text());
        Connection fresh = h2.copy();
        fresh.id = java.util.UUID.randomUUID();
        fresh.readOnly = false;
        fresh.jdbcURL = "jdbc:h2:mem:ferret_fresh;DB_CLOSE_DELAY=-1";
        Jdbc.run(fresh, "create table clients(id int primary key)", "", 10, null);
        for (String st : d.text().split(";\n?")) if (!st.isBlank()) Jdbc.run(fresh, st, "", 10, null);
        java.util.function.Function<List<SchemaItem>, List<String>> shape = l -> l.stream().map(i -> i.name() + " " + i.detail() + " " + i.comment()).toList();
        TableInfo.Meta again = Jdbc.tableMeta(fresh, "", List.of("PUBLIC", "ORDERS"));
        check(shape.apply(Jdbc.children(fresh, "", List.of("PUBLIC", "ORDERS"))).equals(shape.apply(oc)) && "Замовлення 'тест'".equals(again.comment())
                        && again.pk() != null && again.pk().columns().equals(List.of("ID")) && again.fks().size() == 1
                        && again.columns().stream().anyMatch(c -> c.name().equals("PRICE") && !c.nullable()),
                "той самий DDL на порожній базі: ті самі колонки, типи й коментарі, ключ, зовнішній ключ, NOT NULL");
        List<TableInfo.Item> hc = TableInfo.card(h2, "", List.of("PUBLIC", "ORDERS"), new TableInfo.Stop());
        check(hc.stream().filter(i -> i.fact() != TableInfo.Fact.COMMENT).allMatch(i -> i.mark() == TableInfo.Mark.UNKNOWN && TableInfo.NOTE_NO_CARD.equals(i.note())),
                "H2: картки немає — так і сказано");
        Jdbc.forget(fresh.id);
        trino();
        server("FERRET_CH_URL", DBKind.CLICKHOUSE, "create table t (a UInt32, b String, c Array(Nullable(UInt64))) engine = Memory",
                "insert into t select number + 1, concat('рядок ', toString(number + 1)), [number + 210, NULL] from numbers(5)");
        server("FERRET_PG_URL", DBKind.POSTGRES, "create table t (a int, b text)", "insert into t select x, 'рядок ' || x from generate_series(1, 5) x");
    }

    static Connection fromUrl(String env, DBKind k, String[] password) {
        java.net.URI u = java.net.URI.create(env);
        String[] up = u.getRawUserInfo().split(":", 2);
        Connection c = Connection.blank(k);
        c.name = k.raw + "-test";
        c.host = u.getHost();
        c.port = u.getPort() > 0 ? u.getPort() : k.defaultPort;
        c.useTLS = false;
        c.user = java.net.URLDecoder.decode(up[0], java.nio.charset.StandardCharsets.UTF_8);
        password[0] = up.length > 1 ? java.net.URLDecoder.decode(up[1], java.nio.charset.StandardCharsets.UTF_8) : "";
        c.database = u.getPath().replace("/", "");
        return c;
    }

    // Живий ClickHouse / Postgres (порожня тестова база, напр. у Docker): запит, ліміт, дерево, запобіжник.
    static void server(String var, DBKind k, String create, String insert) throws Exception {
        String env = System.getenv(var);
        if (env == null || env.isEmpty()) { System.out.println("— живий " + k.title + " пропущено (немає " + var + ")"); return; }
        T.section(k.title + " наживо (драйвер " + Drivers.spec(k).artifact() + ")");
        String[] pw = new String[1];
        Connection c = fromUrl(env, k, pw);
        Connection w = c.copy();
        w.readOnly = false;
        try { Jdbc.run(w, "drop table if exists t", pw[0], 10, null); } catch (DBError ignored) { }
        Jdbc.run(w, create, pw[0], 10, null);
        Jdbc.run(w, insert, pw[0], 10, null);
        if (k == DBKind.CLICKHOUSE) {   // драйвер 0.11.0 не читав масив з порожніми числами — Ferret має сам перейти на справну
            Drivers.install(k, "0.11.0");
            QueryResult arr = Jdbc.run(c, "select c from t order by a", pw[0], 3, null);
            check(!Drivers.broken(k, Drivers.installed(k).version()) && arr.rows.get(0)[0].contains("210"),
                    "ClickHouse: Array(Nullable(UInt64)) — з 0.11.0 сам перейшов на " + Drivers.installed(k).version() + ", значення " + arr.rows.get(0)[0]);
            Jdbc.forget(c.id);
        }
        QueryResult r = Jdbc.run(c, "select a, b from t order by a", pw[0], 3, null);
        check(r.rows.size() == 3 && "рядок 1".equals(r.rows.get(0)[1]) && r.truncated, k.title + ": запит, кирилиця, ліміт (" + Drivers.installed(k).version() + ")");
        String guard = "";
        try { Jdbc.run(c, "insert into t values (9, 'x')", pw[0], 10, null); } catch (DBError e) { guard = e.getMessage(); }
        check(guard.contains("лише читання"), k.title + ": запис не відправляється");
        List<SchemaItem> root = Jdbc.children(c, pw[0], List.of());
        List<List<String>> all = Jdbc.tables(c, pw[0]);
        List<String> tPath = all.stream().filter(p -> p.get(p.size() - 1).equals("t")).findFirst().orElse(null);
        List<SchemaItem> cols = tPath == null ? List.of() : Jdbc.children(c, pw[0], tPath);
        check(!root.isEmpty() && tPath != null && cols.size() == (k == DBKind.CLICKHOUSE ? 3 : 2) && cols.get(0).name().equals("a"),
                k.title + ": дерево " + root.stream().limit(6).map(SchemaItem::name).toList() + " → " + tPath + " → колонки " + cols.stream().map(i -> i.name() + ":" + i.detail()).toList()
                        + ", глибина " + Jdbc.depth(c));
        if (k == DBKind.POSTGRES) {   // запис повз запобіжник: сервер сам у транзакції лише для читання
            String srv = "";
            try { Jdbc.run(c, "with x as (insert into t values (9, 'x') returning 1) select * from x", pw[0], 10, null); } catch (DBError e) { srv = e.getMessage(); }
            QueryResult cnt = Jdbc.run(c, "select count(*) from t", pw[0], 10, null);
            check("5".equals(cnt.rows.get(0)[0]), "Postgres: запис повз запобіжник не пройшов (" + (srv.length() > 70 ? srv.substring(0, 70) : srv) + ")");
        }
        // вікно «Таблиця» наживо: DDL і картка — лише читання, без жодної помилки в картці
        List<String> up = tPath == null ? List.of() : tPath.subList(0, tPath.size() - 1);
        if (k == DBKind.POSTGRES && tPath != null) {
            Jdbc.run(w, "create or replace view tv as select a, b from t where a > 1", pw[0], 10, null);
            Jdbc.run(w, "comment on table t is 'Тестова'", pw[0], 10, null);
            Jdbc.run(w, "comment on column t.b is 'Текст'", pw[0], 10, null);
            Jdbc.run(w, "analyze t", pw[0], 10, null);
            List<String> vPath = new java.util.ArrayList<>(up);
            vPath.add("tv");
            TableInfo.Ddl dv = TableInfo.ddl(c, pw[0], vPath, new TableInfo.Stop());
            TableInfo.Ddl dt = TableInfo.ddl(c, pw[0], tPath, new TableInfo.Stop());
            List<TableInfo.Item> card = TableInfo.card(c, pw[0], tPath, new TableInfo.Stop());
            check(!dv.built() && dv.kind().equals("view") && dv.text().startsWith("CREATE VIEW " + ferret.core.SqlText.qualifiedName(k, vPath.subList(vPath.size() - 2, vPath.size())) + " AS")
                            && dv.text().contains("WHERE"), "Postgres: view — текстом бази (pg_get_viewdef):\n" + dv.text());
            check(dt.built() && dt.text().contains("COMMENT ON COLUMN " + ferret.core.SqlText.qualifiedName(k, tPath.subList(tPath.size() - 2, tPath.size())) + ".b IS 'Текст';"),
                    "Postgres: таблиця — складена з опису драйвера:\n" + dt.text());
            check(card.stream().noneMatch(i -> i.mark() == TableInfo.Mark.FAILED || i.mark() == TableInfo.Mark.TIMEOUT)
                            && card.stream().anyMatch(i -> i.fact() == TableInfo.Fact.COMMENT && "Тестова".equals(i.value()))
                            && card.stream().anyMatch(i -> i.fact() == TableInfo.Fact.ROWS && i.mark() == TableInfo.Mark.ESTIMATE && "5".equals(i.value())),
                    "Postgres: картка — " + card.stream().map(i -> i.fact() + "=" + i.mark() + ":" + i.value()).toList());
            Jdbc.run(w, "drop view tv", pw[0], 10, null);
        }
        if (k == DBKind.CLICKHOUSE && tPath != null) {
            String own = Jdbc.run(c, "SHOW CREATE TABLE " + ferret.core.SqlText.qualifiedName(k, tPath), pw[0], 10, null).rows.get(0)[0];
            TableInfo.Ddl dt = TableInfo.ddl(c, pw[0], tPath, new TableInfo.Stop());
            List<TableInfo.Item> card = TableInfo.card(c, pw[0], tPath, new TableInfo.Stop());
            check(!dt.built() && dt.text().equals(own.strip()), "ClickHouse: DDL — той самий текст, що SHOW CREATE TABLE:\n" + dt.text());
            check(card.stream().noneMatch(i -> i.mark() == TableInfo.Mark.FAILED || i.mark() == TableInfo.Mark.TIMEOUT)
                            && card.stream().anyMatch(i -> i.fact() == TableInfo.Fact.ROWS && "5".equals(i.value())),
                    "ClickHouse: картка — " + card.stream().map(i -> i.fact() + "=" + i.mark() + ":" + i.value()).toList());
        }
        Jdbc.run(w, "drop table t", pw[0], 10, null);
    }

    // Живий Trino — лише якщо задано FERRET_TRINO_URL (trino://логін:пароль@хост:порт/каталог): тільки читання, легкі запити.
    static void trino() throws Exception {
        String env = System.getenv("FERRET_TRINO_URL");
        if (env == null || env.isEmpty()) { System.out.println("— живий Trino пропущено (немає FERRET_TRINO_URL)"); return; }
        T.section("Trino наживо (драйвер trino-jdbc)");
        java.net.URI u = java.net.URI.create(env);
        String[] up = u.getRawUserInfo().split(":", 2);
        Connection t = Connection.blank(DBKind.TRINO);
        t.name = "trino-test";
        t.host = u.getHost();
        t.port = u.getPort() > 0 ? u.getPort() : 443;
        t.useTLS = t.port == 443 || u.getScheme().endsWith("s");
        t.user = java.net.URLDecoder.decode(up[0], java.nio.charset.StandardCharsets.UTF_8);
        String pw = up.length > 1 ? java.net.URLDecoder.decode(up[1], java.nio.charset.StandardCharsets.UTF_8) : "";
        t.database = u.getPath().replace("/", "");
        QueryResult one = Jdbc.run(t, "select 1 as x", pw, 10, null);
        check("1".equals(one.rows.get(0)[0]), "Trino: запит через драйвер (" + Drivers.installed(DBKind.TRINO).version() + ")");
        List<SchemaItem> cats = Jdbc.children(t, pw, List.of());
        if (cats.stream().noneMatch(i -> i.name().equals(t.database))) t.database = cats.stream().anyMatch(i -> i.name().equals("lake")) ? "lake" : cats.get(0).name();
        Jdbc.forget(t.id);   // адреса з іншим каталогом — нове зʼєднання
        List<SchemaItem> schemas = Jdbc.children(t, pw, List.of(t.database));
        check(cats.size() > 1 && Jdbc.depth(t) == 4 && !schemas.isEmpty(), "Trino: каталоги (" + cats.size() + "), схеми «" + t.database + "» ("
                + schemas.size() + "), глибина 4");
        long t0 = System.nanoTime();
        List<List<String>> all = Jdbc.tables(t, pw);
        check(all.size() > 10 && all.get(0).size() == 3, "Trino: усі таблиці каталогу для підказки — " + all.size() + " за " + (System.nanoTime() - t0) / 1_000_000 + " мс");
        QueryResult lim = Jdbc.run(t, "select * from system.runtime.nodes cross join unnest(sequence(1, 600)) as s(n)", pw, 500, null);
        check(lim.rows.size() == 500 && lim.truncated, "Trino: без LIMIT — 500 рядків, далі обірвано");
        String guard = "";
        try { Jdbc.run(t, "create table x (a int)", pw, 10, null); } catch (DBError e) { guard = e.getMessage(); }
        check(guard.contains("лише читання"), "Trino: запис не відправляється");
        // таблиця для вікна «Таблиця» — FERRET_TRINO_TABLE (каталог.схема.таблиця), інакше перша з каталогу
        String pick = System.getenv("FERRET_TRINO_TABLE");
        List<String> oi = pick != null && !pick.isBlank() ? List.of(pick.split("\\.")) : all.get(0);
        String q = ferret.core.SqlText.qualifiedName(DBKind.TRINO, oi);
        String own = Jdbc.run(t, "SHOW CREATE TABLE " + q, pw, 10, null).rows.get(0)[0];
        long t1 = System.nanoTime();
        TableInfo.Ddl d = TableInfo.ddl(t, pw, oi, new TableInfo.Stop());
        List<TableInfo.Item> card = TableInfo.card(t, pw, oi, new TableInfo.Stop());
        long ms = (System.nanoTime() - t1) / 1_000_000;
        check(!d.built() && d.text().equals(own.strip()), "Trino: DDL " + q + " — той самий текст, що SHOW CREATE TABLE (" + d.text().length() + " знаків); SHOW CREATE — з «лише читання»");
        check(card.stream().noneMatch(i -> i.mark() == TableInfo.Mark.FAILED || i.mark() == TableInfo.Mark.TIMEOUT),
                "Trino: картка " + q + " за " + ms + " мс — " + card.stream().map(i -> i.fact() + "=" + i.mark() + ":" + i.value() + (i.note() == null ? "" : "(" + i.note() + ")")).toList());
    }
}
