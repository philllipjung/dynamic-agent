package com.javaagent.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaagent.commons.SpanLinkConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Span Link Service - Redis에 링크 설정 저장
 *
 * agent-server에서 REST API를 통해 링크 설정 관리
 */
@Service
public class SpanLinkService {

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final String KEY_PREFIX = "spanLink:";
    private static final String INDEX_PREFIX = "spanLinkIndex:source:";

    /**
     * 링크 설정 저장
     *
     * @param config 링크 설정
     */
    public void saveLink(SpanLinkConfig config) {
        try {
            String key = KEY_PREFIX + config.getLinkId();
            String jsonValue = objectMapper.writeValueAsString(config);
            redisTemplate.opsForValue().set(key, jsonValue);

            // 인덱스용: sourceSpanName → linkId 매핑
            String indexKey = INDEX_PREFIX + config.getSourceSpanName();
            redisTemplate.opsForSet().add(indexKey, config.getLinkId());

            System.out.println("[SpanLinkService] Saved link: " + config.getLinkId());

        } catch (Exception e) {
            throw new RuntimeException("Failed to save link config", e);
        }
    }

    /**
     * 링크 설정 조회
     *
     * @param linkId 링크 ID
     * @return 링크 설정 or null
     */
    public SpanLinkConfig getLink(String linkId) {
        try {
            String key = KEY_PREFIX + linkId;
            String jsonValue = redisTemplate.opsForValue().get(key);
            if (jsonValue != null) {
                return objectMapper.readValue(jsonValue, SpanLinkConfig.class);
            }
            return null;
        } catch (Exception e) {
            System.err.println("[SpanLinkService] Error getting link: " + e.getMessage());
            return null;
        }
    }

    /**
     * 소스 스팬에 대한 모든 링크 조회
     *
     * @param sourceSpanName 소스 스팬명
     * @return 링크 설정 목록
     */
    public List<SpanLinkConfig> getLinksForSource(String sourceSpanName) {
        try {
            String indexKey = INDEX_PREFIX + sourceSpanName;
            Set<String> linkIds = redisTemplate.opsForSet().members(indexKey);

            if (linkIds == null || linkIds.isEmpty()) {
                return Collections.emptyList();
            }

            List<SpanLinkConfig> configs = new ArrayList<>();
            for (String linkId : linkIds) {
                SpanLinkConfig config = getLink(linkId);
                if (config != null && config.isEnabled()) {
                    configs.add(config);
                }
            }

            return configs;

        } catch (Exception e) {
            System.err.println("[SpanLinkService] Error: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 모든 링크 조회
     *
     * @return 모든 링크 설정 목록
     */
    public List<SpanLinkConfig> getAllLinks() {
        try {
            Set<String> keys = redisTemplate.keys(KEY_PREFIX + "*");
            if (keys == null || keys.isEmpty()) {
                return Collections.emptyList();
            }

            List<SpanLinkConfig> configs = new ArrayList<>();
            for (String key : keys) {
                String jsonValue = redisTemplate.opsForValue().get(key);
                if (jsonValue != null) {
                    configs.add(objectMapper.readValue(jsonValue, SpanLinkConfig.class));
                }
            }

            return configs;

        } catch (Exception e) {
            System.err.println("[SpanLinkService] Error: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 링크 삭제
     *
     * @param linkId 링크 ID
     */
    public void deleteLink(String linkId) {
        try {
            SpanLinkConfig config = getLink(linkId);
            if (config == null) return;

            // 설정 삭제
            String key = KEY_PREFIX + linkId;
            redisTemplate.delete(key);

            // 인덱스에서도 제거
            String indexKey = INDEX_PREFIX + config.getSourceSpanName();
            redisTemplate.opsForSet().remove(indexKey, linkId);

            System.out.println("[SpanLinkService] Deleted link: " + linkId);

        } catch (Exception e) {
            throw new RuntimeException("Failed to delete link", e);
        }
    }
}
