package com.shubham.aiassistant;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import org.mockito.Mockito;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@SpringBootTest(properties = {
		"spring.ai.google.genai.api-key=dummy-gemini-key",
		"spring.ai.google.genai.project-id=dummy-project",
		"spring.ai.google.genai.embedding.api-key=dummy-gemini-key",
		"spring.ai.google.genai.embedding.project-id=dummy-project",
		"spring.ai.openai.api-key=dummy-groq-key",
		"spring.datasource.url=jdbc:h2:mem:testdb;MODE=PostgreSQL",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.jpa.hibernate.ddl-auto=create-drop"
})
class AiassistantApplicationTests {

	@TestConfiguration
	static class TestConfig {
		@Bean
		@Primary
		public VectorStore mockVectorStore() {
			return Mockito.mock(VectorStore.class);
		}
	}

	@Test
	void contextLoads() {
	}

}
