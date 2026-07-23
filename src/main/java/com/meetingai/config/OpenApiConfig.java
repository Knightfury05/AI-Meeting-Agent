package com.meetingai.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("AI Meeting Agent API")
                        .description("REST API for AI-powered meeting transcription, summarization, and action item management. "
                                + "Supports audio upload, Google Calendar/Gmail integration, and Q&A chat on meeting content.")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("Developer")))
                .servers(List.of(
                        new Server().url("http://localhost:8081").description("Local development")
                ));
    }
}
