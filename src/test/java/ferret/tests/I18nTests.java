package ferret.tests;

import ferret.core.I18n;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static ferret.tests.T.check;

// Мови: кожна має всі ключі англійської з тими самими аргументами {0}…, множина — у своїх формах
// (українська й російська — one / few / many, решта — one / other), а сама підстановка працює.
final class I18nTests {
    static void run() throws Exception {
        T.section("Мови інтерфейсу");
        Properties en = load("");
        check(en.size() > 300, "англійських текстів " + en.size());
        Set<String> bases = new TreeSet<>();
        for (String k : en.stringPropertyNames()) if (k.matches(".*\\.(one|other)$")) bases.add(k.replaceFirst("\\.(one|other)$", ""));
        for (String lang : I18n.LANGS) {
            if (lang.equals("en")) continue;
            Properties p = load("_" + lang);
            Set<String> missing = new TreeSet<>(), args = new TreeSet<>();
            boolean slavic = lang.equals("uk") || lang.equals("ru");
            for (String k : en.stringPropertyNames()) {
                if (k.matches(".*\\.(one|other)$")) continue;   // множину — нижче, за формами мови
                if (!p.containsKey(k)) missing.add(k);
                else if (!placeholders(en.getProperty(k)).equals(placeholders(p.getProperty(k)))) args.add(k);
            }
            for (String b : bases) {
                for (String form : slavic ? new String[] {"one", "few", "many"} : new String[] {"one", "other"})
                    if (!p.containsKey(b + "." + form)) missing.add(b + "." + form);
            }
            check(missing.isEmpty(), I18n.NAMES.get(lang) + ": усі тексти є" + (missing.isEmpty() ? "" : " — бракує " + missing.size() + ": " + head(missing)));
            check(args.isEmpty(), I18n.NAMES.get(lang) + ": ті самі {0}… що й в англійській" + (args.isEmpty() ? "" : " — розходяться: " + head(args)));
        }
        I18n.use("en");
        check(I18n.plural("result.rows", 1).equals("1 row") && I18n.plural("result.rows", 1500).equals("1,500 rows"), "англійська: 1 row, 1,500 rows");
        I18n.use("uk");
        check(I18n.plural("result.rows", 21).equals("21 рядок") && I18n.plural("result.rows", 3).equals("3 рядки")
                && I18n.plural("result.rows", 11).equals("11 рядків"), "українська: 21 рядок, 3 рядки, 11 рядків");
        check(I18n.t("no.such.key").equals("no.such.key"), "немає ключа — видно сам ключ, а не порожнечу");
    }

    static Properties load(String suffix) throws Exception {
        Properties p = new Properties();
        try (InputStream in = I18n.class.getResourceAsStream("/ferret/i18n/messages" + suffix + ".properties")) {
            if (in != null) p.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return p;
    }

    static Set<String> placeholders(String s) {
        Set<String> out = new TreeSet<>();
        Matcher m = Pattern.compile("\\{(\\d+)").matcher(s);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    static String head(Set<String> s) {
        return String.join(", ", s.stream().limit(6).toList()) + (s.size() > 6 ? "…" : "");
    }
}
