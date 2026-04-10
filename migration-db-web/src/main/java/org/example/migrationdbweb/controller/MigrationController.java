package org.example.migrationdbweb.controller;

import java.util.Map;

import org.example.migrationdbweb.dto.MigrationRequest;
import org.example.migrationdbweb.dto.SavedCredentialRequest;
import org.example.migrationdbweb.service.MigrationWebWorkerService;
import org.example.migrationdbweb.service.SavedCredentialService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/migration")
@CrossOrigin(origins = "*") // Hỗ trợ CORS cho VueJS gọi API
public class MigrationController {

    @Autowired
    private MigrationWebWorkerService migrationService;

    @Autowired
    private SavedCredentialService savedCredentialService;

    @PostMapping("/start")
    public ResponseEntity<?> startMigration(@RequestBody MigrationRequest request) {

        // 1. (Tùy chọn) Validate cấu hình đầu vào ở đây trước khi chạy
        if (request.getSource() == null || request.getTarget() == null) {
            return ResponseEntity.badRequest().body("Missing Source or Target configuration.");
        }

        // 2. Kích hoạt tiến trình chạy ngầm
        migrationService.startMigrationProcess(request);

        // 3. Phản hồi ngay lập tức cho Frontend biết là Job đã được tiếp nhận
        return ResponseEntity.ok(Map.of(
                "status", "ACCEPTED",
            "message", "Migration has been queued in the background. Connect WebSocket to monitor progress."
        ));
    }

    @GetMapping("/status")
    public ResponseEntity<?> getCurrentStatus() {
        return ResponseEntity.ok(migrationService.getCurrentStatus());
    }

    @PostMapping("/test-connection")
    public ResponseEntity<?> testConnection(@RequestBody org.example.migrationdbweb.migratetool.DatabaseConfig config) {
        Map<String, Object> result = migrationService.testDatabaseConnection(config);
        boolean success = Boolean.TRUE.equals(result.get("success"));
        if (success) {
            return ResponseEntity.ok(result);
        }
        return ResponseEntity.badRequest().body(result);
    }

    @GetMapping("/credentials")
    public ResponseEntity<?> listSavedCredentials() {
        try {
            return ResponseEntity.ok(savedCredentialService.listCredentials());
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/credentials")
    public ResponseEntity<?> saveCredential(@RequestBody SavedCredentialRequest request) {
        if (request == null || request.getConfig() == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "Missing credential payload for saving."));
        }
        if (request.getName() == null || request.getName().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Credential name is required."));
        }

        try {
            return ResponseEntity.ok(savedCredentialService.saveCredential(request.getName(), request.getConfig()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("message", e.getMessage()));
        }
    }
}