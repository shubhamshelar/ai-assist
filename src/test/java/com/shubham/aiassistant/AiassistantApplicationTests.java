package com.shubham.aiassistant;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import org.mockito.Mockito;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@SpringBootTest(properties = {
		"spring.ai.ollama.base-url=http://localhost:11434",
		"spring.ai.ollama.embedding.model=nomic-embed-text",
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
