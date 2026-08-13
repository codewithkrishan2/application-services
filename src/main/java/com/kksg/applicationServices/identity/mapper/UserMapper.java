package com.kksg.applicationServices.identity.mapper;

import com.kksg.applicationServices.identity.dto.response.UserResponse;
import com.kksg.applicationServices.identity.entity.User;

public final class UserMapper {

    private UserMapper() {
    }

    public static UserResponse toResponse(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .profilePicture(user.getProfilePicture())
                .status(user.getStatus().name())
                .emailVerified(user.isEmailVerified())
                .createdAt(user.getCreatedAt())
                .build();
    }
}
