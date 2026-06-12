package com.sangeng.enums;

public enum AppHttpCodeEnum {
    // 成功
    SUCCESS(200,"操作成功"),
    // 登录
    NEED_LOGIN(401,"需要登录后操作"),
    NO_OPERATOR_AUTH(403,"无权限操作"),
    SYSTEM_ERROR(500,"出现错误"),
    USERNAME_EXIST(501,"用户名已存在"),
     PHONENUMBER_EXIST(502,"手机号已存在"), EMAIL_EXIST(503, "邮箱已存在"),
    REQUIRE_USERNAME(504, "必需填写用户名"),
    CONTENT_NOT_NULL(506, "评论内容不能为空"),
    FILE_TYPE_ERROR(507, "文件类型错误，请上传png文件"),
    USERNAME_NOT_NULL(508, "用户名不能为空"),
    NICKNAME_NOT_NULL(509, "昵称不能为空"),
    PASSWORD_NOT_NULL(510, "密码不能为空"),
    EMAIL_NOT_NULL(511, "邮箱不能为空"),
    NICKNAME_EXIST(512, "昵称已存在"),
    LOGIN_ERROR(505,"用户名或密码错误"),
    ARTICLE_NOT_FOUND(513, "文章不存在"),
    ARTICLE_STATUS_INVALID(514, "当前文章状态不允许此操作"),
    REVIEW_REASON_REQUIRED(515, "审核驳回必须填写原因"),
    VIOLATION_REASON_REQUIRED(516, "违规下架必须填写原因"),
    COMMENT_FROZEN(517, "该文章已违规下架，评论功能已冻结"),
    OSS_REFERENCE_INVALID(518, "文章附件引用失效，无法发布"),
    ARCHIVE_REASON_REQUIRED(519, "归档操作必须填写原因"),
    GRAY_AUDIENCE_REQUIRED(520, "灰度发布必须指定目标受众");
    int code;
    String msg;

    AppHttpCodeEnum(int code, String errorMessage){
        this.code = code;
        this.msg = errorMessage;
    }

    public int getCode() {
        return code;
    }

    public String getMsg() {
        return msg;
    }
}
