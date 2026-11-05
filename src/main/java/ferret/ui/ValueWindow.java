package ferret.ui;

import ferret.core.I18n;
import ferret.core.Json;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.text.DefaultCaret;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.KeyEvent;

// Повне значення клітинки результату (подвійний клік або ⇧↵, Перегляд → Повне значення клітинки): текст як є, з
// переносом рядків. Для JSON — «Форматувати JSON» (лише вигляд); «Копіювати» завжди бере значення з бази як є.
// Значення — текст з бази: показується в текстовому полі, ніякого HTML.
public final class ValueWindow {
    private ValueWindow() { }

    static void show(JFrame owner, String column, String type, String value, int row) {
        JDialog d = new JDialog(owner, I18n.t("cell.title", column, row), false);
        d.setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);   // інакше закрите хрестиком лише ховається й тримає значення
        RSyntaxTextArea a = new RSyntaxTextArea();
        EditorPanel.style(a);
        a.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_NONE);
        a.setEditable(false);
        a.setLineWrap(true);
        ((DefaultCaret) a.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
        a.setText(value == null ? "NULL" : value);
        if (value == null) a.setForeground(Theme.alpha(Theme.MUTED, 150));
        RTextScrollPane sp = new RTextScrollPane(a, false);
        sp.setBorder(BorderFactory.createEmptyBorder());

        JPanel head = new JPanel();
        head.setLayout(new BoxLayout(head, BoxLayout.X_AXIS));
        head.setBackground(Theme.PANEL);
        head.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.LINE), BorderFactory.createEmptyBorder(10, 18, 10, 18)));
        head.add(Ui.label(column, Theme.TEXT, true, 14));
        head.add(Ui.gap(8));
        long n = value == null ? 0 : value.codePointCount(0, value.length());
        head.add(Ui.label((type == null ? "" : type.toLowerCase(java.util.Locale.ROOT) + " · ") + I18n.plural("cell.chars", n), Theme.MUTED, false, 13));
        head.add(Box.createHorizontalGlue());

        String pretty = pretty(value);
        JButton format = Ui.plainButton(I18n.t("cell.formatJson"), null);
        format.setVisible(pretty != null);
        boolean[] formatted = {false};
        format.addActionListener(e -> {
            formatted[0] = !formatted[0];
            a.setSyntaxEditingStyle(formatted[0] ? SyntaxConstants.SYNTAX_STYLE_JSON : SyntaxConstants.SYNTAX_STYLE_NONE);
            a.setText(formatted[0] ? pretty : value);
            a.setCaretPosition(0);
            format.setText(I18n.t(formatted[0] ? "cell.asStored" : "cell.formatJson"));
        });
        JButton copy = Ui.plainButton(I18n.t("cell.copy"), null);
        copy.setEnabled(value != null);
        copy.addActionListener(e -> ResultPanel.clip(value));
        JPanel foot = new JPanel();
        foot.setLayout(new BoxLayout(foot, BoxLayout.X_AXIS));
        foot.setBackground(Theme.PANEL);
        foot.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.LINE), BorderFactory.createEmptyBorder(10, 14, 10, 14)));
        foot.add(format);
        foot.add(Box.createHorizontalGlue());
        foot.add(copy);

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Theme.BG);
        root.add(head, BorderLayout.NORTH);
        root.add(sp, BorderLayout.CENTER);
        root.add(foot, BorderLayout.SOUTH);
        d.setContentPane(root);
        d.getRootPane().registerKeyboardAction(e -> d.dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        d.getRootPane().registerKeyboardAction(e -> d.dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_W, Theme.MENU_MASK), JComponent.WHEN_IN_FOCUSED_WINDOW);
        d.setSize(640, 420);
        d.setMinimumSize(new Dimension(360, 220));
        d.setLocationRelativeTo(owner);
        d.setVisible(true);
    }

    // JSON-обʼєкт чи масив — з відступами; інше (і зламаний JSON) — null, кнопки тоді немає. Лише переставляє пробіли
    // й переноси між лексемами: числа й рядки лишаються знак у знак (розбір у double змінив би довгі дробові числа).
    public static String pretty(String v) {
        if (v == null) return null;
        String t = v.strip();
        if (!(t.startsWith("{") && t.endsWith("}") || t.startsWith("[") && t.endsWith("]"))) return null;
        try {
            Json.parse(t);
        } catch (Exception notJson) {
            return null;
        }
        StringBuilder b = new StringBuilder();
        int depth = 0;
        boolean str = false, esc = false;
        for (char c : t.toCharArray()) {
            if (str) {
                b.append(c);
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') str = false;
                continue;
            }
            switch (c) {
                case '"' -> { str = true; b.append(c); }
                case '{', '[' -> { b.append(c); newline(b, ++depth); }
                case '}', ']' -> { newline(b, --depth); b.append(c); }
                case ',' -> { b.append(c); newline(b, depth); }
                case ':' -> b.append(": ");
                default -> { if (!Character.isWhitespace(c)) b.append(c); }
            }
        }
        return b.toString().replaceAll("([\\[{])\\n\\s*([\\]}])", "$1$2");   // порожні {} і [] — в один рядок
    }

    static void newline(StringBuilder b, int depth) { b.append('\n').append("  ".repeat(Math.max(0, depth))); }
}
