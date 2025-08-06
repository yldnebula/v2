package com.example.v2.chat;

import java.util.Map;

/**
 * HTTP请求头提供者接口。
 * 任何实现了此接口的Bean，都可以为对外的API调用提供动态生成的请求头。
 */
public interface HttpHeaderProvider {
    Map<String, String> getHeaders();
}
