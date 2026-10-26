package ferret.core;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.Locale;
import java.util.regex.Pattern;

// Значення клітинок результату (усі — рядки з драйвера): чи колонка числова за назвою типу, порівняння для сортування
// (числа — як числа, інакше «10» стало б перед «9»), пошук у рядку без регістру.
public final class Values {
    private Values() { }

    // Типи всіх баз: Trino «bigint», «decimal(10,2)»; ClickHouse «Nullable(UInt64)», «LowCardinality(Float64)»;
    // Postgres «int8», «numeric», «float8»; MS SQL «money». «interval» і «point» — не числа.
    static final Pattern NUMERIC = Pattern.compile(
            "(u?int\\d*|tinyint|smallint|mediumint|integer|bigint|hugeint|float\\d*|double( precision)?|real|decimal.*|numeric.*|number.*|money|smallmoney|serial\\d*|bigserial|smallserial)");

    public static boolean numericType(String type) {
        if (type == null) return false;
        String t = type.trim().toLowerCase(Locale.ROOT);
        for (boolean again = true; again; ) {   // обгортки ClickHouse: Nullable(…), LowCardinality(Nullable(…))
            again = false;
            for (String w : new String[] {"nullable(", "lowcardinality("}) {
                if (t.startsWith(w) && t.endsWith(")")) { t = t.substring(w.length(), t.length() - 1).trim(); again = true; }
            }
        }
        t = t.replaceFirst("\\s+unsigned$", "");
        return NUMERIC.matcher(t).matches();
    }

    public static final Comparator<Object> TEXT = (a, b) -> String.valueOf(a).compareToIgnoreCase(String.valueOf(b));

    // Числа порівнюються як числа; те, що числом не читається (NaN, inf, текст у числовій колонці), — після чисел.
    public static final Comparator<Object> NUMBERS = (a, b) -> {
        BigDecimal x = number(a), y = number(b);
        if (x != null && y != null) return x.compareTo(y);
        if (x != null) return -1;
        if (y != null) return 1;
        return TEXT.compare(a, b);
    };

    static BigDecimal number(Object o) {
        if (o == null) return null;
        try {
            return new BigDecimal(o.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // Пошук у результаті: текст трапляється в значенні, без регістру. needle — уже в нижньому регістрі.
    public static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }
}
