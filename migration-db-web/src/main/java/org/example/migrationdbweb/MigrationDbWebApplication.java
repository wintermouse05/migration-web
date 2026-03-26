package org.example.migrationdbweb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class MigrationDbWebApplication {

    public static void main(String[] args) {
        SpringApplication.run(MigrationDbWebApplication.class, args);
    }

}
