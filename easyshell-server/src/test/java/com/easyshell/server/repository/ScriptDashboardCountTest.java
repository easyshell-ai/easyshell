package com.easyshell.server.repository;

import com.easyshell.server.model.entity.Script;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import static org.junit.jupiter.api.Assertions.*;

@org.springframework.test.context.junit.jupiter.SpringJUnitConfig(ScriptDashboardCountTest.Config.class)
@org.springframework.transaction.annotation.Transactional
@TestPropertySource(properties={"spring.flyway.enabled=false","spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:script_dashboard_proof;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"})
class ScriptDashboardCountTest {
    @org.springframework.context.annotation.Configuration
    @org.springframework.boot.autoconfigure.ImportAutoConfiguration({
            org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class,
            org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration.class,
            org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration.class})
    @org.springframework.boot.autoconfigure.domain.EntityScan(basePackageClasses=Script.class)
    @org.springframework.data.jpa.repository.config.EnableJpaRepositories(basePackageClasses=ScriptRepository.class)
    static class Config { }
    @Autowired ScriptRepository scripts;

    @Test
    void countsPublicOwnedAndAdministratorReadableScriptsWithoutOtherPrivateScripts() {
        save("public",2L,true);save("owned",1L,false);save("hidden",2L,false);
        assertEquals(2,scripts.countReadable(1L,false));
        assertEquals(2,scripts.countReadable(2L,false));
        assertEquals(1,scripts.countReadable(3L,false));
        assertEquals(3,scripts.countReadable(3L,true));
    }

    private void save(String name,long owner,boolean publicScript) {
        Script script=new Script();script.setName(name);script.setContent("true");
        script.setCreatedBy(owner);script.setIsPublic(publicScript);scripts.saveAndFlush(script);
    }
}
