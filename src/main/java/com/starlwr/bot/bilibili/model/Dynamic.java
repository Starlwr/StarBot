package com.starlwr.bot.bilibili.model;

import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.annotation.JSONField;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 动态
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Dynamic {
    /**
     * 动态 ID
     */
    @JSONField(name = "id_str")
    private String id;

    /**
     * 动态类型
     */
    private String type;

    /**
     * 是否显示
     */
    private Boolean visible;

    /**
     * 基础信息
     */
    private JSONObject basic;

    /**
     * 动态主体信息
     */
    private JSONObject modules;

    /**
     * 源动态信息
     */
    @JSONField(name = "orig")
    private Dynamic origin;
}
