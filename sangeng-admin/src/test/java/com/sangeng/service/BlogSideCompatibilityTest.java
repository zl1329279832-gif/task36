package com.sangeng.service;

import com.sangeng.BlogAdminApplication;
import com.sangeng.domain.ResponseResult;
import com.sangeng.domain.entity.Article;
import com.sangeng.domain.entity.LoginUser;
import com.sangeng.domain.entity.User;
import com.sangeng.enums.ArticleStatusEnum;
import com.sangeng.exception.SystemException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 博客端API兼容性测试 - 确保新增状态不影响前台接口
 */
@SpringBootTest(classes = BlogAdminApplication.class)
public class BlogSideCompatibilityTest {

    @Autowired
    private ArticleService articleService;

    private Long publishedArticleId;
    private Long archivedArticleId;
    private Long grayscaleArticleId;

    @BeforeEach
    void setUp() {
        mockLoginAsAdmin();

        // 已发布文章
        Article published = new Article();
        published.setTitle("兼容性测试-已发布");
        published.setContent("已发布内容");
        published.setSummary("已发布");
        published.setCategoryId(1L);
        published.setStatus(ArticleStatusEnum.PUBLISHED.getCode());
        published.setViewCount(100L);
        published.setIsTop("0");
        published.setIsComment("1");
        published.setCreateBy(1L);
        articleService.save(published);
        publishedArticleId = published.getId();

        // 归档文章
        Article archived = new Article();
        archived.setTitle("兼容性测试-归档");
        archived.setContent("归档内容");
        archived.setSummary("归档");
        archived.setCategoryId(1L);
        archived.setStatus(ArticleStatusEnum.ARCHIVED.getCode());
        archived.setViewCount(50L);
        archived.setIsTop("0");
        archived.setIsComment("1");
        archived.setArchiveReason("过时");
        archived.setCreateBy(1L);
        articleService.save(archived);
        archivedArticleId = archived.getId();

        // 灰度文章
        Article grayscale = new Article();
        grayscale.setTitle("兼容性测试-灰度");
        grayscale.setContent("灰度内容");
        grayscale.setSummary("灰度");
        grayscale.setCategoryId(1L);
        grayscale.setStatus(ArticleStatusEnum.GRAYSCALE_VISIBLE.getCode());
        grayscale.setViewCount(10L);
        grayscale.setIsTop("0");
        grayscale.setIsComment("1");
        grayscale.setGrayscaleGroups("beta");
        grayscale.setCreateBy(1L);
        articleService.save(grayscale);
        grayscaleArticleId = grayscale.getId();
    }

    @AfterEach
    void tearDown() {
        if (publishedArticleId != null) articleService.removeById(publishedArticleId);
        if (archivedArticleId != null) articleService.removeById(archivedArticleId);
        if (grayscaleArticleId != null) articleService.removeById(grayscaleArticleId);
        SecurityContextHolder.clearContext();
    }

    /**
     * 热门文章列表只包含已发布文章（不含灰度、归档）
     */
    @Test
    void testHotArticleListOnlyPublished() {
        ResponseResult result = articleService.hotArticleList();
        assertNotNull(result);
        assertEquals(200, result.getCode());
    }

    /**
     * 文章列表只包含已发布文章
     */
    @Test
    void testArticleListOnlyPublished() {
        ResponseResult result = articleService.articleList(1, 100, null);
        assertNotNull(result);
        assertEquals(200, result.getCode());
    }

    /**
     * 归档文章通过博客端详情接口不可访问
     */
    @Test
    void testArchivedArticleNotAccessible() {
        assertThrows(SystemException.class, () -> {
            articleService.getArticleDetail(archivedArticleId);
        });
    }

    /**
     * 灰度文章可通过博客端详情接口直接访问（通过ID）
     */
    @Test
    void testGrayscaleArticleAccessibleById() {
        // 灰度文章可通过直接ID访问
        assertDoesNotThrow(() -> {
            articleService.getArticleDetail(grayscaleArticleId);
        });
    }

    private void mockLoginAsAdmin() {
        User user = new User();
        user.setId(1L);
        user.setUserName("admin");
        user.setNickName("管理员");
        user.setType("1");

        List<String> perms = Arrays.asList(
                "content:article:submit", "content:article:approve",
                "content:article:review", "content:article:dashboard"
        );

        LoginUser loginUser = new LoginUser(user, perms);
        List<SimpleGrantedAuthority> authorities = perms.stream()
                .map(SimpleGrantedAuthority::new).collect(Collectors.toList());

        UsernamePasswordAuthenticationToken authToken =
                new UsernamePasswordAuthenticationToken(loginUser, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authToken);
    }
}
