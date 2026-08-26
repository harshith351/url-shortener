package com.url.shortener.web;

import com.url.shortener.config.AppProperties;
import com.url.shortener.error.ForbiddenException;
import com.url.shortener.service.ApiKeyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Key provisioning, guarded by a bootstrap token supplied out of band.
 *
 * <p>Key issuance has a chicken-and-egg problem: the first key cannot be authenticated by
 * a key. Rather than invent a self-service signup flow this prototype does not need, the
 * endpoint is gated on {@code app.auth.admin-token}, which is configured per environment
 * and never has a default. If the token is unset the endpoint refuses everything, so a
 * misconfigured deployment fails closed rather than issuing credentials to the world.
 */
@RestController
@RequestMapping("/api-keys")
@Tag(name = "API keys", description = "Administrative key provisioning")
public class ApiKeyController {

    private final ApiKeyService apiKeys;
    private final AppProperties properties;

    public ApiKeyController(ApiKeyService apiKeys, AppProperties properties) {
        this.apiKeys = apiKeys;
        this.properties = properties;
    }

    public record IssueKeyRequest(
            @NotBlank(message = "name is required")
            @Size(max = 120, message = "name must be at most 120 characters")
            String name) {
    }

    public record IssueKeyResponse(Long id, String name, String apiKey, String warning) {
    }

    @PostMapping
    @Operation(summary = "Issue an API key",
            description = "Requires the X-Admin-Token header. The key is returned once and cannot be recovered.")
    public ResponseEntity<IssueKeyResponse> issue(
            @Valid @RequestBody IssueKeyRequest request,
            @RequestHeader(value = "X-Admin-Token", required = false) String adminToken) {

        requireAdmin(adminToken);
        ApiKeyService.IssuedKey issued = apiKeys.issue(request.name());
        return ResponseEntity.status(HttpStatus.CREATED).body(new IssueKeyResponse(
                issued.id(), issued.name(), issued.rawKey(),
                "Store this now. Only its hash is retained, so it cannot be shown again."));
    }

    /**
     * Constant-time comparison: this one genuinely needs it, because unlike key
     * verification it compares a secret in application code rather than looking one up
     * by hash, and a short-circuiting equals would leak the token byte by byte.
     */
    private void requireAdmin(String presented) {
        String expected = properties.auth().adminToken();
        if (expected == null || expected.isBlank() || presented == null) {
            throw new ForbiddenException();
        }
        boolean matches = MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
        if (!matches) {
            throw new ForbiddenException();
        }
    }
}
