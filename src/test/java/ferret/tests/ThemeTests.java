package ferret.tests;

import ferret.core.I18n;
import ferret.ui.Theme;
import ferret.ui.Theme.Palette;

import java.awt.Color;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static ferret.tests.T.check;

// Кольорові теми: «Нора» — ті самі кольори, що були до тем (і в Swift-Ferret); кожна тема має всі кольори, назву
// пʼятьма мовами й читається — контраст за WCAG (текст і підсвітка SQL ≥ 4,5:1, акцент і мітки серверів ≥ 3:1).
final class ThemeTests {
    private ThemeTests() { }

    static void run() {
        T.section("Кольорові теми");
        Map<String, Integer> nora = Map.ofEntries(Map.entry("bg", 0x17130F), Map.entry("panel", 0x1F1A15), Map.entry("raised", 0x2B241D),
                Map.entry("line", 0x3A3027), Map.entry("text", 0xF3EADF), Map.entry("muted", 0xB3A390), Map.entry("accent", 0xF2A33A),
                Map.entry("onAccent", 0x1F1A15), Map.entry("keyword", 0xF0B357), Map.entry("string", 0xA9C47F), Map.entry("number", 0xE8865A),
                Map.entry("comment", 0x8C7E6E), Map.entry("quoted", 0xC9A2E0), Map.entry("band", 0x3A2A12), Map.entry("error", 0xE8865A),
                Map.entry("selection", 0x5A4325), Map.entry("altRow", 0x1C1713), Map.entry("accentHover", 0xF6B354), Map.entry("accentPressed", 0xD98F2C),
                Map.entry("accentDim", 0x6B5A40), Map.entry("splashTop", 0x241D16), Map.entry("accentDeep", 0xE0882A), Map.entry("ok", 0x7CC7A8));
        boolean same = nora.entrySet().stream().allMatch(e -> Theme.NORA.get(e.getKey()).getRGB() == (0xFF000000 | e.getValue()));
        int[] marks = {0xF2A33A, 0x8FB8E8, 0xC9A2E0, 0x7CC7A8, 0xE88AA0, 0xA9C47F, 0xE8865A, 0xB3A390};
        for (int i = 0; i < 8; i++) same &= Theme.NORA.get("mark" + i).getRGB() == (0xFF000000 | marks[i]);
        check(same && Theme.current == Theme.NORA && Theme.ACCENT.equals(new Color(0xF2A33A)), "«Нора» — ті самі кольори, що до тем; вона й типова");

        Set<String> ids = new HashSet<>();
        boolean keys = true, names = true;
        for (Palette p : Theme.PALETTES) {
            ids.add(p.id);
            keys &= p.keys().equals(Theme.NORA.keys());
            for (String lang : I18n.LANGS) {
                I18n.use(lang);
                names &= I18n.has("theme." + p.id) && !I18n.t("theme." + p.id).equals("theme." + p.id);
            }
        }
        I18n.use("uk");
        check(ids.size() == 7 && keys && names, "7 тем, у кожної — усі кольори «Нори» й назва пʼятьма мовами");

        for (Palette p : Theme.PALETTES) {
            StringBuilder bad = new StringBuilder();
            for (String[] c : List.of(new String[] {"text", "bg", "4.5"}, new String[] {"text", "panel", "4.5"}, new String[] {"text", "raised", "4.5"},
                    new String[] {"muted", "bg", "4.5"}, new String[] {"muted", "panel", "4.5"}, new String[] {"onAccent", "accent", "4.5"},
                    new String[] {"accent", "bg", "3"}, new String[] {"accent", "panel", "3"}, new String[] {"keyword", "bg", "4.5"},
                    new String[] {"string", "bg", "4.5"}, new String[] {"number", "bg", "4.5"}, new String[] {"quoted", "bg", "4.5"},
                    new String[] {"comment", "bg", "3"}, new String[] {"error", "panel", "4.5"}, new String[] {"ok", "panel", "4.5"},
                    new String[] {"text", "selection", "4.5"})) {
                double r = contrast(p.get(c[0]), p.get(c[1]));
                if (r < Double.parseDouble(c[2])) bad.append(String.format(" %s/%s %.2f", c[0], c[1], r));
            }
            for (int i = 0; i < 8; i++) if (contrast(p.get("mark" + i), p.get("panel")) < 3) bad.append(" mark").append(i);
            check(bad.length() == 0, "«" + p.id + "»: текст, підсвітка SQL, акцент і мітки серверів читаються" + bad);
        }
        check(Theme.byId("nope") == Theme.NORA && Theme.byId("paper") == Theme.PAPER && !Theme.PAPER.dark && Theme.DUSK.dark,
                "невідома тема — «Нора»; світлі позначено світлими (FlatLaf і заголовок вікна на Mac — світлі)");
        check(Theme.hex(new Color(0x0A0B0C)).equals("#0A0B0C"), "колір для рядків стилів FlatLaf — #RRGGBB");

        // знак FERRET: мед «Нори» → акцент теми, інші кольори й прозорість — як були
        java.awt.image.BufferedImage w = new java.awt.image.BufferedImage(3, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        w.setRGB(0, 0, 0xFFF2A33A);
        w.setRGB(1, 0, 0x80F2A33A);
        w.setRGB(2, 0, 0xFF8FB8E8);
        Theme.use(Theme.DUSK);
        java.awt.image.BufferedImage t = (java.awt.image.BufferedImage) Theme.image("wordmark.png");
        int accent = Theme.DUSK.get("accent").getRGB() & 0xFFFFFF;
        java.awt.image.BufferedImage tinted = tint(w, Theme.NORA.get("accent"), Theme.DUSK.get("accent"));
        Theme.use(Theme.NORA);
        check(t != null && tinted.getRGB(0, 0) == (0xFF000000 | accent) && tinted.getRGB(1, 0) == (0x80000000 | accent) && tinted.getRGB(2, 0) == 0xFF8FB8E8,
                "знак FERRET у «Сутінках» — лавандовий, край напівпрозорий як був, блакитна смужка — блакитна");
    }

    static java.awt.image.BufferedImage tint(java.awt.image.BufferedImage img, Color from, Color to) {
        try {
            var m = Theme.class.getDeclaredMethod("tint", java.awt.image.BufferedImage.class, Color.class, Color.class);
            m.setAccessible(true);
            return (java.awt.image.BufferedImage) m.invoke(null, img, from, to);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static double contrast(Color a, Color b) {
        double x = lum(a), y = lum(b);
        return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05);
    }

    static double lum(Color c) {
        double[] v = {c.getRed() / 255.0, c.getGreen() / 255.0, c.getBlue() / 255.0};
        for (int i = 0; i < 3; i++) v[i] = v[i] <= 0.03928 ? v[i] / 12.92 : Math.pow((v[i] + 0.055) / 1.055, 2.4);
        return 0.2126 * v[0] + 0.7152 * v[1] + 0.0722 * v[2];
    }
}
