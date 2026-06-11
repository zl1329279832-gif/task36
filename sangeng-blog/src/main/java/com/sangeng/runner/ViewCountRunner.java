package com.sangeng.runner;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sangeng.domain.entity.Article;
import com.sangeng.enums.ArticleStatusEnum;
import com.sangeng.mapper.ArticleMapper;
import com.sangeng.utils.RedisCache;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class ViewCountRunner implements CommandLineRunner {

    @Autowired
    private ArticleMapper articleMapper;

    @Autowired
    private RedisCache redisCache;

    @Override
    public void run(String... args) throws Exception {
        //查询已发布的博客信息  id  viewCount
        LambdaQueryWrapper<Article> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Article::getStatus, ArticleStatusEnum.PUBLISHED.getCode());
        List<Article> articles = articleMapper.selectList(queryWrapper);
        Map<String, Integer> viewCountMap = articles.stream()
                .collect(Collectors.toMap(article -> article.getId().toString(), article -> {
                    return article.getViewCount().intValue();//
                }));
        //存储到redis中
        redisCache.setCacheMap("article:viewCount",viewCountMap);
    }
}
