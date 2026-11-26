package com.kayx.manager.data.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class AiProviderConfigTest {
    @Test fun disabledConfigurationDoesNotRequireEndpointOrModel() {
        assertEquals(AiProviderConfig.Validation.Valid, AiProviderConfig("", "", enabled = false).validate())
    }

    @Test fun enabledConfigurationRequiresHttpsAndModel() {
        assertEquals(
            AiProviderConfig.Validation.Invalid("AI endpoint must use HTTPS"),
            AiProviderConfig("http://example.com/v1", "model", enabled = true).validate()
        )
        assertEquals(
            AiProviderConfig.Validation.Invalid("AI model name is required"),
            AiProviderConfig("https://example.com/v1", " ", enabled = true).validate()
        )
    }

    @Test fun rejectsCredentialsInEndpointAndAcceptsHttpsEndpoint() {
        assertEquals(
            AiProviderConfig.Validation.Invalid("AI endpoint URL is not allowed"),
            AiProviderConfig("https://user:pass@example.com/v1", "model", enabled = true).validate()
        )
        assertEquals(
            AiProviderConfig.Validation.Valid,
            AiProviderConfig("https://api.example.com/v1", "model", enabled = true).validate()
        )
    }

    @Test fun rejectsControlCharactersAndOverlongModelNames() {
        assertEquals(
            AiProviderConfig.Validation.Invalid("AI model name is invalid"),
            AiProviderConfig("https://api.example.com/v1", "bad\nmodel", enabled = true).validate()
        )
        assertEquals(
            AiProviderConfig.Validation.Invalid("AI model name is invalid"),
            AiProviderConfig("https://api.example.com/v1", "m".repeat(129), enabled = true).validate()
        )
    }
}
