package ferret.ui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import ferret.core.Model.Connection;
import ferret.core.Store;

import javax.imageio.ImageIO;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Toolkit;
import java.util.HashMap;
import java.util.Map;

// Тема «Нора» — ті самі кольори, що в Swift-Ferret (Theme.swift): тепла темна гама, медовий акцент. Мітки серверів —
// окрема палітра з 8 кольорів. Шрифт редактора — свій для кожної системи (Menlo на Mac, Consolas на Windows).
public final class Theme {
    private Theme() { }

    public static final Color BG = new Color(0x17130F), PANEL = new Color(0x1F1A15), RAISED = new Color(0x2B241D), LINE = new Color(0x3A3027),
            TEXT = new Color(0xF3EADF), MUTED = new Color(0xB3A390), ACCENT = new Color(0xF2A33A), ON_ACCENT = new Color(0x1F1A15),
            KEYWORD = new Color(0xF0B357), STRING = new Color(0xA9C47F), NUMBER = new Color(0xE8865A), COMMENT = new Color(0x8C7E6E),
            QUOTED = new Color(0xC9A2E0), BAND = new Color(0x3A2A12), ERROR = new Color(0xE8865A);
    public static final Color[] MARKS = {new Color(0xF2A33A), new Color(0x8FB8E8), new Color(0xC9A2E0), new Color(0x7CC7A8),
            new Color(0xE88AA0), new Color(0xA9C47F), new Color(0xE8865A), new Color(0xB3A390)};

    public static final boolean MAC = Store.MAC;
    // ⌘ на Mac, Ctrl на Windows — для клавіш і підписів
    public static final int MENU_MASK = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
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
            return in == null ? null : ImageIO.read(in);
        } catch (Exception e) {
            return null;
        }
    }

    public static void install() {
        Map<String, String> ext = new HashMap<>();
        ext.put("@background", "#1F1A15");
        ext.put("@foreground", "#F3EADF");
        ext.put("@accentColor", "#F2A33A");
        ext.put("@selectionBackground", "#5A4325");
        ext.put("@selectionForeground", "#F3EADF");
        ext.put("Component.arc", "8");
        ext.put("Button.arc", "8");
        ext.put("TextComponent.arc", "8");
        FlatLaf.setGlobalExtraDefaults(ext);
        if (!MAC) {
            javax.swing.JFrame.setDefaultLookAndFeelDecorated(true);
            javax.swing.JDialog.setDefaultLookAndFeelDecorated(true);
        }
        FlatDarkLaf.setup();
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
        UIManager.put("Table.alternateRowColor", new Color(0x1C1713));
        UIManager.put("Table.gridColor", LINE);
        UIManager.put("Table.selectionBackground", new Color(0x5A4325));
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
        // меню «Запит / Правка» і випадні списки — як список підказок: тепле тло, вибір медом, заокруглені кути
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
