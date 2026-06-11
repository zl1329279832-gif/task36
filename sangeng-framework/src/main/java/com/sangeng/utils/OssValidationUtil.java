package com.sangeng.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger log = LoggerFactory.getLogger(OssValidationUtil.class);

    private static final Pattern IMG_PATTERN = Pattern.compile(
            "https?://[^\\s\\)\"'<>]+\\.(jpg|jpeg|png|gif|webp|bmp|svg)",
            Pattern.CASE_INSENSITIVE);

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 5000;

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
     * 通过HTTP HEAD请求校验OSS资源是否可达
     * @return true 资源可达，false 资源不可达
     */
    public static boolean isOssUrlReachable(String url) {
        if (!isValidOssUrl(url)) {
            return false;
        }
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("HEAD");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            int responseCode = connection.getResponseCode();
            connection.disconnect();
            return responseCode >= 200 && responseCode < 400;
        } catch (Exception e) {
            log.warn("OSS资源可达性校验失败: url={}, error={}", url, e.getMessage());
            return false;
        }
    }

    /**
     * 校验文章中所有OSS附件引用是否有效，返回无效URL列表
     * @param content 文章内容
     * @param thumbnail 缩略图URL（可为null）
     * @return 无效的URL列表，空列表表示全部有效
     */
    public static List<String> findInvalidOssUrls(String content, String thumbnail) {
        List<String> invalidUrls = new ArrayList<>();
        List<String> imageUrls = extractImageUrls(content);
        if (thumbnail != null && !thumbnail.trim().isEmpty()) {
            imageUrls.add(thumbnail);
        }
        for (String url : imageUrls) {
            if (!isOssUrlReachable(url)) {
                invalidUrls.add(url);
            }
        }
        return invalidUrls;
    }
}
