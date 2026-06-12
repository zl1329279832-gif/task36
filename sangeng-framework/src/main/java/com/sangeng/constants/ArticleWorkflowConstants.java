package com.sangeng.constants;

public class ArticleWorkflowConstants {
    // Permission keys
    public static final String PERM_ARTICLE_SUBMIT = "content:article:submit";
    public static final String PERM_ARTICLE_APPROVE = "content:article:approve";
    public static final String PERM_ARTICLE_REJECT = "content:article:reject";
    public static final String PERM_ARTICLE_WITHDRAW = "content:article:withdraw";
    public static final String PERM_ARTICLE_VIOLATION = "content:article:violation";
    public static final String PERM_ARTICLE_FORCE_PUBLISH = "content:article:forcePublish";
    public static final String PERM_ARTICLE_GRAYSCALE = "content:article:grayscale";
    public static final String PERM_ARTICLE_ARCHIVE = "content:article:archive";
    public static final String PERM_ARTICLE_REPUBLISH = "content:article:republish";
    public static final String PERM_ARTICLE_DASHBOARD = "content:article:dashboard";

    // Cache keys
    public static final String CACHE_ARTICLE_DETAIL = "article:detail:";
    public static final String CACHE_HOME_ARTICLES = "article:homeList";
    public static final String CACHE_CATEGORY_LIST = "category:list";
    public static final String CACHE_VIEW_COUNT_KEY = "article:viewCount";
    public static final String CACHE_VERSION_KEY = "article:cacheVersion";
    public static final String CACHE_REFRESH_COUNT_KEY = "article:cacheRefreshCount";
}
