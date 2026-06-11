package com.sangeng.constants;

public class SystemConstants
{
    /**
     *  文章是草稿
     */
    public static final int ARTICLE_STATUS_DRAFT = 1;
    /**
     *  文章是正常分布状态
     */
    public static final int ARTICLE_STATUS_NORMAL = 0;

    /**
     * 文章状态 - 字符串常量（用于工作流状态机）
     */
    public static final String ARTICLE_STATUS_PUBLISHED = "0";
    public static final String ARTICLE_STATUS_DRAFT_STR = "1";
    public static final String ARTICLE_STATUS_PENDING_REVIEW = "2";
    public static final String ARTICLE_STATUS_SCHEDULED = "3";
    public static final String ARTICLE_STATUS_WITHDRAWN = "4";
    public static final String ARTICLE_STATUS_VIOLATION_OFFLINE = "5";

    /**
     * 评论开关
     */
    public static final String COMMENT_ALLOWED = "1";
    public static final String COMMENT_DISALLOWED = "0";

    /**
     * Redis 缓存键
     */
    public static final String ARTICLE_VIEW_COUNT_KEY = "article:viewCount";

    public static final String  STATUS_NORMAL = "0";
    /**
     * 友链状态为审核通过
     */
    public static final String  LINK_STATUS_NORMAL = "0";
    /**
     * 评论类型为：文章评论
     */
    public static final String ARTICLE_COMMENT = "0";
    /**
     * 评论类型为：友联评论
     */
    public static final String LINK_COMMENT = "1";
    public static final String MENU = "C";
    public static final String BUTTON = "F";
    /** 正常状态 */
    public static final String NORMAL = "0";
    public static final String ADMAIN = "1";
}