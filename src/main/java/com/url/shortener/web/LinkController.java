package com.url.shortener.web;

import com.url.shortener.config.AppProperties;
import com.url.shortener.service.ApiKeyService;
import com.url.shortener.service.LinkService;
import com.url.shortener.web.dto.CreateLinkRequest;
import com.url.shortener.web.dto.LinkResponse;
import com.url.shortener.web.dto.LinkStatsResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/links")
@Validated
@Tag(name = "Links", description = "Create, inspect and retire short links")
public class LinkController {

    private final LinkService links;
    private final ApiKeyService apiKeys;
    private final AppProperties properties;

    public LinkController(LinkService links, ApiKeyService apiKeys, AppProperties properties) {
        this.links = links;
        this.apiKeys = apiKeys;
        this.properties = properties;
    }

    /**
     * Credentials are resolved here and handed downward as an opaque owner id, so
     * {@link LinkService} never touches key material and stays testable without one.
     */
    private Long caller(String rawKey) {
        return apiKeys.resolve(rawKey).orElse(null);
    }

    @PostMapping
    @Operation(summary = "Create a short link",
            description = "Supply an Idempotency-Key header to make retries safe: a repeated "
                    + "key returns the original link with 200 instead of minting a new code.")
    public ResponseEntity<LinkResponse> create(
            @Valid @RequestBody CreateLinkRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-API-Key", required = false) String apiKey) {

        LinkService.CreateResult result = links.create(new LinkService.CreateCommand(
                request.url(), request.alias(), request.expiresAt(), idempotencyKey, caller(apiKey)));

        LinkResponse body = LinkResponse.from(result.link(), properties.baseUrl());
        if (result.reused()) {
            return ResponseEntity.ok(body);
        }
        return ResponseEntity.created(URI.create(body.shortUrl())).body(body);
    }

    @GetMapping("/{code}")
    @Operation(summary = "Fetch link metadata")
    public LinkResponse get(@PathVariable String code) {
        return LinkResponse.from(links.get(code), properties.baseUrl());
    }

    @GetMapping("/{code}/stats")
    @Operation(summary = "Fetch click analytics",
            description = "Counts are eventually consistent; see freshnessSeconds in the response.")
    public LinkStatsResponse stats(
            @PathVariable String code,
            @Parameter(description = "Number of trailing UTC days to include")
            @RequestParam(defaultValue = "30") @Min(1) @Max(365) int days,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int referrers,
            @RequestHeader(value = "X-API-Key", required = false) String apiKey) {

        return LinkStatsResponse.from(
                links.stats(code, days, referrers, caller(apiKey)),
                properties.baseUrl(),
                properties.analytics().flushInterval().toSeconds());
    }

    @DeleteMapping("/{code}")
    @Operation(summary = "Retire a link",
            description = "Soft delete. The code is never reissued, so old links fail closed.")
    public ResponseEntity<Void> delete(
            @PathVariable String code,
            @RequestHeader(value = "X-API-Key", required = false) String apiKey) {
        links.delete(code, caller(apiKey));
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}
