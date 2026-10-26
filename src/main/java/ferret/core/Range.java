package ferret.core;

// Діапазон у тексті (індекси Java-рядка = UTF-16, як NSRange у Swift-Ferret): початок і довжина.
public record Range(int location, int length) {
    public int end() { return location + length; }

    public boolean contains(int i) { return i >= location && i <= end(); }

    public String of(String text) { return text.substring(location, end()); }
}
