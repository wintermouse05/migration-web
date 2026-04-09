package org.example.migrationdbweb.migratetool;

public class DatabaseConfig {
    private DatabaseType type;
    private String jdbcUrl;
    private String host;
    private int port;
    private String databaseName;
    private String schemaName;
    private String username;
    private String password;
    private int maximumPoolSize = 10;

    public DatabaseConfig(DatabaseType type, String host, int port, String databaseName, String username, String password) {
        this.type = type;
        this.host = host;
        this.port = port;
        this.databaseName = databaseName;
        this.username = username;
        this.password = password;

    }

    public String getJdbcUrl() {
        if (jdbcUrl != null && !jdbcUrl.isBlank()) {
            return jdbcUrl.trim();
        }

        switch (type) {
            case POSTGRESQL:
                return String.format("jdbc:postgresql://%s:%d/%s", host, port, databaseName);
            case ORACLE:
                // Oracle hỗ trợ 2 định dạng chính:
                //   1. Service Name (khuyến nghị): jdbc:oracle:thin:@//host:port/service_name
                //   2. SID (cũ):              jdbc:oracle:thin:@host:port:sid
                //
                // Auto-detect dựa trên format databaseName:
                //   - Có chứa '.' → xem là FQDN/service_name → dùng Service Name format
                //   - Không có '.' → xem là SID → dùng legacy format với ':'
                //
                // Override bằng cách:
                //   - Nhập FQDN/service_name (ví dụ: orcl.example.com, orcl) → Service Name
                //   - Nhập SID đơn giản (ví dụ: ORCL) → SID format
                if (databaseName != null && databaseName.contains(".")) {
                    // FQDN hoặc service name format → dùng Service Name
                    return String.format("jdbc:oracle:thin:@//%s:%d/%s", host, port, databaseName);
                } else {
                    // Không có dấu chấm → phân biệt SID (legacy on-prem) vs Service Name (Docker/PDB):
                    //   gvenzl/oracle-xe Docker: PDB → service name = XEPDB1, XE, ORCLPDB1
                    //   Oracle on-prem legacy: SID = ORCL, SID testdb → dùng ':' format
                    // Heuristic: XEPDB1/XE/ORCLPDB1 → Service Name (/)
                    //            ORCL/SID khác → SID format (:)
                    String upper = databaseName.toUpperCase();
                    if ("XEPDB1".equals(upper) || "XE".equals(upper) || "ORCLPDB1".equals(upper)) {
                        return String.format("jdbc:oracle:thin:@//%s:%d/%s", host, port, databaseName);
                    }
                    return String.format("jdbc:oracle:thin:@%s:%d:%s", host, port, databaseName);
                }
            default:
                throw new UnsupportedOperationException("Database type chưa được hỗ trợ: " + type);
        }
    }

    public DatabaseType getType() {return type;}
    public void setType(DatabaseType type) {this.type = type;}
    public String getJdbcUrlValue() {return jdbcUrl;}
    public void setJdbcUrl(String jdbcUrl) {this.jdbcUrl = jdbcUrl;}
    public String getHost() {return host;}
    public void setHost(String host) {this.host = host;}
    public int getPort() {return port;}
    public void setPort(int port) {this.port = port;}
    public String getDatabaseName() {return databaseName;}
    public void setDatabaseName(String databaseName) {this.databaseName = databaseName;}
    public String getSchemaName() {return schemaName;}
    public void setSchemaName(String schemaName) {this.schemaName = schemaName;}
    public String getUsername() {return username;}
    public void setUsername(String username) {this.username = username;}
    public String getPassword() {return password;}
    public void setPassword(String password) {this.password = password;}
    public int getMaximumPoolSize() {return maximumPoolSize;}
    public void setMaximumPoolSize(int maximumPoolSize) {this.maximumPoolSize = maximumPoolSize;}

}
