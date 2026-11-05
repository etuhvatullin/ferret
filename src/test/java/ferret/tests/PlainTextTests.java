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
        String tip = Ui.tip("kit_id · integer", "<img src='http://x/y.png'> & ще");
        check(tip.startsWith("<html>") && !tip.contains("<img") && tip.contains("&lt;img") && tip.contains("&amp; ще"),
                "підказка з коментарем з бази: HTML лише для переносу, усе з бази екрановано (картинка не завантажиться)");

        T.section("Вікно «Таблиця» і клітинка: як показано");
        check(ferret.ui.TableWindow.size("26812345678").equals("26,8 ГБ") && ferret.ui.TableWindow.size("16384").equals("16,4 КБ")
                        && ferret.ui.TableWindow.size("512").equals("512 Б") && ferret.ui.TableWindow.size("431996928").equals("432 МБ"),
                "розмір — десятковими одиницями, як у Finder: 26,8 ГБ, 16,4 КБ, 512 Б, 432 МБ");
        check(ferret.ui.TableWindow.date("2026-11-05 16:40:35.013 Europe/Kiev").equals("05.11.2026 16:40")
                        && ferret.ui.TableWindow.date("2026-11-04").equals("04.11.2026") && ferret.ui.TableWindow.date("2026-11").equals("11.2026")
                        && ferret.ui.TableWindow.date("202611").equals("202611"),
                "дати: з часом, без часу, місяць; номер партиції — як є");
        check(ferret.ui.TableWindow.scheme("month(created_at)").equals("по місяцях created_at") && ferret.ui.TableWindow.scheme("toYYYYMM(d)").equals("toYYYYMM(d)"),
                "схема партицій: перетворення Iceberg — словами, ClickHouse — як є");
        var est = ferret.ui.TableWindow.texts(new ferret.core.TableInfo.Item(ferret.core.TableInfo.Fact.ROWS, ferret.core.TableInfo.Mark.ESTIMATE, "1204311", null));
        var none = ferret.ui.TableWindow.texts(new ferret.core.TableInfo.Item(ferret.core.TableInfo.Fact.PARTITIONS, ferret.core.TableInfo.Mark.NONE, null, null));
        var late = ferret.ui.TableWindow.texts(new ferret.core.TableInfo.Item(ferret.core.TableInfo.Fact.SIZE, ferret.core.TableInfo.Mark.TIMEOUT, null, "15"));
        check(est[0].equals("≈ " + ferret.core.I18n.num(1204311)) && est[1].equals("оцінка бази") && none[0].equals("немає") && none[1].equals("таблиця не поділена")
                        && late[0].equals("не встигла") && late[1].equals("за 15 с"),
                "оцінка — «≈» і підпис; без партицій — «немає»; межа часу — «не встигла за 15 с»");
        check(ferret.ui.ValueWindow.pretty("{\"a\":12345678901234567.89,\"b\":[],\"c\":[\"x, y\",{\"d\":\"{}\"}]}")
                        .equals("{\n  \"a\": 12345678901234567.89,\n  \"b\": [],\n  \"c\": [\n    \"x, y\",\n    {\n      \"d\": \"{}\"\n    }\n  ]\n}")
                        && ferret.ui.ValueWindow.pretty("просто текст") == null && ferret.ui.ValueWindow.pretty("{зламаний") == null,
                "JSON — з відступами, числа й рядки знак у знак; не JSON — кнопки немає");
        var area = new org.fife.ui.rsyntaxtextarea.RSyntaxTextArea();
        javax.swing.InputMap im = area.getInputMap(JComponent.WHEN_FOCUSED);
        // клавіша меню цієї системи: ⌘ на Mac, Ctrl деінде (на Mac Ctrl+B у редакторі — «курсор назад», як в emacs, і це не наша)
        int menu = System.getProperty("os.name", "").startsWith("Mac") ? java.awt.event.InputEvent.META_DOWN_MASK : java.awt.event.InputEvent.CTRL_DOWN_MASK;
        check(im.get(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_B, menu)) == null,
                "⌘B / Ctrl+B редактор не займає — дістається меню «Перегляд» (справжнє натискання — у ui-перевірках)");
        check("insert-break".equals(im.get(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER, java.awt.event.InputEvent.SHIFT_DOWN_MASK))),
                "⇧↵ у редакторі — новий рядок, як і було: меню «Повне значення клітинки» його не перехоплює");

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
