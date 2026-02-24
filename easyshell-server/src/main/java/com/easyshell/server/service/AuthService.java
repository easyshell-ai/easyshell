package com.easyshell.server.service;

import com.easyshell.server.model.dto.LoginRequest;
import com.easyshell.server.model.dto.LoginResponse;
import com.easyshell.server.model.vo.UserVO;

public interface AuthService {

    LoginResponse login(LoginRequest request);

    LoginResponse refresh(String refreshToken);

    UserVO getCurrentUser(Long userId);
}
