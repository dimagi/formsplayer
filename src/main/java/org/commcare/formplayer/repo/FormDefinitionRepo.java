package org.commcare.formplayer.repo;

import org.commcare.formplayer.objects.SerializableFormDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FormDefinitionRepo extends JpaRepository<SerializableFormDefinition, Long> {
    Optional<SerializableFormDefinition> findByAppIdAndAppVersionAndXmlns(String appId, String appVersion, String xmlns);
}

