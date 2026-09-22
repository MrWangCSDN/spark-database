import java.sql.Driver;

/** Offline check only: loads the supplied driver and checks the URL prefix. */
public class CheckJdbcDriver {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected driver class and URL");
        Driver driver = (Driver) Class.forName(args[0]).getDeclaredConstructor().newInstance();
        if (!driver.acceptsURL(args[1])) {
            throw new IllegalArgumentException("驱动不接受当前 JDBC URL，请核对协议前缀");
        }
        System.out.println("驱动类可加载，URL 协议匹配；尚未连接数据库。");
    }
}
