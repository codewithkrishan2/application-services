package com.kksg.applicationServices.identity.dto.request;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateProfileRequest {

    @Size(max = 100, message = "Name must not exceed 100 characters")
    private String fullName;
}
