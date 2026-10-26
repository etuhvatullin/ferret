package ferret.core;

import ferret.core.Model.DBKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

// Форматування запиту (⌥⌘L / Ctrl+Alt+L, як у DataGrip): речення — з нового рядка (select, from, where, join, group by…),
// колонки select — кожна з рядка, and / or у where — з рядка, підзапит у дужках — з відступом на 4. Змінюються лише
// пробіли й переноси: регістр слів, лапки, коментарі, числа й пробіли довкола знаків — як були. Перевірка наприкінці
// звіряє всі не-пробільні лексеми з оригіналом; розійшлось — null, текст не чіпається. Перенесено зі Swift-Ferret
// (Core/Format.swift) з усіма виправленнями, заміряно там на 2312 справжніх запитах — 0 відмов.
public final class Format {
    private Format() { }

    public enum Kind { WORD, LITERAL, LINE_COMMENT, BLOCK_COMMENT, PUNCT }

    public record FmtToken(String text, Kind kind, boolean spaceBefore) { }

    private static boolean isWord(char c) {
        if (Character.isLetterOrDigit(c) || "_$@#".indexOf(c) >= 0) return true;
        int t = Character.getType(c);   // комбінувальні знаки — частина літери, як Character у Swift
        return t == Character.NON_SPACING_MARK || t == Character.COMBINING_SPACING_MARK || t == Character.ENCLOSING_MARK
                || t == Character.LETTER_NUMBER || t == Character.OTHER_NUMBER;
    }

    private static boolean isSpace(char c) { return Character.isWhitespace(c) || Character.isSpaceChar(c); }

    private static final Set<String> TWO_CHAR = Set.of("<=", ">=", "<>", "!=", "||", "::", "->", "=>");

    public static List<FmtToken> formatTokens(String s, DBKind kind) {
        int n = s.length();
        boolean backslash = kind == DBKind.CLICKHOUSE || kind == DBKind.MYSQL || kind == DBKind.MARIADB;   // там \' — лапка в рядку
        boolean brackets = kind == DBKind.MSSQL;                                                        // [імʼя] у T-SQL
        List<FmtToken> out = new ArrayList<>();
        int i = 0;
        boolean space = false;
        while (i < n) {
            char c = s.charAt(i);
            if (isSpace(c)) { space = true; i++; continue; }
            int st = i;
            Kind k = Kind.PUNCT;
            if (c == '-' && i + 1 < n && s.charAt(i + 1) == '-') {
                while (i < n && s.charAt(i) != '\n') i++;
                k = Kind.LINE_COMMENT;
            } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                i += 2;
                while (i < n && !(s.charAt(i) == '*' && i + 1 < n && s.charAt(i + 1) == '/')) i++;
                i = Math.min(n, i + 2);
                k = Kind.BLOCK_COMMENT;
            } else if (c == '\'' || c == '"' || c == '`' || (brackets && c == '[')) {
                char close = c == '[' ? ']' : c;
                i++;
                while (i < n) {
                    if (backslash && c == '\'' && s.charAt(i) == '\\') { i += 2; continue; }
                    if (s.charAt(i) == close) {
                        if (close != ']' && i + 1 < n && s.charAt(i + 1) == close) { i += 2; continue; }   // подвоєна лапка — частина тексту
                        i++;
                        break;
                    }
                    i++;
                }
                i = Math.min(i, n);
                k = Kind.LITERAL;
            } else if (isWord(c)) {
                while (i < n && isWord(s.charAt(i))) i++;
                k = Kind.WORD;
            } else {
                String two = i + 1 < n ? s.substring(i, i + 2) : "";
                i += TWO_CHAR.contains(two) ? 2 : 1;
            }
            out.add(new FmtToken(s.substring(st, i), k, space));
            space = false;
        }
        return out;
    }

    private static final Set<String> CLAUSE_WORDS = Set.of("select", "from", "where", "having", "qualify", "prewhere", "window",
            "limit", "offset", "fetch", "settings", "format", "values", "set", "returning", "union", "intersect", "except", "minus");
    private static final Set<String> JOIN_MODIFIERS = Set.of("inner", "left", "right", "full", "cross", "outer", "natural", "array",
            "global", "any", "all", "asof", "semi", "anti", "paste", "straight_join");

    // Рівень форматування: свій у кожного підзапиту.
    private static final class Level {
        final int base;
        final int close;          // відступ рядка з «(» — туди стане «)»
        int inline;               // глибина дужок функцій, вікон, списків усередині рівня
        String clause = "";
        int count;                // скільки лексем уже на цьому рівні
        boolean between;          // «between x and y» — and не переносимо
        boolean joinPhrase;       // «left outer …» — до «join» вже з нового рядка

        Level(int base, int close) {
            this.base = base;
            this.close = close;
        }
    }

    private static final class Out {
        final StringBuilder b = new StringBuilder();
        final List<Level> levels = new ArrayList<>(List.of(new Level(0, 0)));
        boolean lineStart = true;

        Level top() { return levels.get(levels.size() - 1); }

        int lineIndent() {
            int k = b.lastIndexOf("\n") + 1, n = 0;
            while (k + n < b.length() && b.charAt(k + n) == ' ') n++;
            return n;
        }

        void newline(int indent) {
            while (b.length() > 0 && b.charAt(b.length() - 1) == ' ') b.setLength(b.length() - 1);
            if (b.length() > 0 && b.charAt(b.length() - 1) != '\n') b.append('\n');   // уже на новому рядку — лише відступ
            b.append(" ".repeat(Math.max(0, indent)));
            lineStart = true;
        }

        void emit(FmtToken t, boolean space) {
            if (!lineStart && space) b.append(' ');
            b.append(t.text());
            lineStart = false;
            top().count++;
        }
    }

    public static String formatSQL(String sql, DBKind kind) {
        List<FmtToken> toks = formatTokens(sql, kind);
        if (toks.isEmpty()) return null;
        Out o = new Out();
        List<Boolean> subquery = new ArrayList<>();   // для кожної відкритої «(»: true — підзапит (свій рівень)
        for (int k = 0; k < toks.size(); k++) {
            FmtToken t = toks.get(k);
            boolean top = o.top().inline == 0;
            String w = t.kind() == Kind.WORD ? t.text().toLowerCase(Locale.ROOT) : "";
            int base = o.top().base;
            if (t.kind() == Kind.LINE_COMMENT) {
                o.emit(t, true);
                String clause = o.top().clause;
                o.newline(base + (clause.equals("select") ? 7 : clause.isEmpty() ? 0 : 4));
                continue;
            }
            if (t.kind() == Kind.PUNCT && t.text().equals("(")) {
                if (Set.of("select", "with", "values").contains(word(toks, k + 1))) {
                    o.emit(t, t.spaceBefore());
                    int indent = o.lineIndent();
                    o.levels.add(new Level(indent + 4, indent));
                    subquery.add(true);
                    o.newline(indent + 4);
                } else {
                    o.emit(t, t.spaceBefore());
                    o.top().inline++;
                    subquery.add(false);
                }
                continue;
            }
            if (t.kind() == Kind.PUNCT && t.text().equals(")")) {
                boolean sub = !subquery.isEmpty() && subquery.remove(subquery.size() - 1);
                if (sub && o.levels.size() > 1) {
                    Level closed = o.levels.remove(o.levels.size() - 1);
                    o.newline(closed.close);
                    o.emit(t, false);
                } else {
                    o.top().inline = Math.max(0, o.top().inline - 1);
                    o.emit(t, t.spaceBefore());
                }
                continue;
            }
            if (t.kind() == Kind.PUNCT && t.text().equals(",") && top) {
                boolean wasLineStart = o.lineStart;   // кома на початку рядка — стиль «, колонка»: далі без переносу
                o.emit(t, t.spaceBefore());
                String clause = o.top().clause;
                if (!wasLineStart && clause.equals("select")) o.newline(base + 7);
                else if (!wasLineStart && clause.equals("with")) o.newline(base);
                continue;
            }
            if (t.kind() == Kind.PUNCT && t.text().equals(";")) {
                o.emit(t, false);
                o.levels.clear();
                o.levels.add(new Level(0, 0));
                subquery.clear();
                o.newline(0);
                continue;
            }
            if (t.kind() != Kind.WORD || !top) {
                o.emit(t, t.spaceBefore());
                continue;
            }
            Level lv = o.top();
            boolean startsLine = false;
            // слово впритул до «(» — функція (format(…), values(…), left(…)), а не речення
            boolean call = k + 1 < toks.size() && toks.get(k + 1).text().equals("(") && !toks.get(k + 1).spaceBefore();
            if (w.equals("with") && lv.count == 0) {
                lv.clause = "with";
            } else if ((w.equals("group") || w.equals("order")) && word(toks, k + 1).equals("by")) {
                startsLine = true;
                lv.clause = w;
            } else if (w.equals("join")) {
                startsLine = !lv.joinPhrase;
                lv.joinPhrase = false;
                lv.clause = "join";
            } else if (JOIN_MODIFIERS.contains(w) && !(w.equals("all") && Set.of("union", "intersect", "except").contains(lv.clause))) {
                int j = k;
                while (JOIN_MODIFIERS.contains(word(toks, j))) j++;
                if (word(toks, j).equals("join")) {
                    startsLine = !lv.joinPhrase;
                    lv.joinPhrase = true;
                }
            } else if (CLAUSE_WORDS.contains(w) && lv.count > 0 && !call) {
                startsLine = true;
                lv.clause = w;
            } else if (w.equals("select")) {
                lv.clause = "select";
            } else if ((w.equals("and") || w.equals("or")) && Set.of("where", "having", "qualify", "prewhere").contains(lv.clause) && !lv.between) {
                o.newline(base + 2);
                o.emit(t, false);
                continue;
            }
            if (w.equals("between")) lv.between = true;
            else if (w.equals("and") && lv.between) lv.between = false;
            // після union / intersect / except — select з нового рядка
            if (w.equals("select") && lv.count > 0
                    && Set.of("union", "intersect", "except", "minus", "all", "distinct").contains(toks.get(Math.max(0, k - 1)).text().toLowerCase(Locale.ROOT))) {
                startsLine = true;
            }
            if (startsLine) o.newline(base);   // і після коментаря: рядок уже новий, але відступ — свого речення
            o.emit(t, t.spaceBefore());
        }
        String[] lines = o.b.toString().split("\n", -1);
        StringBuilder r = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) r.append('\n');
            r.append(lines[i].replaceAll(" +$", ""));
        }
        String result = SqlText.trim(r.toString());
        // запобіжник: ті самі лексеми в тому самому порядку — змінились лише пробіли й переноси
        if (!texts(formatTokens(result, kind)).equals(texts(toks))) return null;
        return result;
    }

    private static String word(List<FmtToken> toks, int k) {
        return k < toks.size() && toks.get(k).kind() == Kind.WORD ? toks.get(k).text().toLowerCase(Locale.ROOT) : "";
    }

    private static List<String> texts(List<FmtToken> toks) {
        List<String> out = new ArrayList<>(toks.size());
        for (FmtToken t : toks) out.add(t.text());
        return out;
    }
}
