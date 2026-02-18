package org.big.bigcollector.repository;

import org.big.bigcollector.entity.LlmPrompt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LlmPromptRepository extends JpaRepository<LlmPrompt, Long> {

    Optional<LlmPrompt> findByPromptNameAndActiveTrue(String promptName);
}
