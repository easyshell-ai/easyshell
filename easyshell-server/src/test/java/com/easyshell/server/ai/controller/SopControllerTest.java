package com.easyshell.server.ai.controller;

import com.easyshell.server.ai.learning.SopExtractor;
import com.easyshell.server.ai.model.entity.AiSopTemplate;
import com.easyshell.server.ai.repository.AiSopTemplateRepository;
import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.model.entity.User;
import com.easyshell.server.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SopControllerTest {
    @Mock AiSopTemplateRepository repository;
    @Mock SopExtractor extractor;
    @Mock UserRepository users;
    SopController controller;
    Authentication auth;
    User user;

    @BeforeEach
    void setUp() {
        controller = new SopController(repository, extractor, users);
        auth = new UsernamePasswordAuthenticationToken(1L, "", List.of(new SimpleGrantedAuthority("ROLE_super_admin")));
        user = new User();
        user.setId(1L);
        user.setRole("viewer");
    }

    void validUser() { when(users.findById(1L)).thenReturn(Optional.of(user)); }
    AiSopTemplate sop(Long owner) { return AiSopTemplate.builder().id(7L).userId(owner).title("old").confidence(0.5).build(); }

    @Test
    void listFiltersBeforePaginationAndCategory() {
        validUser();
        AiSopTemplate own = sop(1L); own.setCategory("ops"); own.setConfidence(0.8);
        AiSopTemplate global = sop(null); global.setCategory("ops");
        AiSopTemplate other = sop(2L); other.setCategory("ops"); other.setConfidence(1.0);
        when(repository.findByUserIdIsNullOrUserId(1L)).thenReturn(List.of(global, other, own));
        var page = controller.list("ops", 0, 1, auth).getData();
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).containsExactly(own);
        assertThat(controller.list("ops", 1, 1, auth).getData().getContent()).containsExactly(global);
    }

    @Test
    void ownerCanReadUpdateAndDeleteButCannotTransferOwnership() {
        validUser();
        AiSopTemplate own = sop(1L);
        when(repository.findById(7L)).thenReturn(Optional.of(own));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        assertThat(controller.getById(7L, auth).getData()).isSameAs(own);
        AiSopTemplate request = sop(2L); request.setStepsJson("new steps");
        controller.update(7L, request, auth);
        assertThat(own.getUserId()).isEqualTo(1L);
        assertThat(own.getStepsJson()).isEqualTo("new steps");
        controller.delete(7L, auth);
        verify(repository).deleteById(7L);
    }

    @Test
    void otherUsersPrivateSopDeniedEvenToCurrentSuperAdmin() {
        user.setRole("super_admin"); validUser();
        AiSopTemplate other = sop(2L);
        when(repository.findById(7L)).thenReturn(Optional.of(other));
        assertThatThrownBy(() -> controller.getById(7L, auth)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> controller.update(7L, sop(1L), auth)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> controller.delete(7L, auth)).isInstanceOf(BusinessException.class);
        verify(repository, never()).save(any());
        verify(repository, never()).deleteById(any());
        assertThat(other.getTitle()).isEqualTo("old");
    }

    @Test
    void globalReadableButWritesRequireCurrentDatabaseSuperAdmin() {
        validUser();
        AiSopTemplate global = sop(null);
        when(repository.findById(7L)).thenReturn(Optional.of(global));
        assertThat(controller.getById(7L, auth).getData()).isSameAs(global);
        assertThatThrownBy(() -> controller.update(7L, sop(1L), auth)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> controller.delete(7L, auth)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> controller.triggerExtraction(auth)).isInstanceOf(BusinessException.class);
        user.setRole("super_admin");
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        controller.update(7L, sop(1L), auth);
        controller.delete(7L, auth);
        controller.triggerExtraction(auth);
        verify(extractor).extractSopPatterns();
        verify(repository).deleteById(7L);
    }

    @Test
    void disabledMissingAndUntrustedPrincipalsFailClosed() {
        assertThatThrownBy(() -> controller.list(null, 0, 20, null)).isInstanceOf(BusinessException.class);
        Authentication unauthenticated = new UsernamePasswordAuthenticationToken(1L, "");
        assertThatThrownBy(() -> controller.list(null, 0, 20, unauthenticated)).isInstanceOf(BusinessException.class);
        Authentication stringPrincipal = new UsernamePasswordAuthenticationToken("1", "", List.of());
        assertThatThrownBy(() -> controller.list(null, 0, 20, stringPrincipal)).isInstanceOf(BusinessException.class);
        when(users.findById(1L)).thenReturn(Optional.empty(), Optional.of(user));
        assertThatThrownBy(() -> controller.getById(7L, auth)).isInstanceOf(BusinessException.class);
        user.setStatus(0);
        assertThatThrownBy(() -> controller.delete(7L, auth)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(repository, extractor);
    }

    @Test
    void missingSopReturnsFailure() {
        validUser();
        when(repository.findById(7L)).thenReturn(Optional.empty());
        assertThat(controller.getById(7L, auth).getCode()).isNotEqualTo(200);
        assertThat(controller.update(7L, sop(1L), auth).getCode()).isNotEqualTo(200);
        assertThat(controller.delete(7L, auth).getCode()).isNotEqualTo(200);
    }
}
