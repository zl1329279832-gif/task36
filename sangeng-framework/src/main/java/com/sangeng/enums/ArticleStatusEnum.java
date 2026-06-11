package com.sangeng.enums;

import java.util.*;

public enum ArticleStatusEnum {
    PUBLISHED("0", "已发布"),
    DRAFT("1", "草稿"),
    PENDING_REVIEW("2", "待审核"),
    SCHEDULED("3", "定时发布"),
    WITHDRAWN("4", "已撤回"),
    VIOLATION_OFFLINE("5", "违规下线");

    private final String code;
    private final String description;

    private static final Map<String, Set<String>> VALID_TRANSITIONS = new HashMap<>();

    static {
        // 草稿 -> 待审核
        VALID_TRANSITIONS.put("1", new HashSet<>(Collections.singletonList("2")));
        // 待审核 -> 已发布 / 定时发布 / 草稿(驳回)
        VALID_TRANSITIONS.put("2", new HashSet<>(Arrays.asList("0", "3", "1")));
        // 定时发布 -> 已发布(自动触发)
        VALID_TRANSITIONS.put("3", new HashSet<>(Collections.singletonList("0")));
        // 已发布 -> 已撤回 / 违规下线
        VALID_TRANSITIONS.put("0", new HashSet<>(Arrays.asList("4", "5")));
        // 已撤回 -> 待审核(重新提交)
        VALID_TRANSITIONS.put("4", new HashSet<>(Collections.singletonList("2")));
        // 违规下线 -> 待审核(重新提交)
        VALID_TRANSITIONS.put("5", new HashSet<>(Collections.singletonList("2")));
    }

    ArticleStatusEnum(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static boolean isValidTransition(String fromStatus, String toStatus) {
        Set<String> allowed = VALID_TRANSITIONS.get(fromStatus);
        return allowed != null && allowed.contains(toStatus);
    }

    public static ArticleStatusEnum fromCode(String code) {
        for (ArticleStatusEnum status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown article status code: " + code);
    }
}
