package org.commcare.formplayer.repo;

import org.commcare.formplayer.objects.FormDefinition;
import org.commcare.formplayer.utils.JpaTestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.persistence.EntityManager;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnableJpaAuditing
public class FormDefinitionRepoTest {

    @Autowired
    FormDefinitionRepo formDefinitionRepo;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    public void setUp() {
        this.jdbcTemplate.execute("DELETE from formplayer_sessions");
        this.jdbcTemplate.execute("DELETE from form_definition");
    }

    @Test
    public void testSaveAndLoad() {
        FormDefinition formDef = new FormDefinition(
                "appId",
                "appVersion",
                "xmlns",
                "formdef"
        );

        formDefinitionRepo.saveAndFlush(formDef);
        entityManager.clear(); // clear the EM cache to force a re-fetch from DB
        FormDefinition loaded = JpaTestUtils.unwrapProxy(
                formDefinitionRepo.getOne(formDef.getId())
        );
        assertThat(loaded).usingRecursiveComparison().ignoringFields("dateCreated", "id").isEqualTo(formDef);
        Instant dateCreated = loaded.getDateCreated();
        assertThat(dateCreated).isNotNull();

        formDefinitionRepo.saveAndFlush(loaded);
        assertThat(loaded.getDateCreated()).isEqualTo(dateCreated);
    }

    @Test
    public void testFindByAppIdAndAppVersionAndXmlns() {
        FormDefinition formDef = new FormDefinition(
                "appId",
                "appVersion",
                "xmlns",
                "formdef"
        );
        formDefinitionRepo.save(formDef);
        Optional<FormDefinition> optFormDef = formDefinitionRepo.findByAppIdAndAppVersionAndXmlns(
                "appId", "appVersion", "xmlns"
        );
        assertThat(optFormDef.isPresent()).isTrue();
        FormDefinition fetchedFormDef = optFormDef.get();
        assertThat(fetchedFormDef.getAppId()).isEqualTo("appId");
        assertThat(fetchedFormDef.getAppVersion()).isEqualTo("appVersion");
        assertThat(fetchedFormDef.getXmlns()).isEqualTo("xmlns");
        assertThat(fetchedFormDef.getDateCreated()).isEqualTo(formDef.getDateCreated());
    }
}
