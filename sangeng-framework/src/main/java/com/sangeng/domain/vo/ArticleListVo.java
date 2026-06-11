package com.sangeng.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ArticleListVo {

    private Long id;
    //标题
    private String title;
    //文章摘要
    private String summary;
    //所属分类名
    private String categoryName;
    //缩略图
    private String thumbnail;


    //访问量
    private Long viewCount;

    private Date createTime;

    //状态（0草稿，1待审核，2定时发布，3已发布，4已撤回，5违规下架）
    private String status;
    //定时发布时间
    private Date publishTime;
    //驳回原因
    private String rejectReason;
    //违规原因
    private String violationReason;

}
