package ferret.core;

import java.util.List;

// Вузол дерева схем: шлях (каталог / схема / таблиця / колонка — скільки рівнів у бази) і тип колонки.
public record SchemaItem(List<String> path, String detail) {
    public String name() { return path.isEmpty() ? "" : path.get(path.size() - 1); }

    public String id() { return String.join("\u001F", path); }
}
