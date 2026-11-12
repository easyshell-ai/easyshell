package com.easyshell.server.workflow.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring-owned endpoint inventory for {@link WorkflowController}.
 *
 * <p>This is deliberately separate from {@link WorkflowControllerTest}: the latter's inventory
 * is a unit-test fixture, whereas this test reads the mappings actually registered by Spring.
 * A new or changed HTTP mapping therefore fails here until the published inventory and its
 * access contract are reviewed together.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Workflow REST endpoint mapping inventory")
class WorkflowEndpointMappingTest {

    private static final String PREFIX = "/api/v1/workflow";

    /**
     * The deliberately explicit 18/14 contract. The value is the method-level authorization
     * contract; null means authentication is required but there is no role annotation.
     */
    private static final Map<Endpoint, String> DECLARED_ENDPOINTS = declaredEndpoints();

    @MockitoBean
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    @DisplayName("Spring mappings are exactly the declared 18 endpoint contract")
    void springMappingsMatchDeclaredContract() {
        Map<Endpoint, HandlerMethod> actual = actualWorkflowMappings();

        assertThat(DECLARED_ENDPOINTS).hasSize(18);
        assertThat(actual).hasSize(18);
        assertThat(actual.keySet())
                .as("HTTP method/path additions and changes require an inventory review")
                .containsExactlyInAnyOrderElementsOf(DECLARED_ENDPOINTS.keySet());

        for (Map.Entry<Endpoint, String> entry : DECLARED_ENDPOINTS.entrySet()) {
            HandlerMethod handler = actual.get(entry.getKey());
            PreAuthorize authorization = handler.getMethodAnnotation(PreAuthorize.class);
            String expression = authorization == null ? null : authorization.value();
            assertThat(expression)
                    .as("authorization for %s %s", entry.getKey().method(), entry.getKey().path())
                    .isEqualTo(entry.getValue());
        }
    }

    @Test
    @DisplayName("every mapped path variable is covered by the existing parameterized 403 fixture")
    void mappedPathVariablesMatchParameterizedForbiddenFixture() throws Exception {
        Set<String> mappedPathVariables = actualWorkflowMappings().keySet().stream()
                .map(Endpoint::asDisplayName)
                .filter(endpoint -> endpoint.contains("{"))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Set<String> forbiddenFixture = WorkflowControllerTest.pathVariableEndpoints()
                .collect(Collectors.toCollection(LinkedHashSet::new));

        assertThat(mappedPathVariables)
                .as("each mapped path-variable endpoint must have a concrete 403 case")
                .containsExactlyInAnyOrderElementsOf(forbiddenFixture);
        assertThat(forbiddenFixture).hasSize(14);

        Method deniedTest = WorkflowControllerTest.class
                .getDeclaredMethod("pathVariableEndpoint_nonOwner_isDenied", String.class);
        assertThat(deniedTest.getAnnotation(ParameterizedTest.class)).isNotNull();
        MethodSource source = deniedTest.getAnnotation(MethodSource.class);
        assertThat(source).isNotNull();
        assertThat(source.value()).containsExactly("pathVariableEndpoints");
    }

    private Map<Endpoint, HandlerMethod> actualWorkflowMappings() {
        Map<Endpoint, HandlerMethod> mappings = new LinkedHashMap<>();
        handlerMapping.getHandlerMethods().forEach((info, handler) -> {
            for (String fullPath : info.getPatternValues()) {
                if (!fullPath.equals(PREFIX) && !fullPath.startsWith(PREFIX + "/")) {
                    continue;
                }
                String path = fullPath.substring(PREFIX.length());
                if (path.isEmpty()) {
                    path = "/";
                }
                assertThat(info.getMethodsCondition().getMethods())
                        .as("workflow mapping must explicitly declare HTTP methods: %s", fullPath)
                        .isNotEmpty();
                for (RequestMethod method : info.getMethodsCondition().getMethods()) {
                    Endpoint endpoint = new Endpoint(method, path);
                    assertThat(mappings.put(endpoint, handler))
                            .as("duplicate mapping in the workflow inventory: %s", endpoint)
                            .isNull();
                }
            }
        });
        return mappings;
    }

    private static Map<Endpoint, String> declaredEndpoints() {
        Map<Endpoint, String> endpoints = new LinkedHashMap<>();
        add(endpoints, RequestMethod.GET, "/page", null);
        add(endpoints, RequestMethod.POST, "/", "hasAnyRole('OPERATOR','ADMIN','SUPER_ADMIN')");
        add(endpoints, RequestMethod.GET, "/{id}", null);
        add(endpoints, RequestMethod.PUT, "/{id}", "hasAnyRole('OPERATOR','ADMIN','SUPER_ADMIN')");
        add(endpoints, RequestMethod.DELETE, "/{id}", "hasAnyRole('OPERATOR','ADMIN','SUPER_ADMIN')");
        add(endpoints, RequestMethod.GET, "/{id}/versions", null);
        add(endpoints, RequestMethod.GET, "/{id}/versions/{versionId}", null);
        add(endpoints, RequestMethod.PUT, "/{id}/versions/draft", "hasAnyRole('OPERATOR','ADMIN','SUPER_ADMIN')");
        add(endpoints, RequestMethod.POST, "/{id}/validate", "hasAnyRole('OPERATOR','ADMIN','SUPER_ADMIN')");
        add(endpoints, RequestMethod.POST, "/{id}/publish", "hasAnyRole('OPERATOR','ADMIN','SUPER_ADMIN')");
        add(endpoints, RequestMethod.POST, "/{id}/runs", "hasAnyRole('OPERATOR','ADMIN','SUPER_ADMIN')");
        add(endpoints, RequestMethod.POST, "/{id}/nodes/code/test", "hasAnyRole('OPERATOR','ADMIN','SUPER_ADMIN')");
        add(endpoints, RequestMethod.GET, "/runs", null);
        add(endpoints, RequestMethod.GET, "/runs/{runId}", null);
        add(endpoints, RequestMethod.POST, "/runs/{runId}/cancel", "hasAnyRole('OPERATOR','ADMIN','SUPER_ADMIN')");
        add(endpoints, RequestMethod.POST, "/runs/{runId}/nodes/{nodeKey}/test-run", "hasAnyRole('OPERATOR','ADMIN','SUPER_ADMIN')");
        add(endpoints, RequestMethod.GET, "/approvals/pending", "hasAnyRole('ADMIN','SUPER_ADMIN')");
        add(endpoints, RequestMethod.POST, "/approvals/{approvalId}/decision", "hasAnyRole('ADMIN','SUPER_ADMIN')");
        return java.util.Collections.unmodifiableMap(endpoints);
    }

    private static void add(Map<Endpoint, String> endpoints, RequestMethod method,
                            String path, String authorization) {
        endpoints.put(new Endpoint(method, path), authorization);
    }

    private record Endpoint(RequestMethod method, String path) {
        String asDisplayName() {
            return method + " " + path;
        }
    }
}
