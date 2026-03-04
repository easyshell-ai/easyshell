package com.easyshell.server.service;

import com.easyshell.server.model.vo.FileInfoVO;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.List;

public interface FileProxyService {
    List<FileInfoVO> listFiles(String agentId, String path);
    ResponseEntity<StreamingResponseBody> downloadFile(String agentId, String path);
    void uploadFile(String agentId, String path, MultipartFile file);
    void mkdir(String agentId, String path);
    void delete(String agentId, String path);
    void rename(String agentId, String oldPath, String newPath);
    void handleFileResponse(String requestId, String responseJson);
}
