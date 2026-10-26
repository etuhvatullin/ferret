package ferret.core;

import ferret.core.Model.DBKind;
import ferret.core.SqlText.Suggestion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

// Підказки SQL під кожну базу: ключові слова й конструкції діалекту, функції з аргументами й коротким поясненням.
// Списки — свої, з документації баз (ClickHouse, Trino, PostgreSQL, MS SQL, MySQL, Oracle); не вичерпні —
// найуживаніше в аналітиці. Решта баз через драйвер — спільний набір (Redshift, DuckDB, Vertica — ще й Postgres-ові).
// Перенесено зі Swift-Ferret (Core/Dialect.swift) без змін у списках.
public final class Dialect {
    private Dialect() { }

    public record SQLFunction(String name, String args, String about) { }

    // Трійки «назва, аргументи, пояснення» — у список функцій.
    private static List<SQLFunction> fns(String... t) {
        List<SQLFunction> out = new ArrayList<>(t.length / 3);
        for (int i = 0; i + 2 < t.length; i += 3) out.add(new SQLFunction(t[i], t[i + 1], t[i + 2]));
        return List.copyOf(out);
    }

    private static final List<String> COMMON_KEYWORDS = List.of(
            "SELECT", "FROM", "WHERE", "GROUP BY", "ORDER BY", "HAVING", "LIMIT", "OFFSET", "WITH", "AS", "AND", "OR", "NOT",
            "IN", "IS NULL", "IS NOT NULL", "LIKE", "BETWEEN", "CASE", "WHEN", "THEN", "ELSE", "END", "DISTINCT", "UNION ALL",
            "JOIN", "LEFT JOIN", "RIGHT JOIN", "INNER JOIN", "FULL JOIN", "CROSS JOIN", "ON", "USING", "EXISTS", "ASC", "DESC",
            "NULLS FIRST", "NULLS LAST", "OVER", "PARTITION BY", "ROWS BETWEEN", "UNBOUNDED PRECEDING", "CURRENT ROW",
            "INTERVAL", "CAST", "EXPLAIN", "DESCRIBE", "SHOW TABLES");

    private static List<String> plus(List<String> base, String... more) {
        List<String> out = new ArrayList<>(base);
        out.addAll(List.of(more));
        return List.copyOf(out);
    }

    private static List<String> without(List<String> base, String... drop) {
        List<String> out = new ArrayList<>(base);
        out.removeAll(List.of(drop));
        return out;
    }

    public static List<String> dialectKeywords(DBKind kind) {
        return switch (kind) {
            case CLICKHOUSE -> plus(COMMON_KEYWORDS,
                    "FINAL", "PREWHERE", "SAMPLE", "ARRAY JOIN", "LEFT ARRAY JOIN", "GLOBAL IN", "GLOBAL JOIN", "ANY LEFT JOIN",
                    "ANY INNER JOIN", "ASOF JOIN", "SEMI JOIN", "ANTI JOIN", "LIMIT BY", "WITH TOTALS", "WITH ROLLUP", "WITH CUBE",
                    "SETTINGS", "FORMAT", "ILIKE", "EXCEPT", "SHOW DATABASES", "SHOW CREATE TABLE", "EXISTS TABLE");
            case TRINO -> plus(COMMON_KEYWORDS,
                    "UNNEST", "CROSS JOIN UNNEST", "WITH ORDINALITY", "TABLESAMPLE BERNOULLI", "FETCH FIRST", "ROWS ONLY",
                    "AT TIME ZONE", "TRY_CAST", "LATERAL", "GROUPING SETS", "ROLLUP", "CUBE", "SHOW CATALOGS", "SHOW SCHEMAS",
                    "SHOW COLUMNS", "SHOW CREATE TABLE", "ROW_NUMBER() OVER", "TIMESTAMP", "DATE");
            case POSTGRES -> plus(COMMON_KEYWORDS,
                    "DISTINCT ON", "ILIKE", "RETURNING", "LATERAL", "FILTER (WHERE", "WITH RECURSIVE", "FETCH FIRST", "ROWS ONLY",
                    "ON CONFLICT", "SIMILAR TO", "IS DISTINCT FROM", "TIMESTAMPTZ", "JSONB");
            case MSSQL -> plus(without(COMMON_KEYWORDS, "LIMIT", "SHOW TABLES", "DESCRIBE"),
                    "TOP", "WITH (NOLOCK)", "CROSS APPLY", "OUTER APPLY", "OFFSET", "FETCH NEXT", "ROWS ONLY", "PIVOT", "UNPIVOT",
                    "OPTION (RECOMPILE)", "DECLARE", "NVARCHAR", "DATETIME2", "INFORMATION_SCHEMA.TABLES", "INFORMATION_SCHEMA.COLUMNS");
            case MYSQL, MARIADB -> plus(COMMON_KEYWORDS,
                    "STRAIGHT_JOIN", "REGEXP", "DIV", "SHOW DATABASES", "SHOW CREATE TABLE", "SHOW PROCESSLIST", "SHOW COLUMNS FROM",
                    "SEPARATOR", "UNSIGNED", "INFORMATION_SCHEMA.TABLES");
            case ORACLE -> plus(without(COMMON_KEYWORDS, "LIMIT", "SHOW TABLES"),
                    "FETCH FIRST", "ROWS ONLY", "ROWNUM", "CONNECT BY", "START WITH", "PRIOR", "FROM DUAL", "MINUS", "SYSDATE",
                    "SYSTIMESTAMP", "WITHIN GROUP", "ALL_TABLES", "ALL_TAB_COLUMNS");
            default -> COMMON_KEYWORDS;
        };
    }

    public static List<SQLFunction> dialectFunctions(DBKind kind) {
        return switch (kind) {
            case CLICKHOUSE -> clickhouseFunctions;
            case TRINO -> trinoFunctions;
            case POSTGRES, REDSHIFT, DUCKDB, VERTICA -> postgresFunctions;
            case MSSQL -> mssqlFunctions;
            case MYSQL, MARIADB -> mysqlFunctions;
            case ORACLE -> oracleFunctions;
            default -> commonFunctions;
        };
    }

    private static final List<SQLFunction> mssqlFunctions = fns(
            "getdate", "", "зараз", "sysdatetime", "", "зараз, точніше", "dateadd", "day, n, d", "додати до дати",
            "datediff", "day, a, b", "різниця дат", "datepart", "year, d", "", "datename", "month, d", "", "eomonth", "d", "кінець місяця",
            "datefromparts", "y, m, d", "", "format", "x, 'yyyy-MM-dd'", "", "convert", "type, x, style", "", "try_convert", "type, x", "",
            "try_cast", "x AS type", "", "isnull", "x, y", "y, якщо x — NULL", "coalesce", "a, b, …", "", "nullif", "a, b", "",
            "iif", "cond, a, b", "", "len", "s", "довжина", "left", "s, n", "", "right", "s, n", "", "substring", "s, start, len", "",
            "charindex", "sub, s", "позиція", "replace", "s, from, to", "", "upper", "s", "", "lower", "s", "", "trim", "s", "",
            "ltrim", "s", "", "rtrim", "s", "", "concat", "a, b, …", "", "concat_ws", "', ', a, b", "", "string_agg", "x, ', '", "склеїти в рядок",
            "stuff", "s, start, len, new", "", "year", "d", "", "month", "d", "", "day", "d", "", "round", "x, n", "",
            "floor", "x", "", "ceiling", "x", "", "abs", "x", "", "count", "*", "", "count_big", "*", "", "sum", "x", "",
            "avg", "x", "", "min", "x", "", "max", "x", "", "row_number", "", "номер рядка у вікні", "rank", "", "",
            "dense_rank", "", "", "lag", "x, offset", "", "lead", "x, offset", "", "newid", "", "", "json_value", "json, '$.k'", "",
            "openjson", "json", "JSON у рядки");

    private static final List<SQLFunction> mysqlFunctions = fns(
            "now", "", "зараз", "curdate", "", "сьогодні", "date_format", "d, '%Y-%m-%d'", "", "str_to_date", "s, '%Y-%m-%d'", "",
            "date_add", "d, INTERVAL 1 DAY", "", "date_sub", "d, INTERVAL 1 DAY", "", "datediff", "a, b", "різниця в днях",
            "timestampdiff", "unit, a, b", "", "unix_timestamp", "d", "", "from_unixtime", "ts", "", "last_day", "d", "",
            "year", "d", "", "month", "d", "", "day", "d", "", "week", "d", "", "ifnull", "x, y", "y, якщо x — NULL",
            "if", "cond, a, b", "", "coalesce", "a, b, …", "", "nullif", "a, b", "", "concat", "a, b, …", "", "concat_ws", "', ', a, b", "",
            "group_concat", "x SEPARATOR ', '", "склеїти в рядок", "substring_index", "s, ',', 1", "", "substring", "s, start, len", "",
            "length", "s", "у байтах", "char_length", "s", "у символах", "lower", "s", "", "upper", "s", "", "trim", "s", "",
            "replace", "s, from, to", "", "round", "x, n", "", "floor", "x", "", "ceil", "x", "", "abs", "x", "",
            "json_extract", "json, '$.k'", "", "json_unquote", "x", "", "cast", "x AS type", "", "count", "*", "", "sum", "x", "",
            "avg", "x", "", "min", "x", "", "max", "x", "", "row_number", "", "номер рядка у вікні", "rank", "", "",
            "lag", "x, offset", "", "lead", "x, offset", "");

    private static final List<SQLFunction> oracleFunctions = fns(
            "nvl", "x, y", "y, якщо x — NULL", "nvl2", "x, a, b", "", "decode", "x, a, ra, …, default", "", "coalesce", "a, b, …", "",
            "to_char", "d, 'YYYY-MM-DD'", "", "to_date", "s, 'YYYY-MM-DD'", "", "to_number", "s", "", "trunc", "d, 'MM'", "обрізати дату",
            "add_months", "d, n", "", "months_between", "a, b", "", "last_day", "d", "", "extract", "YEAR FROM d", "",
            "substr", "s, start, len", "", "instr", "s, sub", "позиція", "length", "s", "", "lower", "s", "", "upper", "s", "",
            "trim", "s", "", "replace", "s, from, to", "", "regexp_like", "s, 'шаблон'", "", "regexp_substr", "s, 'шаблон'", "",
            "listagg", "x, ', '", "склеїти в рядок (WITHIN GROUP)", "round", "x, n", "", "floor", "x", "", "ceil", "x", "",
            "abs", "x", "", "mod", "a, b", "", "count", "*", "", "sum", "x", "", "avg", "x", "", "min", "x", "", "max", "x", "",
            "row_number", "", "номер рядка у вікні", "rank", "", "", "dense_rank", "", "", "lag", "x, offset", "", "lead", "x, offset", "");

    private static final List<SQLFunction> commonFunctions = fns(
            "count", "*", "", "sum", "x", "", "avg", "x", "", "min", "x", "", "max", "x", "", "coalesce", "a, b, …", "",
            "nullif", "a, b", "", "cast", "x AS type", "", "upper", "s", "", "lower", "s", "", "trim", "s", "",
            "substring", "s, start, len", "", "replace", "s, from, to", "", "concat", "a, b, …", "", "round", "x, n", "",
            "abs", "x", "", "floor", "x", "", "ceil", "x", "", "current_date", "", "", "current_timestamp", "", "",
            "row_number", "", "номер рядка у вікні", "rank", "", "", "dense_rank", "", "", "lag", "x, offset", "",
            "lead", "x, offset", "", "first_value", "x", "", "last_value", "x", "");

    private static final List<SQLFunction> clickhouseFunctions = fns(
            // агрегати
            "count", "", "кількість рядків", "countIf", "cond", "кількість, де умова", "sum", "x", "сума",
            "sumIf", "x, cond", "сума, де умова", "avg", "x", "середнє", "avgIf", "x, cond", "середнє, де умова",
            "min", "x", "", "max", "x", "", "minIf", "x, cond", "", "maxIf", "x, cond", "",
            "uniq", "x", "приблизна кількість унікальних", "uniqExact", "x", "точна кількість унікальних",
            "uniqIf", "x, cond", "унікальні, де умова", "uniqExactIf", "x, cond", "точні унікальні, де умова",
            "any", "x", "будь-яке значення", "anyLast", "x", "останнє значення",
            "argMax", "arg, val", "arg при максимумі val", "argMin", "arg, val", "arg при мінімумі val",
            "groupArray", "x", "значення в масив", "groupUniqArray", "x", "унікальні в масив",
            "quantile", "level)(x", "квантиль, напр. quantile(0.5)(x)", "quantiles", "l1, l2)(x", "кілька квантилів",
            "quantileExact", "level)(x", "точний квантиль", "median", "x", "медіана", "topK", "N)(x", "найчастіші N значень",
            "sumMap", "keys, values", "суми за ключами", "stddevPop", "x", "", "varPop", "x", "", "corr", "x, y", "кореляція",
            // дати й час
            "now", "", "зараз", "today", "", "сьогодні", "yesterday", "", "вчора",
            "toDate", "x", "у дату", "toDateTime", "x", "у дату-час", "toDate32", "x", "", "toDateTime64", "x, precision", "",
            "toStartOfDay", "dt", "початок дня", "toStartOfWeek", "dt, mode", "початок тижня", "toMonday", "dt", "понеділок тижня",
            "toStartOfMonth", "dt", "перше число місяця", "toStartOfQuarter", "dt", "початок кварталу",
            "toStartOfYear", "dt", "початок року", "toStartOfHour", "dt", "початок години", "toStartOfMinute", "dt", "",
            "toStartOfInterval", "dt, INTERVAL n unit", "початок довільного інтервалу",
            "toYear", "dt", "", "toMonth", "dt", "", "toDayOfMonth", "dt", "", "toDayOfWeek", "dt", "1 — понеділок",
            "toHour", "dt", "", "toYYYYMM", "dt", "рік і місяць числом", "toYYYYMMDD", "dt", "",
            "dateDiff", "'unit', start, end", "різниця дат: 'day', 'month'…", "dateAdd", "unit, n, dt", "", "dateSub", "unit, n, dt", "",
            "addDays", "dt, n", "", "addMonths", "dt, n", "", "subtractDays", "dt, n", "",
            "formatDateTime", "dt, '%Y-%m-%d'", "дата в текст за шаблоном", "parseDateTimeBestEffort", "s", "розпізнати дату з тексту",
            "toUnixTimestamp", "dt", "", "fromUnixTimestamp", "n", "", "toTimeZone", "dt, 'Europe/Kyiv'", "",
            // типи
            "toString", "x", "", "toInt32", "x", "", "toInt64", "x", "", "toUInt32", "x", "", "toUInt64", "x", "",
            "toFloat64", "x", "", "toDecimal64", "x, scale", "", "toUUID", "s", "", "toTypeName", "x", "тип значення",
            "toInt64OrZero", "s", "число або 0", "toInt64OrNull", "s", "число або NULL", "toFloat64OrZero", "s", "",
            "toDateOrNull", "s", "дата або NULL",
            // умови й NULL
            "if", "cond, then, else", "", "multiIf", "c1, v1, c2, v2, …, else", "кілька умов", "ifNull", "x, alt", "",
            "nullIf", "x, y", "", "coalesce", "x, y, …", "перше не NULL", "isNull", "x", "", "isNotNull", "x", "",
            "assumeNotNull", "x", "", "empty", "x", "порожній рядок/масив", "notEmpty", "x", "",
            // рядки
            "length", "x", "довжина", "lower", "s", "", "upper", "s", "", "lowerUTF8", "s", "нижній регістр (кирилиця)",
            "upperUTF8", "s", "", "lengthUTF8", "s", "довжина в символах", "concat", "s1, s2, …", "",
            "substring", "s, offset, length", "", "position", "s, needle", "", "positionCaseInsensitive", "s, needle", "",
            "replaceAll", "s, from, to", "", "replaceOne", "s, from, to", "", "replaceRegexpAll", "s, regexp, to", "",
            "match", "s, regexp", "відповідає регулярному виразу", "extract", "s, regexp", "перший збіг",
            "extractAll", "s, regexp", "усі збіги", "splitByChar", "',', s", "розбити за символом",
            "splitByString", "', ', s", "", "arrayStringConcat", "arr, ', '", "масив у рядок", "trimBoth", "s", "",
            "leftPad", "s, len, ' '", "", "startsWith", "s, prefix", "", "endsWith", "s, suffix", "", "format", "pattern, …", "",
            // масиви
            "arrayJoin", "arr", "розгорнути масив у рядки", "has", "arr, x", "масив містить x", "hasAny", "arr1, arr2", "",
            "hasAll", "arr1, arr2", "", "indexOf", "arr, x", "", "arrayMap", "x -> …, arr", "", "arrayFilter", "x -> …, arr", "",
            "arrayExists", "x -> …, arr", "", "arrayCount", "x -> …, arr", "", "arraySum", "arr", "", "arraySort", "arr", "",
            "arrayDistinct", "arr", "", "arrayUniq", "arr", "", "arraySlice", "arr, offset, length", "", "range", "n", "",
            // JSON
            "JSONExtractString", "json, 'key'", "", "JSONExtractInt", "json, 'key'", "", "JSONExtractFloat", "json, 'key'", "",
            "JSONExtractBool", "json, 'key'", "", "JSONExtractRaw", "json, 'key'", "", "JSONHas", "json, 'key'", "",
            "JSONLength", "json", "", "JSONExtractArrayRaw", "json, 'key'", "", "visitParamExtractString", "json, 'key'", "",
            // математика й інше
            "round", "x, n", "", "floor", "x", "", "ceil", "x", "", "abs", "x", "", "greatest", "a, b", "", "least", "a, b", "",
            "intDiv", "a, b", "ціле ділення", "modulo", "a, b", "", "sqrt", "x", "", "pow", "x, y", "", "log", "x", "",
            "formatReadableSize", "bytes", "байти по-людськи", "formatReadableQuantity", "n", "",
            "generateUUIDv4", "", "", "rand", "", "", "runningDifference", "x", "", "neighbor", "x, offset", "",
            "dictGet", "'dict', 'attr', key", "значення зі словника", "dictGetOrDefault", "'dict', 'attr', key, default", "",
            "cityHash64", "x", "", "sipHash64", "x", "", "tuple", "a, b, …", "", "mapKeys", "m", "", "mapValues", "m", "",
            "currentDatabase", "", "", "row_number", "", "номер рядка у вікні", "rank", "", "", "dense_rank", "", "",
            "lagInFrame", "x, offset", "попереднє значення у вікні", "leadInFrame", "x, offset", "");

    private static final List<SQLFunction> trinoFunctions = fns(
            "count", "*", "кількість рядків", "count_if", "cond", "кількість, де умова", "sum", "x", "", "avg", "x", "",
            "min", "x", "", "max", "x", "", "approx_distinct", "x", "приблизна кількість унікальних",
            "approx_percentile", "x, 0.5", "приблизний перцентиль", "arbitrary", "x", "будь-яке значення", "any_value", "x", "",
            "array_agg", "x", "значення в масив", "map_agg", "key, value", "", "max_by", "x, y", "x при максимумі y",
            "min_by", "x, y", "x при мінімумі y", "bool_and", "cond", "", "bool_or", "cond", "", "stddev", "x", "",
            "variance", "x", "", "corr", "x, y", "", "histogram", "x", "", "listagg", "x, ', '", "рядки через роздільник",
            "geometric_mean", "x", "",
            "current_date", "", "сьогодні", "current_timestamp", "", "зараз", "now", "", "",
            "date_trunc", "'month', ts", "обрізати до дня/тижня/місяця", "date_add", "'day', n, ts", "",
            "date_diff", "'day', start, end", "різниця дат", "date_format", "ts, '%Y-%m-%d'", "дата в текст (як у MySQL)",
            "date_parse", "s, '%Y-%m-%d'", "текст у дату", "format_datetime", "ts, 'yyyy-MM-dd'", "",
            "parse_datetime", "s, 'yyyy-MM-dd'", "", "from_iso8601_timestamp", "s", "", "from_iso8601_date", "s", "",
            "to_iso8601", "ts", "", "from_unixtime", "n", "", "to_unixtime", "ts", "", "day", "ts", "",
            "day_of_week", "ts", "1 — понеділок", "month", "ts", "", "year", "ts", "", "quarter", "ts", "", "hour", "ts", "",
            "week", "ts", "", "last_day_of_month", "ts", "", "at_timezone", "ts, 'Europe/Kyiv'", "", "date", "x", "у дату",
            "extract", "unit FROM ts", "",
            "cast", "x AS type", "", "try_cast", "x AS type", "перетворити або NULL", "try", "expr", "помилка → NULL",
            "typeof", "x", "тип значення", "format", "'%s', …", "",
            "if", "cond, then, else", "", "coalesce", "x, y, …", "перше не NULL", "nullif", "x, y", "", "greatest", "a, b", "",
            "least", "a, b", "",
            "length", "s", "", "lower", "s", "", "upper", "s", "", "concat", "s1, s2, …", "", "concat_ws", "', ', s1, s2", "",
            "substr", "s, start, length", "", "strpos", "s, needle", "", "replace", "s, from, to", "",
            "regexp_replace", "s, regexp, to", "", "regexp_extract", "s, regexp, group", "", "regexp_extract_all", "s, regexp", "",
            "regexp_like", "s, regexp", "", "split", "s, ','", "рядок у масив", "split_part", "s, ',', n", "", "trim", "s", "",
            "lpad", "s, len, ' '", "", "starts_with", "s, prefix", "", "reverse", "s", "", "levenshtein_distance", "a, b", "",
            "url_extract_host", "url", "", "url_extract_parameter", "url, 'name'", "",
            "cardinality", "arr", "розмір масиву", "element_at", "arr, i", "", "contains", "arr, x", "",
            "array_join", "arr, ', '", "масив у рядок", "array_distinct", "arr", "", "array_sort", "arr", "", "array_max", "arr", "",
            "array_position", "arr, x", "", "filter", "arr, x -> …", "", "transform", "arr, x -> …", "",
            "reduce", "arr, 0, (s, x) -> …, s -> s", "", "flatten", "arr", "", "sequence", "start, stop", "", "slice", "arr, start, length", "",
            "map_keys", "m", "", "map_values", "m", "",
            "json_extract", "json, '$.key'", "", "json_extract_scalar", "json, '$.key'", "значення з JSON текстом",
            "json_parse", "s", "", "json_format", "json", "", "json_array_length", "json", "", "json_value", "json, 'lax $.key'", "",
            "round", "x, n", "", "floor", "x", "", "ceil", "x", "", "abs", "x", "", "mod", "a, b", "", "power", "x, y", "",
            "sqrt", "x", "", "ln", "x", "", "random", "", "", "truncate", "x", "", "width_bucket", "x, low, high, n", "",
            "row_number", "", "номер рядка у вікні", "rank", "", "", "dense_rank", "", "", "ntile", "n", "",
            "lag", "x, offset", "попереднє значення у вікні", "lead", "x, offset", "", "first_value", "x", "", "last_value", "x", "",
            "percent_rank", "", "", "cume_dist", "", "");

    private static final List<SQLFunction> postgresFunctions = fns(
            "count", "*", "кількість рядків", "sum", "x", "", "avg", "x", "", "min", "x", "", "max", "x", "",
            "string_agg", "x, ', '", "рядки через роздільник", "array_agg", "x", "", "json_agg", "x", "", "jsonb_agg", "x", "",
            "bool_and", "cond", "", "bool_or", "cond", "", "percentile_cont", "0.5) WITHIN GROUP (ORDER BY x", "медіана й перцентилі",
            "mode", ") WITHIN GROUP (ORDER BY x", "найчастіше значення", "stddev", "x", "", "variance", "x", "",
            "now", "", "зараз", "current_date", "", "", "current_timestamp", "", "", "date_trunc", "'month', ts", "",
            "date_part", "'day', ts", "", "extract", "day FROM ts", "", "age", "ts1, ts2", "різниця як інтервал",
            "to_char", "ts, 'YYYY-MM-DD'", "дата/число в текст", "to_date", "s, 'YYYY-MM-DD'", "", "to_timestamp", "s, 'YYYY-MM-DD'", "",
            "make_date", "y, m, d", "", "generate_series", "start, stop, step", "ряд чисел чи дат",
            "coalesce", "x, y, …", "перше не NULL", "nullif", "x, y", "", "greatest", "a, b", "", "least", "a, b", "",
            "length", "s", "", "lower", "s", "", "upper", "s", "", "concat", "s1, s2, …", "", "concat_ws", "', ', s1, s2", "",
            "substring", "s FROM start FOR length", "", "position", "needle IN s", "", "replace", "s, from, to", "",
            "regexp_replace", "s, regexp, to, 'g'", "", "regexp_match", "s, regexp", "", "split_part", "s, ',', n", "",
            "string_to_array", "s, ','", "", "array_to_string", "arr, ', '", "", "trim", "s", "", "lpad", "s, len, ' '", "",
            "left", "s, n", "", "right", "s, n", "", "initcap", "s", "", "format", "'%s', …", "", "md5", "s", "",
            "jsonb_extract_path_text", "json, 'key'", "", "jsonb_array_elements", "json", "", "jsonb_array_length", "json", "",
            "jsonb_object_keys", "json", "", "jsonb_build_object", "'k', v, …", "", "to_jsonb", "x", "", "jsonb_typeof", "json", "",
            "unnest", "arr", "масив у рядки", "array_length", "arr, 1", "", "cardinality", "arr", "", "array_position", "arr, x", "",
            "round", "x, n", "", "floor", "x", "", "ceil", "x", "", "abs", "x", "", "mod", "a, b", "", "power", "x, y", "",
            "sqrt", "x", "", "random", "", "", "trunc", "x", "",
            "row_number", "", "номер рядка у вікні", "rank", "", "", "dense_rank", "", "", "ntile", "n", "",
            "lag", "x, offset", "попереднє значення у вікні", "lead", "x, offset", "", "first_value", "x", "", "last_value", "x", "");

    // Підказки під час набору: функції діалекту (вставляються з дужкою), ключові слова (у регістрі, яким пишеш),
    // імена з дерева (таблиці, колонки з типом). Збіг — з початку слова, без урахування регістру; коротші — вище.
    // depth — скільки рівнів до колонок у цієї бази (0 або менше — як за замовчуванням для типу).
    public static List<Suggestion> sqlSuggestions(String fragment, DBKind kind, List<SchemaItem> names, int depth, int limit) {
        String f = fragment.toLowerCase(Locale.ROOT);
        if (f.isEmpty()) return List.of();
        boolean upper = !fragment.equals(f);
        List<Suggestion> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        java.util.function.Consumer<Suggestion> add = s -> {
            if (!s.label().toLowerCase(Locale.ROOT).equals(f) && seen.add(s.kind().name() + s.label().toLowerCase(Locale.ROOT))) out.add(s);
        };
        for (SQLFunction fn : dialectFunctions(kind)) {
            if (!fn.name().toLowerCase(Locale.ROOT).startsWith(f)) continue;
            add.accept(new Suggestion(fn.name() + "(", fn.name(), "(" + fn.args() + ")" + (fn.about().isEmpty() ? "" : " — " + fn.about()),
                    false, Suggestion.Kind.FUNCTION));
        }
        for (String kw : dialectKeywords(kind)) {
            if (!kw.toLowerCase(Locale.ROOT).startsWith(f)) continue;
            String text = upper ? kw : kw.toLowerCase(Locale.ROOT);
            add.accept(new Suggestion(text, text, "ключове слово", false, Suggestion.Kind.KEYWORD));
        }
        int d = depth > 0 ? depth : SqlText.schemaDepth(kind);
        for (SchemaItem n : names) {
            if (!n.name().toLowerCase(Locale.ROOT).startsWith(f)) continue;
            boolean column = n.path().size() == d, table = n.path().size() == d - 1;
            String detail = column ? "колонка" + (n.detail() != null ? " · " + n.detail() : "")
                    : table ? "таблиця · " + String.join(".", n.path().subList(0, n.path().size() - 1)) : "схема";
            add.accept(new Suggestion(SqlText.qualifiedName(kind, List.of(n.name())), n.name(), detail, table,
                    column ? Suggestion.Kind.COLUMN : table ? Suggestion.Kind.TABLE : Suggestion.Kind.CONTAINER));
        }
        out.sort(Comparator.comparingInt((Suggestion s) -> s.label().length()).thenComparing(Suggestion::label));
        return out.size() > limit ? List.copyOf(out.subList(0, limit)) : out;
    }

    public static List<Suggestion> sqlSuggestions(String fragment, DBKind kind, List<SchemaItem> names) {
        return sqlSuggestions(fragment, kind, names, 0, 40);
    }
}
