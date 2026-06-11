package com.sangeng.utils;

import java.net.HttpURLConnection;
import java.net.URL;
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

    /** 连接超时（毫秒） */
    private static final int CONNECT_TIMEOUT = 3000;
    /** 读取超时（毫秒） */
    private static final int READ_TIMEOUT = 5000;

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

    /**
     * 校验OSS附件引用是否有效：格式合法 + HTTP可达性验证。
     * <p>
     * 在文章发布前调用，确保内容和缩略图中的图片引用仍然可用。
     * 如果OSS对象被删除或URL失效，该方法返回false，调用方应阻止发布并记录审计日志。
     *
     * @param url 待校验的OSS URL
     * @return true=引用有效，false=引用失效
     */
    public static boolean verifyOssReference(String url) {
        // 1. 基础格式校验
        if (!isValidOssUrl(url)) {
            return false;
        }

        // 2. HTTP HEAD 请求验证对象是否存在
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("HEAD");
            connection.setConnectTimeout(CONNECT_TIMEOUT);
            connection.setReadTimeout(READ_TIMEOUT);
            connection.setInstanceFollowRedirects(true);

            int responseCode = connection.getResponseCode();
            connection.disconnect();

            // 2xx 成功 或 3xx 重定向均视为有效
            return responseCode >= 200 && responseCode < 400;
        } catch (Exception e) {
            // 网络不可达、DNS解析失败、超时等均视为引用失效
            return false;
        }
    }
}
