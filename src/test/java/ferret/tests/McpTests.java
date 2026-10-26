package ferret.tests;

import ferret.core.Model.DBKind;
import ferret.core.Drivers;
import ferret.core.Jdbc;
import ferret.core.Json;
import ferret.core.Mcp;
import ferret.core.McpAccess;
import ferret.core.Model.Connection;
import ferret.core.Store;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static ferret.tests.T.check;

// Доступ Claude через MCP: ключ, які підключення видно, права проти запобіжника підключення, журнал і сам протокол.
// База — H2 у памʼяті (драйвер уже поставили JdbcTests); без нього — пропуск.
final class McpTests {
    static void run() throws Exception {
        T.section("Доступ для Claude (MCP)");
        if (Drivers.installed(DBKind.H2) == null) { System.out.println("⚠︎ немає драйвера H2 — перевірки MCP пропущено"); return; }
        Connection guarded = h2("guarded", true), open = h2("open", false), hidden = h2("hidden", false);
        Jdbc.run(open, "create table orders(id int, item varchar(20))", "", 10, null);
        Jdbc.run(open, "insert into orders values (1, 'чайник'), (2, 'тостер')", "", 10, null);
        List<Object> all = new ArrayList<>();
        for (Connection c : List.of(guarded, open, hidden)) all.add(c.toJson());
        Store.saveJSON("connections.json", all);
        Map<UUID, String> rights = new HashMap<>();
        rights.put(guarded.id, McpAccess.WRITE);
        rights.put(open.id, McpAccess.READ);
        McpAccess.save(rights);

        Mcp none = new Mcp(new ByteArrayOutputStream(), "fk_будь-що", "test");
        check(text(none.callTool(1, "list_connections", Map.of())).contains("not set up"), "ключа ще немає — відмова з порадою, де його взяти");
        String key = McpAccess.newKey();
        check(err(new Mcp(new ByteArrayOutputStream(), "fk_wrong", "test").callTool(1, "list_connections", Map.of())), "чужий ключ — відмова");
        check(err(new Mcp(new ByteArrayOutputStream(), null, "test").callTool(1, "list_connections", Map.of())), "без ключа — відмова");

        Mcp m = new Mcp(new ByteArrayOutputStream(), key, "test");
        String list = text(m.callTool(1, "list_connections", Map.of()));
        check(list.contains("\"guarded\"") && list.contains("\"open\"") && !list.contains("hidden") && !list.contains("jdbc:"),
                "видно лише відкриті підключення, без адрес і паролів");
        check(list.matches("(?s).*\"access\" : \"read\".*\"name\" : \"guarded\".*"), "«зміни» при «лише читання» в підключенні — читання");

        Map<String, Object> sel = m.callTool(2, "run_query", Map.of("connection", "open", "sql", "select * from orders order by id", "max_rows", 1));
        check(!err(sel) && text(sel).startsWith("1 row × 2 columns") && text(sel).contains("ID\tITEM\n1\tчайник") && text(sel).contains("more rows exist"),
                "запит: рядки табуляцією, кирилиця, max_rows");
        Map<String, Object> ins = m.callTool(3, "run_query", Map.of("connection", "open", "sql", "insert into orders values (3, 'праска')"));
        check(err(ins) && text(ins).startsWith("Your access to this connection is read-only"), "права «читання» — insert не відправлено, причина — права Claude");
        check(err(m.callTool(4, "run_query", Map.of("connection", "guarded", "sql", "delete from orders"))),
                "права «зміни», але підключення «лише читання» — delete не відправлено");
        check(err(m.callTool(5, "run_query", Map.of("connection", "hidden", "sql", "select 1"))), "закрите підключення — відмова");

        rights.put(open.id, McpAccess.WRITE);
        McpAccess.save(rights);
        Map<String, Object> ins2 = m.callTool(6, "run_query", Map.of("connection", "open", "sql", "insert into orders values (3, 'праска')"));
        check(!err(ins2) && text(ins2).contains("1"), "права змінили у Ferret — діє одразу, без перезапуску: insert пройшов");
        String objs = text(m.callTool(7, "list_objects", Map.of("connection", "open", "path", List.of("PUBLIC", "ORDERS"))));
        check(objs.contains("ID\tINTEGER") && objs.contains("ITEM\tCHARACTER VARYING"), "дерево: колонки з типами");
        check(text(m.callTool(8, "find_tables", Map.of("connection", "open", "words", "public ord"))).contains("PUBLIC.ORDERS"), "пошук таблиць за словами");

        List<McpAccess.Entry> j = McpAccess.journal(10);
        check(j.size() == 5 && j.get(0).sql().startsWith("insert") && j.get(0).rows() == null && j.get(0).error() == null
                && j.get(1).connection().equals("hidden") && j.get(1).error() != null, "журнал: кожен запит, новіші першими, і відмови теж");

        String in = """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","clientInfo":{"name":"claude-code"}}}
                {"jsonrpc":"2.0","method":"notifications/initialized"}
                {"jsonrpc":"2.0","id":"a","method":"tools/list"}
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"run_query","arguments":{"connection":"open","sql":"select count(*) from orders"}}}
                {"jsonrpc":"2.0","id":4,"method":"nope"}
                {"jsonrpc":"2.0","id":5,"method":"ping"}
                """;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new Mcp(out, key, "test").loop(new ByteArrayInputStream(in.getBytes(StandardCharsets.UTF_8)), 30);
        Map<String, Map<String, Object>> byId = new HashMap<>();
        for (String l : out.toString(StandardCharsets.UTF_8).split("\n")) byId.put(String.valueOf(Json.map(Json.parse(l)).get("id")), Json.map(Json.parse(l)));
        check(byId.size() == 5, "на кожен запит — одна відповідь, на сповіщення — жодної (" + byId.keySet() + ")");
        check("2025-06-18".equals(Json.map(byId.get("1").get("result")).get("protocolVersion")), "initialize: та сама версія протоколу");
        check(Json.list(Json.map(byId.get("a").get("result")).get("tools")).size() == 4, "tools/list: чотири інструменти");
        check(text(Json.map(byId.get("3").get("result"))).contains("COUNT(*)\n3"), "tools/call через протокол");
        check(Long.valueOf(-32601).equals(Json.map(byId.get("4").get("error")).get("code")), "невідомий метод — -32601");
        check(Json.map(byId.get("5").get("result")).isEmpty(), "ping");
        check("claude-code".equals(McpAccess.journal(1).get(0).client()), "у журналі — хто питав (clientInfo)");
    }

    static Connection h2(String name, boolean readOnly) {
        Connection c = Connection.blank(DBKind.H2);
        c.name = name;
        c.user = "sa";
        c.readOnly = readOnly;
        c.jdbcURL = "jdbc:h2:mem:ferret_mcp;DB_CLOSE_DELAY=-1";
        return c;
    }

    static String text(Map<String, Object> r) {
        return Json.str(Json.map(Json.list(r.get("content")).get(0)).get("text"), "");
    }

    static boolean err(Map<String, Object> r) { return Boolean.TRUE.equals(r.get("isError")); }
}
