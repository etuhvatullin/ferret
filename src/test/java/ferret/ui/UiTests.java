package ferret.ui;

import ferret.core.Json;
import ferret.core.Model.Connection;
import ferret.core.Model.DBKind;
import ferret.core.Model.QueryResult;
import ferret.core.SchemaItem;
import ferret.core.Store;
import ferret.ui.AppState.ConsoleTab;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;

import javax.swing.SwingUtilities;
import java.awt.event.KeyEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

// Перевірки вікна — зі справжнім екраном (./build.sh ui; WSLg, Mac, Windows; без екрана — пропуск). Перша половина подає
// події прямо в редактор (список підказок, ⌘↵ у підзапиті, формат, відступи), друга — справжні натискання й клацання
// через Robot: так видно, хто забирає клавішу (редактор чи меню) і чи не губляться літери. Далі вікно підключень і запит
// на H2 з кешу. Дані — тимчасова тека; Robot у WSLg «тисне» лише всередині Linux-вікон, не у Windows.
public final class UiTests {
    static int failures;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "✓ " : "✗ ") + what);
        if (!ok) failures++;
    }

    static void ui(Runnable r) throws Exception { SwingUtilities.invokeAndWait(r); }

    static void pause(int ms) throws Exception { Thread.sleep(ms); }

    public static void main(String[] args) throws Exception {
        try { run(); } catch (Throwable e) { e.printStackTrace(System.out); System.exit(2); }
    }

    static void run() throws Exception {
        if (java.awt.GraphicsEnvironment.isHeadless()) { System.out.println("— перевірки вікна пропущено: немає екрана"); return; }
        Path dir = ferret.tests.T.temp("ferret-ui");
        Store.useDir(dir);
        ferret.core.I18n.use("uk");   // компоненти шукаються за українськими текстами
        Connection t = Connection.blank(DBKind.TRINO);
        t.name = "Trino · тест";
        t.host = "trino.invalid";
        t.database = "lake";
        t.color = 0;
        List<Object> conns = new ArrayList<>();
        conns.add(t.toJson());
        Connection h2 = h2Driver();
        if (h2 != null) conns.add(h2.toJson());
        Store.saveJSON("connections.json", conns);
        AtomicReference<MainWindow> wr = new AtomicReference<>();
        ui(() -> { Theme.install(); wr.set(MainWindow.open()); });
        MainWindow w = wr.get();
        pause(800);
        AppState s = w.state;
        // таблиці сервера й корінь дерева — без мережі
        ui(() -> {
            s.tables.put(t.id, List.of(List.of("lake", "sales", "orders"), List.of("lake", "sales", "orders_items"), List.of("lake", "goods", "items")));
            s.tree.put(s.treeKey(t.id, List.of()), List.of(new SchemaItem(List.of("lake"), null), new SchemaItem(List.of("clickhouse_prod"), null)));
            s.loading.clear();
        });
        RSyntaxTextArea a = w.editor.area();
        EditorPanel.Ed ed = w.editor.current();
        ui(() -> { a.setText("select * from "); a.setCaretPosition(a.getDocument().getLength()); a.requestFocusInWindow(); });
        pause(300);

        type(a, "l");
        pause(300);
        check(ed.popup.isVisible() && ed.popup.current() != null && ed.popup.current().label().equals("lake"), "набрав «l» після FROM — список сам, першим lake");
        key(a, KeyEvent.VK_TAB, 0);
        pause(200);
        check(a.getText().equals("select * from lake") && !ed.popup.isVisible(), "Tab — вставлено «lake», список закрито");
        type(a, ".");
        pause(300);
        check(ed.popup.isVisible() && ed.popup.size() == 2, "«lake.» — схеми (" + ed.popup.size() + ")");
        key(a, KeyEvent.VK_DOWN, 0);
        key(a, KeyEvent.VK_TAB, 0);
        pause(200);
        check(a.getText().equals("select * from lake.sales"), "↓ і Tab — друга схема (" + a.getText() + ")");
        type(a, ".or");
        pause(300);
        key(a, KeyEvent.VK_ENTER, 0);
        pause(200);
        check(a.getText().equals("select * from lake.sales.or\n") && !ed.popup.isVisible(), "↵ без вибору стрілками — перенос рядка, а не вставка");

        // ⌘↵ у підзапиті — вибір; ↓ ↵ — увесь запит
        List<String> ran = new ArrayList<>();
        AtomicReference<java.util.function.Consumer<String>> realRun = new AtomicReference<>();
        ui(() -> { realRun.set(w.editor.onRun); w.editor.onRun = ran::add; });
        String nested = "select o.id\nfrom orders o\njoin (\n    select order_id, count(*) cnt\n    from items\n    group by order_id\n) x on x.order_id = o.id";
        ui(() -> { a.setText(nested); a.setCaretPosition(nested.indexOf("count(*)") + 7); });
        ui(() -> w.editor.requestRun());
        pause(200);
        check(ed.popup.isVisible() && ed.choosing != null && ed.popup.size() == 2 && ran.isEmpty(), "⌘↵ у підзапиті — список «Підзапит / Увесь запит», ще нічого не запущено");
        key(a, KeyEvent.VK_DOWN, 0);
        key(a, KeyEvent.VK_ENTER, 0);
        pause(200);
        check(ran.size() == 1 && ran.get(0).equals(nested) && !ed.popup.isVisible(), "↓ і ↵ — запущено весь запит");
        ui(() -> { a.setText("select 1\n\nselect 2"); a.setCaretPosition(2); });
        ui(() -> w.editor.requestRun());
        pause(100);
        check(ran.size() == 2 && ran.get(1).equals("select 1"), "поза підзапитом — одразу запит під курсором (межа — порожній рядок)");
        ui(() -> { a.select(10, 18); w.editor.requestRun(); });
        check(ran.size() == 3 && ran.get(2).equals("select 2"), "виділено текст — виконується виділене");

        // формат, відкат одним кроком
        String raw = "select a, count(*) cnt from t join (select id from u where x=1) s on s.id=t.id group by a";
        ui(() -> { a.setText(raw); a.discardAllEdits(); a.setCaretPosition(3); w.editor.formatQuery(DBKind.TRINO); });
        check(a.getText().startsWith("select a,\n       count(*) cnt\nfrom t\njoin (\n    select id"), "⌥⌘L — запит відформатовано");
        ui(a::undoLastAction);
        check(a.getText().equals(raw), "⌘Z — формат скасовано одним кроком");

        // відступи й коментарі
        ui(() -> { a.setText("select a,\nb\nfrom t"); a.select(0, 12); w.editor.indent(false); });
        check(a.getText().equals("    select a,\n    b\nfrom t"), "відступ виділених рядків (" + a.getText().replace("\n", "⏎") + ")");
        ui(() -> { a.select(0, 20); w.editor.indent(true); });
        check(a.getText().equals("select a,\nb\nfrom t"), "відступ назад (" + a.getText().replace("\n", "⏎") + ")");
        ui(() -> { a.select(0, 12); w.editor.toggleComment(); });
        check(a.getText().startsWith("--"), "⌘/ — рядки закоментовано (" + a.getText().replace("\n", "⏎") + ")");

        // поки запит іде — значок підстрибує: колись мітка без найбільшого розміру мала 0×0, і значка не було взагалі
        ui(() -> { s.current.running = true; s.current.startedAt = java.time.Instant.now(); s.emit("result"); });
        pause(300);
        AtomicReference<java.awt.Dimension> ico = new AtomicReference<>();
        ui(() -> { for (javax.swing.JLabel l : all(w.result, javax.swing.JLabel.class)) if (l.isShowing() && l.getClass() != javax.swing.JLabel.class) ico.set(l.getSize()); });
        check(ico.get() != null && ico.get().width == 72, "запит іде — значок видно (" + ico.get() + ")");
        ui(() -> { s.current.running = false; s.emit("result"); });

        // текст із бази — лише як текст: «<html>…» у значенні й у назві колонки не малюється як HTML (див. Ui.noHtml)
        ui(() -> {
            QueryResult r = new QueryResult();
            r.columns.add("<html><b>h</b>"); r.types.add("varchar");
            r.rows.add(new String[] {"<html><b>v</b>"});
            s.current.result = r;
            s.current.lastSQL = "select 1";
            s.emit("result");
        });
        pause(200);
        AtomicReference<String> html = new AtomicReference<>();
        ui(() -> {
            javax.swing.JTable g = all(w.result, javax.swing.JTable.class).get(0);
            javax.swing.JLabel cell = (javax.swing.JLabel) g.prepareRenderer(g.getCellRenderer(0, 1), 0, 1);
            javax.swing.JLabel head = (javax.swing.JLabel) g.getTableHeader().getDefaultRenderer()
                    .getTableCellRendererComponent(g, g.getColumnModel().getColumn(1).getHeaderValue(), false, false, -1, 1);
            html.set((cell.getClientProperty(javax.swing.plaf.basic.BasicHTML.propertyKey) == null ? "" : "клітинка ")
                    + (head.getClientProperty(javax.swing.plaf.basic.BasicHTML.propertyKey) == null ? "" : "заголовок ")
                    + "|" + cell.getText() + "|" + head.getText().strip());
        });
        check(html.get().equals("|<html><b>v</b>|<html><b>h</b>"), "«<html>…» з бази — текстом у клітинці й заголовку (" + html.get() + ")");

        // «Показати всі» видно, коли результат обрізано
        ui(() -> {
            ConsoleTab cur = s.current;
            QueryResult r = new QueryResult();
            r.columns.add("x");
            r.types.add("int");
            for (int i = 0; i < 500; i++) r.rows.add(new String[] {String.valueOf(i)});
            r.truncated = true;
            cur.result = r;
            cur.lastSQL = "select x";
            s.emit("result");
        });
        pause(200);
        check(w.result.isShowing() && findButton(w.result, "Показати всі"), "обрізаний результат — кнопка «Показати всі»");

        // сортування кліком по заголовку (↑ → ↓ → як прийшло; числа — як числа) і пошук у результаті
        ui(() -> {
            QueryResult r = new QueryResult();
            r.columns.add("n"); r.types.add("bigint");
            r.columns.add("name"); r.types.add("varchar");
            r.rows.add(new String[] {"10", "Кавомолки"});
            r.rows.add(new String[] {"9", "чайники"});
            r.rows.add(new String[] {"100", "кавоварки"});
            r.rows.add(new String[] {null, "тостери"});
            s.current.result = r;
            s.current.lastSQL = "select n, name";
            s.emit("result");
        });
        pause(200);
        javax.swing.JTable tb = all(w.result, javax.swing.JTable.class).get(0);
        java.util.function.Supplier<String> col1 = () -> {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < tb.getRowCount(); i++) b.append(tb.getValueAt(i, 1)).append(' ');
            return b.toString().trim();
        };
        AtomicReference<String> seen = new AtomicReference<>();
        ui(() -> { tb.getRowSorter().toggleSortOrder(1); seen.set(col1.get()); });
        check(seen.get().equals("null 9 10 100"), "клік по заголовку — за зростанням, числа як числа (" + seen.get() + ")");
        ui(() -> seen.set(((javax.swing.JLabel) tb.prepareRenderer(tb.getCellRenderer(0, 0), 0, 0)).getText()));
        check("1".equals(seen.get()), "№ рядка — як видно після сортування (" + seen.get() + ")");
        ui(() -> { tb.getRowSorter().toggleSortOrder(1); seen.set(col1.get()); });
        check(seen.get().equals("100 10 9 null"), "удруге — за спаданням (" + seen.get() + ")");
        ui(() -> { tb.getRowSorter().toggleSortOrder(1); seen.set(col1.get()); });
        check(seen.get().equals("10 9 100 null"), "утретє — як прийшло з бази (" + seen.get() + ")");
        javax.swing.JTextField flt = field(w.result, "Пошук у результаті");
        ui(() -> flt.setText("КАВ"));
        pause(400);
        AtomicReference<String> sum = new AtomicReference<>();
        ui(() -> { seen.set(col1.get()); for (javax.swing.JLabel l : all(w.result, javax.swing.JLabel.class)) if (l.isShowing() && l.getText() != null && l.getText().contains(" мс")) sum.set(l.getText()); });
        check(seen.get().equals("10 100") && sum.get() != null && sum.get().startsWith("2 з 4 рядків"), "пошук у результаті — без регістру, «2 з 4» (" + seen.get() + " · " + sum.get() + ")");
        ui(() -> button(w.result, "Копіювати все").doClick());
        String copied = (String) java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().getData(java.awt.datatransfer.DataFlavor.stringFlavor);
        check(copied.split("\n").length == 3 && copied.contains("Кавомолки") && !copied.contains("чайники"), "«Копіювати все» — те, що видно (" + copied.split("\n").length + " рядки)");
        ui(() -> flt.setText(""));
        pause(400);
        ui(() -> seen.set(col1.get()));
        check(seen.get().equals("10 9 100 null"), "поле порожнє — знову всі рядки");

        // панель вкладок: 15 вкладок — частина у «усі вкладки», поточна видно завжди
        ui(() -> { for (int i = 0; i < 15; i++) s.newTab("", "Довга назва вкладки " + i, t.id); });
        pause(300);
        check(w.tabBar.strip.getComponentCount() - 1 < s.tabs.size() && containsTitle(w.tabBar, s.current.title), "панель вкладок: що не вміщається — у «усі вкладки», поточна видно");

        robot(w, s, ran);
        tooltip(w, s, t);
        connections(w, s);
        menusAndDialogs(w, s);
        ui(() -> w.editor.onRun = realRun.get());   // далі — справжній запуск
        if (h2 != null) live(w, s, h2);
        else System.out.println("— запит у вікні пропущено: немає драйвера H2 у кеші (спершу ./build.sh)");

        ui(() -> { w.state.saveTabs(); w.dispose(); });
        List<Object> saved = Json.list(Store.loadJSON("tabs.json"));
        check(saved.size() >= 16, "вкладки записано на диск (" + saved.size() + ")");
        ferret.tests.T.cleanup();
        System.out.println(failures == 0 ? "\nВікно: усе гаразд." : "\nВікно: помилок " + failures);
        System.exit(failures == 0 ? 0 : 1);
    }

    // Справжні натискання (Robot): вони йдуть усім шляхом клавіатури — редактор, потім меню. Так видно, хто з них
    // забирає клавішу: ⌘[ редактор колись забирав собі під «парну дужку», і відступ з меню мовчав.
    static void robot(MainWindow w, AppState s, List<String> ran) throws Exception {
        RSyntaxTextArea a = w.editor.area();
        java.awt.Robot r = new java.awt.Robot();
        r.setAutoDelay(30);
        ui(() -> { w.toFront(); w.requestFocus(); a.requestFocusInWindow(); });
        pause(500);
        if (!a.isFocusOwner()) {   // WSLg не дає фокус вікну саме — клацання мишею по редактору
            java.awt.Point p = a.getLocationOnScreen();
            r.mouseMove(p.x + 40, p.y + 20);
            r.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
            r.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
            pause(800);
        }
        if (!a.isFocusOwner()) { System.out.println("— натискання пропущено: вікно не отримало фокус"); return; }
        if (System.getenv("FERRET_UI_KEYS") != null) java.awt.Toolkit.getDefaultToolkit().addAWTEventListener(e -> {
            KeyEvent k = (KeyEvent) e;
            if (k.getID() != KeyEvent.KEY_RELEASED) System.out.println("    " + k.paramString() + " → " + k.getSource().getClass().getSimpleName());
        }, java.awt.AWTEvent.KEY_EVENT_MASK);
        if (System.getenv("FERRET_UI_KEYS") != null) java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().addPropertyChangeListener("focusOwner",
                e -> System.out.println("    фокус: " + (e.getNewValue() == null ? "нікого" : e.getNewValue().getClass().getSimpleName())));
        int m = Theme.MENU_MASK == java.awt.event.InputEvent.META_DOWN_MASK ? KeyEvent.VK_META : KeyEvent.VK_CONTROL;
        ui(() -> { a.setText("select 1\n\nselect 2"); a.setCaretPosition(2); });
        int before = ran.size();
        press(r, m, KeyEvent.VK_ENTER);
        check(ran.size() == before + 1 && ran.get(before).equals("select 1") && a.getText().equals("select 1\n\nselect 2"), "натиск ⌘↵ — запит один раз, текст не змінено");
        ui(() -> a.select(0, 8));
        press(r, m, KeyEvent.VK_CLOSE_BRACKET);
        check(a.getText().startsWith("    select 1"), "натиск ⌘] — відступ");
        ui(() -> a.select(0, 12));
        press(r, m, KeyEvent.VK_OPEN_BRACKET);
        check(a.getText().startsWith("select 1"), "натиск ⌘[ — відступ назад (" + a.getText().replace("\n", "⏎") + ")");
        ui(() -> a.select(0, 8));
        press(r, m, KeyEvent.VK_SLASH);
        check(a.getText().startsWith("--select 1"), "натиск ⌘/ — коментар (" + a.getText().replace("\n", "⏎") + ")");
        press(r, m, KeyEvent.VK_SLASH);
        check(a.getText().startsWith("select 1"), "натиск ⌘/ ще раз — коментар знято (" + a.getText().replace("\n", "⏎") + ")");
        ui(() -> { a.setText("select a, b from t where x=1"); a.setCaretPosition(3); });
        press(r, m, KeyEvent.VK_ALT, KeyEvent.VK_L);
        check(a.getText().startsWith("select a,\n       b\nfrom t"), "натиск ⌥⌘L — формат (" + a.getText().replace("\n", "⏎") + ")");
        EditorPanel.Ed ed = w.editor.current();
        ui(() -> a.setCaretPosition(3));   // «sel|ect»: на порожньому місці списку немає й у Swift-версії
        press(r, KeyEvent.VK_ESCAPE);
        pause(200);
        check(ed.popup.isVisible(), "натиск Esc — список підказок уручну");
        press(r, KeyEvent.VK_ESCAPE);
        pause(100);
        check(!ed.popup.isVisible(), "ще Esc — список закрито");
        // швидкий набір через список: окреме вікно списку під Linux губило літери, поки зʼявлялось і ховалось
        ui(() -> { a.setText("select * from "); a.setCaretPosition(a.getDocument().getLength()); });
        press(r, KeyEvent.VK_L);
        pause(200);
        r.setAutoDelay(40);
        for (int k : new int[] {KeyEvent.VK_TAB, KeyEvent.VK_PERIOD, KeyEvent.VK_S, KeyEvent.VK_A}) { r.keyPress(k); r.keyRelease(k); }
        r.waitForIdle();
        pause(300);
        check(a.getText().startsWith("select * from lake.sa"), "швидко: l, Tab, «.sa» — жодна літера не загубилась (" + a.getText() + ")");
        press(r, KeyEvent.VK_ESCAPE);
        int tabs = s.tabs.size();
        press(r, m, KeyEvent.VK_T);
        pause(200);
        check(s.tabs.size() == tabs + 1, "натиск ⌘T — нова вкладка");
        ui(() -> w.editor.area().requestFocusInWindow());
        pause(200);
        press(r, m, KeyEvent.VK_W);
        pause(200);
        check(s.tabs.size() == tabs, "натиск ⌘W — вкладку закрито");
        ui(() -> w.editor.area().requestFocusInWindow());
        pause(200);
        press(r, m, KeyEvent.VK_F);
        pause(200);
        check(w.editor.findBar.isShowing(), "натиск ⌘F — панель пошуку");
        press(r, KeyEvent.VK_ESCAPE);
        pause(200);
        check(!w.editor.findBar.isShowing() && a.isFocusOwner(), "Esc у пошуку — панель закрито, курсор у редакторі");
    }

    // Помилка в дереві — одним рядком з «…», повністю — у підказці під мишею: наводимо курсор і шукаємо намальовану підказку.
    static void tooltip(MainWindow w, AppState s, Connection t) throws Exception {
        String err = "Сервер «trino.invalid» не знайдено — перевір адресу або VPN.\nError executing query: java.net.UnknownHostException: trino.invalid";
        ui(() -> { s.treeErrors.put(s.treeKey(t.id, List.of()), err); s.emit("tree"); });
        pause(300);
        AtomicReference<java.awt.Point> at = new AtomicReference<>();
        ui(() -> {
            for (javax.swing.JList<?> l : all(w, javax.swing.JList.class)) {
                for (int i = 0; i < l.getModel().getSize(); i++) {
                    if (l.getModel().getElementAt(i) instanceof Sidebar.TreeRow r && r.item() == null && r.note().startsWith("⚠") && l.isShowing()) {
                        java.awt.Rectangle b = l.getCellBounds(i, i);
                        java.awt.Point p = new java.awt.Point(b.x + 60, b.y + b.height / 2);
                        javax.swing.SwingUtilities.convertPointToScreen(p, l);
                        at.set(p);
                    }
                }
            }
        });
        if (at.get() == null) { check(false, "рядок помилки в дереві"); return; }
        java.awt.Robot r = new java.awt.Robot();
        r.mouseMove(at.get().x, at.get().y);
        pause(100);
        r.mouseMove(at.get().x + 4, at.get().y);
        pause(1600);
        AtomicReference<String> tip = new AtomicReference<>("");
        ui(() -> {
            for (java.awt.Window x : java.awt.Window.getWindows())
                for (javax.swing.JToolTip j : all(x, javax.swing.JToolTip.class)) if (j.isShowing() && j.getTipText() != null) tip.set(j.getTipText());
        });
        check(tip.get().contains("UnknownHostException") && tip.get().contains("<br>"), "наведення на помилку в дереві — повний текст у підказці");
        r.mouseMove(at.get().x + 600, at.get().y + 300);
        ui(() -> { s.treeErrors.clear(); s.emit("tree"); });
    }

    static <C> List<C> all(java.awt.Container c, Class<C> type) {
        List<C> out = new ArrayList<>();
        for (java.awt.Component x : c.getComponents()) {
            if (type.isInstance(x)) out.add(type.cast(x));
            if (x instanceof java.awt.Container k) out.addAll(all(k, type));
        }
        return out;
    }

    // Вікно підключень наживо: нове підключення — назва й пароль натисканнями, ↵ = «Готово»; Esc закриває без змін.
    // Меню й нові вікна: Ctrl+Shift+, — «Підключення», Ctrl+, — «Налаштування» (мова — у settings.json), «Про Ferret» —
    // з версією; підписи клавіш у «Гарячих клавішах» — як на цій системі.
    static void menusAndDialogs(MainWindow w, AppState s) throws Exception {
        boolean mac = Theme.MAC;
        check(Help.keys(javax.swing.KeyStroke.getKeyStroke(KeyEvent.VK_COMMA, Theme.MENU_MASK | java.awt.event.InputEvent.SHIFT_DOWN_MASK)).equals(mac ? "⇧⌘," : "Ctrl+Shift+,")
                && Help.keys(javax.swing.KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, Theme.MENU_MASK)).equals(mac ? "⌘↵" : "Ctrl+Enter"), "гарячі клавіші — підписи як на цій системі");
        java.util.Set<String> menus = new java.util.LinkedHashSet<>();
        ui(() -> { for (int i = 0; i < w.getJMenuBar().getMenuCount(); i++) if (w.getJMenuBar().getMenu(i) != null) menus.add(w.getJMenuBar().getMenu(i).getText()); });   // знак FERRET — не меню
        check(menus.containsAll(java.util.List.of("Файл", "Редагування", "Запит", "Підключення", "Довідка")), "меню: " + menus);
        java.awt.Robot r = new java.awt.Robot();
        r.setAutoDelay(30);
        if (!focusEditor(r, w)) { System.out.println("— натискання меню пропущено: вікно не отримало фокус"); return; }
        int m = Theme.MENU_MASK == java.awt.event.InputEvent.META_DOWN_MASK ? KeyEvent.VK_META : KeyEvent.VK_CONTROL;
        press(r, m, KeyEvent.VK_SHIFT, KeyEvent.VK_COMMA);
        ConnectionsDialog cd = waitDialog();
        check(cd != null, "натиск Ctrl+Shift+, — вікно «Підключення»");
        if (cd != null) { ui(cd::dispose); pause(300); }
        focusEditor(r, w);
        press(r, m, KeyEvent.VK_COMMA);
        javax.swing.JDialog sd = waitTitled(ferret.core.I18n.t("settings.title"));
        check(sd != null, "натиск Ctrl+, — вікно «Налаштування»");
        if (sd != null) {
            // не чекати на дію: «Зберегти» відкриває питання про перезапуск, і воно тримає, доки не відповіси
            SwingUtilities.invokeLater(() -> {
                javax.swing.JComboBox<?> lang = find(sd, javax.swing.JComboBox.class);
                lang.setSelectedItem("English");
                button(sd, ferret.core.I18n.t("settings.save")).doClick();
            });
            javax.swing.JDialog ask = waitTitled(ferret.core.I18n.t("main.restart.title"));   // «Перезапустити зараз?» — пізніше
            check(ask != null && button(ask, ferret.core.I18n.t("main.restart.later")) != null, "питання про перезапуск — вікно Ferret із кнопкою «Пізніше»");
            if (ask != null) ui(() -> button(ask, ferret.core.I18n.t("main.restart.later")).doClick());
            pause(300);
            check(Files.readString(Store.file("settings.json")).contains("\"language\" : \"en\""), "збережена мова — у settings.json");
        }
        SwingUtilities.invokeLater(() -> AboutDialog.show(w));
        javax.swing.JDialog about = waitTitled(ferret.core.I18n.t("main.menu.about"));
        AtomicReference<Boolean> hasVersion = new AtomicReference<>(false);
        if (about != null) ui(() -> {
            for (javax.swing.JLabel l : all(about, javax.swing.JLabel.class)) if (l.getText() != null && l.getText().contains(ferret.App.version())) hasVersion.set(true);
            about.dispose();
        });
        check(about != null && hasVersion.get(), "«Про Ferret» — з поточною версією " + ferret.App.version());

        // «Доступ для Claude…» — у меню «Підключення»; права підключення зберігаються одразу, без кнопки
        AtomicReference<Boolean> inMenu = new AtomicReference<>(false);
        ui(() -> {
            for (int i = 0; i < w.getJMenuBar().getMenuCount(); i++) {
                javax.swing.JMenu jm = w.getJMenuBar().getMenu(i);
                if (jm == null || !jm.getText().equals(ferret.core.I18n.t("main.menu.conn"))) continue;
                for (java.awt.Component it : jm.getMenuComponents())
                    if (it instanceof javax.swing.JMenuItem mi && ferret.core.I18n.t("main.menu.claude").equals(mi.getText())) inMenu.set(true);
            }
        });
        SwingUtilities.invokeLater(() -> ClaudeDialog.show(w, s));
        javax.swing.JDialog cl = waitTitled(ferret.core.I18n.t("claude.title"));
        AtomicReference<String> saved = new AtomicReference<>("");
        if (cl != null) {
            ui(() -> find(cl, javax.swing.JComboBox.class).setSelectedIndex(1));
            pause(200);
            saved.set(String.valueOf(ferret.core.McpAccess.rights().values()));
            ui(cl::dispose);
            ferret.core.McpAccess.save(java.util.Map.of());
        }
        check(inMenu.get() && cl != null && saved.get().equals("[read]"), "«Доступ для Claude…» у меню «Підключення»; права зберігаються одразу (" + saved.get() + ")");
    }

    // Фокус у редактор: після закритого вікна він сам не вертається — тоді клацання по редактору
    static boolean focusEditor(java.awt.Robot r, MainWindow w) throws Exception {
        ui(() -> { w.toFront(); w.editor.area().requestFocusInWindow(); });
        pause(400);
        if (!w.editor.area().isFocusOwner()) {
            java.awt.Point p = w.editor.area().getLocationOnScreen();
            r.mouseMove(p.x + 40, p.y + 20);
            r.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
            r.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
            pause(600);
        }
        return w.editor.area().isFocusOwner();
    }

    static javax.swing.JDialog waitTitled(String title) throws Exception {
        for (int i = 0; i < 40; i++) {
            for (java.awt.Window x : java.awt.Window.getWindows()) if (x instanceof javax.swing.JDialog d && d.isShowing() && title.equals(d.getTitle())) return d;
            pause(100);
        }
        return null;
    }

    static void connections(MainWindow w, AppState s) throws Exception {
        java.awt.Robot r = new java.awt.Robot();
        r.setAutoDelay(30);
        int before = s.connections.size();
        SwingUtilities.invokeLater(() -> w.openConnections(null, DBKind.TRINO));
        ConnectionsDialog d = waitDialog();
        if (d == null) { check(false, "вікно підключень відкрилось"); return; }
        pause(400);
        javax.swing.JTextField name = field(d, "Напр., Trino · прод");
        javax.swing.JPasswordField pw = find(d, javax.swing.JPasswordField.class);
        if (name == null || pw == null || !d.isFocused()) {
            check(false, "форма нового підключення (назва " + (name != null) + ", пароль " + (pw != null) + ", фокус " + d.isFocused() + ")");
            ui(d::dispose);
            return;
        }
        click(r, name);
        int m = Theme.MENU_MASK == java.awt.event.InputEvent.META_DOWN_MASK ? KeyEvent.VK_META : KeyEvent.VK_CONTROL;
        press(r, m, KeyEvent.VK_A);
        typeKeys(r, "qa trino");
        click(r, field(d, "trino.example.com"));
        typeKeys(r, "trino.invalid");
        click(r, pw);
        typeKeys(r, "x1");
        pause(200);
        press(r, KeyEvent.VK_ENTER);
        pause(400);
        Connection c = s.connections.stream().filter(x -> "qa trino".equals(x.name)).findFirst().orElse(null);
        check(!d.isShowing() && s.connections.size() == before + 1 && c != null && "trino.invalid".equals(c.host),
                "нове підключення: назва й хост натисканнями, ↵ — «Готово» (" + s.connections.size() + ")");
        check(c != null && "x1".equals(ferret.core.Passwords.get(c.id)), "пароль збережено у файлі паролів");
        String saved = Files.readString(Store.file("connections.json"));
        check(saved.contains("qa trino"), "підключення записано на диск");
        SwingUtilities.invokeLater(() -> w.openConnections(c == null ? null : c.id, null));
        ConnectionsDialog d2 = waitDialog();
        pause(400);
        if (d2 != null) {
            click(r, field(d2, "Напр., Trino · прод"));
            typeKeys(r, "zz");
            press(r, KeyEvent.VK_ESCAPE);
            pause(300);
        }
        check(d2 != null && !d2.isShowing() && s.connections.stream().anyMatch(x -> "qa trino".equals(x.name)), "Esc — вікно закрито, зміни не збережено");
    }

    // Драйвер H2 — з кешу звичайних перевірок (./build.sh кладе його в .cache): мережі тут не треба.
    static Connection h2Driver() throws Exception {
        Path jar = Path.of(System.getenv().getOrDefault("FERRET_TEST_CACHE", ".cache")).resolve("h2-2.5.252.jar");
        if (!Files.exists(jar)) return null;
        Files.createDirectories(ferret.core.Drivers.folder(DBKind.H2));
        Files.copy(jar, ferret.core.Drivers.folder(DBKind.H2).resolve(jar.getFileName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Store.saveJSON("drivers.json", java.util.Map.of("h2", "2.5.252"));
        Connection h2 = Connection.blank(DBKind.H2);
        h2.name = "H2 · тест";
        h2.user = "sa";
        h2.jdbcURL = "jdbc:h2:mem:ferret_ui;DB_CLOSE_DELAY=-1";
        h2.color = 3;
        return h2;
    }

    // Запит у вікні на справжній базі: «Виконати» мишею — 500 рядків і «Показати всі»; клік по ній — усі 600.
    static void live(MainWindow w, AppState s, Connection h2) throws Exception {
        java.awt.Robot r = new java.awt.Robot();
        r.setAutoDelay(30);
        ui(() -> s.newTab("select x as n, 'рядок ' || x as b\nfrom system_range(1, 600)", "H2", h2.id));
        pause(300);
        javax.swing.JButton run = button(w, "Виконати");
        click(r, run);
        boolean done = waitFor(() -> s.current.result != null || s.current.error != null);
        javax.swing.JTable table = find(w.result, javax.swing.JTable.class);
        check(done && s.current.error == null && table != null && table.getRowCount() == 500 && button(w.result, "Показати всі") != null,
                "«Виконати» мишею — 500 рядків, кнопка «Показати всі»" + (s.current.error != null ? " (" + s.current.error + ")" : ""));
        check(table != null && "рядок 1".equals(String.valueOf(table.getValueAt(0, 2))), "кирилиця в клітинці, № першою колонкою");
        click(r, button(w.result, "Показати всі"));
        boolean all = waitFor(() -> s.current.result != null && s.current.result.rows.size() == 600);
        pause(300);
        javax.swing.JTable t2 = find(w.result, javax.swing.JTable.class);
        check(all && t2 != null && t2.getRowCount() == 600 && button(w.result, "Показати всі") == null, "«Показати всі» — 600 рядків, кнопку сховано"
                + (all ? "" : " (рядків " + (s.current.result == null ? "—" : s.current.result.rows.size()) + ", іде " + s.current.running + ", помилка " + s.current.error
                + ", фокус " + java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() + ")"));
        ui(() -> { w.editor.area().setText("delete from t"); w.editor.area().setCaretPosition(3); });
        int m = Theme.MENU_MASK == java.awt.event.InputEvent.META_DOWN_MASK ? KeyEvent.VK_META : KeyEvent.VK_CONTROL;
        focusEditor(r, w);
        press(r, m, KeyEvent.VK_ENTER);
        boolean refused = waitFor(() -> s.current.error != null);
        check(refused && s.current.error.contains("лише читання"), "лише читання: delete не відправлено, видно причину");
    }

    static boolean waitFor(java.util.function.BooleanSupplier ok) throws Exception {
        for (int i = 0; i < 100; i++) {
            AtomicReference<Boolean> v = new AtomicReference<>(false);
            ui(() -> v.set(ok.getAsBoolean()));
            if (v.get()) return true;
            pause(100);
        }
        return false;
    }

    static javax.swing.JButton button(java.awt.Container c, String text) {
        for (java.awt.Component x : c.getComponents()) {
            if (x instanceof javax.swing.JButton b && text.equals(b.getText()) && b.isShowing()) return b;
            if (x instanceof java.awt.Container k) { var b = button(k, text); if (b != null) return b; }
        }
        return null;
    }

    static ConnectionsDialog waitDialog() throws Exception {
        for (int i = 0; i < 40; i++) {
            for (java.awt.Window x : java.awt.Window.getWindows()) if (x instanceof ConnectionsDialog d && d.isShowing()) return d;
            pause(100);
        }
        return null;
    }

    static javax.swing.JTextField field(java.awt.Container c, String placeholder) {
        for (java.awt.Component x : c.getComponents()) {
            if (x instanceof javax.swing.JTextField f && !(x instanceof javax.swing.JPasswordField)
                    && placeholder.equals(f.getClientProperty(com.formdev.flatlaf.FlatClientProperties.PLACEHOLDER_TEXT)) && f.isShowing()) return f;
            if (x instanceof java.awt.Container k) { var f = field(k, placeholder); if (f != null) return f; }
        }
        return null;
    }

    static <C> C find(java.awt.Container c, Class<C> type) {
        for (java.awt.Component x : c.getComponents()) {
            if (type.isInstance(x) && x.isShowing()) return type.cast(x);
            if (x instanceof java.awt.Container k) { C f = find(k, type); if (f != null) return f; }
        }
        return null;
    }

    static void click(java.awt.Robot r, java.awt.Component c) throws Exception {
        if (c == null) return;
        java.awt.Point p = c.getLocationOnScreen();
        r.mouseMove(p.x + c.getWidth() / 2, p.y + c.getHeight() / 2);
        r.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
        r.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
        r.waitForIdle();
        pause(150);
    }

    static void typeKeys(java.awt.Robot r, String s) throws Exception {
        for (char ch : s.toCharArray()) {
            int k = ch == ' ' ? KeyEvent.VK_SPACE : ch == '.' ? KeyEvent.VK_PERIOD : KeyEvent.getExtendedKeyCodeForChar(ch);
            r.keyPress(k);
            r.keyRelease(k);
        }
        r.waitForIdle();
        pause(100);
    }

    static void press(java.awt.Robot r, int... keys) throws Exception {
        for (int k : keys) r.keyPress(k);
        for (int i = keys.length - 1; i >= 0; i--) r.keyRelease(keys[i]);
        r.waitForIdle();
        pause(150);
    }

    static void type(RSyntaxTextArea a, String s) throws Exception {
        for (char c : s.toCharArray()) {
            ui(() -> a.dispatchEvent(new KeyEvent(a, KeyEvent.KEY_TYPED, System.currentTimeMillis(), 0, KeyEvent.VK_UNDEFINED, c)));
            pause(40);
        }
    }

    static void key(RSyntaxTextArea a, int code, int mods) throws Exception {
        ui(() -> {
            a.dispatchEvent(new KeyEvent(a, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), mods, code, KeyEvent.CHAR_UNDEFINED));
            if (code == KeyEvent.VK_ENTER || code == KeyEvent.VK_TAB) {
                char ch = code == KeyEvent.VK_ENTER ? '\n' : '\t';
                a.dispatchEvent(new KeyEvent(a, KeyEvent.KEY_TYPED, System.currentTimeMillis(), mods, KeyEvent.VK_UNDEFINED, ch));
            }
            a.dispatchEvent(new KeyEvent(a, KeyEvent.KEY_RELEASED, System.currentTimeMillis(), mods, code, KeyEvent.CHAR_UNDEFINED));
        });
        pause(60);
    }

    static boolean findButton(java.awt.Container c, String text) {
        for (java.awt.Component x : c.getComponents()) {
            if (x instanceof javax.swing.JButton b && text.equals(b.getText()) && b.isVisible()) return true;
            if (x instanceof java.awt.Container k && findButton(k, text)) return true;
        }
        return false;
    }

    static boolean containsTitle(java.awt.Container c, String title) {
        for (java.awt.Component x : c.getComponents()) {
            if (x instanceof javax.swing.JLabel l && title.equals(l.getText()) && l.isShowing()) return true;
            if (x instanceof java.awt.Container k && containsTitle(k, title)) return true;
        }
        return false;
    }
}
