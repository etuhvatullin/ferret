package ferret.tests;

import ferret.ui.Ui;

import javax.swing.JLabel;
import javax.swing.plaf.basic.BasicHTML;
import java.awt.Color;

import static ferret.tests.T.check;

// Текст із бази показується як текст: мітка не будує HTML-подання (а з ним — і запитів по картинки), див. Ui.noHtml.
// Таблицю результатів і її заголовок перевіряє ferret.ui.UiTests: панелі без вікна не створити.
final class PlainTextTests {
    private PlainTextTests() { }

    static void run() {
        T.section("Текст із бази — лише як текст");
        String evil = "<html><b>x</b>";
        check(new JLabel(evil).getClientProperty(BasicHTML.propertyKey) != null, "контроль: звичайна мітка Swing малює «<html>…» як HTML");
        JLabel l = Ui.label(evil, Color.WHITE, false, 12);
        check(l.getClientProperty(BasicHTML.propertyKey) == null && evil.equals(l.getText()), "мітка Ferret — «<html>…» текстом");
        l.setText("<HTML><i>y</i>");
        check(l.getClientProperty(BasicHTML.propertyKey) == null, "і після зміни тексту (регістр не рятує)");
        check(!BasicHTML.isHTMLString(Ui.plain(evil)) && Ui.plain(evil).strip().equals(evil), "plain(): підказка й заголовок — текстом, текст той самий");
        check("orders".equals(Ui.plain("orders")) && Ui.plain(null) == null, "plain(): звичайний рядок — без змін");
    }
}
