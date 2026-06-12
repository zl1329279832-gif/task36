package com.sangeng.constants;

public class SystemConstants
{
    /**
     * @deprecated 使用 ArticleStatusEnum.DRAFT 代替
     */
    @Deprecated
    public static final int ARTICLE_STATUS_DRAFT = 1;
    /**
     * @deprecated 使用 ArticleStatusEnum.PUBLISHED 代替
     */
    @Deprecated
    public static final int ARTICLE_STATUS_NORMAL = 0;

    // ========== 文章生命周期状态 ==========
    /** 草稿 */
    public static final String ARTICLE_STATUS_DRAFT_STR = "0";
    /** 待审核 */
    public static final String ARTICLE_STATUS_PENDING_REVIEW = "1";
    /** 定时发布 */
    public static final String ARTICLE_STATUS_SCHEDULED = "2";
    /** 已发布 */
    public static final String ARTICLE_STATUS_PUBLISHED = "3";
    /** 已撤回 */
    public static final String ARTICLE_STATUS_WITHDRAWN = "4";
    /** 违规下架 */
    public static final String ARTICLE_STATUS_VIOLATION_OFFLINE = "5";
    /** 灰度可见 */
    public static final String ARTICLE_STATUS_GRAY_VISIBLE = "6";
    /** 重新发布 */
    public static final String ARTICLE_STATUS_REPUBLISH = "7";
    /** 已归档 */
    public static final String ARTICLE_STATUS_ARCHIVED = "8";


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