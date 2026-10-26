package ferret.tests;

import ferret.core.Drivers;
import ferret.core.Model.Connection;
import ferret.core.Model.DBKind;

import java.util.List;

import static ferret.tests.T.check;

// Драйвери: адреси з полів, властивості, версії, повнота підключення.
final class DriversTests {
    static void run() {
        T.section("Драйвери: адреси, версії");
        Connection t = Connection.blank(DBKind.TRINO);
        t.host = "trino.example";
        t.database = "lake";
        check(Drivers.defaultURL(t).equals("jdbc:trino://trino.example:443/lake?source=ferret&SSL=true"), "Trino: адреса з каталогом і шифруванням");
        Connection ch = Connection.blank(DBKind.CLICKHOUSE);
        ch.host = "10.0.0.1";
        ch.database = "db";
        check(Drivers.defaultURL(ch).equals("jdbc:clickhouse://10.0.0.1:8123/db"), "ClickHouse: адреса HTTP");
        Connection pg = Connection.blank(DBKind.POSTGRES);
        pg.host = "pg";
        pg.database = "d";
        pg.schema = "s";
        check(Drivers.defaultURL(pg).equals("jdbc:postgresql://pg:5432/d?ApplicationName=ferret&sslmode=disable&currentSchema=s"), "Postgres: схема й без шифрування");
        Connection ms = Connection.blank(DBKind.MSSQL);
        ms.name = "apteka prod";
        ms.host = "rdsql.example";
        ms.database = "apteka";
        check(Drivers.defaultURL(ms).equals("jdbc:sqlserver://rdsql.example:1433;databaseName=apteka;encrypt=true;trustServerCertificate=true;applicationName=ferret"),
                "MS SQL: шифровано без перевірки сертифіката");
        Connection my = Connection.blank(DBKind.MYSQL);
        my.host = "10.0.0.2";
        my.database = "splitter";
        check(Drivers.defaultURL(my).equals("jdbc:mysql://10.0.0.2:3306/splitter?sslMode=DISABLED&allowPublicKeyRetrieval=true"), "MySQL: адреса");
        Connection ora = Connection.blank(DBKind.ORACLE);
        ora.host = "db";
        ora.database = "ORCL";
        check(Drivers.defaultURL(ora).equals("jdbc:oracle:thin:@//db:1521/ORCL"), "Oracle: сервіс");
        Connection lite = Connection.blank(DBKind.SQLITE);
        lite.database = "/tmp/x.db";
        check(Drivers.defaultURL(lite).equals("jdbc:sqlite:/tmp/x.db") && "1".equals(Drivers.props(lite, "").get("open_mode")),
                "SQLite у режимі «лише читання» — файл лише на читання");
        Connection manual = ms.copy();
        manual.jdbcURL = "jdbc:sqlserver://x:1;foo=bar";
        check(Drivers.url(manual).equals("jdbc:sqlserver://x:1;foo=bar") && !Drivers.defaultURL(ms).contains("pw")
                && "pw".equals(Drivers.props(ms, "pw").get("password")), "адреса вручну — як є; пароль — у властивостях, не в адресі");
        Connection custom = Connection.blank(DBKind.JDBC);
        custom.name = "своя";
        Connection noName = ms.copy();
        noName.name = "";
        Connection duck = Connection.blank(DBKind.DUCKDB);
        duck.name = "duck";
        check(Drivers.complete(ms) && !Drivers.complete(noName) && Drivers.complete(duck) && !Drivers.complete(custom),
                "зберегти можна з хостом, файлом чи адресою; «Інша база» — лише з адресою й файлом драйвера");
        String meta = "<versions><version>12.8.1.jre11</version><version>12.10.0.jre11</version><version>13.5.0.jre11-preview</version>"
                + "<version>12.9.0.jre8</version></versions>";
        check(Drivers.stableVersions(meta, Drivers.spec(DBKind.MSSQL)).equals(List.of("12.10.0.jre11", "12.8.1.jre11"))
                && Drivers.display(DBKind.MSSQL, "12.10.0.jre11").equals("12.10.0"), "версії: лише стабільні під Java 11, найновіша першою");
        check(Drivers.stableVersions("<version>12.1.5.0</version><version>12.1.5.0_special_1</version><version>11.5.9.0</version>",
                Drivers.spec(DBKind.DB2)).equals(List.of("12.1.5.0", "11.5.9.0")), "Db2: «_special» відкинуто");
        check(Drivers.stableVersions("<version>476</version><version>484</version><version>480-rc</version>", Drivers.spec(DBKind.TRINO))
                .equals(List.of("484", "476")), "Trino: версії-числа");
        check(Drivers.redact("bad password secret-pw here", "secret-pw").equals("bad password ••• here"), "пароль із тексту помилки прибирається");
        check(!Drivers.rollbackGuard(DBKind.TRINO) && Drivers.rollbackGuard(DBKind.MSSQL), "відкат після запиту — там, де бази мають транзакції");
    }
}
