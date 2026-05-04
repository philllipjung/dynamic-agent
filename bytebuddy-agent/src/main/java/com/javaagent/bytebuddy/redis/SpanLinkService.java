package com.javaagent.bytebuddy.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaagent.commons.SpanLinkConfig;
import redis.clients.jedis.Jedis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Span Link Service - Redis에서 링크 설정 조회
 *
 * bytebuddy-agent에서 런타임에 링크 설정을 조회
 */
public class SpanLinkService {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final String KEY_PREFIX = "spanLink:";
    private static final String INDEX_PREFIX = "spanLinkIndex:source:";

    /**
     * 소스 스팬에 대한 모든 링크 설정 조회
     *
     * @param sourceSpanName 소스 스팬명
     * @return 링크 설정 목록
     */
    public static List<SpanLinkConfig> getLinksForSource(String sourceSpanName) {
        if (ParameterMappingService.jedisPool == null) {
            return Collections.emptyList();
        }

        try (Jedis jedis = ParameterMappingService.jedisPool.getResource()) {
            String indexKey = INDEX_PREFIX + sourceSpanName;
            Set<String> linkIds = jedis.smembers(indexKey);

            if (linkIds == null || linkIds.isEmpty()) {
                return Collections.emptyList();
            }

            List<SpanLinkConfig> configs = new ArrayList<>();
            for (String linkId : linkIds) {
                String key = KEY_PREFIX + linkId;
                String jsonValue = jedis.get(key);
                if (jsonValue != null) {
                    SpanLinkConfig config = objectMapper.readValue(jsonValue, SpanLinkConfig.class);
                    if (config.isEnabled()) {
                        configs.add(config);
                    }
                }
            }

            System.out.println("[SpanLinkService] Found " + configs.size() +
                    " link configs for: " + sourceSpanName);
            return configs;

        } catch (Exception e) {
            System.err.println("[SpanLinkService] Error getting links: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 링크 ID로 설정 조회
     *
     * @param linkId 링크 ID
     * @return 링크 설정 or null
     */
    public static SpanLinkConfig getLink(String linkId) {
        if (ParameterMappingService.jedisPool == null) {
            return null;
        }

        try (Jedis jedis = ParameterMappingService.jedisPool.getResource()) {
            String key = KEY_PREFIX + linkId;
            String jsonValue = jedis.get(key);
            if (jsonValue != null) {
                return objectMapper.readValue(jsonValue, SpanLinkConfig.class);
            }
            return null;
        } catch (Exception e) {
            System.err.println("[SpanLinkService] Error getting link: " + e.getMessage());
            return null;
        }
    }
}
