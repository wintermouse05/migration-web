If you want to use Desktop App ver:
- Step 1: Build with Artifact 
- Step 2: build a portable with jpackage 
Command: jpackage --type app-image --name MigrationSwingApp --input "C:\Users\dongdd\Documents\Development\migration-web\migration-db-web\out\artifacts\migration_db_web_jar" --dest "C:\Users\dongdd\Documents\Development\migration-web\migration-db-web\out\jpackage-portable" --main-jar migration-db-web.jar --main-class org.example.migrationdbweb.migratetool.MigrationAppUI