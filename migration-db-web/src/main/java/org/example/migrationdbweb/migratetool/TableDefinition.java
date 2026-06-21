package org.example.migrationdbweb.migratetool;

import java.util.ArrayList;
import java.util.List;

public class TableDefinition {
    private String tableName;
    private DatabaseType sourceDialect;
    private List<ColumnDefinition> columns = new ArrayList<>();
    private List<String> primaryKeys = new ArrayList<>();
    private List<ForeignKeyDefinition> foreignKeys = new ArrayList<>();
    private List<String> foreignKeyDdls = new ArrayList<>();
    private String sourceSchema;
    private String targetSchema;
    private String ddlText;

    public TableDefinition(String tableName) {
        this.tableName = tableName;
    }

    public void addColumn(ColumnDefinition columnDefinition) {
        this.columns.add(columnDefinition);
    }
    public void addPrimaryKey(String primaryKey) {
        this.primaryKeys.add(primaryKey);
    }
    public void addForeignKey(ForeignKeyDefinition foreignKey) {
        this.foreignKeys.add(foreignKey);
    }
    public void addForeignKeyDdl(String ddl) {
        if (ddl != null && !ddl.isBlank()) {
            this.foreignKeyDdls.add(ddl);
        }
    }

    public String getTableName() {
        return tableName;
    }

    public DatabaseType getSourceDialect() {
        return sourceDialect;
    }

    public void setSourceDialect(DatabaseType sourceDialect) {
        this.sourceDialect = sourceDialect;
    }

    public List<ColumnDefinition> getColumns() {
        return columns;
    }
    public List<String> getPrimaryKeys() {
        return primaryKeys;
    }
    public List<ForeignKeyDefinition> getForeignKeys() {
        return foreignKeys;
    }

    public List<String> getForeignKeyDdls() {
        return foreignKeyDdls;
    }

    public String getSourceSchema() {
        return sourceSchema;
    }

    public void setSourceSchema(String sourceSchema) {
        this.sourceSchema = sourceSchema;
    }

    public String getTargetSchema() {
        return targetSchema;
    }

    public void setTargetSchema(String targetSchema) {
        this.targetSchema = targetSchema;
    }

    public String getDdlText() {
        return ddlText;
    }

    public void setDdlText(String ddlText) {
        this.ddlText = ddlText;
    }
}
