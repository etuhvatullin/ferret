package ferret.ui;

import ferret.core.Format;
import ferret.core.I18n;
import ferret.core.Model.Connection;
import ferret.core.Model.DBKind;
import ferret.core.Range;
import ferret.core.SqlText;
import ferret.core.SqlText.Suggestion;
import ferret.ui.AppState.ConsoleTab;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.SyntaxScheme;
import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rtextarea.Gutter;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.fife.ui.rtextarea.SearchContext;
import org.fife.ui.rtextarea.SearchEngine;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

// Редактор SQL: RSyntaxTextArea на кожну відкриту вкладку (своя історія ⌘Z), підсвітка кольорами «Нори», номери рядків
// (номер рядка з курсором — медом), відступи: Tab / ⇧Tab на виділенні (уміє сам), Enter тримає відступ, ⌘/ — коментар.
// Свій список підказок (після FROM / JOIN — таблиці; деінде — слова діалекту з 2 літер; Esc — завжди): Tab вставляє,
// ↵ — лише після вибору стрілками. ⌘↵ — запит під курсором; у підзапиті — вибір «Підзапит / Увесь запит».
public final class EditorPanel extends JPanel {
    private final AppState state;
    private final CardLayout cards = new CardLayout();
    private final JPanel deck = new JPanel(cards);
    private final Map<UUID, Ed> editors = new HashMap<>();
    final JPanel findBar = new JPanel(new BorderLayout(6, 0));
    private final JTextField findField = new JTextField();
    private final JCheckBox matchCase = new JCheckBox("Aa");
    public Consumer<String> onRun = sql -> { };

    final class Ed {
        final ConsoleTab tab;
        final RSyntaxTextArea area;
        final RTextScrollPane scroll;
        final SuggestionPopup popup;
        Range popupRange;
        boolean popupExplicit, popupNavigated, typedIdentifier;
        List<SqlText.RunChoice> choosing;
        Object mark;

        Ed(ConsoleTab tab) {
            this.tab = tab;
            area = new RSyntaxTextArea();
            area.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_SQL);
            area.setText(tab.sql);
            area.discardAllEdits();
            style(area);
            scroll = new RTextScrollPane(area);
            scroll.setBorder(BorderFactory.createEmptyBorder());
            Gutter g = scroll.getGutter();
            g.setBackground(Theme.BG);
            g.setLineNumberColor(Theme.alpha(Theme.MUTED, 170));
            g.setCurrentLineNumberColor(Theme.ACCENT);
            g.setLineNumberFont(Theme.mono(11));
            g.setBorderColor(Theme.LINE);
            popup = new SuggestionPopup(area);
            popup.onPick = this::picked;
            area.getDocument().addDocumentListener(new DocumentListener() {
                @Override public void insertUpdate(DocumentEvent e) { changed(e, true); }
                @Override public void removeUpdate(DocumentEvent e) { changed(e, false); }
                @Override public void changedUpdate(DocumentEvent e) { }
            });
            area.addCaretListener(e -> tab.cursor = e.getDot());
            area.addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) { hidePopup(); }
            });
            area.addFocusListener(new java.awt.event.FocusAdapter() {
                @Override public void focusLost(java.awt.event.FocusEvent e) { hidePopup(); }
            });
            area.addKeyListener(new KeyAdapter() {
                @Override public void keyPressed(KeyEvent e) { key(e); }

                // символ із затиснутим ⌘ / Ctrl — це гаряча клавіша, а не текст: під FlatLaf ⌘/ ставив коментар і ще
                // вписував «/» замість виділеного, Ctrl+1…9 — цифру. Ctrl+Alt (AltGr на Windows) набирає символи — його лишаємо
                @Override public void keyTyped(KeyEvent e) {
                    int m = e.getModifiersEx();
                    if ((m & (InputEvent.CTRL_DOWN_MASK | InputEvent.META_DOWN_MASK)) != 0 && (m & InputEvent.ALT_DOWN_MASK) == 0) e.consume();
                }
            });
            bind(area, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, Theme.MENU_MASK), "ferret-run", EditorPanel.this::requestRun);
            bind(area, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "ferret-complete", () -> { if (!popup.isVisible()) refreshSuggestions(true); });
            // ⌘[ редактор забирає під «парну дужку» — відступ із меню тоді б не спрацював; ⌘] — для пари
            bind(area, KeyStroke.getKeyStroke(KeyEvent.VK_OPEN_BRACKET, Theme.MENU_MASK), "ferret-outdent", () -> indent(true));
            bind(area, KeyStroke.getKeyStroke(KeyEvent.VK_CLOSE_BRACKET, Theme.MENU_MASK), "ferret-indent", () -> indent(false));
            int caret = Math.min(tab.cursor, area.getDocument().getLength());
            area.setCaretPosition(caret);
        }

        void changed(DocumentEvent e, boolean insert) {
            tab.sql = area.getText();
            tab.modified = Instant.now();
            state.scheduleSave();
            if (insert && e.getLength() == 1) {
                try {
                    char ch = area.getDocument().getText(e.getOffset(), 1).charAt(0);
                    typedIdentifier = SqlText.identifierChar(ch);
                } catch (BadLocationException ignored) { }
            }
            boolean typed = typedIdentifier;
            typedIdentifier = false;
            if (typed || popup.isVisible()) SwingUtilities.invokeLater(() -> refreshSuggestions(popupExplicit && popup.isVisible()));
        }

        // Клавіші списку: ↑ ↓ — вибір, Tab — вставити, ↵ — лише після стрілок, Esc — закрити. Список вибору запиту —
        // ↑ ↓ підсвічують, ↵ / Tab / повторний ⌘↵ запускають, будь-яка інша клавіша — скасовує.
        void key(KeyEvent e) {
            if (choosing != null && popup.isVisible()) {
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_DOWN, KeyEvent.VK_UP -> {
                        popup.move(e.getKeyCode() == KeyEvent.VK_DOWN ? 1 : -1);
                        mark(choosing.get(popup.selected()).range());
                        e.consume();
                    }
                    case KeyEvent.VK_ENTER, KeyEvent.VK_TAB -> {
                        if ((e.getModifiersEx() & Theme.MENU_MASK) != 0 && e.getKeyCode() == KeyEvent.VK_ENTER) return;   // ⌘↵ — сам requestRun
                        runChoice(choosing.get(popup.selected()));
                        e.consume();
                    }
                    case KeyEvent.VK_ESCAPE -> { hidePopup(); e.consume(); }
                    case KeyEvent.VK_SHIFT, KeyEvent.VK_META, KeyEvent.VK_CONTROL, KeyEvent.VK_ALT -> { }
                    default -> hidePopup();
                }
                return;
            }
            if (!popup.isVisible()) return;
            switch (e.getKeyCode()) {
                case KeyEvent.VK_DOWN -> { popup.move(1); popupNavigated = true; e.consume(); }
                case KeyEvent.VK_UP -> { popup.move(-1); popupNavigated = true; e.consume(); }
                case KeyEvent.VK_TAB -> { if (popup.current() != null) accept(popup.current()); e.consume(); }
                case KeyEvent.VK_ENTER -> {
                    if (popupNavigated && e.getModifiersEx() == 0 && popup.current() != null) { accept(popup.current()); e.consume(); }
                    else popup.hide();   // ↵ без вибору — перенос рядка, а не вставка (набрав «from» і ↵ — не from_unixtime)
                }
                case KeyEvent.VK_ESCAPE -> { popup.hide(); e.consume(); }
                case KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT -> popup.hide();
                default -> { }
            }
        }

        void refreshSuggestions(boolean explicit) {
            if (choosing != null) return;
            String text = area.getText();
            int cur = area.getCaretPosition();
            if (area.getSelectionStart() != area.getSelectionEnd()) { popup.hide(); return; }
            Range range = null;
            List<Suggestion> items = List.of();
            Range tr = SqlText.tableCompletionRange(text, cur);
            if (tr != null && (tr.length() > 0 || explicit)) {
                range = tr;
                items = state.tableSuggestions(tab, tr.of(text), () -> { if (area.isFocusOwner()) refreshSuggestions(false); });
            } else if (!SqlText.inCommentOrString(text, cur)) {
                Range r = SqlText.wordFragment(text, cur);
                String frag = r.of(text);
                if (explicit || (frag.length() >= 2 && Character.isLetter(frag.charAt(0)))) {
                    range = r;
                    items = state.wordSuggestions(tab, frag);
                }
            }
            if (range == null || items.isEmpty()) { popup.hide(); return; }
            if (!popup.isVisible()) popupNavigated = false;
            popupRange = range;
            popupExplicit = explicit;
            popup.show(items, below(range.location()));
        }

        Point below(int offset) {
            try {
                Rectangle2D r = area.modelToView2D(offset);
                return new Point((int) r.getX(), (int) (r.getY() + r.getHeight()));
            } catch (BadLocationException e) {
                return new Point(0, 0);
            }
        }

        void accept(Suggestion s) {
            popup.hide();
            if (popupRange == null) return;
            area.replaceRange(s.text(), popupRange.location(), popupRange.end());
            typedIdentifier = false;
        }

        void picked(Suggestion s) {
            if (choosing != null) {
                for (SqlText.RunChoice c : choosing) if (c.title().equals(s.label())) { runChoice(c); return; }
            } else {
                accept(s);
            }
        }

        void hidePopup() {
            popup.hide();
            if (choosing != null) {
                choosing = null;
                unmark();
            }
        }

        void mark(Range r) {
            unmark();
            try {
                mark = area.getHighlighter().addHighlight(r.location(), r.end(), new DefaultHighlighter.DefaultHighlightPainter(Theme.alpha(Theme.ACCENT, 55)));
            } catch (BadLocationException ignored) { }
        }

        void unmark() {
            if (mark != null) area.getHighlighter().removeHighlight(mark);
            mark = null;
        }

        void runChoice(SqlText.RunChoice c) {
            String text = area.getText();
            hidePopup();
            mark(c.range());   // коротко видно, що саме пішло на сервер
            new javax.swing.Timer(400, e -> { if (choosing == null) unmark(); ((javax.swing.Timer) e.getSource()).stop(); }).start();
            onRun.accept(SqlText.trim(c.range().of(text)));
        }
    }

    public EditorPanel(AppState state) {
        super(new BorderLayout());
        this.state = state;
        setBackground(Theme.BG);
        deck.setBackground(Theme.BG);
        add(deck, BorderLayout.CENTER);
        buildFindBar();
        state.on(e -> { if (e.equals("tabs") || e.equals("consoles")) sync(); });
        sync();
    }

    static void bind(JComponent c, KeyStroke ks, String name, Runnable r) {
        c.getInputMap(JComponent.WHEN_FOCUSED).put(ks, name);
        c.getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { r.run(); }
        });
    }

    static void style(RSyntaxTextArea a) {
        a.setFont(Theme.mono(13));
        a.setBackground(Theme.BG);
        a.setForeground(Theme.TEXT);
        a.setCaretColor(Theme.ACCENT);
        a.setSelectionColor(Theme.alpha(Theme.ACCENT, 77));
        // усі збіги пошуку — тихий мед (типово яскраво-жовтий, і текст під ним не читався); поточний — виділенням
        a.setMarkAllHighlightColor(Theme.alpha(Theme.ACCENT, 38));
        a.setSelectedTextColor(null);
        a.setUseSelectedTextColor(false);
        a.setHighlightCurrentLine(false);
        a.setAntiAliasingEnabled(true);
        a.setTabSize(4);
        a.setTabsEmulated(true);
        a.setAutoIndentEnabled(true);
        a.setBracketMatchingEnabled(true);
        a.setMatchedBracketBGColor(Theme.alpha(Theme.ACCENT, 50));
        a.setMatchedBracketBorderColor(Theme.ACCENT);
        a.setCodeFoldingEnabled(false);
        a.setMarkOccurrences(false);
        a.setMargin(new java.awt.Insets(8, 6, 8, 6));
        SyntaxScheme s = a.getSyntaxScheme();
        color(s, Token.RESERVED_WORD, Theme.KEYWORD);
        color(s, Token.RESERVED_WORD_2, Theme.KEYWORD);
        color(s, Token.DATA_TYPE, Theme.KEYWORD);
        color(s, Token.FUNCTION, Theme.TEXT);
        color(s, Token.LITERAL_CHAR, Theme.STRING);
        color(s, Token.LITERAL_STRING_DOUBLE_QUOTE, Theme.QUOTED);
        color(s, Token.LITERAL_BACKQUOTE, Theme.QUOTED);
        color(s, Token.LITERAL_NUMBER_DECIMAL_INT, Theme.NUMBER);
        color(s, Token.LITERAL_NUMBER_FLOAT, Theme.NUMBER);
        color(s, Token.LITERAL_NUMBER_HEXADECIMAL, Theme.NUMBER);
        color(s, Token.LITERAL_BOOLEAN, Theme.NUMBER);
        color(s, Token.COMMENT_EOL, Theme.COMMENT);
        color(s, Token.COMMENT_MULTILINE, Theme.COMMENT);
        color(s, Token.IDENTIFIER, Theme.TEXT);
        color(s, Token.OPERATOR, Theme.TEXT);
        color(s, Token.SEPARATOR, Theme.TEXT);
        color(s, Token.VARIABLE, Theme.QUOTED);
        color(s, Token.PREPROCESSOR, Theme.TEXT);
        if (s.getStyle(Token.COMMENT_EOL) != null) s.getStyle(Token.COMMENT_EOL).font = Theme.mono(13);
        if (s.getStyle(Token.COMMENT_MULTILINE) != null) s.getStyle(Token.COMMENT_MULTILINE).font = Theme.mono(13);
        a.revalidate();
    }

    static void color(SyntaxScheme s, int type, Color c) {
        if (s.getStyle(type) != null) s.getStyle(type).foreground = c;
    }

    // Показати редактор поточної вкладки; редактори закритих вкладок прибираються.
    void sync() {
        List<UUID> open = new ArrayList<>();
        for (ConsoleTab t : state.tabs) open.add(t.id);
        for (UUID id : new ArrayList<>(editors.keySet())) {
            if (!open.contains(id)) {
                Ed ed = editors.remove(id);
                ed.popup.hide();
                deck.remove(ed.scroll);
            }
        }
        ConsoleTab t = state.current;
        if (t == null) return;
        Ed ed = editors.get(t.id);
        if (ed == null) {
            ed = new Ed(t);
            editors.put(t.id, ed);
            deck.add(ed.scroll, t.id.toString());
        } else if (!ed.area.getText().equals(t.sql)) {
            ed.area.setText(t.sql);
        }
        cards.show(deck, t.id.toString());
        Ed f = ed;
        SwingUtilities.invokeLater(() -> {
            f.area.requestFocusInWindow();
            try {
                Rectangle2D r = f.area.modelToView2D(f.area.getCaretPosition());
                if (r != null) f.area.scrollRectToVisible(new Rectangle((int) r.getX(), (int) r.getY() - 40, 1, (int) r.getHeight() + 80));
            } catch (BadLocationException ignored) { }
        });
    }

    public Ed current() { return state.current == null ? null : editors.get(state.current.id); }

    public RSyntaxTextArea area() { Ed e = current(); return e == null ? null : e.area; }

    // MARK: запуск (⌘↵): межі запиту визначає редактор

    // Виділення або запит під курсором — одразу; у підзапиті — список «Підзапит / Увесь запит», вибране підсвічене.
    public void requestRun() {
        Ed ed = current();
        if (ed == null) return;
        if (ed.choosing != null && ed.popup.isVisible()) { ed.runChoice(ed.choosing.get(ed.popup.selected())); return; }
        String text = ed.area.getText();
        int a = ed.area.getSelectionStart(), b = ed.area.getSelectionEnd();
        List<SqlText.RunChoice> choices = SqlText.runChoices(text, ed.area.getCaretPosition(), new Range(a, b - a));
        if (choices.isEmpty()) return;
        if (choices.size() == 1) { ed.runChoice(choices.get(0)); return; }
        ed.choosing = choices;
        ed.mark(choices.get(0).range());
        List<Suggestion> items = new ArrayList<>();
        for (SqlText.RunChoice c : choices) {
            String line = c.range().of(text).lines().findFirst().orElse("").trim();
            items.add(new Suggestion("", c.title(), line, false, Suggestion.Kind.QUERY));
        }
        ed.popup.show(items, ed.below(ed.area.getCaretPosition()));
    }

    // MARK: письмо

    // ⌥⌘L — форматувати виділене або запит під курсором; не вийшло — звук, текст не чіпається. Один крок ⌘Z.
    public void formatQuery(DBKind kind) {
        Ed ed = current();
        if (ed == null) return;
        String text = ed.area.getText();
        int a = ed.area.getSelectionStart(), b = ed.area.getSelectionEnd();
        Range r = a != b ? new Range(a, b - a) : SqlText.statementRange(text, ed.area.getCaretPosition());
        String f = r == null ? null : Format.formatSQL(r.of(text), kind);
        if (f == null) { Toolkit.getDefaultToolkit().beep(); return; }
        int lineStart = text.lastIndexOf('\n', r.location() - 1) + 1;
        String lead = text.substring(lineStart, r.location()).replaceAll("[^ \t].*", "");
        String repl = f.replace("\n", "\n" + lead);
        if (repl.equals(r.of(text))) return;
        ed.area.beginAtomicEdit();
        try { ed.area.replaceRange(repl, r.location(), r.end()); } finally { ed.area.endAtomicEdit(); }
        ed.area.setCaretPosition(r.location());
    }

    public void toggleComment() {
        RSyntaxTextArea a = area();
        if (a == null) return;
        var act = a.getActionMap().get("RSTA.ToggleCommentAction");
        if (act != null) act.actionPerformed(new ActionEvent(a, ActionEvent.ACTION_PERFORMED, "toggle"));
    }

    public void indent(boolean outdent) {
        RSyntaxTextArea a = area();
        if (a == null) return;
        var act = a.getActionMap().get(outdent ? "RSTA.DecreaseIndentAction" : "insert-tab");
        if (act != null) act.actionPerformed(new ActionEvent(a, ActionEvent.ACTION_PERFORMED, "indent"));
    }

    public void insertText(String s) {
        RSyntaxTextArea a = area();
        if (a == null) return;
        a.replaceSelection(s);
        a.requestFocusInWindow();
    }

    // MARK: пошук (⌘F)

    private void buildFindBar() {
        findBar.setBackground(Theme.PANEL);
        findBar.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.LINE), BorderFactory.createEmptyBorder(4, 8, 4, 8)));
        findField.putClientProperty(com.formdev.flatlaf.FlatClientProperties.PLACEHOLDER_TEXT, I18n.t("editor.find"));
        findField.putClientProperty(com.formdev.flatlaf.FlatClientProperties.TEXT_FIELD_LEADING_ICON, Ui.icon(Ui.Glyph.SEARCH, Theme.MUTED));
        JPanel buttons = new JPanel();
        buttons.setOpaque(false);
        var prev = Ui.plainButton("↑", null);
        var next = Ui.plainButton("↓", null);
        var close = Ui.iconButton(Ui.icon(Ui.Glyph.CLOSE, Theme.MUTED), I18n.t("editor.closeFind"));
        prev.addActionListener(e -> find(false));
        next.addActionListener(e -> find(true));
        close.addActionListener(e -> closeFind());
        buttons.add(matchCase);
        buttons.add(prev);
        buttons.add(next);
        buttons.add(close);
        findBar.add(findField, BorderLayout.CENTER);
        findBar.add(buttons, BorderLayout.EAST);
        findField.addActionListener(e -> find(true));
        bind(findField, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close-find", this::closeFind);
        bind(findField, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK), "find-prev", () -> find(false));
    }

    public void showFind() {
        if (findBar.getParent() == null) {
            add(findBar, BorderLayout.NORTH);
            revalidate();
        }
        RSyntaxTextArea a = area();
        if (a != null && a.getSelectedText() != null && !a.getSelectedText().contains("\n")) findField.setText(a.getSelectedText());
        findField.requestFocusInWindow();
        findField.selectAll();
    }

    void closeFind() {
        remove(findBar);
        revalidate();
        repaint();
        for (Ed e : editors.values()) e.area.clearMarkAllHighlights();   // підсвітка збігів зникає разом із панеллю
        RSyntaxTextArea a = area();
        if (a != null) a.requestFocusInWindow();
    }

    public void find(boolean forward) {
        RSyntaxTextArea a = area();
        if (a == null || findField.getText().isEmpty()) return;
        SearchContext ctx = new SearchContext(findField.getText(), matchCase.isSelected());
        ctx.setSearchForward(forward);
        ctx.setWholeWord(false);
        ctx.setSearchWrap(true);
        if (!SearchEngine.find(a, ctx).wasFound()) Toolkit.getDefaultToolkit().beep();
    }

    public Connection connectionOfCurrent() { return state.connection(state.current); }
}
