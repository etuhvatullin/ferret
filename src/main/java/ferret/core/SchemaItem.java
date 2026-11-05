package ferret.core;

import java.util.List;

// Вузол дерева схем: шлях (каталог / схема / таблиця / колонка — скільки рівнів у бази), тип колонки й коментар з бази
// (null — немає; показується лише підказкою). Коментар у кеші схем — необовʼязкове поле: старі файли читаються як були.
public record SchemaItem(List<String> path, String detail, String comment) {
    public SchemaItem(List<String> path, String detail) { this(path, detail, null); }

    public String name() { return path.isEmpty() ? "" : path.get(path.size() - 1); }

    public String id() { return String.join("\u001F", path); }
}
