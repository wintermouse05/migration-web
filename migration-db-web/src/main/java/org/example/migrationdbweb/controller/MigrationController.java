package org.example.migrationdbweb.controller;

import java.util.Map;

import org.example.migrationdbweb.dto.MigrationRequest;
import org.example.migrationdbweb.service.MigrationWebWorkerService;
import org.springframework.beans.factory.annotation.Autowired;
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

    @PostMapping("/start")
    public ResponseEntity<?> startMigration(@RequestBody MigrationRequest request) {

        // 1. (Tùy chọn) Validate cấu hình đầu vào ở đây trước khi chạy
        if (request.getSource() == null || request.getTarget() == null) {
            return ResponseEntity.badRequest().body("Thiếu thông tin cấu hình Source hoặc Target.");
        }

        // 2. Kích hoạt tiến trình chạy ngầm
        migrationService.startMigrationProcess(request);

        // 3. Phản hồi ngay lập tức cho Frontend biết là Job đã được tiếp nhận
        return ResponseEntity.ok(Map.of(
                "status", "ACCEPTED",
                "message", "Tiến trình migration đã được đưa vào hàng đợi nền. Vui lòng kết nối WebSocket để theo dõi."
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
}