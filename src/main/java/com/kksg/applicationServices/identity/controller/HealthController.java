package com.kksg.applicationServices.identity.controller;

import com.kksg.applicationServices.common.response.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/health")
public class HealthController {

    @GetMapping
    public ResponseEntity<ApiResponse<String>> checkHealth() {
        return ResponseEntity.ok(ApiResponse.success("Application is running", "UP"));
    }
}
