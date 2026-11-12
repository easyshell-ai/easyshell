package com.easyshell.server.service.impl;

import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.controller.HostProvisionController;
import com.easyshell.server.repository.AgentRepository;
import com.easyshell.server.repository.HostCredentialRepository;
import com.easyshell.server.repository.SystemConfigRepository;
import com.easyshell.server.util.CryptoUtils;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class HostProvisionServiceImplTest {

    @Test
    void unifiedListFailsExplicitlyWithoutReadingOrMatchingHistoricalRecords() {
        HostCredentialRepository credentials = mock(HostCredentialRepository.class);
        AgentRepository agents = mock(AgentRepository.class);
        HostProvisionServiceImpl service = new HostProvisionServiceImpl(credentials, agents,
                mock(CryptoUtils.class), mock(TransactionTemplate.class), mock(SystemConfigRepository.class));

        assertThatThrownBy(service::listUnified)
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(503);
                    assertThat(ex.getMessage()).contains("unavailable", "ownership", "generation");
                });
        verifyNoInteractions(credentials, agents);
    }

    @Test
    void credentialReadEndpointsRemainRestrictedToAdministrators() throws Exception {
        for (String method : new String[] {"list", "getById", "unifiedList"}) {
            var endpoint = method.equals("getById")
                    ? HostProvisionController.class.getMethod(method, Long.class)
                    : method.equals("unifiedList") ? HostProvisionController.class.getMethod(method,org.springframework.security.core.Authentication.class)
                    : HostProvisionController.class.getMethod(method);
            assertThat(endpoint.getAnnotation(PreAuthorize.class).value())
                    .isEqualTo("hasAnyRole('ADMIN','SUPER_ADMIN')");
        }
    }
}
