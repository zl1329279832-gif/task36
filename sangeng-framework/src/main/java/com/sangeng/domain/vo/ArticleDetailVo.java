package com.sangeng.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ArticleDetailVo {

    private Long id;
    //标题
    private String title;
    //文章摘要
    private String summary;
    //所属分类id
    private Long categoryId;
    //所属分类名
    private String categoryName;
    //缩略图
    private String thumbnail;

    //文章内容
    private String content;
    //访问量
    private Long viewCount;

    private Date createTime;

    //状态（0草稿，1待审核，2定时发布，3已发布，4已撤回，5违规下架，6灰度可见，7重新发布，8已归档）
    private String status;
    //定时发布时间
    private Date publishTime;
    //驳回原因
    private String rejectReason;
    //违规原因
    private String violationReason;
    //灰度受众
    private String grayAudience;
    //归档时间
    private Date archivedTime;
    //灰度发布时间
    private Date grayPublishTime;
    //重新发布次数
    private Integer republishCount;

}
