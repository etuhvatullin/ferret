package ferret.tests;

import ferret.core.Model.DBKind;
import ferret.core.Range;
import ferret.core.SqlText;
import ferret.core.SqlText.RunChoice;
import ferret.core.SqlText.Suggestion;
import ferret.core.SqlText.TokenKind;

import java.util.ArrayList;
import java.util.List;

import static ferret.tests.T.check;

// Текст SQL: межі запиту, підзапит, запобіжник, лексеми, лапки, «перші рядки», підказка таблиць — ті самі перевірки,
// що в Swift-Ferret (Tests/main.swift, розділи 1–4, 14, 18).
public final class SqlTextTests {
    private SqlTextTests() { }

    public static void run() {
        T.section("Межі запиту й підзапит");
        String text = "select 1;\nselect ';' as x; -- коментар; із крапкою з комою\nselect 3";
        check(SqlText.statementRanges(text).size() == 3, "«;» у рядку й коментарі не ріже запит — 3 запити");
        int second = text.indexOf("as x");
        check(SqlText.statement(text, second, null).equals("select ';' as x"), "курсор у другому — береться другий");
        check(SqlText.sqlWithoutComments(SqlText.statement(text, text.length(), null)).equals("select 3"),
                "курсор у кінці — останній (коментар перед ним лишається в тексті)");
        check(SqlText.statement("select 1;\n\n", 11, null).equals("select 1"), "після «;» порожньо — береться найближчий вище");
        check(SqlText.statement(text, 0, new Range(0, 8)).equals("select 1"), "виділення — як є");
        check(SqlText.statement("-- лише коментар", 3, null).isEmpty(), "лише коментар — запиту немає");
        // межа без «;» — порожній рядок, як у DataGrip, але не всередині запиту
        String multi = "Select *\nfrom goods.goods_option__general\nlimit 100\n\nselect *\nfrom aggregator.category_option_value_extended\nlimit 100\n";
        check(SqlText.statement(multi, multi.indexOf("aggregator"), null)
                .equals("select *\nfrom aggregator.category_option_value_extended\nlimit 100"), "порожній рядок — межа: береться запит, де курсор");
        String cte = "with a as (\n  select 1 x\n),\n\nb as (select 2 y)\n\nselect *\nfrom a, b\n\nselect 3";
        check(SqlText.statementRanges(cte).size() == 2 && SqlText.statement(cte, 3, null).endsWith("from a, b"),
                "порожні рядки між CTE і головним запитом — один запит");
        check(SqlText.statementRanges("select 1\nunion all\n\nselect 2\n\nselect 3").size() == 2, "«union all» + порожній рядок — продовження");
        check(SqlText.statement("select 1\n\n\n-- примітка\n\nselect 2", 10, null).equals("select 1"), "курсор на порожньому рядку — запит вище");
        check(SqlText.statement("select 'a\n\nb' as x\n\nselect 2", 3, null).equals("select 'a\n\nb' as x"), "порожній рядок у лапках не ділить");
        check(SqlText.statement("select *\nfrom t\n\nwhere a = 1", 0, null).endsWith("where a = 1"), "після порожнього рядка «where» — той самий запит");
        check(SqlText.statement("select * from t where a = 'x'\n\nselect 2", 0, null).equals("select * from t where a = 'x'"),
                "рядок у лапках у кінці не тягне за собою наступний запит");
        check(SqlText.statement("insert into t (a)\n\nselect 1\n\nselect 2", 0, null).equals("insert into t (a)\n\nselect 1"),
                "insert + порожній рядок + select — один запит");
        String nested = "select o.id, x.cnt\nfrom orders o\njoin (\n    select order_id, count(*) cnt\n    from items\n    group by order_id\n) x on x.order_id = o.id\nlimit 100";
        int inSub = nested.indexOf("count(*)") + 7;   // у дужках count — береться підзапит назовні
        List<RunChoice> ch = SqlText.runChoices(nested, inSub, new Range(inSub, 0));
        check(ch.size() == 2 && ch.get(0).title().equals("Підзапит") && ch.get(1).title().equals("Увесь запит")
                        && ch.get(0).range().of(nested).equals("select order_id, count(*) cnt\n    from items\n    group by order_id")
                        && ch.get(1).range().equals(new Range(0, nested.length())),
                "у підзапиті — вибір: підзапит (дужки count пропущено) або увесь запит");
        List<RunChoice> outside = SqlText.runChoices(nested, 3, new Range(3, 0));
        check(outside.size() == 1 && outside.get(0).title().equals("Запит"), "поза підзапитом — одразу запит");

        T.section("Запобіжник «лише читання»");
        for (String ok : List.of("select 1", "  -- нотатка\n WITH x AS (select 1) select * from x", "show tables", "explain select 1",
                "DESCRIBE lake.sales.orders", "(select 1)", "values 1", "/* так */ SELECT 2")) {
            check(SqlText.readOnlyViolation(ok) == null, "дозволено: " + ok.replace("\n", " "));
        }
        for (String bad : List.of("delete from t", "/* ой */ INSERT INTO t VALUES (1)", "explain analyze select 1", "drop table x",
                "update t set a = 1", "create table t (a int)", "call system.sync_partition_metadata('a','b','FULL')")) {
            check(SqlText.readOnlyViolation(bad) != null, "заблоковано: " + bad);
        }
        String typo = SqlText.readOnlyViolation("SELEC 1");
        check(typo != null && typo.contains("«SELEC»"), "описка названа прямо, без звинувачення в записі");
        check(SqlText.readOnlyViolation("select 1 delete from t", true) != null && SqlText.readOnlyViolation("select 1 delete from t") == null,
                "«select 1 delete from t» без «;» — заблоковано для баз через драйвер (MS SQL виконав би обидві)");
        check(SqlText.readOnlyViolation("select * into #tmp from t", true) != null
                        && SqlText.readOnlyViolation("with x as (select 1) delete from x", true) != null,
                "SELECT … INTO і WITH … DELETE — заблоковано");
        check(SqlText.readOnlyViolation("select replace(a, 'x', 'y'), [update], \"delete\", t.update from t -- drop", true) == null,
                "назви в лапках і [дужках], колонка після крапки, коментар — не запис");

        T.section("Лексеми для підсвітки");
        List<SqlText.Token> toks = SqlText.tokenize("SELECT 'a;b', \"Col\" FROM t WHERE x = 42 -- кінець");
        List<TokenKind> kinds = new ArrayList<>();
        for (SqlText.Token t : toks) kinds.add(t.kind());
        check(kinds.equals(List.of(TokenKind.KEYWORD, TokenKind.STRING, TokenKind.QUOTED, TokenKind.KEYWORD, TokenKind.KEYWORD,
                TokenKind.NUMBER, TokenKind.COMMENT)), "лексеми: ключове, рядок, імʼя в лапках, ключові, число, коментар");
        check(toks.get(1).range().of("SELECT 'a;b'").equals("'a;b'"), "діапазон рядка точний (UTF-16)");
        List<TokenKind> kinds2 = new ArrayList<>();
        for (SqlText.Token t : SqlText.tokenize("select 'it''s' as k")) kinds2.add(t.kind());
        check(kinds2.equals(List.of(TokenKind.KEYWORD, TokenKind.STRING, TokenKind.KEYWORD)), "подвоєна лапка лишається в рядку");
        check(SqlText.tokenize("select 'кирилиця' as імʼя").size() == 3, "кирилиця не ламає діапазони");

        T.section("Лапки, імена, «перші рядки»");
        check(SqlText.quoteIdent("a\"b", DBKind.TRINO).equals("\"a\"\"b\"") && SqlText.quoteIdent("a`b", DBKind.CLICKHOUSE).equals("`a``b`"),
                "імена в лапках із подвоєнням");
        check(SqlText.quoteLiteral("it's", DBKind.POSTGRES).equals("'it''s'") && SqlText.quoteLiteral("a\\'", DBKind.CLICKHOUSE).equals("'a\\\\'''"),
                "рядки в лапках, ClickHouse — ще й \\");
        check(SqlText.qualifiedName(DBKind.TRINO, List.of("lake", "sales", "orders")).equals("lake.sales.orders"), "прості імена — без лапок");
        check(SqlText.qualifiedName(DBKind.TRINO, List.of("lake", "My Schema", "t")).equals("lake.\"My Schema\".t"), "імʼя з пробілом — у лапках");
        check(SqlText.selectAllSQL(DBKind.CLICKHOUSE, List.of("aggregator", "abc_12_weeks")).equals("select * from aggregator.abc_12_weeks limit 1000")
                        && SqlText.selectAllSQL(DBKind.TRINO, List.of("lake", "My Schema", "t")).equals("select * from lake.\"My Schema\".t limit 1000")
                        && SqlText.readOnlyViolation(SqlText.selectAllSQL(DBKind.TRINO, List.of("lake", "sales", "orders"))) == null,
                "правий клік: «select * from … limit 1000» з повним імʼям, проходить запобіжник");
        check(SqlText.selectAllSQL(DBKind.MSSQL, List.of("apteka", "dbo", "Orders")).equals("select top 1000 * from apteka.dbo.Orders")
                        && SqlText.selectAllSQL(DBKind.ORACLE, List.of("HR", "EMP")).equals("select * from HR.EMP fetch first 1000 rows only")
                        && SqlText.selectAllSQL(DBKind.TRINO, List.of("lake", "sales", "orders")).equals("select * from lake.sales.orders limit 1000"),
                "«перші рядки» мовою бази: TOP, FETCH FIRST, LIMIT");
        check(SqlText.previewSQL(DBKind.TRINO, List.of("lake", "sales", "orders")).equals("SELECT *\nFROM lake.sales.orders\nLIMIT 100"),
                "перегляд таблиці — 100 рядків, ключові слова великими, імʼя як є");
        check(SqlText.qualifiedName(DBKind.MYSQL, List.of("db", "my table")).equals("db.`my table`"), "MySQL — імена в зворотних лапках");
        check(SqlText.plainIdentifier("Orders", DBKind.MSSQL) && !SqlText.plainIdentifier("Orders", DBKind.TRINO)
                        && SqlText.plainIdentifier("EMP", DBKind.ORACLE) && !SqlText.plainIdentifier("emp", DBKind.ORACLE),
                "без лапок: MS SQL — будь-який регістр, Trino — малими, Oracle — великими");
        check(SqlText.schemaDepth(DBKind.TRINO) == 4 && SqlText.schemaDepth(DBKind.CLICKHOUSE) == 3, "глибина дерева: Trino — 4, решта — 3");

        T.section("Підказка таблиць після FROM / JOIN");
        String sqlFrom = "select *\nfrom  lake.sa";
        check(new Range(15, 7).equals(SqlText.tableCompletionRange(sqlFrom, sqlFrom.length())), "після FROM — шматок з крапками");
        String sqlJoin = "select * from a join ord";
        Range rj = SqlText.tableCompletionRange(sqlJoin, sqlJoin.length());
        check(rj != null && rj.of(sqlJoin).equals("ord"), "після JOIN — теж");
        String sqlWhere = "select * from a where ord";
        check(SqlText.tableCompletionRange(sqlWhere, sqlWhere.length()) == null, "після WHERE — не таблиця");
        check(SqlText.tableCompletionRange("select * fromx", 14) == null, "fromx — не FROM");
        List<List<String>> paths = List.of(List.of("lake", "sales", "orders"), List.of("lake", "sales", "orders_items"),
                List.of("lake", "goods", "items"), List.of("lake", "sales", "coupon_discounts"));
        List<Suggestion> sF = SqlText.tableSuggestions(paths, "l", DBKind.TRINO);
        check(!sF.isEmpty() && sF.get(0).equals(new Suggestion("lake", "lake", "каталог", false, Suggestion.Kind.CONTAINER)),
                "«l» → першим каталог lake");
        check(texts(SqlText.tableSuggestions(paths, "lake.", DBKind.TRINO)).equals(List.of("lake.goods", "lake.sales")), "«lake.» → схеми");
        List<Suggestion> sOr = SqlText.tableSuggestions(paths, "lake.sales.or", DBKind.TRINO);
        check(texts(sOr).equals(List.of("lake.sales.orders", "lake.sales.orders_items")) && sOr.stream().allMatch(Suggestion::isTable),
                "«lake.sales.or» → таблиці");
        List<Suggestion> sOrd = SqlText.tableSuggestions(paths, "ord", DBKind.TRINO);
        check(!sOrd.isEmpty() && sOrd.get(0).text().equals("lake.sales.orders"), "за початком назви таблиці — повним іменем");
        List<Suggestion> sSa = SqlText.tableSuggestions(paths, "sa", DBKind.TRINO);
        check(!sSa.isEmpty() && sSa.get(0).equals(new Suggestion("lake.sales", "sales", "схема", false, Suggestion.Kind.CONTAINER)),
                "Trino: схема каталогу за замовчуванням");
        List<List<String>> ch1 = List.of(List.of("aggregator", "abc_12_weeks"));
        List<Suggestion> agg = SqlText.tableSuggestions(ch1, "agg", DBKind.CLICKHOUSE);
        check(texts(SqlText.tableSuggestions(ch1, "aggregator.a", DBKind.CLICKHOUSE)).equals(List.of("aggregator.abc_12_weeks"))
                && !agg.isEmpty() && agg.get(0).detail().equals("база"), "ClickHouse: база → таблиця");
        // Trino: каталогів багато (з дерева), а «схема.таблиця» без каталогу — у каталозі підключення
        List<List<String>> multiCat = new ArrayList<>(paths);
        multiCat.addAll(List.of(List.of("clickhouse_prod"), List.of("warehouse"), List.of("lake"), List.of("clickhouse_prod", "goods"),
                List.of("clickhouse_prod", "goods", "goods__general")));
        List<Suggestion> cl = SqlText.tableSuggestions(multiCat, "cl", DBKind.TRINO, 0, "lake", 200);
        check(!cl.isEmpty() && cl.get(0).text().equals("clickhouse_prod"), "«cl» → каталог clickhouse_prod з дерева");
        check(texts(SqlText.tableSuggestions(multiCat, "clickhouse_prod.", DBKind.TRINO, 0, "lake", 200)).equals(List.of("clickhouse_prod.goods"))
                        && texts(SqlText.tableSuggestions(multiCat, "clickhouse_prod.goods.go", DBKind.TRINO, 0, "lake", 200))
                        .equals(List.of("clickhouse_prod.goods.goods__general")),
                "інший каталог: каталог. → схеми, каталог.схема. → таблиці");
        check(texts(SqlText.tableSuggestions(multiCat, "sales.or", DBKind.TRINO, 0, "lake", 200)).equals(List.of("sales.orders", "sales.orders_items")),
                "«sales.or» без каталогу — таблиці lake, вставляється як набрано");
        check(texts(SqlText.tableSuggestions(multiCat, "go", DBKind.TRINO, 0, "lake", 200)).contains("clickhouse_prod.goods.goods__general"),
                "за назвою — і таблиці інших каталогів, які вже відомі");
        check(SqlText.identifierFragment("select * from lake.sa", 21).equals(new Range(14, 7)) && SqlText.wordFragment("select coun", 11).equals(new Range(7, 4)),
                "шматок імені — з крапками, шматок слова — без");
    }

    static List<String> texts(List<Suggestion> s) {
        List<String> out = new ArrayList<>();
        for (Suggestion x : s) out.add(x.text());
        return out;
    }
}
