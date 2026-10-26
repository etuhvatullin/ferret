package ferret.tests;

// Усі перевірки поспіль (без вікон — працює й по ssh, і в WSL). Код виходу 1, якщо щось не так.
public final class All {
    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        // перевірки — у тимчасовій теці даних: справжні підключення й паролі не чіпаються
        if (System.getenv("FERRET_DATA_DIR") == null) ferret.core.Store.useDir(T.temp("ferret-test"));
        CoreTests.run();
        SqlTextTests.run();
        FormatTests.run();
        DialectTests.run();
        DriversTests.run();
        JdbcTests.run();
        T.cleanup();
        System.out.println(T.failures == 0 ? "\nУсе гаразд." : "\nПомилок: " + T.failures);
        System.exit(T.failures == 0 ? 0 : 1);
    }
}
