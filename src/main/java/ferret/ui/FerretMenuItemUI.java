package ferret.ui;

import com.formdev.flatlaf.ui.FlatMenuItemRenderer;
import com.formdev.flatlaf.ui.FlatMenuItemUI;

import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.plaf.ComponentUI;

// Підписи гарячих клавіш у меню: Java на Windows і Linux пише «Ctrl+Period», «Ctrl+Comma», «Ctrl+Close Bracket» —
// показуємо знаки, як на клавіатурі. На Mac меню рідне, там і так символи.
public class FerretMenuItemUI extends FlatMenuItemUI {
    public static ComponentUI createUI(JComponent c) { return new FerretMenuItemUI(); }

    @Override protected FlatMenuItemRenderer createRenderer() {
        return new FlatMenuItemRenderer(menuItem, checkIcon, arrowIcon, acceleratorFont, acceleratorDelimiter) {
            @Override protected String getTextForAccelerator(KeyStroke ks) { return keyText(super.getTextForAccelerator(ks)); }
        };
    }

    static String keyText(String s) {
        return s.replace("Close Bracket", "]").replace("Open Bracket", "[").replace("Period", ".").replace("Comma", ",").replace("Slash", "/");
    }
}
