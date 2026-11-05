package ferret.ui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import ferret.core.Json;
import ferret.core.Model.Connection;
import ferret.core.Store;

import javax.imageio.ImageIO;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Кольорові теми. «Нора» — ті самі кольори, що в Swift-Ferret (Theme.swift): тепла темна гама, медовий акцент; поруч —
// інші палітри (Palette). Тема вибирається один раз під час запуску (settings.json → «theme», Налаштування — з
// перезапуском, як мова): вікна будуються вже з її кольорами, тож усе, що малює Ferret, бере кольори лише звідси —
// і рядки стилів FlatLaf теж (hex()). Мітки серверів — окрема палітра з 8 кольорів у кожній темі. Шрифт редактора —
// свій для кожної системи (Menlo на Mac, Consolas на Windows).
public final class Theme {
    private Theme() { }

    // Палітра теми: назва → колір. Ключі однакові в усіх темах (перевіряє ThemeTests): bg, panel, raised, line — тла й
    // лінії від найтемнішого; text, muted — текст; accent, onAccent (текст на акценті), accentHover / accentPressed /
    // accentDim (вимкнений текст на акценті), accentDeep (початок градієнта смужки на заставці); keyword, string,
    // number, comment, quoted — підсвітка SQL; band — смуга сервера; error, ok — стани; selection — виділення в
    // таблиці; altRow — кожен другий рядок; splashTop — верх градієнта заставки; mark0…mark7 — мітки серверів.
    public static final class Palette {
        public final String id;
        public final boolean dark;
        final Map<String, Color> c = new LinkedHashMap<>();

        Palette(String id, boolean dark, String spec) {
            this.id = id;
            this.dark = dark;
            for (String kv : spec.trim().split("\\s+")) {
                String[] p = kv.split("=");
                c.put(p[0], new Color(Integer.parseInt(p[1], 16)));
            }
        }

        public static Palette of(String id, boolean dark, String spec) { return new Palette(id, dark, spec); }

        public Color get(String key) {
            Color v = c.get(key);
            if (v == null) throw new IllegalArgumentException("theme " + id + ": no colour «" + key + "»");
            return v;
        }

        public java.util.Set<String> keys() { return c.keySet(); }
    }

    // «Нора» — тепла темна, медовий акцент (типова)
    public static final Palette NORA = Palette.of("nora", true, """
            bg=17130F panel=1F1A15 raised=2B241D line=3A3027 text=F3EADF muted=B3A390 accent=F2A33A onAccent=1F1A15
            accentHover=F6B354 accentPressed=D98F2C accentDim=6B5A40 accentDeep=E0882A keyword=F0B357 string=A9C47F
            number=E8865A comment=8C7E6E quoted=C9A2E0 band=3A2A12 error=E8865A ok=7CC7A8 selection=5A4325 altRow=1C1713
            splashTop=241D16 mark0=F2A33A mark1=8FB8E8 mark2=C9A2E0 mark3=7CC7A8 mark4=E88AA0 mark5=A9C47F mark6=E8865A
            mark7=B3A390""");

    // «Сутінки» — сливова темна, лавандовий акцент
    public static final Palette DUSK = Palette.of("dusk", true, """
            bg=15121C panel=1C1826 raised=272235 line=362F49 text=EEEAF7 muted=A79DC0 accent=B69CFF onAccent=1A1424
            accentHover=C4AFFF accentPressed=9D80F0 accentDim=5E5480 accentDeep=8F6FEA keyword=C2ABFF string=9ED3A6
            number=F2A27C comment=857C9C quoted=7CC9E8 band=2E2546 error=F08C8C ok=7CC7A8 selection=46386C altRow=19151F
            splashTop=221C30 mark0=B69CFF mark1=8FB8E8 mark2=E8A2D0 mark3=7CC7A8 mark4=F2A33A mark5=A9C47F mark6=F08C8C
            mark7=A79DC0""");

    // «Індиго» — синьо-фіолетова темна, барвінковий акцент
    public static final Palette INDIGO = Palette.of("indigo", true, """
            bg=12131F panel=181A29 raised=22253A line=31354F text=E9EBF7 muted=9DA2C4 accent=8F94FF onAccent=12131F
            accentHover=A3A7FF accentPressed=7479EE accentDim=4D5080 accentDeep=6A63E8 keyword=A9ADFF string=94D1B4
            number=F0A07E comment=7F84A3 quoted=E0A4E0 band=262A48 error=F08C8C ok=7CC7A8 selection=3A3E6E altRow=15161F
            splashTop=1E2034 mark0=8F94FF mark1=7CC9E8 mark2=E0A4E0 mark3=7CC7A8 mark4=F2A33A mark5=A9C47F mark6=F08C8C
            mark7=9DA2C4""");

    // «Графіт» — нейтральна сіра, сталево-блакитний акцент
    public static final Palette GRAPHITE = Palette.of("graphite", true, """
            bg=141516 panel=1B1C1E raised=26282B line=35383C text=ECEDEF muted=A0A4AA accent=6CB4F5 onAccent=0F1A26
            accentHover=86C2F7 accentPressed=4E9BDF accentDim=3F5A73 accentDeep=3F8FD8 keyword=7FBFF6 string=A6CF8A
            number=E9A06E comment=7E8389 quoted=C9A2E0 band=1F3044 error=EE8A80 ok=7CC7A8 selection=2E4560 altRow=18191A
            splashTop=1F2123 mark0=6CB4F5 mark1=F2A33A mark2=C9A2E0 mark3=7CC7A8 mark4=E88AA0 mark5=A9C47F mark6=EE8A80
            mark7=A0A4AA""");

    // «Мʼята» — нейтральна сіра, мʼятний акцент
    public static final Palette MINT = Palette.of("mint", true, """
            bg=161717 panel=1D1E1F raised=28292B line=37393B text=EDEEEE muted=A3A6A8 accent=5FD0B0 onAccent=0E1F1A
            accentHover=7ADBBF accentPressed=45B898 accentDim=3A6157 accentDeep=36B08E keyword=6FD6B8 string=C8D27E
            number=EBA272 comment=808486 quoted=9DB8F0 band=1C3A32 error=EE8A80 ok=5FD0B0 selection=2B4A42 altRow=191A1A
            splashTop=212224 mark0=5FD0B0 mark1=8FB8E8 mark2=C9A2E0 mark3=F2A33A mark4=E88AA0 mark5=C8D27E mark6=EE8A80
            mark7=A3A6A8""");

    // «Папір» — тепла світла, темно-медовий акцент
    public static final Palette PAPER = Palette.of("paper", false, """
            bg=FBF8F3 panel=F2EDE5 raised=E7E0D5 line=D6CCBE text=2A241E muted=665C50 accent=A85C0C onAccent=FFFFFF
            accentHover=B96A16 accentPressed=8F4D08 accentDim=E2C49C accentDeep=8A4A06 keyword=9A4F08 string=3A7320
            number=AE4318 comment=807566 quoted=7B3FA8 band=F1DEC2 error=B3361F ok=277050 selection=F0D9B5 altRow=F5F1EA
            splashTop=FFFFFF mark0=A8680C mark1=2F6FB8 mark2=8350B5 mark3=2A8A66 mark4=C1406A mark5=5A8A2A mark6=C4552B
            mark7=7A6E60""");

    // «Іній» — біла холодна, синій акцент
    public static final Palette FROST = Palette.of("frost", false, """
            bg=FFFFFF panel=F5F6F8 raised=E9ECF0 line=D6DBE1 text=1E2329 muted=56606A accent=1F6FD1 onAccent=FFFFFF
            accentHover=3580DB accentPressed=175CB0 accentDim=A9C5EC accentDeep=1559AE keyword=1B5BBA string=2C7630
            number=AF4610 comment=78808A quoted=8B3FA6 band=DCE8F8 error=C0392B ok=2E7D5B selection=CFE0F7 altRow=F7F8FA
            splashTop=FFFFFF mark0=1F6FD1 mark1=C27A12 mark2=8350B5 mark3=2A8A66 mark4=C1406A mark5=5A8A2A mark6=C4552B
            mark7=6B7480""");

    public static final List<Palette> PALETTES = List.of(NORA, DUSK, INDIGO, GRAPHITE, MINT, PAPER, FROST);

    public static Color BG, PANEL, RAISED, LINE, TEXT, MUTED, ACCENT, ON_ACCENT, ACCENT_HOVER, ACCENT_PRESSED, ACCENT_DIM, ACCENT_DEEP,
            KEYWORD, STRING, NUMBER, COMMENT, QUOTED, BAND, ERROR, OK, SELECTION, ALT_ROW, SPLASH_TOP;
    public static Color[] MARKS;
    public static Palette current;

    static { use(NORA); }

    // Палітру — у кольори теми; до побудови перших вікон (App.main), далі вони вже не міняються.
    public static void use(Palette p) {
        current = p;
        BG = p.get("bg"); PANEL = p.get("panel"); RAISED = p.get("raised"); LINE = p.get("line"); TEXT = p.get("text"); MUTED = p.get("muted");
        ACCENT = p.get("accent"); ON_ACCENT = p.get("onAccent"); ACCENT_HOVER = p.get("accentHover"); ACCENT_PRESSED = p.get("accentPressed");
        ACCENT_DIM = p.get("accentDim"); ACCENT_DEEP = p.get("accentDeep");
        KEYWORD = p.get("keyword"); STRING = p.get("string"); NUMBER = p.get("number"); COMMENT = p.get("comment"); QUOTED = p.get("quoted");
        BAND = p.get("band"); ERROR = p.get("error"); OK = p.get("ok"); SELECTION = p.get("selection"); ALT_ROW = p.get("altRow");
        SPLASH_TOP = p.get("splashTop");
        MARKS = new Color[8];
        for (int i = 0; i < 8; i++) MARKS[i] = p.get("mark" + i);
    }

    public static Palette byId(String id) {
        for (Palette p : PALETTES) if (p.id.equals(id)) return p;
        return NORA;
    }

    // Тема з settings.json («theme»); немає чи невідома — «Нора»
    public static Palette saved() { return byId(Json.str(Json.map(Store.loadJSON("settings.json")).get("theme"), NORA.id)); }

    // Колір для рядків стилів FlatLaf («background: #F2A33A»)
    public static String hex(Color c) { return String.format("#%06X", c.getRGB() & 0xFFFFFF); }

    public static final boolean MAC = Store.MAC;
    // ⌘ на Mac, Ctrl на Windows — для клавіш і підписів. Не через Toolkit: Theme читає тему ще до запуску віконної
    // системи (App.main ставить за нею вигляд заголовка на Mac), а Toolkit її запустив би раніше.
    public static final int MENU_MASK = MAC ? java.awt.event.InputEvent.META_DOWN_MASK : java.awt.event.InputEvent.CTRL_DOWN_MASK;
    public static final String CMD = MAC ? "⌘" : "Ctrl+";

    public static Color mark(Connection c) { return c == null || c.color == null ? MUTED : MARKS[Math.floorMod(c.color, MARKS.length)]; }

    public static Color alpha(Color c, int a) { return new Color(c.getRed(), c.getGreen(), c.getBlue(), a); }

    public static Font mono(float size) {
        String name = MAC ? "Menlo" : "Consolas";
        for (String f : GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()) if (f.equals(name)) return new Font(name, Font.PLAIN, Math.round(size)).deriveFont(size);
        return new Font(Font.MONOSPACED, Font.PLAIN, Math.round(size)).deriveFont(size);
    }

    public static Image image(String name) {
        try (var in = Theme.class.getResourceAsStream("/ferret/assets/" + name)) {
            if (in == null) return null;
            java.awt.image.BufferedImage img = ImageIO.read(in);
            return name.equals("wordmark.png") ? tint(img, NORA.get("accent"), ACCENT) : img;
        } catch (Exception e) {
            return null;
        }
    }

    // Знак FERRET намальовано медом «Нори»: в іншій темі мед стає її акцентом, прозорість країв — та сама, смужки інших
    // кольорів — як були
    static java.awt.image.BufferedImage tint(java.awt.image.BufferedImage src, Color from, Color to) {
        if (from.equals(to)) return src;
        java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(src.getWidth(), src.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < src.getHeight(); y++) for (int x = 0; x < src.getWidth(); x++) {
            int p = src.getRGB(x, y), a = p >>> 24, r = p >> 16 & 255, g = p >> 8 & 255, b = p & 255;
            boolean honey = Math.abs(r - from.getRed()) + Math.abs(g - from.getGreen()) + Math.abs(b - from.getBlue()) < 90;
            out.setRGB(x, y, honey ? a << 24 | (to.getRGB() & 0xFFFFFF) : p);
        }
        return out;
    }

    public static void install() {
        Map<String, String> ext = new HashMap<>();
        ext.put("@background", hex(PANEL));
        ext.put("@foreground", hex(TEXT));
        ext.put("@accentColor", hex(ACCENT));
        ext.put("@selectionBackground", hex(SELECTION));
        ext.put("@selectionForeground", hex(TEXT));
        ext.put("Component.arc", "8");
        ext.put("Button.arc", "8");
        ext.put("TextComponent.arc", "8");
        FlatLaf.setGlobalExtraDefaults(ext);
        if (!MAC) {
            javax.swing.JFrame.setDefaultLookAndFeelDecorated(true);
            javax.swing.JDialog.setDefaultLookAndFeelDecorated(true);
        }
        if (current.dark) FlatDarkLaf.setup(); else FlatLightLaf.setup();
        UIManager.put("Tree.background", PANEL);
        UIManager.put("Tree.selectionBackground", RAISED);
        UIManager.put("Tree.selectionInactiveBackground", RAISED);
        UIManager.put("TextField.background", RAISED);
        UIManager.put("PasswordField.background", RAISED);
        UIManager.put("ComboBox.background", RAISED);
        UIManager.put("Spinner.background", RAISED);
        UIManager.put("Button.background", RAISED);
        UIManager.put("ScrollBar.track", BG);
        UIManager.put("ScrollPane.background", BG);
        UIManager.put("Table.background", BG);
        UIManager.put("Table.alternateRowColor", ALT_ROW);
        UIManager.put("Table.gridColor", LINE);
        UIManager.put("Table.selectionBackground", SELECTION);
        UIManager.put("TableHeader.background", PANEL);
        UIManager.put("TableHeader.separatorColor", LINE);
        UIManager.put("TableHeader.bottomSeparatorColor", LINE);
        UIManager.put("List.background", PANEL);
        UIManager.put("List.selectionBackground", RAISED);
        UIManager.put("TitlePane.background", PANEL);
        UIManager.put("TitlePane.inactiveBackground", PANEL);
        UIManager.put("RootPane.background", PANEL);
        UIManager.put("SplitPane.background", PANEL);
        UIManager.put("SplitPaneDivider.draggingColor", ACCENT);
        // меню «Запит / Правка» і випадні списки — як список підказок: тло теми, вибір акцентом, заокруглені кути
        UIManager.put("Menu.foreground", MUTED);
        UIManager.put("MenuBar.hoverBackground", RAISED);
        UIManager.put("MenuBar.selectionBackground", RAISED);
        UIManager.put("MenuBar.selectionForeground", TEXT);
        UIManager.put("MenuBar.selectionArc", 8);
        UIManager.put("MenuBar.selectionInsets", new java.awt.Insets(2, 0, 2, 0));
        UIManager.put("MenuBar.selectionEmbeddedInsets", new java.awt.Insets(5, 0, 5, 0));
        UIManager.put("MenuBar.itemMargins", new java.awt.Insets(3, 10, 3, 10));
        UIManager.put("PopupMenu.background", RAISED);
        UIManager.put("PopupMenu.borderColor", LINE);
        UIManager.put("PopupMenu.borderCornerRadius", 8);
        UIManager.put("MenuItem.foreground", TEXT);
        UIManager.put("MenuItem.selectionBackground", ACCENT);
        UIManager.put("MenuItem.selectionForeground", ON_ACCENT);
        UIManager.put("MenuItem.selectionArc", 6);
        UIManager.put("MenuItem.selectionInsets", new java.awt.Insets(0, 4, 0, 4));
        UIManager.put("MenuItem.acceleratorForeground", MUTED);
        UIManager.put("MenuItem.acceleratorSelectionForeground", ON_ACCENT);
        UIManager.put("Separator.foreground", LINE);
        if (!MAC) UIManager.put("MenuItemUI", FerretMenuItemUI.class.getName());
        UIManager.put("ToolTip.background", RAISED);
        UIManager.put("ToolTip.foreground", TEXT);
    }
}
