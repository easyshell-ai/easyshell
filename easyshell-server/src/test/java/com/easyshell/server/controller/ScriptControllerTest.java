package com.easyshell.server.controller;

import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.model.entity.Script;
import com.easyshell.server.service.AuditLogService;
import com.easyshell.server.service.ScriptService;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ScriptControllerTest {
    private final ScriptService service = mock(ScriptService.class);
    private final com.easyshell.server.repository.ScriptRepository scripts = mock(com.easyshell.server.repository.ScriptRepository.class);
    private final com.easyshell.server.repository.UserRepository users = mock(com.easyshell.server.repository.UserRepository.class);
    private final ScriptController controller = new ScriptController(service, mock(AuditLogService.class),
            new com.easyshell.server.security.ScriptAccess(scripts, users));

    private Authentication auth(long userId, String role) {
        var user = new com.easyshell.server.model.entity.User();
        user.setId(userId);
        user.setRole(role.substring(5).toLowerCase());
        lenient().when(users.findById(userId)).thenReturn(Optional.of(user));
        return new UsernamePasswordAuthenticationToken(userId, "", List.of(new SimpleGrantedAuthority(role)));
    }

    private Script script(long id, Long owner, Boolean isPublic) {
        Script script = new Script();
        script.setId(id);
        script.setCreatedBy(owner);
        script.setIsPublic(isPublic);
        script.setContent("echo {{secret}}");
        return script;
    }

    @Test
    void collectionsFilterPrivateScriptsBeforeReturning() {
        Script own = script(1, 1L, false);
        Script other = script(2, 2L, false);
        Script publicScript = script(3, 2L, true);
        Script ownerless = script(4, null, false);
        List<Script> all = List.of(own, other, publicScript, ownerless);
        when(service.findAll()).thenReturn(all);
        when(service.findTemplates()).thenReturn(all);
        when(service.findUserScripts()).thenReturn(all);
        Authentication user = auth(1, "ROLE_USER");

        assertThat(controller.list(user).getData()).containsExactly(own, publicScript);
        assertThat(controller.templates(user).getData()).containsExactly(own, publicScript);
        assertThat(controller.userScripts(user).getData()).containsExactly(own, publicScript);
        assertThat(controller.list(auth(3, "ROLE_ADMIN")).getData()).containsExactlyElementsOf(all);
        assertThat(controller.list(auth(3, "ROLE_SUPER_ADMIN")).getData()).containsExactlyElementsOf(all);
    }

    @Test
    void detailAndParametersHideOtherUsersPrivateScripts() {
        Script privateScript = script(1, 1L, false);
        Script publicScript = script(2, 1L, true);
        when(scripts.findById(1L)).thenReturn(Optional.of(privateScript));
        when(scripts.findById(2L)).thenReturn(Optional.of(publicScript));
        Authentication other = auth(2, "ROLE_USER");

        assertThatThrownBy(() -> controller.detail(1L, other)).isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(404));
        assertThatThrownBy(() -> controller.getScriptParameters(1L, other)).isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(404));
        assertThat(controller.detail(1L, auth(1, "ROLE_USER")).getData()).isSameAs(privateScript);
        assertThat(controller.detail(1L, auth(2, "ROLE_ADMIN")).getData()).isSameAs(privateScript);
        assertThat(controller.detail(2L, other).getData()).isSameAs(publicScript);
        assertThat(controller.getScriptParameters(2L, other).getData()).contains("secret");
    }

    @Test
    void missingOrInvalidIdentityFailsClosedBeforeQuery() {
        Authentication invalid = new UsernamePasswordAuthenticationToken("anonymous", "", List.of());
        Authentication unauthenticated = new UsernamePasswordAuthenticationToken(1L, "");
        for (Authentication identity : new Authentication[]{null, invalid, unauthenticated}) {
            assertThatThrownBy(() -> controller.list(identity)).isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> controller.templates(identity)).isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> controller.userScripts(identity)).isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> controller.detail(1L, identity)).isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> controller.getScriptParameters(1L, identity)).isInstanceOf(BusinessException.class);
        }
        verifyNoInteractions(service);
    }
}
