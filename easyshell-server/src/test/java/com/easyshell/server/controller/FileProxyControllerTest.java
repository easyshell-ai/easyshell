package com.easyshell.server.controller;

import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.model.dto.FileRenameRequest;
import com.easyshell.server.model.entity.User;
import com.easyshell.server.repository.UserRepository;
import com.easyshell.server.service.AuditLogService;
import com.easyshell.server.service.FileProxyService;
import com.easyshell.server.service.HostAclService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayOutputStream;
import java.util.Optional;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FileProxyControllerTest {
    private final FileProxyService files = mock(FileProxyService.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final HostAclService acl = mock(HostAclService.class);
    private final UserRepository users = mock(UserRepository.class);
    private final FileProxyController controller = new FileProxyController(files, audit, acl, users);
    private final UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(7L, null, java.util.List.of());
    private final MockHttpServletRequest request = new MockHttpServletRequest();

    private void admin() {
        User user = new User();
        user.setRole("admin");
        user.setStatus(1);
        when(users.findById(7L)).thenReturn(Optional.of(user));
    }

    private void invoke(String operation) {
        var rename = new FileRenameRequest();
        rename.setOldPath("/old");
        rename.setNewPath("/new");
        switch (operation) {
            case "list" -> controller.listFiles("host", "/secret", auth, request);
            case "download" -> controller.downloadFile("host", "/secret", auth, request);
            case "upload" -> controller.uploadFile("host", "/secret", new MockMultipartFile("file", new byte[0]), auth, request);
            case "mkdir" -> controller.mkdir("host", "/secret", auth, request);
            case "delete" -> controller.deleteFile("host", "/secret", auth, request);
            case "rename" -> controller.rename("host", rename, auth, request);
            default -> throw new AssertionError(operation);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"list", "upload", "mkdir", "delete", "rename"})
    void terminalGrantAllowsOperation(String operation) {
        admin();
        when(acl.authorizedGeneration(7L, "host", HostAclService.Permission.TERMINAL)).thenReturn(OptionalLong.of(3));
        invoke(operation);
        verify(acl).authorizedGeneration(7L, "host", HostAclService.Permission.TERMINAL);
        verifyNoMoreInteractions(acl);
        assertNotNull(mockingDetails(files).getInvocations().stream().findFirst().orElse(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"list", "download", "upload", "mkdir", "delete", "rename"})
    void deniedAndAclFailureNeverCallFileService(String operation) {
        admin();
        when(acl.authorizedGeneration(7L, "host", HostAclService.Permission.TERMINAL)).thenReturn(OptionalLong.empty());
        assertEquals(403, assertThrows(BusinessException.class, () -> invoke(operation)).getCode());
        when(acl.authorizedGeneration(7L, "host", HostAclService.Permission.TERMINAL)).thenThrow(new IllegalStateException("secret"));
        var error = assertThrows(BusinessException.class, () -> invoke(operation));
        assertEquals(403, error.getCode());
        assertFalse(error.getMessage().contains("secret"));
        verifyNoInteractions(files);
    }

    @ParameterizedTest
    @ValueSource(strings = {"list", "download", "upload", "mkdir", "delete", "rename"})
    void staleAdminRoleIsRejected(String operation) {
        User user = new User();
        user.setRole("operator");
        user.setStatus(1);
        when(users.findById(7L)).thenReturn(Optional.of(user));
        assertEquals(403, assertThrows(BusinessException.class, () -> invoke(operation)).getCode());
        verifyNoInteractions(files, acl);
    }

    @Test
    void downloadRechecksGenerationBeforeWriting() throws Exception {
        admin();
        when(acl.authorizedGeneration(7L, "host", HostAclService.Permission.TERMINAL)).thenReturn(OptionalLong.of(3));
        StreamingResponseBody body = mock(StreamingResponseBody.class);
        when(files.downloadFile("host", "/secret")).thenReturn(ResponseEntity.ok(body));
        var response = controller.downloadFile("host", "/secret", auth, request);
        assertThrows(BusinessException.class, () -> response.getBody().writeTo(new ByteArrayOutputStream()));
        verify(acl).allowedForGeneration(7L, "host", 3, HostAclService.Permission.TERMINAL);
        verifyNoInteractions(body);
    }

    @Test
    void authorizedDownloadWritesBody() throws Exception {
        admin();
        when(acl.authorizedGeneration(7L, "host", HostAclService.Permission.TERMINAL)).thenReturn(OptionalLong.of(3));
        when(acl.allowedForGeneration(7L, "host", 3, HostAclService.Permission.TERMINAL)).thenReturn(true);
        StreamingResponseBody body = mock(StreamingResponseBody.class);
        when(files.downloadFile("host", "/secret")).thenReturn(ResponseEntity.ok(body));
        var output = new ByteArrayOutputStream();
        controller.downloadFile("host", "/secret", auth, request).getBody().writeTo(output);
        verify(body).writeTo(output);
    }
}
