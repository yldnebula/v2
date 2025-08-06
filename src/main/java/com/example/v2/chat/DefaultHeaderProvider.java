package com.example.v2.chat;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * 默认的请求头提供者实现。
 * 在每次被调用时，都会生成一个唯一的追踪ID (X-Request-ID)。
 */
@Component
public class DefaultHeaderProvider implements HttpHeaderProvider {
    @Override
    public Map<String, String> getHeaders() {
        // 每次调用都生成一个新的UUID作为追踪ID
        return Map.of("X-Request-ID", UUID.randomUUID().toString());
    }
}
