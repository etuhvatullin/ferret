package ferret.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

// Найпростіший JSON без залежностей: обʼєкти (LinkedHashMap), масиви (ArrayList), рядки, числа (Long / Double),
// true / false / null. Запис — з відступами й ключами за абеткою, як писав Swift-Ferret (файли читаються обома).
public final class Json {
    private final String s;
    private int i;

    private Json(String s) { this.s = s; }

    public static Object parse(String text) {
        Json j = new Json(text);
        Object v = j.value();
        j.ws();
        if (j.i < j.s.length()) throw new IllegalArgumentException("зайве після JSON на позиції " + j.i);
        return v;
    }

    private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

    private char at() {
        if (i >= s.length()) throw new IllegalArgumentException("JSON обірвався");
        return s.charAt(i);
    }

    private Object value() {
        ws();
        char c = at();
        if (c == '{') {
            i++;
            Map<String, Object> m = new LinkedHashMap<>();
            ws();
            if (at() == '}') { i++; return m; }
            while (true) {
                ws();
                String k = string();
                ws();
                if (at() != ':') throw new IllegalArgumentException("очікувалась «:» на позиції " + i);
                i++;
                m.put(k, value());
                ws();
                char e = at();
                i++;
                if (e == '}') return m;
                if (e != ',') throw new IllegalArgumentException("очікувалась «,» на позиції " + (i - 1));
            }
        }
        if (c == '[') {
            i++;
            List<Object> l = new ArrayList<>();
            ws();
            if (at() == ']') { i++; return l; }
            while (true) {
                l.add(value());
                ws();
                char e = at();
                i++;
                if (e == ']') return l;
                if (e != ',') throw new IllegalArgumentException("очікувалась «,» на позиції " + (i - 1));
            }
        }
        if (c == '"') return string();
        if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
        if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
        if (s.startsWith("null", i)) { i += 4; return null; }
        int st = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        String n = s.substring(st, i);
        if (n.isEmpty()) throw new IllegalArgumentException("незрозумілий символ «" + c + "» на позиції " + i);
        return n.contains(".") || n.contains("e") || n.contains("E") ? (Object) Double.parseDouble(n) : (Object) Long.parseLong(n);
    }

    private String string() {
        if (at() != '"') throw new IllegalArgumentException("очікувався рядок на позиції " + i);
        i++;
        StringBuilder b = new StringBuilder();
        while (true) {
            char c = at();
            i++;
            if (c == '"') return b.toString();
            if (c != '\\') { b.append(c); continue; }
            char e = at();
            i++;
            switch (e) {
                case 'n' -> b.append('\n');
                case 't' -> b.append('\t');
                case 'r' -> b.append('\r');
                case 'b' -> b.append('\b');
                case 'f' -> b.append('\f');
                case 'u' -> { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                default -> b.append(e);
            }
        }
    }

    // MARK: запис

    public static String write(Object o) { return write(o, false); }

    public static String pretty(Object o) { return write(o, true); }

    private static String write(Object o, boolean pretty) {
        StringBuilder b = new StringBuilder();
        write(b, o, pretty, 0);
        return b.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder b, Object o, boolean pretty, int level) {
        if (o == null) { b.append("null"); return; }
        if (o instanceof Map<?, ?> raw) {
            Map<String, Object> m = pretty ? new TreeMap<>((Map<String, Object>) raw) : (Map<String, Object>) raw;
            if (m.isEmpty()) { b.append("{}"); return; }
            b.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (!first) b.append(',');
                first = false;
                if (pretty) newline(b, level + 1);
                quote(b, e.getKey());
                b.append(pretty ? " : " : ":");
                write(b, e.getValue(), pretty, level + 1);
            }
            if (pretty) newline(b, level);
            b.append('}');
        } else if (o instanceof List<?> l) {
            if (l.isEmpty()) { b.append("[]"); return; }
            b.append('[');
            boolean first = true;
            for (Object v : l) {
                if (!first) b.append(',');
                first = false;
                if (pretty) newline(b, level + 1);
                write(b, v, pretty, level + 1);
            }
            if (pretty) newline(b, level);
            b.append(']');
        } else if (o instanceof Boolean || o instanceof Number) {
            b.append(o);
        } else {
            quote(b, String.valueOf(o));
        }
    }

    private static void newline(StringBuilder b, int level) {
        b.append('\n');
        b.append("  ".repeat(level));
    }

    private static void quote(StringBuilder b, String s) {
        b.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) b.append(String.format("\\u%04x", (int) c)); else b.append(c);
                }
            }
        }
        b.append('"');
    }

    // MARK: зручні перетворення

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object o) { return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>(); }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Object o) { return o instanceof List ? (List<Object>) o : new ArrayList<>(); }

    public static String str(Object o) { return o == null ? null : String.valueOf(o); }

    public static String str(Object o, String fallback) { return o == null ? fallback : String.valueOf(o); }

    public static boolean bool(Object o, boolean fallback) { return o instanceof Boolean b ? b : fallback; }

    public static Integer integer(Object o) { return o instanceof Number n ? n.intValue() : null; }

    public static List<String> strings(Object o) {
        List<String> out = new ArrayList<>();
        for (Object v : list(o)) if (v != null) out.add(String.valueOf(v));
        return out;
    }
}
