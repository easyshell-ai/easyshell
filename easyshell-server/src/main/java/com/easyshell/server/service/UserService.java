package com.easyshell.server.service;

import com.easyshell.server.model.dto.PasswordResetRequest;
import com.easyshell.server.model.dto.UserCreateRequest;
import com.easyshell.server.model.dto.UserUpdateRequest;
import com.easyshell.server.model.vo.UserVO;

import java.util.List;

public interface UserService {

    List<UserVO> findAll();

    UserVO findById(Long id);

    UserVO create(UserCreateRequest request);

    UserVO update(Long id, UserUpdateRequest request);

    void delete(Long id);

    void resetPassword(Long id, PasswordResetRequest request);
}
