package ferret.tests;

import ferret.core.Dialect;
import ferret.core.Model.DBKind;
import ferret.core.SchemaItem;
import ferret.core.SqlText;
import ferret.core.SqlText.Suggestion;

import java.util.List;

import static ferret.tests.T.check;

// Підказки SQL під кожну базу — ті самі перевірки, що в Swift-Ferret (Tests/main.swift, розділ 14).
public final class DialectTests {
    private DialectTests() { }

    public static void run() {
        T.section("Підказки діалектів");
        List<Suggestion> chS = Dialect.sqlSuggestions("uniqe", DBKind.CLICKHOUSE, List.of());
        check(!chS.isEmpty() && chS.get(0).text().equals("uniqExact(") && chS.get(0).kind() == Suggestion.Kind.FUNCTION,
                "ClickHouse: uniqExact — функція, вставляється з дужкою");
        List<Suggestion> approx = Dialect.sqlSuggestions("approx_d", DBKind.TRINO, List.of());
        check(Dialect.sqlSuggestions("uniqe", DBKind.TRINO, List.of()).isEmpty() && !approx.isEmpty() && approx.get(0).label().equals("approx_distinct"),
                "у Trino свої функції, ClickHouse-ових немає");
        List<Suggestion> prew = Dialect.sqlSuggestions("prew", DBKind.CLICKHOUSE, List.of());
        List<Suggestion> prewUp = Dialect.sqlSuggestions("PREW", DBKind.CLICKHOUSE, List.of());
        check(!prew.isEmpty() && prew.get(0).text().equals("prewhere") && !prewUp.isEmpty() && prewUp.get(0).text().equals("PREWHERE")
                        && Dialect.sqlSuggestions("prew", DBKind.POSTGRES, List.of()).isEmpty(),
                "PREWHERE — лише ClickHouse, у регістрі, яким пишеш");
        List<Suggestion> sa = Dialect.sqlSuggestions("string_a", DBKind.POSTGRES, List.of());
        check(!sa.isEmpty() && sa.get(0).label().equals("string_agg"), "Postgres: string_agg");
        List<Suggestion> colS = Dialect.sqlSuggestions("user", DBKind.TRINO, List.of(new SchemaItem(List.of("lake", "sales", "orders", "user_id"), "integer")));
        check(!colS.isEmpty() && colS.get(0).kind() == Suggestion.Kind.COLUMN && colS.get(0).detail().equals("колонка · integer"), "колонки з дерева — з типом");
        check(SqlText.inCommentOrString("select 'abc", 11) && SqlText.inCommentOrString("-- uniq", 7) && !SqlText.inCommentOrString("select uniq", 11),
                "у рядку в лапках і в коментарі підказок немає");
        List<Suggestion> co = Dialect.sqlSuggestions("co", DBKind.CLICKHOUSE, List.of());
        check(co.stream().anyMatch(s -> s.label().equals("countIf")), "набір «co» у ClickHouse — у списку є countIf");
        List<Suggestion> top = Dialect.sqlSuggestions("to", DBKind.MSSQL, List.of());
        check(top.stream().anyMatch(s -> s.text().equals("top")) && Dialect.sqlSuggestions("lim", DBKind.MSSQL, List.of()).isEmpty()
                        && Dialect.sqlSuggestions("nv", DBKind.ORACLE, List.of()).stream().anyMatch(s -> s.label().equals("nvl")),
                "MS SQL — TOP замість LIMIT; Oracle — свої функції (nvl)");
        check(Dialect.dialectFunctions(DBKind.REDSHIFT) == Dialect.dialectFunctions(DBKind.POSTGRES)
                        && Dialect.dialectFunctions(DBKind.MARIADB) == Dialect.dialectFunctions(DBKind.MYSQL),
                "Redshift — функції Postgres, MariaDB — MySQL");
        check(Dialect.sqlSuggestions("c", DBKind.CLICKHOUSE, List.of()).size() <= 40, "не більше 40 підказок");
    }
}
