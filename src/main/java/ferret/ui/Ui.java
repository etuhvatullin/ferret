package ferret.ui;

import com.formdev.flatlaf.FlatClientProperties;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

// Дрібниці вікна: підписи, кольорові крапки серверів, кнопки, намальовані значки (не емодзі: на Mac і Windows
// вони різні й тягнуть свій колір).
public final class Ui {
    private Ui() { }

    // Текст із бази — лише як текст. Рядок, що починається з «<html>», Swing малює як сторінку, разом із картинками
    // з адрес у ньому, а значення в базі може вписати будь-хто. Тому HTML вимкнено в усіх мітках Ferret; де властивість
    // не передати (підказки, заголовок таблиці) — plain().
    public static <T extends JComponent> T noHtml(T c) {
        c.putClientProperty("html.disable", Boolean.TRUE);
        return c;
    }

    // Пробіл попереду — і Swing уже не бачить HTML: він дивиться лише на перші шість знаків
    public static String plain(String s) {
        return javax.swing.plaf.basic.BasicHTML.isHTMLString(s) ? " " + s : s;
    }

    public static JLabel label(String text, Color color, boolean bold, float size) {
        JLabel l = noHtml(new JLabel());
        l.setText(text);   // після noHtml: HTML-подання будується в момент, коли міняється текст
        l.setForeground(color);
        l.setFont(l.getFont().deriveFont(bold ? Font.BOLD : Font.PLAIN, size));
        return l;
    }

    public static JComponent dot(Color c) {
        JComponent d = new JComponent() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = smooth(g);
                g2.setColor(c);
                g2.fill(new Ellipse2D.Float(1, (getHeight() - 9) / 2f, 9, 9));
                g2.dispose();
            }
        };
        d.setPreferredSize(new Dimension(11, 11));
        d.setMinimumSize(new Dimension(11, 11));
        d.setMaximumSize(new Dimension(11, 11));
        return d;
    }

    public static JPanel row(int top, int left, int bottom, int right) {
        JPanel r = new JPanel();
        r.setLayout(new BoxLayout(r, BoxLayout.X_AXIS));
        r.setOpaque(false);
        r.setBorder(BorderFactory.createEmptyBorder(top, left, bottom, right));
        r.setAlignmentX(Component.LEFT_ALIGNMENT);
        return r;
    }

    public static Component gap(int w) { return Box.createHorizontalStrut(w); }

    // Кнопки в один рядок, що НІКОЛИ не переноситься; найбільший розмір — поточний бажаний, рахується вже на екрані.
    // Був FlowLayout із максимумом, замороженим до показу вікна: на Windows із масштабом 125% текст на екрані на 1 px
    // ширший, і друга кнопка заставки («Обновить») зʼїжджала в другий рядок, якого не видно.
    public static JComponent buttons(int gap, JButton... bs) {
        Box r = new Box(BoxLayout.X_AXIS) {
            @Override public Dimension getMaximumSize() { return getPreferredSize(); }
        };
        for (int i = 0; i < bs.length; i++) {
            if (i > 0) r.add(gap(gap));
            r.add(bs[i]);
        }
        return r;
    }

    // Кнопка-дія медом (темний текст на меду — контраст ~9:1).
    public static JButton accentButton(String text, Icon icon) {
        JButton b = new JButton(text, icon);
        b.putClientProperty(FlatClientProperties.STYLE, "background: #F2A33A; foreground: #1F1A15; disabledText: #6B5A40; borderWidth: 0; "
                + "focusWidth: 0; innerFocusWidth: 0; arc: 10; font: bold; hoverBackground: #F6B354; pressedBackground: #D98F2C; margin: 4,12,4,12");
        b.setFocusable(false);
        return b;
    }

    public static JButton plainButton(String text, Icon icon) {
        JButton b = new JButton(text, icon);
        b.putClientProperty(FlatClientProperties.STYLE, "borderWidth: 0; focusWidth: 0; innerFocusWidth: 0; arc: 10; margin: 4,10,4,10");
        b.setFocusable(false);
        return b;
    }

    public static JButton iconButton(Icon icon, String tip) {
        JButton b = new JButton(icon);
        b.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
        b.setToolTipText(tip);
        b.setFocusable(false);
        return b;
    }

    public static Graphics2D smooth(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        return g2;
    }

    public static void fillRounded(Graphics g, Color c, int x, int y, int w, int h, int arc) {
        Graphics2D g2 = smooth(g);
        g2.setColor(c);
        g2.fill(new RoundRectangle2D.Float(x, y, w, h, arc, arc));
        g2.dispose();
    }

    // MARK: значки (14×14, лінія 1.5)

    public enum Glyph { PLAY, STOP, GEAR, LOCK, REFRESH, PLUS, MINUS, COPY, CLOSE, HISTORY, SEARCH, CHEVRON, FOLDER, TABLE, COLUMN, SWAP, WARN, DOWNLOAD }

    public static Icon icon(Glyph g, Color color) { return icon(g, color, 14); }

    public static Icon icon(Glyph glyph, Color color, int size) {
        return new Icon() {
            @Override public int getIconWidth() { return size; }

            @Override public int getIconHeight() { return size; }

            @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = smooth(g);
                g2.translate(x, y);
                g2.scale(size / 14.0, size / 14.0);
                g2.setColor(c != null && !c.isEnabled() ? Theme.alpha(color, 90) : color);
                g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                draw(g2, glyph);
                g2.dispose();
            }
        };
    }

    private static void draw(Graphics2D g, Glyph glyph) {
        switch (glyph) {
            case PLAY -> {
                Path2D p = new Path2D.Float();
                p.moveTo(3.5, 2); p.lineTo(12, 7); p.lineTo(3.5, 12); p.closePath();
                g.fill(p);
            }
            case STOP -> g.fill(new RoundRectangle2D.Float(3, 3, 8, 8, 2, 2));
            case GEAR -> {
                g.draw(new Ellipse2D.Float(4.5f, 4.5f, 5, 5));
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI / 4 * i;
                    g.draw(new Line2D.Double(7 + Math.cos(a) * 4.2, 7 + Math.sin(a) * 4.2, 7 + Math.cos(a) * 6, 7 + Math.sin(a) * 6));
                }
            }
            case LOCK -> {
                g.fill(new RoundRectangle2D.Float(2.5f, 6, 9, 6.5f, 2, 2));
                g.draw(new Arc2D.Float(4.2f, 1.8f, 5.6f, 7, 0, 180, Arc2D.OPEN));
                g.draw(new Line2D.Float(4.2f, 5.3f, 4.2f, 6.5f));
                g.draw(new Line2D.Float(9.8f, 5.3f, 9.8f, 6.5f));
            }
            case REFRESH -> {
                g.draw(new Arc2D.Float(2.5f, 2.5f, 9, 9, 40, 280, Arc2D.OPEN));
                Path2D p = new Path2D.Float();
                p.moveTo(10.5, 1.8); p.lineTo(11.2, 4.6); p.lineTo(8.4, 4.9);
                g.draw(p);
            }
            case PLUS -> { g.draw(new Line2D.Float(7, 2.5f, 7, 11.5f)); g.draw(new Line2D.Float(2.5f, 7, 11.5f, 7)); }
            case MINUS -> g.draw(new Line2D.Float(2.5f, 7, 11.5f, 7));
            case COPY -> {
                g.draw(new RoundRectangle2D.Float(4.5f, 4.5f, 7.5f, 7.5f, 2, 2));
                g.draw(new RoundRectangle2D.Float(2, 2, 7.5f, 7.5f, 2, 2));
            }
            case CLOSE -> { g.draw(new Line2D.Float(3.5f, 3.5f, 10.5f, 10.5f)); g.draw(new Line2D.Float(10.5f, 3.5f, 3.5f, 10.5f)); }
            case HISTORY -> {
                g.draw(new Arc2D.Float(1.8f, 1.8f, 10.4f, 10.4f, 100, 300, Arc2D.OPEN));
                g.draw(new Line2D.Float(7, 4.2f, 7, 7.2f));
                g.draw(new Line2D.Float(7, 7.2f, 9.2f, 8.6f));
                Path2D p = new Path2D.Float();
                p.moveTo(1.2, 3.2); p.lineTo(2.6, 5.4); p.lineTo(4.6, 4);
                g.draw(p);
            }
            case SEARCH -> { g.draw(new Ellipse2D.Float(2, 2, 7.5f, 7.5f)); g.draw(new Line2D.Float(8.4f, 8.4f, 12, 12)); }
            case CHEVRON -> {
                Path2D p = new Path2D.Float();
                p.moveTo(3.5, 5.5); p.lineTo(7, 9); p.lineTo(10.5, 5.5);
                g.draw(p);
            }
            case FOLDER -> {
                Path2D p = new Path2D.Float();
                p.moveTo(1.5, 3.5); p.lineTo(5.5, 3.5); p.lineTo(6.8, 5); p.lineTo(12.5, 5); p.lineTo(12.5, 11.5); p.lineTo(1.5, 11.5); p.closePath();
                g.draw(p);
            }
            case TABLE -> {
                g.draw(new RoundRectangle2D.Float(1.8f, 2.5f, 10.4f, 9, 2, 2));
                g.draw(new Line2D.Float(1.8f, 5.5f, 12.2f, 5.5f));
                g.draw(new Line2D.Float(6, 5.5f, 6, 11.5f));
            }
            case COLUMN -> g.fill(new Ellipse2D.Float(4.5f, 4.5f, 5, 5));
            case SWAP -> {
                g.draw(new Line2D.Float(4.5f, 2.5f, 4.5f, 11.5f));
                g.draw(new Line2D.Float(9.5f, 2.5f, 9.5f, 11.5f));
                Path2D p = new Path2D.Float();
                p.moveTo(2.5, 4.5); p.lineTo(4.5, 2.5); p.lineTo(6.5, 4.5);
                p.moveTo(7.5, 9.5); p.lineTo(9.5, 11.5); p.lineTo(11.5, 9.5);
                g.draw(p);
            }
            case WARN -> {
                Path2D p = new Path2D.Float();
                p.moveTo(7, 1.8); p.lineTo(12.6, 11.8); p.lineTo(1.4, 11.8); p.closePath();
                g.draw(p);
                g.draw(new Line2D.Float(7, 5.5f, 7, 8.2f));
                g.fill(new Ellipse2D.Float(6.3f, 9.4f, 1.4f, 1.4f));
            }
            case DOWNLOAD -> {
                g.draw(new Line2D.Float(7, 2, 7, 9.5f));
                Path2D p = new Path2D.Float();
                p.moveTo(4, 6.8); p.lineTo(7, 9.8); p.lineTo(10, 6.8);
                g.draw(p);
                g.draw(new Line2D.Float(2.5f, 12, 11.5f, 12));
            }
        }
    }
}
