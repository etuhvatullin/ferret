package ferret.tests;

import ferret.core.Format;
import ferret.core.Model.DBKind;
import ferret.core.SqlText;

import static ferret.tests.T.check;

// Форматування запиту (⌥⌘L / Ctrl+Alt+L) — ті самі перевірки, що в Swift-Ferret (Tests/main.swift, розділ 19).
public final class FormatTests {
    private FormatTests() { }

    public static void run() {
        T.section("Форматування запиту");
        String fIn = "select a, count(*) cnt from t join (select id from u where x=1) s on s.id=t.id group by a";
        String fOut = "select a,\n       count(*) cnt\nfrom t\njoin (\n    select id\n    from u\n    where x=1\n) s on s.id=t.id\ngroup by a";
        check(fOut.equals(Format.formatSQL(fIn, DBKind.TRINO)), "формат: речення з нового рядка, колонки select — кожна з рядка, підзапит з відступом");
        check(fOut.equals(Format.formatSQL(fOut, DBKind.TRINO)), "формат удруге нічого не змінює");
        check("select *\nfrom t\nwhere a between 1 and 2\n  and b = 3\n  or c = 'x and y' -- кінець"
                        .equals(Format.formatSQL("select * from t where a between 1 and 2 and b = 3 or c = 'x and y' -- кінець\n", DBKind.TRINO)),
                "формат: and / or з рядка, крім between; рядок у лапках цілий");
        check("with a as (\n    select 1 x\n),\nb as (\n    select 2 y\n)\nselect *\nfrom a, b"
                .equals(Format.formatSQL("with a as (select 1 x), b as (select 2 y) select * from a, b", DBKind.TRINO)), "формат: CTE");
        check("select row_number() over (partition by a order by b) rn,\n       format('%s', x)\nfrom t"
                        .equals(Format.formatSQL("select row_number() over (partition by a order by b) rn, format('%s', x) from t", DBKind.TRINO)),
                "формат: вікно й функції — в рядку");
        check("select *\nfrom a\nleft join b on a.id = b.id and b.x = 1\nleft outer join c on c.id = a.id\nunion all\nselect 1"
                        .equals(Format.formatSQL("select * from a left join b on a.id = b.id and b.x = 1 left outer join c on c.id = a.id union all select 1", DBKind.TRINO)),
                "формат: join-и й union all");
        check("select a -- навіщо\nfrom t\nwhere x = 1 -- умова\n  and y = 2"
                        .equals(Format.formatSQL("select a -- навіщо\nfrom t where x = 1 -- умова\n and y = 2", DBKind.TRINO)),
                "формат: коментарі в кінці рядка лишаються, без порожніх рядків");
        // ⌥⌘L форматує лише запит під курсором: у редакторі береться statementRange, тут — те саме вручну
        String two = "select 1;\n\nselect a, b from t where x::int = 1";
        String under = SqlText.statement(two, two.length() - 2, null);
        check("select a,\n       b\nfrom t\nwhere x::int = 1".equals(Format.formatSQL(under, DBKind.TRINO)) && under.startsWith("select a"),
                "форматується лише запит під курсором; «::» — без пробілів, як було");
        check(Format.formatSQL("   ", DBKind.TRINO) == null, "порожньо — нічого форматувати");
        check("select [order],\n       'a'\nfrom t".equals(Format.formatSQL("select [order], 'a' from t", DBKind.MSSQL))
                        && "select 'it\\'s'\nfrom t".equals(Format.formatSQL("select 'it\\'s' from t", DBKind.CLICKHOUSE)),
                "лапки баз: [імʼя] у MS SQL, \\' у ClickHouse — лексеми цілі");
    }
}
