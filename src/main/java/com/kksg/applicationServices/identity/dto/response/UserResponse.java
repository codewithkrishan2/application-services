package com.kksg.applicationServices.identity.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserResponse {

    private Integer id;
    private String email;
    private String fullName;
    private String profilePicture;
    private String status;
    private boolean emailVerified;
    private Instant createdAt;
}
