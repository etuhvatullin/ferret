package ferret.tests;

import ferret.ui.Ui;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.plaf.basic.BasicHTML;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;

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

        // Заставка на Windows із масштабом 125%: рядку кнопок бракувало 1 px, і «Обновить» зникала в другому рядку.
        T.section("Рядок кнопок не переноситься");
        JButton a = new JButton("Позже"), b = new JButton("Обновить");
        JPanel flow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        flow.add(new JButton("Позже"));
        JButton fb = new JButton("Обновить");
        flow.add(fb);
        Dimension fp = flow.getPreferredSize();
        flow.setSize(fp.width - 1, fp.height);
        flow.doLayout();
        check(fb.getY() >= fp.height, "контроль: FlowLayout без 1 px ставить другу кнопку нижче видимого рядка");
        JComponent row = Ui.buttons(8, a, b);
        Dimension rp = row.getPreferredSize();
        row.setSize(rp.width - 1, rp.height);
        row.doLayout();
        check(b.getY() == 0 && b.getX() >= a.getWidth() && b.getX() < row.getWidth(), "Ui.buttons без 1 px — друга кнопка в тому ж рядку, видно");
        b.setText("Обновить до новой версии");
        row.invalidate();   // у вікні це робить revalidate() з setText; без вікна він відкладається
        check(row.getMaximumSize().equals(row.getPreferredSize()) && row.getMaximumSize().width > rp.width,
                "найбільший розмір рядка — поточний, а не заморожений при створенні");
    }
}
