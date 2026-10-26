package ferret.core;

import ferret.core.Model.DBKind;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.IntConsumer;
import java.util.regex.Pattern;

// Текст SQL: розбиття на запити (курсор → один запит), запобіжник «лише читання», лексеми для підсвітки, безпечне
// взяття імен і рядків у лапки, підказка таблиць після FROM / JOIN, «перші рядки» мовою бази. Перенесено зі
// Swift-Ferret (Core/SQLText.swift + частина Schema.swift) — поведінка та сама; індекси — звичайні індекси Java-рядка
// (UTF-16, як NSRange у Swift).
public final class SqlText {
    private SqlText() { }

    public enum TokenKind { KEYWORD, STRING, QUOTED, COMMENT, NUMBER }

    public record Token(TokenKind kind, Range range) { }

    public static final Set<String> SQL_KEYWORDS = Set.of(
            "select", "from", "where", "group", "by", "order", "having", "limit", "offset", "with", "as", "and", "or", "not",
            "in", "is", "null", "like", "ilike", "between", "case", "when", "then", "else", "end", "join", "left", "right",
            "full", "inner", "outer", "cross", "on", "using", "union", "all", "intersect", "except", "distinct", "asc", "desc",
            "nulls", "first", "last", "over", "partition", "rows", "range", "unbounded", "preceding", "following", "current",
            "row", "cast", "try_cast", "interval", "date", "timestamp", "true", "false", "exists", "any", "some", "values",
            "show", "describe", "explain", "analyze", "table", "tables", "schemas", "catalogs", "columns", "insert", "into",
            "update", "set", "delete", "create", "drop", "alter", "truncate", "grant", "revoke", "merge", "call", "lateral",
            "unnest", "filter", "within", "format", "settings", "final", "prewhere", "array", "map", "if", "qualify", "fetch",
            "next", "only", "tablesample", "window", "returning");

    private interface Visit { void at(TokenKind kind, int start, int end); }

    // Сканер тексту: пропускає рядки, імена в лапках і коментарі — щоб «;» усередині них нічого не різала.
    private static void scan(String s, Visit visit, IntConsumer onSemicolon) {
        int n = s.length(), i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '-' && at(s, i + 1) == '-') {
                int st = i;
                while (i < n && s.charAt(i) != '\n') i++;
                visit.at(TokenKind.COMMENT, st, i);
            } else if (c == '/' && at(s, i + 1) == '*') {
                int st = i;
                i += 2;
                while (i < n && !(s.charAt(i) == '*' && at(s, i + 1) == '/')) i++;
                i = Math.min(n, i + 2);
                visit.at(TokenKind.COMMENT, st, i);
            } else if (c == '\'' || c == '"' || c == '`') {
                int st = i;
                i++;
                while (i < n) {
                    if (s.charAt(i) == c) {
                        if (at(s, i + 1) == c) { i += 2; continue; }   // подвоєна лапка — частина тексту
                        i++;
                        break;
                    }
                    i++;
                }
                visit.at(c == '\'' ? TokenKind.STRING : TokenKind.QUOTED, st, i);
            } else if (c == ';') {
                onSemicolon.accept(i);
                i++;
            } else if (isWordChar(c)) {
                int st = i;
                while (i < n && isWordChar(s.charAt(i))) i++;
                if (Character.isDigit(c)) {
                    visit.at(TokenKind.NUMBER, st, i);
                } else if (SQL_KEYWORDS.contains(s.substring(st, i).toLowerCase(Locale.ROOT))) {
                    visit.at(TokenKind.KEYWORD, st, i);
                }
            } else {
                i++;
            }
        }
    }

    private static void scan(String s, Visit visit) { scan(s, visit, k -> { }); }

    private static char at(String s, int k) { return k < s.length() ? s.charAt(k) : 0; }

    private static boolean isWordChar(char c) {
        return (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '_' || c > 127;
    }

    public static List<Token> tokenize(String text) {
        List<Token> out = new ArrayList<>();
        scan(text, (kind, a, b) -> out.add(new Token(kind, new Range(a, b - a))));
        return out;
    }

    // MARK: межі запиту під курсором (як у DataGrip)

    // Запити в консолі розділені «;» або порожнім рядком. Порожній рядок ділить, лише коли він поза дужками і наступний
    // шматок справді починає новий запит: «with a as (…)» + порожній рядок + «select» — один запит, як і «… union all» +
    // порожній рядок + «select». Дужки, «;» і порожні рядки в лапках і коментарях нічого не значать.
    public static final Set<String> STATEMENT_STARTS = Set.of(
            "select", "with", "insert", "update", "delete", "merge", "create", "drop", "alter", "truncate", "show", "describe",
            "desc", "explain", "values", "table", "set", "use", "grant", "revoke", "call", "exec", "execute", "declare", "optimize",
            "system", "kill", "analyze", "refresh", "comment", "begin", "commit", "rollback", "prepare", "deallocate", "copy",
            "vacuum", "reset", "attach", "detach", "rename", "exchange", "check", "pragma", "upsert");
    // Чим закінчений незавершений шматок: після такого наступний — продовження, а не новий запит.
    private static final Set<String> CONTINUATION_ENDS = Set.of(
            ",", "(", ".", "=", "<", ">", "+", "-", "*", "/", "|", "%", "and", "or", "not", "on", "in", "is", "like", "ilike",
            "between", "from", "join", "where", "by", "having", "as", "select", "with", "union", "all", "intersect", "except",
            "minus", "distinct", "case", "when", "then", "else", "into", "set", "values", "using", "over", "partition", "explain",
            "top", "limit", "offset", "returning", "recursive", "exists");
    private static final Set<String> MAIN_VERBS = Set.of("select", "insert", "update", "delete", "merge", "values", "table");

    // Що де в тексті: 0 — код, 1 — рядок чи імʼя в лапках, 2 — коментар.
    private static byte[] literalMask(String s) {
        byte[] m = new byte[s.length()];
        scan(s, (kind, a, b) -> {
            if (kind != TokenKind.STRING && kind != TokenKind.QUOTED && kind != TokenKind.COMMENT) return;
            for (int k = a; k < b; k++) m[k] = (byte) (kind == TokenKind.COMMENT ? 2 : 1);
        });
        return m;
    }

    // Перше й останнє значуще слово чи знак шматка (рядок у лапках — один знак) і слова поза дужками.
    private static final class PieceInfo {
        String first;
        String last;
        final List<String> top = new ArrayList<>();

        void note(String t) {
            if (first == null) first = t;
            last = t;
        }
    }

    private static PieceInfo pieceInfo(String s, byte[] mask, int a, int b) {
        PieceInfo info = new PieceInfo();
        int depth = 0, i = a;
        while (i < b) {
            if (mask[i] == 2) { i++; continue; }
            if (mask[i] == 1) {
                while (i < b && mask[i] == 1) i++;
                info.note("'");
                continue;
            }
            char c = s.charAt(i);
            if (isWordChar(c)) {
                int st = i;
                while (i < b && mask[i] == 0 && isWordChar(s.charAt(i))) i++;
                String w = s.substring(st, i).toLowerCase(Locale.ROOT);
                info.note(w);
                if (depth == 0) info.top.add(w);
                continue;
            }
            if (c == '(') depth++;
            else if (c == ')') depth = Math.max(0, depth - 1);
            if (c > 32) info.note(String.valueOf(c));
            i++;
        }
        return info;
    }

    private static boolean continues(PieceInfo prev, PieceInfo next) {
        if (next.first == null || !STATEMENT_STARTS.contains(next.first)) return true;
        if (prev.last != null && CONTINUATION_ENDS.contains(prev.last)) return true;
        List<String> rest = prev.top.isEmpty() ? List.of() : prev.top.subList(1, prev.top.size());
        String head = prev.top.isEmpty() ? null : prev.top.get(0);
        if ("with".equals(head) && rest.stream().noneMatch(MAIN_VERBS::contains)) return true;   // лише CTE — головний запит далі
        if ("insert".equals(head) && rest.stream().noneMatch(Set.of("select", "values", "with", "table")::contains)) return true;
        return false;
    }

    private static boolean blank(char c) { return c == ' ' || c == '\t' || c == '\n' || c == '\r'; }

    private static Range trimmed(String s, int a, int b) {
        while (a < b && blank(s.charAt(a))) a++;
        while (b > a && blank(s.charAt(b - 1))) b--;
        return new Range(a, b - a);
    }

    private record Piece(int a, int b, boolean semi) { }

    // Запити консолі — діапазони (без пробілів по краях). Шматки з самих коментарів запитами не вважаються.
    public static List<Range> statementRanges(String s) {
        int n = s.length();
        byte[] mask = literalMask(s);
        List<Piece> pieces = new ArrayList<>();
        int start = 0, depth = 0, i = 0;
        while (i < n) {
            if (mask[i] == 0) {
                char c = s.charAt(i);
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth = Math.max(0, depth - 1);
                } else if (c == ';') {
                    pieces.add(new Piece(start, i, true));
                    start = i + 1;
                    depth = 0;
                } else if (c == '\n' && depth == 0) {
                    int j = i + 1;
                    while (j < n && (s.charAt(j) == ' ' || s.charAt(j) == '\t' || s.charAt(j) == '\r')) j++;
                    if (j < n && s.charAt(j) == '\n') {
                        pieces.add(new Piece(start, i, false));
                        start = j;
                        i = j;
                        continue;
                    }
                }
            }
            i++;
        }
        pieces.add(new Piece(start, n, false));
        List<Range> out = new ArrayList<>();
        int curA = -1, curB = -1;
        PieceInfo curInfo = null;
        boolean closed = false;
        for (Piece p : pieces) {
            PieceInfo info = pieceInfo(s, mask, p.a(), p.b());
            if (info.first == null) {   // порожньо або самі коментарі
                if (p.semi() && curInfo != null) closed = true;
                continue;
            }
            if (curInfo != null && !closed && continues(curInfo, info)) {
                curB = p.b();
                curInfo = pieceInfo(s, mask, curA, curB);
                closed = p.semi();
            } else {
                if (curInfo != null) out.add(trimmed(s, curA, curB));
                curA = p.a();
                curB = p.b();
                curInfo = info;
                closed = p.semi();
            }
        }
        if (curInfo != null) out.add(trimmed(s, curA, curB));
        return out;
    }

    // Запит під курсором: той, у якому курсор; курсор між запитами (порожній рядок, коментар) — найближчий вище.
    public static Range statementRange(String text, int cursor) {
        List<Range> ranges = statementRanges(text);
        for (Range r : ranges) if (cursor >= r.location() && cursor <= r.end()) return r;
        Range above = null;
        for (Range r : ranges) if (r.location() <= cursor) above = r;
        return above != null ? above : ranges.isEmpty() ? null : ranges.get(0);
    }

    // Текст для запуску: виділення — як є; інакше — запит під курсором.
    public static String statement(String text, int cursor, Range selection) {
        if (selection != null && selection.length() > 0) return trim(selection.of(text));
        Range r = statementRange(text, cursor);
        return r == null ? "" : r.of(text);
    }

    // Найглибший підзапит у дужках навколо курсора: «(select …)», «(with …)», «(values …)». Дужки функцій
    // («count(x)») пропускаються — береться найближчий підзапит назовні.
    public static Range subqueryRange(String s, Range r, int cursor) {
        byte[] mask = literalMask(s);
        List<Integer> stack = new ArrayList<>();
        Range best = null;
        for (int i = r.location(); i < Math.min(r.end(), s.length()); i++) {
            if (mask[i] != 0) continue;
            char c = s.charAt(i);
            if (c == '(') {
                stack.add(i);
            } else if (c == ')' && !stack.isEmpty()) {
                int open = stack.remove(stack.size() - 1);
                if (open < cursor && cursor <= i && best == null) {
                    String f = pieceInfo(s, mask, open + 1, i).first;
                    if (f != null && Set.of("select", "with", "values").contains(f)) best = trimmed(s, open + 1, i);
                }
            }
        }
        return best;
    }

    // Що запускає ⌘↵: виділення; запит під курсором; а в підзапиті — вибір «підзапит / увесь запит» (як у DataGrip).
    public record RunChoice(String title, Range range) { }

    public static List<RunChoice> runChoices(String text, int cursor, Range selection) {
        if (selection != null && selection.length() > 0) return List.of(new RunChoice(I18n.t("sql.run.selection"), selection));
        Range whole = statementRange(text, cursor);
        if (whole == null) return List.of();
        Range sub = subqueryRange(text, whole, cursor);
        if (sub != null) return List.of(new RunChoice(I18n.t("sql.run.subquery"), sub), new RunChoice(I18n.t("sql.run.whole"), whole));
        return List.of(new RunChoice(I18n.t("sql.run.query"), whole));
    }

    public static String sqlWithoutComments(String sql) {
        boolean[] drop = new boolean[sql.length()];
        scan(sql, (kind, a, b) -> {
            if (kind == TokenKind.COMMENT) for (int k = a; k < b; k++) drop[k] = true;
        });
        StringBuilder b = new StringBuilder(sql.length());
        for (int i = 0; i < sql.length(); i++) if (!drop[i]) b.append(sql.charAt(i));
        return trim(b.toString());
    }

    // Пробіли й переноси по краях (як trimmingCharacters(in: .whitespacesAndNewlines) у Swift — разом із нерозривним пробілом).
    public static String trim(String s) {
        int a = 0, b = s.length();
        while (a < b && isSpace(s.charAt(a))) a++;
        while (b > a && isSpace(s.charAt(b - 1))) b--;
        return s.substring(a, b);
    }

    private static boolean isSpace(char c) { return Character.isWhitespace(c) || Character.isSpaceChar(c); }

    // MARK: запобіжник «лише читання»

    // Дозволені лише запити, що нічого не змінюють. EXPLAIN ANALYZE заборонений — він справді виконує вкладений запит.
    public static final Set<String> READ_ONLY_STARTS = Set.of("select", "with", "show", "describe", "desc", "explain", "values", "table");

    // Перші три слова з літер і «_» (цифри й знаки — роздільники, як у Swift-версії).
    private static List<String> firstWords(String body, int max) {
        List<String> out = new ArrayList<>();
        String low = body.toLowerCase(Locale.ROOT);
        int i = 0, n = low.length();
        while (i < n && out.size() < max) {
            while (i < n && !(Character.isLetter(low.charAt(i)) || low.charAt(i) == '_')) i++;
            int st = i;
            while (i < n && (Character.isLetter(low.charAt(i)) || low.charAt(i) == '_')) i++;
            if (i > st) out.add(low.substring(st, i));
        }
        return out;
    }

    public static String readOnlyViolation(String sql, boolean strict) {
        String body = sqlWithoutComments(sql);
        List<String> words = firstWords(body, 3);
        if (words.isEmpty()) return null;
        String first = words.get(0);
        if (!READ_ONLY_STARTS.contains(first)) {
            // текст однаковий для описки й для справжнього запису: запобіжник не вгадує намір
            return I18n.t("sql.readonly.start", first.toUpperCase(Locale.ROOT));
        }
        if (first.equals("explain") && words.contains("analyze")) {
            return I18n.t("sql.readonly.explainanalyze");
        }
        String w = strict ? writeWord(body) : null;
        if (w != null) {
            return I18n.t("sql.readonly.word", w.toUpperCase(Locale.ROOT));
        }
        return null;
    }

    public static String readOnlyViolation(String sql) { return readOnlyViolation(sql, false); }

    // Слова запису будь-де в запиті (поза лапками, коментарями, [дужками] T-SQL і не після крапки — «t.update» це колонка).
    public static final Set<String> WRITE_WORDS = Set.of(
            "insert", "update", "delete", "merge", "upsert", "drop", "create", "alter", "truncate", "rename", "grant", "revoke",
            "deny", "exec", "execute", "call", "into", "bulk", "dbcc", "backup", "restore", "kill", "shutdown", "reconfigure",
            "commit", "openrowset", "opendatasource", "openquery", "attach", "detach", "copy", "vacuum", "pragma");

    private static String writeWord(String s) {
        byte[] mask = literalMask(s);
        int i = 0, bracket = 0, n = s.length();
        while (i < n) {
            if (mask[i] != 0) { i++; continue; }
            char c = s.charAt(i);
            if (isWordChar(c)) {
                int st = i;
                while (i < n && mask[i] == 0 && isWordChar(s.charAt(i))) i++;
                String w = s.substring(st, i).toLowerCase(Locale.ROOT);
                if (bracket == 0 && !(st > 0 && s.charAt(st - 1) == '.') && WRITE_WORDS.contains(w)) return w;
                continue;
            }
            if (c == '[') bracket++;
            else if (c == ']') bracket = Math.max(0, bracket - 1);
            i++;
        }
        return null;
    }

    // MARK: лапки й імена

    private static boolean backtickKind(DBKind kind) {
        return kind == DBKind.CLICKHOUSE || kind == DBKind.MYSQL || kind == DBKind.MARIADB;
    }

    // Імена й рядки в лапки — для службових запитів дерева схем (ніякої склейки «як є»).
    public static String quoteIdent(String name, DBKind kind) {
        return backtickKind(kind) ? "`" + name.replace("`", "``") + "`" : "\"" + name.replace("\"", "\"\"") + "\"";
    }

    public static String quoteLiteral(String s, DBKind kind) {
        String v = s.replace("'", "''");
        if (backtickKind(kind)) v = v.replace("\\", "\\\\");
        return "'" + v + "'";
    }

    private static final Pattern ANY_CASE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern UPPER = Pattern.compile("[A-Z_][A-Z0-9_$#]*");
    private static final Pattern LOWER = Pattern.compile("[a-z_][a-z0-9_]*");

    // Імʼя, яке можна вставити без лапок: Trino, ClickHouse, Postgres і схожі — малими; MS SQL, MySQL, SQLite — будь-який
    // регістр (не розрізняють); Oracle, Db2, Snowflake, H2 — великими (без лапок імʼя стає великими літерами).
    public static boolean plainIdentifier(String s, DBKind kind) {
        Pattern p = switch (kind) {
            case MSSQL, MYSQL, MARIADB, SQLITE, TERADATA, JDBC -> ANY_CASE;
            case ORACLE, DB2, SNOWFLAKE, H2, EXASOL, HANA, FIREBIRD -> UPPER;
            default -> LOWER;
        };
        return p.matcher(s).matches();
    }

    // Повне імʼя таблиці для вставки в запит: прості імена — як є (lake.sales.orders), решта — у лапках.
    public static String qualifiedName(DBKind kind, List<String> path) {
        List<String> parts = new ArrayList<>(path.size());
        for (String p : path) parts.add(plainIdentifier(p, kind) ? p : quoteIdent(p, kind));
        return String.join(".", parts);
    }

    // Скільки рівнів до колонок: Trino — каталог/схема/таблиця/колонка, решта — схема (база)/таблиця/колонка.
    // (Бази через драйвер мають свою глибину від DatabaseMetaData — її знає дерево схем; це — за замовчуванням.)
    public static int schemaDepth(DBKind kind) { return kind == DBKind.TRINO ? 4 : 3; }

    // «Перші n рядків» мовою бази: MS SQL і Teradata — TOP, Oracle, Db2, Firebird — FETCH FIRST, решта — LIMIT.
    public static String firstRowsSQL(DBKind kind, List<String> path, int n, boolean oneLine, boolean upper) {
        String name = qualifiedName(kind, path), br = oneLine ? " " : "\n";
        java.util.function.UnaryOperator<String> k = s -> upper ? s.toUpperCase(Locale.ROOT) : s;
        return switch (kind) {
            case MSSQL, TERADATA -> k.apply("select top " + n + " *") + br + k.apply("from ") + name;
            case ORACLE, DB2, FIREBIRD -> k.apply("select *") + br + k.apply("from ") + name + br + k.apply("fetch first " + n + " rows only");
            default -> k.apply("select *") + br + k.apply("from ") + name + br + k.apply("limit " + n);
        };
    }

    public static String previewSQL(DBKind kind, List<String> path) { return firstRowsSQL(kind, path, 100, false, true); }

    // Готовий запит у буфер (правий клік по таблиці) — одним рядком: select * … limit 1000.
    public static String selectAllSQL(DBKind kind, List<String> path) { return firstRowsSQL(kind, path, 1000, true, false); }

    // MARK: підказка таблиць після FROM / JOIN

    private static boolean isAlnum(char c) {
        if (Character.isLetterOrDigit(c)) return true;
        int t = Character.getType(c);
        return t == Character.NON_SPACING_MARK || t == Character.COMBINING_SPACING_MARK || t == Character.ENCLOSING_MARK
                || t == Character.LETTER_NUMBER || t == Character.OTHER_NUMBER;
    }

    public static boolean identifierChar(char c) { return isAlnum(c) || "_.\"`$".indexOf(c) >= 0; }

    // Шматок імені перед курсором (літери, цифри, _, крапки, лапки) — саме його замінює вибрана підказка.
    public static Range identifierFragment(String text, int cursor) {
        int i = cursor;
        while (i > 0 && identifierChar(text.charAt(i - 1))) i--;
        return new Range(i, cursor - i);
    }

    // Курсор там, де пишуть таблицю: перед шматком імені (через пробіл) стоїть FROM або JOIN. Повертає діапазон шматка.
    public static Range tableCompletionRange(String text, int cursor) {
        Range frag = identifierFragment(text, cursor);
        int i = frag.location();
        while (i > 0 && isSpace(text.charAt(i - 1))) i--;
        if (i >= frag.location()) return null;
        int j = i;
        while (j > 0 && Character.isLetter(text.charAt(j - 1))) j--;
        String word = text.substring(j, i).toLowerCase(Locale.ROOT);
        return word.equals("from") || word.equals("join") ? frag : null;
    }

    // Підказка в списку під курсором: що стане на місце набраного шматка, що показати й дрібний опис праворуч.
    public record Suggestion(String text, String label, String detail, boolean isTable, Kind kind) {
        public enum Kind { TABLE, CONTAINER, FUNCTION, KEYWORD, COLUMN, QUERY, OTHER }
    }

    // Підказки для місця таблиці — за рівнями, як у DataGrip. «lake.sa» → дочірні імена шляху lake, що починаються
    // з «sa»; без крапок — імена верхніх рівнів і таблиці за початком назви (вставляються повним іменем). Шляхи —
    // таблиці (довжиною tableDepth) і, коротші, каталоги й схеми з дерева. Trino: каталогів багато (lake, clickhouse_prod,
    // warehouse…), а «схема.таблиця» без каталогу — це каталог підключення (defaultCatalog), і вставляється як набрано.
    // tableDepth ≤ 0 — за замовчуванням для типу (Trino — 3, решта — 2).
    public static List<Suggestion> tableSuggestions(List<List<String>> paths, String fragment, DBKind kind, int tableDepth,
                                                    String defaultCatalog, int limit) {
        int depth = Math.max(1, tableDepth > 0 ? tableDepth : (kind == DBKind.TRINO ? 3 : 2));
        String db = I18n.t("sql.level.database"), schema = I18n.t("sql.level.schema"), table = I18n.t("sql.level.table");
        List<String> names = kind == DBKind.TRINO ? List.of(I18n.t("sql.level.catalog"), schema, table)
                : kind == DBKind.CLICKHOUSE ? List.of(db, table) : List.of(db, schema, table);
        List<String> levels = names.subList(Math.max(0, names.size() - depth), names.size());
        String cleaned = fragment.replace("\"", "").replace("`", "");
        List<String> parts = new ArrayList<>(List.of(cleaned.split("\\.", -1)));
        String partial = parts.get(parts.size() - 1).toLowerCase(Locale.ROOT);
        List<String> typed = new ArrayList<>(parts.subList(0, parts.size() - 1));
        String catalog = defaultCatalog == null ? "" : defaultCatalog;
        int implicit = 0;
        if (kind == DBKind.TRINO && !catalog.isEmpty() && !typed.isEmpty()) {
            String head = typed.get(0).toLowerCase(Locale.ROOT);
            boolean known = paths.stream().anyMatch(p -> !p.isEmpty() && p.get(0).toLowerCase(Locale.ROOT).equals(head));
            if (!known) {
                typed.add(0, catalog);
                implicit = 1;
            }
        }
        Picked out = new Picked(kind, implicit, limit);
        List<List<String>> sorted = new ArrayList<>(paths);
        sorted.sort(Comparator.comparing(p -> String.join(".", p)));
        if (!typed.isEmpty()) {
            List<String> pre = new ArrayList<>();
            for (String t : typed) pre.add(t.toLowerCase(Locale.ROOT));
            for (List<String> p : sorted) {
                if (p.size() <= pre.size()) continue;
                boolean match = true;
                for (int k = 0; k < pre.size(); k++) if (!pre.get(k).equals(p.get(k).toLowerCase(Locale.ROOT))) { match = false; break; }
                if (!match || !p.get(pre.size()).toLowerCase(Locale.ROOT).startsWith(partial)) continue;
                int level = pre.size();
                out.add(p.subList(0, level + 1), levels.get(Math.min(level, levels.size() - 1)), level + 1 == depth);
            }
            return out.list;
        }
        if (depth > 1) {
            for (List<String> p : sorted) {
                if (!p.isEmpty() && p.size() <= depth && p.get(0).toLowerCase(Locale.ROOT).startsWith(partial)) {
                    out.add(List.of(p.get(0)), levels.get(0), false);
                }
            }
        }
        if (kind == DBKind.TRINO) {   // схеми — каталогу підключення; решта — після «каталог.»
            for (List<String> p : sorted) {
                if (p.size() > 1 && p.size() <= depth && (catalog.isEmpty() || p.get(0).equals(catalog))
                        && p.get(1).toLowerCase(Locale.ROOT).startsWith(partial)) {
                    out.add(p.subList(0, 2), levels.get(1), false);
                }
            }
        }
        for (List<String> p : sorted) {
            if (p.size() == depth && p.get(p.size() - 1).toLowerCase(Locale.ROOT).startsWith(partial)) {
                out.add(p, I18n.t("sql.level.table.in", String.join(".", p.subList(0, p.size() - 1))), true);
            }
        }
        return out.list;
    }

    // Зібрані підказки без повторів і не більше limit; implicit — скільки рівнів шляху не вставляти (каталог за замовчуванням).
    private static final class Picked {
        final List<Suggestion> list = new ArrayList<>();
        private final Set<String> seen = new HashSet<>();
        private final DBKind kind;
        private final int implicit, limit;

        Picked(DBKind kind, int implicit, int limit) {
            this.kind = kind;
            this.implicit = implicit;
            this.limit = limit;
        }

        void add(List<String> path, String detail, boolean isTable) {
            String text = qualifiedName(kind, path.subList(Math.min(implicit, path.size()), path.size()));
            if (list.size() >= limit || !seen.add(text)) return;
            list.add(new Suggestion(text, path.isEmpty() ? "" : path.get(path.size() - 1), detail, isTable,
                    isTable ? Suggestion.Kind.TABLE : Suggestion.Kind.CONTAINER));
        }
    }

    public static List<Suggestion> tableSuggestions(List<List<String>> paths, String fragment, DBKind kind) {
        return tableSuggestions(paths, fragment, kind, 0, "", 200);
    }

    // MARK: підказка слів

    // Шматок слова перед курсором — лише літери, цифри, _ (без крапок: для «таблиця.колонка» — свій шлях підказки).
    public static Range wordFragment(String text, int cursor) {
        int i = cursor;
        while (i > 0 && (isAlnum(text.charAt(i - 1)) || text.charAt(i - 1) == '_')) i--;
        return new Range(i, cursor - i);
    }

    // Курсор у коментарі «--» чи всередині рядка в лапках на своєму рядку — там підказки не потрібні.
    public static boolean inCommentOrString(String text, int cursor) {
        int nl = text.lastIndexOf('\n', cursor - 1);
        String line = text.substring(nl < 0 ? 0 : nl + 1, cursor);
        if (line.contains("--")) return true;
        return line.chars().filter(ch -> ch == '\'').count() % 2 == 1;
    }
}
