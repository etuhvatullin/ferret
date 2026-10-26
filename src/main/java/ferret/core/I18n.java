package ferret.core;

import java.text.MessageFormat;
import java.text.NumberFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

// Мови інтерфейсу: англійська, українська, російська, німецька, іспанська. Тексти — ferret/i18n/messages*.properties
// (UTF-8; messages.properties — англійська). Мова — з settings.json («language»), а вперше — мова системи, якщо вона з
// п'яти, інакше англійська. Бракує ключа в мові — англійський текст, бракує й там — сам ключ (видно в інтерфейсі).
// Аргументи підставляє MessageFormat лише тоді, коли вони є: у такому тексті апостроф пишеться подвійним ('').
public final class I18n {
    private I18n() { }

    public static final List<String> LANGS = List.of("en", "uk", "ru", "de", "es");
    public static final Map<String, String> NAMES = new LinkedHashMap<>();   // назва мови — її ж мовою
    static {
        NAMES.put("en", "English");
        NAMES.put("uk", "Українська");
        NAMES.put("ru", "Русский");
        NAMES.put("de", "Deutsch");
        NAMES.put("es", "Español");
    }

    static final String BASE = "ferret.i18n.messages";
    static final ResourceBundle.Control EXACT = ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES);
    private static String lang;
    private static Locale locale;
    private static ResourceBundle texts, english;

    public static synchronized String lang() {
        if (lang == null) use(initial());
        return lang;
    }

    public static synchronized Locale locale() {
        lang();
        return locale;
    }

    // Збережена мова; немає — мова системи з п'яти; інакше англійська.
    static String initial() {
        String saved = Json.str(Json.map(Store.loadJSON("settings.json")).get("language"));
        if (saved != null && LANGS.contains(saved)) return saved;
        String sys = Locale.getDefault().getLanguage();
        return LANGS.contains(sys) ? sys : "en";
    }

    public static synchronized void use(String l) {
        lang = LANGS.contains(l) ? l : "en";
        locale = Locale.forLanguageTag(lang);
        english = ResourceBundle.getBundle(BASE, Locale.ROOT, EXACT);
        texts = lang.equals("en") ? english : ResourceBundle.getBundle(BASE, locale, EXACT);
    }

    public static boolean has(String key) {
        lang();
        return texts.containsKey(key) || english.containsKey(key);
    }

    public static String t(String key, Object... args) {
        lang();
        String s;
        try {
            s = texts.getString(key);
        } catch (MissingResourceException e) {
            try { s = english.getString(key); } catch (MissingResourceException e2) { return key; }
        }
        return args.length == 0 ? s : new MessageFormat(s, locale).format(args);
    }

    // Множина: ключі <key>.one / .few / .many (українська, російська) або .one / .other; {0} — число з розрядами.
    public static String plural(String key, long n) {
        String cat = category(n), k = key + "." + cat;
        if (!has(k)) k = key + "." + (has(key + ".other") ? "other" : "many");
        return t(k, num(n));
    }

    static String category(long n) {
        long a = Math.abs(n), d = a % 10, h = a % 100;
        if (lang().equals("uk") || lang().equals("ru")) {
            if (d == 1 && h != 11) return "one";
            if (d >= 2 && d <= 4 && (h < 12 || h > 14)) return "few";
            return "many";
        }
        return a == 1 ? "one" : "other";
    }

    public static String num(long n) {
        return NumberFormat.getIntegerInstance(locale()).format(n);
    }
}
