import java.sql.DriverManager;

// Standalone source-launcher tool. Credentials come only from the invoking process environment.
class Day28DatabaseProbe {
    public static void main(String[] args) throws Exception {
        long owner = Long.parseLong(args[0]);
        long expectedPending = Long.parseLong(args[1]);
        String url = System.getenv().getOrDefault("CLOUDDRIVE_DB_URL",
                "jdbc:mysql://127.0.0.1:3306/cloud_drive_learning?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai");
        try (var connection = DriverManager.getConnection(url, System.getenv("CLOUDDRIVE_DB_USERNAME"),
                System.getenv("CLOUDDRIVE_DB_PASSWORD"))) {
            try (var query = connection.prepareStatement("SELECT COUNT(*) FROM file_quota_operations WHERE owner_id=? AND phase <> 'DONE'")) {
                query.setLong(1, owner);
                try (var rows = query.executeQuery()) {
                    rows.next(); long pending = rows.getLong(1);
                    if (pending != expectedPending) throw new AssertionError("pending=" + pending + " expected=" + expectedPending);
                    System.out.println("pending=" + pending);
                }
            }
            try (var query = connection.prepareStatement("EXPLAIN SELECT id FROM drive_entries WHERE owner_id=? AND kind='DOCUMENT' AND deleted_at IS NULL AND LOCATE(CAST(? AS BINARY),CAST(display_name AS BINARY))>0 ORDER BY id DESC LIMIT 20")) {
                query.setLong(1, owner); query.setString(2, "report");
                try (var rows = query.executeQuery()) {
                    while (rows.next()) System.out.println("EXPLAIN type=" + rows.getString("type")
                            + " key=" + rows.getString("key") + " rows=" + rows.getString("rows") + " extra=" + rows.getString("Extra"));
                }
            }
        }
    }
}
