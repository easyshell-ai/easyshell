package com.easyshell.server.security;

import io.jsonwebtoken.Claims;
import java.util.Date;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentCredentialGenerationValidatorTest {
    @Test
    void legacyDenialSurvivesRetirementAndDatabaseFailure() {
        String url = "jdbc:h2:mem:legacy_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").load().migrate();
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
        var validator = new AgentCredentialGenerationValidator(mock(JwtUtils.class), jdbc);
        assertTrue(validator.permitsLegacy("host"));
        jdbc.update("INSERT INTO host_identity (agent_id, generation, active) VALUES ('host', 1, 1)");
        jdbc.update("INSERT INTO agent_bound_key (agent_id, generation, public_key) VALUES ('host', 1, 'key')");
        assertFalse(validator.permitsLegacy("host"));
        jdbc.update("UPDATE host_identity SET active = 0 WHERE agent_id = 'host'");
        assertFalse(validator.permitsLegacy("host"));
        jdbc.execute("DROP TABLE agent_bound_key");
        assertFalse(validator.permitsLegacy("host"));
    }

    @Test
    void validatesOnlyActiveExactIdentityAndCurrentGeneration() {
        String url = "jdbc:h2:mem:credential_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").load().migrate();
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
        var jwt = mock(JwtUtils.class);
        var claims = mock(Claims.class);
        when(jwt.parseToken("token")).thenReturn(claims);
        when(claims.getSubject()).thenReturn("host");
        when(claims.getExpiration()).thenReturn(new Date(System.currentTimeMillis() + 120_000));
        when(claims.get("type")).thenReturn(AgentCredentialGenerationValidator.TOKEN_TYPE_BOUND_AGENT);
        when(claims.get(AgentCredentialGenerationValidator.GENERATION_CLAIM)).thenReturn(1);
        var validator = new AgentCredentialGenerationValidator(jwt, jdbc);

        assertTrue(validator.validate("token").isEmpty());
        jdbc.update("INSERT INTO host_identity (agent_id, generation, active) VALUES ('host', 1, 1)");
        assertEquals(new AgentCredentialGenerationValidator.Identity("host", 1), validator.validate("token").orElseThrow());
        when(claims.getSubject()).thenReturn("other");
        assertTrue(validator.validate("token").isEmpty());
        when(claims.getSubject()).thenReturn("host");
        jdbc.update("UPDATE host_identity SET active = 0 WHERE agent_id = 'host'");
        assertTrue(validator.validate("token").isEmpty());
        jdbc.update("UPDATE host_identity SET active = 1, generation = 2 WHERE agent_id = 'host'");
        assertTrue(validator.validate("token").isEmpty());
        when(claims.get(AgentCredentialGenerationValidator.GENERATION_CLAIM)).thenReturn(2L);
        assertTrue(validator.validate("token").isPresent());
        when(claims.get("type")).thenReturn(JwtUtils.TOKEN_TYPE_AGENT);
        assertTrue(validator.validate("token").isEmpty());
        when(claims.get("type")).thenReturn(AgentCredentialGenerationValidator.TOKEN_TYPE_BOUND_AGENT);
        for (Object invalid : new Object[] { null, 0, -1L, "2", 2.0 }) {
            when(claims.get(AgentCredentialGenerationValidator.GENERATION_CLAIM)).thenReturn(invalid);
            assertTrue(validator.validate("token").isEmpty());
        }
        when(jwt.parseToken("bad")).thenThrow(new IllegalArgumentException("invalid or expired"));
        assertTrue(validator.validate("bad").isEmpty());
        assertTrue(validator.validate(null).isEmpty());
        when(claims.get(AgentCredentialGenerationValidator.GENERATION_CLAIM)).thenReturn(2);
        when(claims.getExpiration()).thenReturn(null);
        assertTrue(validator.validate("token").isEmpty());
        when(claims.getExpiration()).thenReturn(new Date(0));
        assertTrue(validator.validate("token").isEmpty());
        when(claims.getExpiration()).thenReturn(new Date(System.currentTimeMillis() + 120_000));
        var unavailable = new AgentCredentialGenerationValidator(jwt, mock(JdbcTemplate.class));
        // A null or failed query is rejected rather than accepted.
        assertTrue(unavailable.validate("token").isEmpty());
    }
}
