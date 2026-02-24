package com.easyshell.server.service.impl;

import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.model.dto.PasswordResetRequest;
import com.easyshell.server.model.dto.UserCreateRequest;
import com.easyshell.server.model.dto.UserUpdateRequest;
import com.easyshell.server.model.entity.User;
import com.easyshell.server.model.vo.UserVO;
import com.easyshell.server.repository.UserRepository;
import com.easyshell.server.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    private static final Set<String> VALID_ROLES = Set.of("super_admin", "admin", "operator", "viewer");

    @Override
    public List<UserVO> findAll() {
        return userRepository.findAll().stream()
                .map(this::toVO)
                .toList();
    }

    @Override
    public UserVO findById(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "用户不存在"));
        return toVO(user);
    }

    @Override
    @Transactional
    public UserVO create(UserCreateRequest request) {
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new BusinessException(400, "用户名已存在");
        }

        if (!VALID_ROLES.contains(request.getRole())) {
            throw new BusinessException(400, "无效的角色: " + request.getRole());
        }

        User user = new User();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setEmail(request.getEmail());
        user.setRole(request.getRole());
        user.setStatus(1);

        user = userRepository.save(user);
        return toVO(user);
    }

    @Override
    @Transactional
    public UserVO update(Long id, UserUpdateRequest request) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "用户不存在"));

        if (request.getEmail() != null) {
            user.setEmail(request.getEmail());
        }
        if (request.getRole() != null) {
            if (!VALID_ROLES.contains(request.getRole())) {
                throw new BusinessException(400, "无效的角色: " + request.getRole());
            }
            user.setRole(request.getRole());
        }
        if (request.getStatus() != null) {
            user.setStatus(request.getStatus());
        }

        user = userRepository.save(user);
        return toVO(user);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "用户不存在"));

        // Prevent deleting the last super_admin
        if ("super_admin".equals(user.getRole())) {
            long superAdminCount = userRepository.findAll().stream()
                    .filter(u -> "super_admin".equals(u.getRole()))
                    .count();
            if (superAdminCount <= 1) {
                throw new BusinessException(400, "不能删除最后一个超级管理员");
            }
        }

        userRepository.delete(user);
    }

    @Override
    @Transactional
    public void resetPassword(Long id, PasswordResetRequest request) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "用户不存在"));
        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);
    }

    private UserVO toVO(User user) {
        return UserVO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .role(user.getRole())
                .status(user.getStatus())
                .lastLoginAt(user.getLastLoginAt())
                .createdAt(user.getCreatedAt())
                .build();
    }
}
