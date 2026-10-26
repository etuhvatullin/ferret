package ferret.core;

import ferret.core.Model.Connection;
import ferret.core.Model.DBError;
import ferret.core.Model.DBKind;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Драйвери JDBC — для КОЖНОЇ бази (у Java-версії й Trino, ClickHouse, Postgres теж через драйвер): що завантажувати
// (координати в Maven Central), як скласти адресу, які властивості передати; завантаження з перевіркою контрольної суми.
// Один драйвер (одна версія) — на всі підключення свого типу: `drivers/<тип>/`, вибір — `drivers.json` (той самий
// формат, що в Swift-Ferret). Драйвери НЕ вкладаються в програму: кожен качає свій сам (ліцензії Oracle / MySQL — на
// користувача), і так вони оновлюються без нової версії Ferret.
public final class Drivers {
    private Drivers() { }

    // extras — бібліотеки, без яких драйвер не запускається (не вшиті в його файл): група:артефакт:версія, закріплені.
    public record Spec(String group, String artifact, String classifier, String versions, String suffix, double mb, List<String> extras) {
        Spec(String group, String artifact, String versions, double mb) { this(group, artifact, null, versions, "", mb, List.of()); }

        Spec(String group, String artifact, String classifier, String versions, String suffix, double mb) {
            this(group, artifact, classifier, versions, suffix, mb, List.of());
        }
    }

    // збірка «all» драйвера ClickHouse без slf4j не стартує (NoClassDefFoundError: org/slf4j/LoggerFactory); nop — щоб мовчав
    static final List<String> SLF4J = List.of("org.slf4j:slf4j-api:2.0.20", "org.slf4j:slf4j-nop:2.0.20");

    // Версії з відомою поломкою: не пропонуються, а встановлена така замінюється на найновішу справну (з першим
    // зʼєднанням). clickhouse-jdbc 0.11.0 не читає Array(Nullable(UInt64)) — «array element type mismatch» (UnsignedLong[]
    // проти BigInteger); заміряно 25.10.2026 на aggregator.category_option_value_extended: 0.10.0 і 0.9.9 читають.
    static final Map<DBKind, Set<String>> BROKEN = Map.of(DBKind.CLICKHOUSE, Set.of("0.11.0"));

    public static boolean broken(DBKind k, String v) { return BROKEN.getOrDefault(k, Set.of()).contains(v); }

    static final String STABLE = "^\\d+(\\.\\d+)+$";   // лише стабільні: без rc, beta, preview, «_special»
    static final String SEMVER = "^\\d+\\.\\d+\\.\\d+$";

    public static Spec spec(DBKind k) {
        return switch (k) {
            case TRINO -> new Spec("io.trino", "trino-jdbc", "^\\d+$", 10.1);
            case CLICKHOUSE -> new Spec("com.clickhouse", "clickhouse-jdbc", "all", SEMVER, "", 13.1, SLF4J);
            case POSTGRES -> new Spec("org.postgresql", "postgresql", SEMVER, 1.1);
            case MSSQL -> new Spec("com.microsoft.sqlserver", "mssql-jdbc", null, "^\\d+\\.\\d+\\.\\d+\\.jre11$", ".jre11", 1.5);
            case MYSQL -> new Spec("com.mysql", "mysql-connector-j", SEMVER, 2.5);
            case MARIADB -> new Spec("org.mariadb.jdbc", "mariadb-java-client", SEMVER, 0.8);
            case ORACLE -> new Spec("com.oracle.database.jdbc", "ojdbc11", STABLE, 7.4);
            case SQLITE -> new Spec("org.xerial", "sqlite-jdbc", STABLE, 11.4);
            case DUCKDB -> new Spec("org.duckdb", "duckdb_jdbc", duckdbBuild(), STABLE, "", 18);   // збірка під свою систему
            case SNOWFLAKE -> new Spec("net.snowflake", "snowflake-jdbc", SEMVER, 94);
            case REDSHIFT -> new Spec("com.amazon.redshift", "redshift-jdbc42", STABLE, 1.1);
            case ATHENA -> new Spec("com.amazonaws", "athena-jdbc", "^\\d{4}\\.\\d+\\.\\d+$", 91);
            case BIGQUERY -> new Spec("com.google.cloud", "google-cloud-bigquery-jdbc", "all", SEMVER, "", 65);
            case DATABRICKS -> new Spec("com.databricks", "databricks-jdbc", SEMVER, 41);
            case VERTICA -> new Spec("com.vertica.jdbc", "vertica-jdbc", "^\\d+\\.\\d+\\.\\d+-\\d+$", 1.8);
            case DB2 -> new Spec("com.ibm.db2", "jcc", STABLE, 6.3);
            case HANA -> new Spec("com.sap.cloud.db.jdbc", "ngdbc", SEMVER, 1.5);
            case TERADATA -> new Spec("com.teradata.jdbc", "terajdbc", STABLE, 1.2);
            case EXASOL -> new Spec("com.exasol", "exasol-jdbc", SEMVER, 9.1);
            case FIREBIRD -> new Spec("org.firebirdsql.jdbc", "jaybird", SEMVER, 1.5);
            case H2 -> new Spec("com.h2database", "h2", SEMVER, 2.6);
            case JDBC -> null;
        };
    }

    // DuckDB — рідна бібліотека всередині драйвера: лише під свою систему (замість збірки на всі одразу).
    static String duckdbBuild() {
        String arch = System.getProperty("os.arch", "");
        boolean arm = arch.contains("aarch64") || arch.contains("arm");
        if (Store.MAC) return arm ? "macos_arm64" : "macos_amd64";
        if (Store.WINDOWS) return arm ? "windows_arm64" : "windows_amd64";
        return arm ? "linux_arm64" : "linux_amd64";
    }

    public static String size(DBKind k) {
        Spec s = spec(k);
        if (s == null) return "";
        return I18n.t("drivers.size.mb", s.mb() < 10 ? String.format(I18n.locale(), "%.1f", s.mb()) : String.valueOf(Math.round(s.mb())));
    }

    // MARK: адреса й властивості

    // Чи досить полів, щоб зберегти підключення: назва і те, куди стукати (хост, файл бази чи адреса JDBC).
    public static boolean complete(Connection c) {
        String url = c.jdbcURL == null ? "" : c.jdbcURL.trim();
        if (c.name.trim().isEmpty()) return false;
        if (c.kind == DBKind.JDBC) return !url.isEmpty() && c.driverJars != null && !c.driverJars.isEmpty();
        if (c.kind.isFile() || c.kind == DBKind.BIGQUERY || !url.isEmpty()) return true;
        return !c.host.trim().isEmpty();
    }

    // Адреса JDBC: задана вручну — як є; інакше складається з полів підключення за шаблоном драйвера.
    public static String url(Connection c) {
        if (c.jdbcURL != null && !c.jdbcURL.trim().isEmpty()) return c.jdbcURL.trim();
        return defaultURL(c);
    }

    public static String defaultURL(Connection c) {
        String host = c.host.trim(), db = c.database.trim(), schema = c.schema.trim(), hp = host + ":" + c.port;
        return switch (c.kind) {
            // пароль Trino віддає лише шифрованим зʼєднанням; source — щоб у Trino було видно, звідки запит
            case TRINO -> "jdbc:trino://" + hp + (db.isEmpty() ? "" : "/" + db + (schema.isEmpty() ? "" : "/" + schema))
                    + "?source=ferret" + (c.useTLS ? "&SSL=true" : "");
            case CLICKHOUSE -> "jdbc:clickhouse://" + hp + "/" + db + (c.useTLS ? "?ssl=true" : "");
            case POSTGRES -> "jdbc:postgresql://" + hp + "/" + db + "?ApplicationName=ferret&sslmode=" + (c.useTLS ? "require" : "disable")
                    + (schema.isEmpty() ? "" : "&currentSchema=" + schema);
            // драйвер MS SQL з 10-ї версії шифрує завжди; внутрішні сервери мають власні сертифікати — без їх перевірки
            case MSSQL -> "jdbc:sqlserver://" + hp + (db.isEmpty() ? "" : ";databaseName=" + db)
                    + (c.useTLS ? ";encrypt=true;trustServerCertificate=true" : ";encrypt=false") + ";applicationName=ferret";
            case MYSQL -> "jdbc:mysql://" + hp + "/" + db + (c.useTLS ? "?sslMode=REQUIRED" : "?sslMode=DISABLED&allowPublicKeyRetrieval=true");
            case MARIADB -> "jdbc:mariadb://" + hp + "/" + db + (c.useTLS ? "?sslMode=trust" : "");
            case ORACLE -> "jdbc:oracle:thin:@" + (c.useTLS ? "tcps://" : "//") + hp + "/" + db;
            case SQLITE -> "jdbc:sqlite:" + db;
            case DUCKDB -> "jdbc:duckdb:" + db;
            case H2 -> {
                String path = db.endsWith(".mv.db") ? db.substring(0, db.length() - 6) : db;
                yield path.isEmpty() ? "jdbc:h2:mem:ferret" : "jdbc:h2:file:" + path + (c.readOnly ? ";ACCESS_MODE_DATA=r" : "");
            }
            case SNOWFLAKE -> {
                List<String> q = new ArrayList<>();
                if (!db.isEmpty()) q.add("db=" + db);
                if (!schema.isEmpty()) q.add("schema=" + schema);
                yield "jdbc:snowflake://" + host + "/" + (q.isEmpty() ? "" : "?" + String.join("&", q));
            }
            case REDSHIFT -> "jdbc:redshift://" + hp + "/" + db;
            case ATHENA -> "jdbc:athena://Region=" + host + ";Catalog=" + (db.isEmpty() ? "AwsDataCatalog" : db) + (schema.isEmpty() ? "" : ";Database=" + schema);
            case BIGQUERY -> "jdbc:bigquery://https://www.googleapis.com/bigquery/v2:443;ProjectId=" + db + ";OAuthType=3";
            case DATABRICKS -> "jdbc:databricks://" + hp + ";httpPath=" + db + ";AuthMech=3";
            case VERTICA -> "jdbc:vertica://" + hp + "/" + db + (c.useTLS ? "?TLSmode=require" : "");
            case DB2 -> "jdbc:db2://" + hp + "/" + db + (c.useTLS ? ":sslConnection=true;" : "");
            case HANA -> {
                List<String> q = new ArrayList<>();
                if (!db.isEmpty()) q.add("databaseName=" + db);
                if (c.useTLS) q.add("encrypt=true");
                yield "jdbc:sap://" + hp + "/" + (q.isEmpty() ? "" : "?" + String.join("&", q));
            }
            case TERADATA -> "jdbc:teradata://" + host + "/DBS_PORT=" + c.port + (db.isEmpty() ? "" : ",DATABASE=" + db) + (c.useTLS ? ",ENCRYPTDATA=ON" : "");
            case EXASOL -> "jdbc:exa:" + hp + (schema.isEmpty() ? "" : ";schema=" + schema) + (c.useTLS ? ";validateservercertificate=0" : ";encryption=0");
            case FIREBIRD -> "jdbc:firebird://" + hp + "/" + db;
            case JDBC -> "";
        };
    }

    // Властивості зʼєднання: логін і пароль (пароль — лише сюди, не в адресу) і те, що драйвер бере не з адреси.
    public static Map<String, String> props(Connection c, String password) {
        Map<String, String> p = new LinkedHashMap<>();
        if (!c.user.isEmpty()) p.put("user", c.user);
        if (password != null && !password.isEmpty()) p.put("password", password);
        switch (c.kind) {
            case MYSQL, MARIADB -> p.put("connectTimeout", "15000");
            case ORACLE -> p.put("oracle.net.CONNECT_TIMEOUT", "15000");
            case POSTGRES, REDSHIFT -> p.put("connectTimeout", "15");
            // стиснення засобами HTTP, а не власне LZ4: 0.10.0 (останній, що читає масиви, див. BROKEN) не розбирає LZ4
            // від сервера 26.x — «Invalid LZ4 magic byte»; так працює і з 25.7, і з 26.10 (заміряно 25.10.2026)
            case CLICKHOUSE -> p.put("client.use_http_compression", "true");
            case SQLITE -> { if (c.readOnly) p.put("open_mode", "1"); }          // лише читання — і описка в шляху не створить порожню базу
            case DUCKDB -> { if (c.readOnly) p.put("duckdb.read_only", "true"); }
            case DATABRICKS -> {                                               // логін — «token», пароль — особистий токен доступу
                p.put("UID", c.user.isEmpty() ? "token" : c.user);
                if (password != null && !password.isEmpty()) p.put("PWD", password);
            }
            default -> { }
        }
        return p;
    }

    // Бази, де «лише читання» тримається відкатом транзакції після запиту. Trino, ClickHouse і хмарні сховища
    // транзакцій у звичному сенсі не мають — там запобіжник лише в тексті запиту.
    public static boolean rollbackGuard(DBKind k) {
        return switch (k) {
            case TRINO, CLICKHOUSE, ATHENA, BIGQUERY, DATABRICKS -> false;
            default -> true;
        };
    }

    // MARK: версії

    // Версії за зростанням: числа порівнюються як числа (12.10 новіша за 12.9).
    public static int compareVersions(String a, String b) {
        String[] pa = a.split("\\D+"), pb = b.split("\\D+");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            long x = i < pa.length && !pa[i].isEmpty() ? Long.parseLong(pa[i]) : 0, y = i < pb.length && !pb[i].isEmpty() ? Long.parseLong(pb[i]) : 0;
            if (x != y) return Long.compare(x, y);
        }
        return 0;
    }

    // Стабільні версії з переліку maven-metadata.xml — найновіша першою.
    public static List<String> stableVersions(String xml, Spec spec) {
        Matcher m = Pattern.compile("<version>([^<]+)</version>").matcher(xml);
        Pattern ok = Pattern.compile(spec.versions());
        Set<String> found = new LinkedHashSet<>();
        while (m.find()) if (ok.matcher(m.group(1)).matches()) found.add(m.group(1));
        List<String> out = new ArrayList<>(found);
        out.sort(Comparator.comparing((String v) -> bare(v, spec), Drivers::compareVersions).reversed());
        return out;
    }

    static String bare(String v, Spec s) { return !s.suffix().isEmpty() && v.endsWith(s.suffix()) ? v.substring(0, v.length() - s.suffix().length()) : v; }

    public static String display(DBKind k, String v) {
        Spec s = spec(k);
        return s == null ? v : bare(v, s);
    }

    // MARK: встановлені драйвери

    public static final String REPO = "https://repo1.maven.org/maven2/";
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private static final Map<DBKind, List<String>> known = new ConcurrentHashMap<>();
    private static final Map<String, CompletableFuture<Path>> running = new ConcurrentHashMap<>();

    public static Path dir() { return Store.file("drivers"); }

    public static Path folder(DBKind k) { return dir().resolve(k.raw); }

    public record Installed(String version, List<Path> jars) { }

    // Встановлена версія типу і її файли (драйвер + потрібні йому бібліотеки); null — ще не завантажено (або файл зник).
    public static Installed installed(DBKind k) {
        String v = Json.str(Json.map(Store.loadJSON("drivers.json")).get(k.raw));
        Spec s = spec(k);
        if (v == null || s == null) return null;
        try (var files = Files.list(folder(k))) {
            List<Path> jars = files.filter(p -> p.toString().endsWith(".jar")).sorted().toList();
            if (jars.size() < 1 + s.extras().size()) return null;
            return new Installed(v, jars);
        } catch (IOException e) {
            return null;
        }
    }

    // Стабільні версії з Maven Central, найновіша першою (раз за запуск програми, якщо не просять оновити).
    public static List<String> versions(DBKind k, boolean refresh) throws DBError {
        Spec s = spec(k);
        if (s == null) return List.of();
        if (!refresh && known.containsKey(k)) return known.get(k);
        try {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(REPO + s.group().replace('.', '/') + "/" + s.artifact() + "/maven-metadata.xml"))
                    .timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) throw new DBError(I18n.t("drivers.versions.status", s.artifact(), r.statusCode()));
            List<String> list = new ArrayList<>(stableVersions(r.body(), s));
            list.removeIf(v -> broken(k, v));
            known.put(k, list);
            return list;
        } catch (IOException | InterruptedException e) {
            throw new DBError(I18n.t("drivers.offline", e.getMessage()));
        }
    }

    // Файли драйвера для підключення: свій (.jar) — як є; інакше встановлена версія, а вперше — найновіша.
    public static List<Path> jars(Connection c) throws DBError {
        if (c.kind == DBKind.JDBC) {
            List<Path> out = new ArrayList<>();
            for (String j : c.driverJars == null ? List.<String>of() : c.driverJars) out.add(Path.of(j));
            if (out.isEmpty()) throw new DBError(I18n.t("drivers.jdbc.nojar"));
            return out;
        }
        Installed i = installed(c.kind);
        if (i != null && !broken(c.kind, i.version())) return i.jars();
        try {
            List<String> v = versions(c.kind, false);
            if (v.isEmpty()) throw new DBError(I18n.t("drivers.nostable", c.kind.title));
            install(c.kind, v.get(0));
        } catch (DBError e) {
            if (i != null) return i.jars();   // немає мережі — краще версія з поломкою, ніж жодної
            throw e;
        }
        Installed now = installed(c.kind);
        if (now == null) throw new DBError(I18n.t("drivers.notinstalled", c.kind.title));
        return now.jars();
    }

    // Завантажити версію й зробити встановленою (одночасні прохання тієї самої версії — одне завантаження).
    public static Path install(DBKind k, String version) throws DBError {
        String key = k.raw + "@" + version;
        CompletableFuture<Path> mine = new CompletableFuture<>();
        CompletableFuture<Path> f = running.putIfAbsent(key, mine);
        if (f == null) {
            f = mine;
            try {
                mine.complete(download(k, version));
            } catch (Exception e) {
                mine.completeExceptionally(e);
            } finally {
                running.remove(key);
            }
        }
        try {
            return f.join();
        } catch (Exception e) {
            Throwable t = e.getCause() != null ? e.getCause() : e;
            throw t instanceof DBError d ? d : new DBError(String.valueOf(t.getMessage()));
        }
    }

    // З Maven Central: спершу в тимчасовий файл поруч, перевірка контрольної суми (sha512 → sha256 → sha1 — що
    // опубліковано), і лише тоді — заміна попередньої версії.
    static Path download(DBKind k, String v) throws Exception {
        Spec s = spec(k);
        if (s == null) throw new DBError(I18n.t("drivers.nodownload", k.title));
        String base = REPO + s.group().replace('.', '/') + "/" + s.artifact() + "/" + v + "/";
        List<String> names = new ArrayList<>();
        names.add(s.artifact() + "-" + v + (s.classifier() == null ? "" : "-" + s.classifier()) + ".jar");
        if (s.classifier() != null) names.add(s.artifact() + "-" + v + ".jar");   // старі версії — без окремої збірки
        Files.createDirectories(dir());
        for (String name : names) {
            Path part = dir().resolve("." + k.raw + "-" + UUID.randomUUID() + ".part");
            try {
                HttpResponse<Path> r = HTTP.send(HttpRequest.newBuilder(URI.create(base + name)).timeout(Duration.ofMinutes(10)).build(),
                        HttpResponse.BodyHandlers.ofFile(part));
                if (r.statusCode() == 404) continue;
                if (r.statusCode() != 200) throw new DBError(I18n.t("drivers.status", r.statusCode(), name));
                verify(part, base + name);
                // потрібні драйверу бібліотеки — теж з перевіркою суми, у тимчасову теку поруч
                Path staging = dir().resolve("." + k.raw + "-" + UUID.randomUUID());
                Files.createDirectories(staging);
                try {
                    for (String coord : s.extras()) {
                        String[] g = coord.split(":");
                        String eName = g[1] + "-" + g[2] + ".jar";
                        String eUrl = REPO + g[0].replace('.', '/') + "/" + g[1] + "/" + g[2] + "/" + eName;
                        HttpResponse<Path> er = HTTP.send(HttpRequest.newBuilder(URI.create(eUrl)).timeout(Duration.ofMinutes(5)).build(),
                                HttpResponse.BodyHandlers.ofFile(staging.resolve(eName)));
                        if (er.statusCode() != 200) throw new DBError(I18n.t("drivers.status", er.statusCode(), eName));
                        verify(staging.resolve(eName), eUrl);
                    }
                    Files.move(part, staging.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                    Path dest = folder(k);
                    deleteTree(dest);   // одна версія на тип
                    Files.move(staging, dest);
                } finally {
                    deleteTree(staging);
                }
                Path target = folder(k).resolve(name);
                Map<String, Object> map = new LinkedHashMap<>(Json.map(Store.loadJSON("drivers.json")));
                map.put(k.raw, v);
                Store.saveJSON("drivers.json", map);
                return target;
            } finally {
                Files.deleteIfExists(part);
            }
        }
        throw new DBError(I18n.t("drivers.nofile", s.artifact(), v));
    }

    static void verify(Path file, String url) throws Exception {
        for (String[] alg : new String[][] {{"sha512", "SHA-512"}, {"sha256", "SHA-256"}, {"sha1", "SHA-1"}}) {
            HttpResponse<String> r;
            try {
                r = HTTP.send(HttpRequest.newBuilder(URI.create(url + "." + alg[0])).timeout(Duration.ofSeconds(30)).build(),
                        HttpResponse.BodyHandlers.ofString());
            } catch (IOException e) {
                continue;
            }
            if (r.statusCode() != 200) continue;
            String expected = r.body().trim().split("\\s+")[0].toLowerCase();
            if (!expected.equals(hash(file, alg[1]))) {
                throw new DBError(I18n.t("drivers.checksum.mismatch", alg[0]));
            }
            return;
        }
        throw new DBError(I18n.t("drivers.checksum.missing"));
    }

    public static String hash(Path file, String algorithm) throws Exception {
        MessageDigest md = MessageDigest.getInstance(algorithm);
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (var walk = Files.walk(p)) {
            for (Path x : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(x);
        }
    }

    // «Інша база»: свій файл драйвера копіюється в теку даних — щоб не зник разом із «Завантаженнями».
    public static String adoptCustom(Path src) throws IOException {
        Path d = dir().resolve("custom");
        Files.createDirectories(d);
        Path target = d.resolve(src.getFileName().toString());
        if (Files.exists(target)) {
            if (Files.mismatch(src, target) == -1) return target.toString();
            target = d.resolve(UUID.randomUUID().toString().substring(0, 8) + "-" + src.getFileName());
        }
        Files.copy(src, target);
        return target.toString();
    }

    // Пароль, який драйвер міг повторити в тексті помилки (напр., в адресі), — не показуємо.
    public static String redact(String text, String password) {
        return password != null && password.length() >= 3 && text != null ? text.replace(password, "•••") : text;
    }

}
