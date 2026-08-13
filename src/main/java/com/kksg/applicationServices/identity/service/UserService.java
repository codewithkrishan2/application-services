package com.kksg.applicationServices.identity.service;

import com.kksg.applicationServices.common.exception.ResourceNotFoundException;
import com.kksg.applicationServices.identity.dto.request.UpdateProfileRequest;
import com.kksg.applicationServices.identity.dto.response.UserResponse;
import com.kksg.applicationServices.identity.entity.User;
import com.kksg.applicationServices.identity.mapper.UserMapper;
import com.kksg.applicationServices.identity.repository.UserRepository;

import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public UserResponse getCurrentUser(User user) {
        return UserMapper.toResponse(user);
    }

    @Transactional
    public UserResponse updateProfile(@NonNull User user, UpdateProfileRequest request) {
        if (request.getFullName() != null) {
            user.setFullName(request.getFullName());
        }

        User updatedUser = userRepository.save(user);
        return UserMapper.toResponse(updatedUser);
    }

    public User findById(Integer id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", id));
    }
}
