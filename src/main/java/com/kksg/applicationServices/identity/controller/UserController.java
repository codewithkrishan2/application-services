package com.kksg.applicationServices.identity.controller;

import com.kksg.applicationServices.common.exception.ResourceNotFoundException;
import com.kksg.applicationServices.common.response.ApiResponse;
import com.kksg.applicationServices.identity.dto.request.UpdateProfileRequest;
import com.kksg.applicationServices.identity.dto.response.UserResponse;
import com.kksg.applicationServices.identity.entity.User;
import com.kksg.applicationServices.identity.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.NonNull;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "User", description = "User management endpoints")
@SecurityRequirement(name = "bearerAuth")
public class UserController {

    private static final Logger log = LoggerFactory.getLogger(UserController.class);

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/me")
    @Operation(summary = "Get current user", description = "Get the currently authenticated user's profile")
    public ResponseEntity<ApiResponse<UserResponse>> getCurrentUser(@AuthenticationPrincipal User user) {
        try {
            UserResponse response = userService.getCurrentUser(user);
            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (ResourceNotFoundException ex) {
            log.warn("User not found: {}", ex.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(ex.getMessage()));
        } catch (Exception ex) {
            log.error("Unexpected error fetching current user", ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Failed to retrieve user profile"));
        }
    }

    @PatchMapping("/me")
    @Operation(summary = "Update profile", description = "Update the current user's profile (name only)")
    public ResponseEntity<ApiResponse<UserResponse>> updateProfile(
            @AuthenticationPrincipal @NonNull User user,
            @Valid @RequestBody UpdateProfileRequest request) {
        try {
            UserResponse response = userService.updateProfile(user, request);
            return ResponseEntity.ok(ApiResponse.success("Profile updated successfully", response));
        } catch (ResourceNotFoundException ex) {
            log.warn("User not found during profile update: {}", ex.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(ex.getMessage()));
        } catch (Exception ex) {
            log.error("Unexpected error updating user profile", ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Failed to update user profile"));
        }
    }
}
