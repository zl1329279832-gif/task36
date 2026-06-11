package com.sangeng.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OSS附件引用校验工具类
 */
public class OssValidationUtil {

    private static final Pattern IMG_PATTERN = Pattern.compile(
            "https?://[^\\s\\)\"'<>]+\\.(jpg|jpeg|png|gif|webp|bmp|svg)",
            Pattern.CASE_INSENSITIVE);

    /**
     * 从内容中提取所有图片URL
     */
    public static List<String> extractImageUrls(String content) {
        List<String> urls = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            return urls;
        }
        Matcher matcher = IMG_PATTERN.matcher(content);
        while (matcher.find()) {
            urls.add(matcher.group());
        }
        return urls;
    }

    /**
     * 校验OSS URL格式是否合法（基础校验：非空、以http开头）
     * 生产环境可对接OSS SDK进行对象存在性校验
     */
    public static boolean isValidOssUrl(String url) {
        return url != null && !url.trim().isEmpty() && url.startsWith("http");
    }
}
