package com.berry.oss.module.vo;

import lombok.Data;

/**
 * Created with IntelliJ IDEA.
 *
 * @author Berry_Cooper.
 * @date 2019/9/2 12:50
 * fileName：ObjectInfoVo
 * Use：
 */
@Data
public class ObjectInfoVo {

    /**
     * 主键id
     */
    private String id;

    /**
     * 文件名
     */
    private String fileName;

    /**
     * 文件路径
     */
    private String filePath;

    /**
     * 访问url， 不带签名
     */
    private String url;

    /**
     * 读写权限
     */
    private String acl;

    /**
     * 文件大小
     */
    private Long size;

    /**
     * 格式化文件大小
     */
    private String formattedSize;

    /**
     * 上传类型，false-普通上传，true-极速上传
     */
    private Boolean uploadType = true;

    /**
     * 已覆盖旧文件
     */
    private Boolean replace;

    /**
     * 成功与否, 默认 成功=true
     */
    private Boolean success = true;
}
