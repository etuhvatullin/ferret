package ferret.ui;

import ferret.core.I18n;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.KeyEvent;

// Питання й повідомлення у стилі Ferret замість JOptionPane: у того кнопки розтягнуті на однакову ширину, а «OK» /
// «Cancel» — англійською в будь-якій мові (Java не має їх перекладу). Тут кнопки праворуч, за шириною тексту, головна —
// медова; Enter — головна, Esc — друга.
public final class Ask {
    private Ask() { }

    // Так / ні: true — натиснули головну кнопку
    public static boolean confirm(Component parent, String title, String message, String yes, String no) {
        return show(parent, title, message, null, yes, no) != null;
    }

    public static void inform(Component parent, String title, String message) {
        show(parent, title, message, null, I18n.t("common.ok"), null);
    }

    // Рядок тексту: null — скасовано
    public static String input(Component parent, String title, String message, String initial, String yes) {
        return show(parent, title, message, initial == null ? "" : initial, yes, I18n.t("common.cancel"));
    }

    private static String show(Component parent, String title, String message, String initial, String yes, String no) {
        Window owner = parent == null ? null : parent instanceof Window w ? w : SwingUtilities.getWindowAncestor(parent);
        JDialog d = new JDialog(owner, title, java.awt.Dialog.ModalityType.APPLICATION_MODAL);
        String[] result = {null};
        JPanel body = new JPanel(new BorderLayout(0, 12));
        body.setBackground(Theme.PANEL);
        body.setBorder(BorderFactory.createEmptyBorder(20, 22, 16, 22));
        JTextArea text = new JTextArea(message);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setEditable(false);
        text.setFocusable(false);
        text.setOpaque(false);
        text.setForeground(Theme.TEXT);
        text.setFont(text.getFont().deriveFont(13.5f));
        text.setBorder(null);
        int width = Math.min(420, Math.max(260, text.getFontMetrics(text.getFont()).stringWidth(message) + 8));
        text.setSize(width, 1);
        text.setPreferredSize(new Dimension(width, text.getPreferredSize().height));
        body.add(text, BorderLayout.NORTH);
        JTextField field = initial == null ? null : new JTextField(initial);
        if (field != null) body.add(field, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        buttons.setOpaque(false);
        JButton primary = Ui.accentButton(yes, null);
        primary.addActionListener(e -> {
            result[0] = field == null ? "" : field.getText();
            d.dispose();
        });
        if (no != null) {
            JButton second = Ui.plainButton(no, null);
            second.addActionListener(e -> d.dispose());
            buttons.add(second);
            buttons.add(Box.createHorizontalStrut(8));
        }
        buttons.add(primary);
        body.add(buttons, BorderLayout.SOUTH);
        d.setContentPane(body);
        d.getRootPane().setDefaultButton(primary);
        d.getRootPane().registerKeyboardAction(e -> d.dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        d.pack();
        d.setResizable(false);
        d.setLocationRelativeTo(owner);
        if (field != null) SwingUtilities.invokeLater(() -> { field.requestFocusInWindow(); field.selectAll(); });
        d.setVisible(true);
        return result[0];
    }
}
