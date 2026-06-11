package com.sangeng.enums;

public enum ArticleStatusEnum {
    DRAFT("0", "草稿"),
    PENDING_REVIEW("1", "待审核"),
    SCHEDULED("2", "定时发布"),
    PUBLISHED("3", "已发布"),
    WITHDRAWN("4", "已撤回"),
    VIOLATION_OFFLINE("5", "违规下架");

    private final String code;
    private final String desc;

    ArticleStatusEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public String getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    public static ArticleStatusEnum fromCode(String code) {
        for (ArticleStatusEnum status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Invalid article status: " + code);
    }
}
