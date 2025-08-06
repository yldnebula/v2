package com.example.v2.chat;

import java.util.Set;

/**
 * 我们自定义的ChatOptions DTO，模仿Spring AI。
 * @param functions 本次调用启用的函数名称集合。
 */
public record ChatOptions(Set<String> functions) {}