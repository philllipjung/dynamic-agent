package com.javaagent.server.controller;

import com.javaagent.bytebuddy.ByteBuddyAgent;
import com.javaagent.commons.SpanLinkConfig;
import com.javaagent.server.service.SpanLinkService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Span Link REST API Controller
 *
 * 스팬 링크 설정을 관리하고 ByteBuddy Advice를 적용
 */
@RestController
@RequestMapping("/api/spanlink")
@CrossOrigin(origins = "*")
public class SpanLinkController {

    @Autowired
    private SpanLinkService spanLinkService;

    /**
     * 링크 생성
     *
     * POST /api/spanlink/create
     * Body: {
     *   "sourceSpanName": "/FindDriverIDs",
     *   "sourceAttributeKey": "arthas.attribute.userId",
     *   "targetSpanName": "/GetDriverProfile",
     *   "targetAttributeKey": "arthas.attribute.userId",
     *   "linkType": "PARENT_TO_CHILD"
     * }
     */
    @PostMapping("/create")
    public Map<String, Object> createLink(@RequestBody CreateLinkRequest request) {
        try {
            String linkId = generateLinkId(request);

            SpanLinkConfig config = SpanLinkConfig.builder()
                    .linkId(linkId)
                    .sourceSpanName(request.getSourceSpanName())
                    .sourceAttributeKey(request.getSourceAttributeKey())
                    .targetSpanName(request.getTargetSpanName())
                    .targetAttributeKey(request.getTargetAttributeKey())
                    .linkType(request.getLinkType() != null ?
                            SpanLinkConfig.LinkType.valueOf(request.getLinkType()) :
                            SpanLinkConfig.LinkType.PARENT_TO_CHILD)
                    .enabled(true)
                    .build();

            // Redis에 저장
            spanLinkService.saveLink(config);

            // ByteBuddy Advice 적용
            String className = extractClassName(config.getSourceSpanName());
            String methodName = extractMethodName(config.getSourceSpanName());

            String result = ByteBuddyAgent.createSpanLinkAdvice(className, methodName);

            return Map.of(
                    "success", true,
                    "linkId", linkId,
                    "message", "Span link created",
                    "bytebuddyResult", result
            );

        } catch (Exception e) {
            return Map.of(
                    "success", false,
                    "error", e.getMessage()
            );
        }
    }

    /**
     * 링크 목록 조회
     *
     * GET /api/spanlink/list
     */
    @GetMapping("/list")
    public Map<String, Object> listLinks() {
        List<SpanLinkConfig> links = spanLinkService.getAllLinks();
        return Map.of(
                "success", true,
                "links", links,
                "count", links.size()
        );
    }

    /**
     * 소스 스팬에 대한 링크 조회
     *
     * GET /api/spanlink/source/{spanName}
     */
    @GetMapping("/source/{spanName}")
    public Map<String, Object> getLinksForSource(@PathVariable String spanName) {
        List<SpanLinkConfig> links = spanLinkService.getLinksForSource(spanName);
        return Map.of(
                "success", true,
                "links", links,
                "count", links.size()
        );
    }

    /**
     * 링크 조회
     *
     * GET /api/spanlink/{linkId}
     */
    @GetMapping("/{linkId}")
    public Map<String, Object> getLink(@PathVariable String linkId) {
        SpanLinkConfig config = spanLinkService.getLink(linkId);
        if (config == null) {
            return Map.of(
                    "success", false,
                    "error", "Link not found: " + linkId
            );
        }
        return Map.of(
                "success", true,
                "link", config
        );
    }

    /**
     * 링크 삭제
     *
     * DELETE /api/spanlink/{linkId}
     */
    @DeleteMapping("/{linkId}")
    public Map<String, Object> deleteLink(@PathVariable String linkId) {
        spanLinkService.deleteLink(linkId);
        return Map.of(
                "success", true,
                "message", "Link deleted: " + linkId
        );
    }

    /**
     * 링크 활성화/비활성화
     *
     * PUT /api/spanlink/{linkId}/enabled
     * Body: {"enabled": true}
     */
    @PutMapping("/{linkId}/enabled")
    public Map<String, Object> setEnabled(
            @PathVariable String linkId,
            @RequestBody Map<String, Boolean> request
    ) {
        SpanLinkConfig config = spanLinkService.getLink(linkId);
        if (config == null) {
            return Map.of(
                    "success", false,
                    "error", "Link not found: " + linkId
            );
        }

        Boolean enabled = request.get("enabled");
        if (enabled != null) {
            config.setEnabled(enabled);
            spanLinkService.saveLink(config);
        }

        return Map.of(
                "success", true,
                "linkId", linkId,
                "enabled", config.isEnabled()
        );
    }

    // ============================================================
    // Helper Methods
    // ============================================================

    private String generateLinkId(CreateLinkRequest request) {
        String base = request.getSourceSpanName() + "-" + request.getTargetSpanName();
        base = base.replaceAll("[^a-zA-Z0-9]", "-");
        return base.toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String extractClassName(String spanName) {
        // spanName 형식: "com.example.Service.method" 또는 "/method"
        if (spanName.contains(".")) {
            int lastDot = spanName.lastIndexOf('.');
            if (lastDot > 0) {
                return spanName.substring(0, lastDot);
            }
        }
        return "Unknown"; // fallback
    }

    private String extractMethodName(String spanName) {
        // spanName 형식: "com.example.Service.method" 또는 "/method"
        if (spanName.contains(".")) {
            int lastDot = spanName.lastIndexOf('.');
            return spanName.substring(lastDot + 1);
        } else if (spanName.startsWith("/")) {
            return spanName.substring(1);
        }
        return spanName; // fallback
    }

    // ============================================================
    // Request DTOs
    // ============================================================

    public static class CreateLinkRequest {
        private String sourceSpanName;
        private String sourceAttributeKey;
        private String targetSpanName;
        private String targetAttributeKey;
        private String linkType;

        public String getSourceSpanName() { return sourceSpanName; }
        public void setSourceSpanName(String sourceSpanName) { this.sourceSpanName = sourceSpanName; }

        public String getSourceAttributeKey() { return sourceAttributeKey; }
        public void setSourceAttributeKey(String sourceAttributeKey) { this.sourceAttributeKey = sourceAttributeKey; }

        public String getTargetSpanName() { return targetSpanName; }
        public void setTargetSpanName(String targetSpanName) { this.targetSpanName = targetSpanName; }

        public String getTargetAttributeKey() { return targetAttributeKey; }
        public void setTargetAttributeKey(String targetAttributeKey) { this.targetAttributeKey = targetAttributeKey; }

        public String getLinkType() { return linkType; }
        public void setLinkType(String linkType) { this.linkType = linkType; }
    }
}
