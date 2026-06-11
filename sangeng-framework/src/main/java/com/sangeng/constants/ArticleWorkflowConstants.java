package com.sangeng.constants;

public class ArticleWorkflowConstants {
    // Permission keys
    public static final String PERM_ARTICLE_SUBMIT = "content:article:submit";
    public static final String PERM_ARTICLE_APPROVE = "content:article:approve";
    public static final String PERM_ARTICLE_REJECT = "content:article:reject";
    public static final String PERM_ARTICLE_WITHDRAW = "content:article:withdraw";
    public static final String PERM_ARTICLE_VIOLATION = "content:article:violation";
    public static final String PERM_ARTICLE_FORCE_PUBLISH = "content:article:forcePublish";

    // Cache keys
    public static final String CACHE_ARTICLE_DETAIL = "article:detail:";
    public static final String CACHE_HOME_ARTICLES = "article:homeList";
    public static final String CACHE_CATEGORY_LIST = "category:list";
    public static final String CACHE_VIEW_COUNT_KEY = "article:viewCount";
}
