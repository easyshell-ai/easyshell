package com.easyshell.server.ai.controller;

import com.easyshell.server.ai.service.InspectReportAccessService;
import com.easyshell.server.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AiInspectControllerTest {
    @Test void delegatesBothReadsToSharedAccessService() {
        var access = mock(InspectReportAccessService.class);
        var controller = new AiInspectController(access);
        var auth = new UsernamePasswordAuthenticationToken(1L, "user", List.of(new SimpleGrantedAuthority("ROLE_VIEWER")));
        when(access.page(1L, false, 0, 20)).thenReturn(org.springframework.data.domain.Page.empty(org.springframework.data.domain.PageRequest.of(0, 20)));
        controller.listReports(0, 20, auth);
        verify(access).page(1L, false, 0, 20);
        when(access.detail(7L, 1L, false)).thenThrow(new BusinessException(503, "unavailable"));
        assertThatThrownBy(() -> controller.getReport(7L, auth)).isInstanceOf(BusinessException.class);
    }

    @Test void missingIdentityIsRejectedBeforeAnyQuery() {
        var access = mock(InspectReportAccessService.class);
        var controller = new AiInspectController(access);
        assertThatThrownBy(() -> controller.listReports(0, 20, null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> controller.getReport(7L, null)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(access);
    }
}
