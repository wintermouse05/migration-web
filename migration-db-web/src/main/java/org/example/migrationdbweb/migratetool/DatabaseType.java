package org.example.migrationdbweb.migratetool;

public enum DatabaseType {
    POSTGRESQL("org.postgresql.Driver"),
    ORACLE("oracle.jdbc.OracleDriver");

    private final String driverClassName;
    private DatabaseType(String driverClassName) {
        this.driverClassName = driverClassName;
    }
    public String getDriverClassName() {
        return driverClassName;
    }
}
