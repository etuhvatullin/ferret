package ferret.tests;

import ferret.core.Json;
import ferret.core.Model;
import ferret.core.Model.Connection;
import ferret.core.Model.DBKind;
import ferret.core.Passwords;
import ferret.core.Store;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static ferret.tests.T.check;

// Ядро: JSON, підключення в форматі Swift-Ferret, файл паролів (сумісний із CryptoKit), перенесення даних.
final class CoreTests {
    static void run() throws Exception {
        T.section("Значення результату: сортування й пошук");
        check(ferret.core.Values.numericType("bigint") && ferret.core.Values.numericType("decimal(10,2)")
                && ferret.core.Values.numericType("Nullable(UInt64)") && ferret.core.Values.numericType("LowCardinality(Nullable(Float64))")
                && ferret.core.Values.numericType("int8") && ferret.core.Values.numericType("double precision"), "числові типи всіх баз, з обгортками ClickHouse");
        check(!ferret.core.Values.numericType("varchar") && !ferret.core.Values.numericType("interval day to second")
                && !ferret.core.Values.numericType("Array(Nullable(UInt64))") && !ferret.core.Values.numericType("DateTime('UTC')"), "текст, інтервал, масив, дата — не числа");
        java.util.List<Object> nums = new java.util.ArrayList<>(java.util.List.of("10", "9", "-3.5", "1e3", "x", "100"));
        nums.sort(ferret.core.Values.NUMBERS);
        check(nums.equals(java.util.List.of("-3.5", "9", "10", "100", "1e3", "x")), "числа — як числа, не текстом (" + nums + ")");
        check(ferret.core.Values.contains("Кавомолки", "мол") && !ferret.core.Values.contains(null, "a"), "пошук без регістру, кирилиця, NULL");

        T.section("Ядро: JSON, підключення, паролі, тека даних");
        Object j = Json.parse("{\"a\": [1, 2.5, \"x\\n\\u0442\"], \"b\": {\"c\": null, \"d\": true}}");
        check(Json.write(j).equals("{\"a\":[1,2.5,\"x\\nт\"],\"b\":{\"c\":null,\"d\":true}}"), "JSON: розбір і запис (кирилиця, \\u, null, true)");
        check(Json.pretty(Map.of("b", 1L, "a", 2L)).startsWith("{\n  \"a\""), "JSON у файл — ключі за абеткою, з відступами");

        // запис із connections.json Swift-Ferret (так його пише Swift-версія)
        String swift = "{\"color\" : 0, \"database\" : \"lake\", \"host\" : \"trino.example\", \"id\" : \"173A16D4-F0F0-4576-BC45-28438604FC6C\","
                + " \"kind\" : \"trino\", \"name\" : \"Trino · прод\", \"port\" : 443, \"readOnly\" : true, \"schema\" : \"\", \"useTLS\" : true, \"user\" : \"u\"}";
        Connection c = Connection.fromJson(Json.parse(swift));
        check(c != null && c.kind == DBKind.TRINO && c.name.equals("Trino · прод") && c.port == 443 && c.color == 0 && c.jdbcURL == null,
                "підключення Swift-Ferret читається як є");
        check(c != null && Connection.fromJson(Json.parse(Json.write(c.toJson()))).equals(c), "підключення: запис → читання — те саме");
        check(Connection.fromJson(Json.parse("{\"id\":\"" + UUID.randomUUID() + "\",\"kind\":\"невідомий\"}")) == null,
                "незрозумілий запис пропускається, а не ламає весь файл");
        Connection a = Connection.blank(DBKind.MSSQL);
        check(a.port == 1433 && a.useTLS && a.readOnly, "нове MS SQL: порт 1433, шифровано, лише читання");
        a.color = 0;
        Connection b = Connection.blank(DBKind.MYSQL);
        b.color = 1;
        check(Model.leastUsedColor(List.of(a, b)) == 2, "колір нового підключення — найменш уживаний");

        // паролі: файл від CryptoKit (Swift-Ferret) з тестовим ключем 0…31 — Java розшифровує
        Path dir = Store.dir();
        byte[] key = new byte[32];
        for (int i = 0; i < 32; i++) key[i] = (byte) i;
        Files.write(dir.resolve("passwords.key"), key);
        Files.write(dir.resolve("passwords.enc"), Base64.getDecoder().decode(
                "iamT4oJVtUVPq9AjfpAbOHL5PqQXYpQfHwsZ3DNGi5x/a/Fl7t7ysHr8Zm/rz0EUC574r4Rh9Zh/ChaPqWXUQh9L3QC2J9ik0fbjfVrXUpcpev895Q/o48tqLeKDJVKAdOqfvv7XLEY5BwH34XCz0Mkk80vYOICfKBkiVQ=="));
        Passwords.reload();
        UUID fixture = UUID.fromString("0A1B2C3D-0000-4000-8000-000000000001");
        check("тестовий-пароль-не-справжній".equals(Passwords.get(fixture)), "файл паролів Swift-Ferret (CryptoKit AES-GCM) розшифровано");
        UUID other = UUID.randomUUID();
        Passwords.set(other, "секрет-тест");
        Passwords.reload();
        check("секрет-тест".equals(Passwords.get(other)) && "тестовий-пароль-не-справжній".equals(Passwords.get(fixture)),
                "новий пароль записано — і старий цілий після перечитування");
        Passwords.remove(other);
        Passwords.reload();
        check(Passwords.get(other) == null, "видалений пароль зник");
        if (!Store.WINDOWS) {
            String perm = java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("passwords.enc")));
            check(perm.equals("rw-------"), "файл паролів — лише для власника (" + perm + ")");
        }

        // перенесення з теки Swift-Ferret: копією, один раз
        Path old = T.temp("ferret-old");
        Path to = old.resolve("v2");
        Files.createDirectories(to);
        Files.writeString(old.resolve("connections.json"), "[" + swift + "]");
        Files.writeString(old.resolve("tabs.json"), "[]");
        Files.createDirectories(old.resolve("schema-cache"));
        Files.writeString(old.resolve("schema-cache/x.json"), "{}");
        var m = Store.class.getDeclaredMethod("migrateFrom", Path.class, Path.class);
        m.setAccessible(true);
        m.invoke(null, old, to);
        check(Files.exists(to.resolve("connections.json")) && Files.exists(to.resolve("schema-cache/x.json")) && Files.exists(old.resolve("connections.json")),
                "дані Swift-Ferret скопійовано в нову теку (оригінал на місці)");
        Files.writeString(old.resolve("connections.json"), "[]");
        m.invoke(null, old, to);
        check(Files.readString(to.resolve("connections.json")).contains("Trino"), "повторно не копіюється — свіже не затирається");

        // Раніше всі збої тут ковтались: у колеги на Windows після перезапуску зникали підключення, а ключ Claude
        // щоразу був новий — без жодного слова чому.
        T.section("Збої файлів даних — не мовчки");
        List<Store.Problem> seen = new java.util.ArrayList<>();
        Store.onProblem(seen::add);
        Path home = Store.dir();
        Files.writeString(home.resolve("broken.json"), "[{\"id\": ");   // обірваний JSON
        check(Store.loadJSON("broken.json") == null && !Files.exists(home.resolve("broken.json")), "зіпсований JSON — null, а сам файл відкладено");
        check(seen.size() == 1 && !seen.get(0).write() && seen.get(0).aside() != null && Files.exists(seen.get(0).aside()),
                "про зіпсований файл сказано, і відкладена копія на місці — наступне збереження її не затре");
        check(Store.loadJSON("nothing-here.json") == null && seen.size() == 1, "файлу нема — це не збій");

        // тека даних, яку не створити: на її місці звичайний файл — однаково на всіх системах, без прав доступу
        Path blocked = T.temp("ferret-blocked").resolve("not-a-folder");
        Files.writeString(blocked, "");
        Store.useDir(blocked);
        Passwords.reload();
        seen.clear();
        boolean threw = false;
        try { Store.saveJSON("connections.json", List.of()); } catch (java.io.IOException e) { threw = true; }
        check(threw && seen.size() == 1 && seen.get(0).write() && seen.get(0).file().endsWith("connections.json") && !seen.get(0).reason().isEmpty(),
                "файл не записався — виняток тому, хто пише, і повідомлення вікну з причиною (" + seen + ")");
        check(ferret.core.McpAccess.newKey() == null, "ключ Claude, що не записався, не видається за справжній");

        // паролі, що не розшифровуються, — вбік і повідомлення (раніше відкладались мовчки, і паролі просто зникали)
        Path pw = T.temp("ferret-pw");
        Files.write(pw.resolve("passwords.key"), new byte[32]);
        Files.write(pw.resolve("passwords.enc"), new byte[40]);
        Store.useDir(pw);
        Passwords.reload();
        seen.clear();
        check(Passwords.get(UUID.randomUUID()) == null && !Files.exists(pw.resolve("passwords.enc")), "нерозшифровний файл паролів відкладено");
        check(seen.size() == 1 && !seen.get(0).write() && seen.get(0).aside() != null && seen.get(0).file().endsWith("passwords.enc"),
                "і про це сказано (" + seen + ")");
        Store.useDir(home);
        Passwords.reload();
        Store.onProblem(x -> { });
    }
}
