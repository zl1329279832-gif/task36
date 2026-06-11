package com.sangeng.utils;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OssReferenceValidator {

    private static final Pattern OSS_URL_PATTERN =
            Pattern.compile("https?://[\\w.-]+\\.oss-cn-[\\w-]+\\.aliyuncs\\.com/[^\\s\"'<>)]+");

    public static List<String> extractOssUrls(String content) {
        List<String> urls = new ArrayList<>();
        if (content == null) {
            return urls;
        }
        Matcher matcher = OSS_URL_PATTERN.matcher(content);
        while (matcher.find()) {
            urls.add(matcher.group());
        }
        return urls;
    }

    public static List<String> findBrokenReferences(String content) {
        List<String> brokenUrls = new ArrayList<>();
        List<String> ossUrls = extractOssUrls(content);
        for (String ossUrl : ossUrls) {
            if (!isUrlAccessible(ossUrl)) {
                brokenUrls.add(ossUrl);
            }
        }
        return brokenUrls;
    }

    private static boolean isUrlAccessible(String urlStr) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("HEAD");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            int responseCode = connection.getResponseCode();
            connection.disconnect();
            return responseCode >= 200 && responseCode < 400;
        } catch (Exception e) {
            return false;
        }
    }
}
