package burp;

import javax.swing.DefaultComboBoxModel;
import javax.swing.JComboBox;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.Locale;

/**
 * An editable combo box that narrows its drop-down as you type.
 * <p>
 * The fingerprint list has ~80 entries, which is unusable in a plain combo box. Typing any
 * substring ("chrome_1", "firefox") filters the list; the full list comes back when nothing
 * matches, so the field can never trap the user in an empty drop-down.
 */
final class SearchableComboBox extends JComboBox<String> {
    private final List<String> allItems;

    /**
     * Guards against the model updates below re-entering the filter through editor events.
     */
    private boolean updating;

    SearchableComboBox(List<String> items) {
        super(items.toArray(new String[0]));

        this.allItems = List.copyOf(items);

        setEditable(true);
        setMaximumRowCount(15);

        editorField().addKeyListener(new KeyAdapter() {
            @Override
            public void keyReleased(KeyEvent e) {
                switch (e.getKeyCode()) {
                    // Navigation and commit keys must reach the popup untouched.
                    case KeyEvent.VK_UP, KeyEvent.VK_DOWN, KeyEvent.VK_ENTER, KeyEvent.VK_ESCAPE,
                         KeyEvent.VK_TAB, KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT, KeyEvent.VK_HOME,
                         KeyEvent.VK_END -> {
                        return;
                    }
                }
                // Defer so the editor's document has settled before it is read back.
                SwingUtilities.invokeLater(() -> filter(editorField().getText()));
            }
        });
    }

    private JTextField editorField() {
        return (JTextField) getEditor().getEditorComponent();
    }

    private void filter(String typed) {
        if (updating) return;

        updating = true;
        try {
            var needle = typed.toLowerCase(Locale.ROOT).trim();
            var matches = needle.isEmpty()
                    ? allItems
                    : allItems.stream().filter(i -> i.toLowerCase(Locale.ROOT).contains(needle)).toList();
            if (matches.isEmpty()) {
                matches = allItems;
            }

            setModel(new DefaultComboBoxModel<>(matches.toArray(new String[0])));

            // setModel selects the first entry and overwrites the editor, so put the typed text back.
            var editor = editorField();
            editor.setText(typed);
            editor.setCaretPosition(Math.min(typed.length(), editor.getText().length()));

            if (isShowing()) {
                hidePopup();
                showPopup();
            }
        } finally {
            updating = false;
        }
    }

    /**
     * @return the current text, which may be a value the user typed rather than a list entry.
     */
    String getValue() {
        var editor = editorField().getText();
        return editor == null ? "" : editor.trim();
    }

    void setValue(String value) {
        updating = true;
        try {
            setModel(new DefaultComboBoxModel<>(allItems.toArray(new String[0])));
            setSelectedItem(value);
            editorField().setText(value == null ? "" : value);
        } finally {
            updating = false;
        }
    }

    /**
     * @return true if {@code value} is one of the known entries.
     */
    boolean isKnown(String value) {
        return allItems.contains(value);
    }
}
