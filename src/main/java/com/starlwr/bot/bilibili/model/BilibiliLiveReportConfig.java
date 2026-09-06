package com.starlwr.bot.bilibili.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;

/**
 * 直播报告配置
 */
@Getter
@Setter
@NoArgsConstructor
@ToString
public class BilibiliLiveReportConfig {
    /**
     * 各模块展示顺序，基础信息模块不可调整顺序
     */
    private List<String> sequence = new ArrayList<>(List.of(
            "changeInfo",
            "danmuAnalysis",
            "boxAnalysis",
            "giftAnalysis",
            "superChatAnalysis",
            "guardAnalysis",
            "enterRoomAnalysis",
            "likeAnalysis",
            "shareAnalysis"
    ));

    // ================ 基础信息 ================

    /**
     * 是否启用基础信息模块（UID、昵称、房间号、头像、直播分区、直播标题、直播时长）
     */
    private boolean enableBasicInfo;

    /**
     * 是否展示直播分区
     */
    private boolean showLiveArea;

    /**
     * 是否展示直播标题
     */
    private boolean showLiveTitle;

    /**
     * 是否展示直播时长
     */
    private boolean showLiveTime;

    // ================ 数据变动 ================

    /**
     * 是否启用本场直播数据变动模块（粉丝、粉丝团、大航海）
     */
    private boolean enableChangeInfo;

    /**
     * 是否展示本场直播粉丝变动
     */
    private boolean showFansChange;

    /**
     * 是否展示本场直播粉丝团（粉丝勋章数）变动
     */
    private boolean showFansMedalChange;

    /**
     * 是否展示本场直播大航海变动
     */
    private boolean showGuardChange;

    // ================ 弹幕分析 ================

    /**
     * 是否启用弹幕分析模块
     */
    private boolean enableDanmuAnalysis;

    /**
     * 是否展示本场直播收到弹幕数、发送弹幕人数
     */
    private boolean showDanmuDetails;

    /**
     * 展示本场直播弹幕排行榜的前多少名，0 为不展示
     */
    private int danmuRankingLimit;

    /**
     * 是否展示本场直播的弹幕累计曲线图
     */
    private boolean showDanmuGrowthChart;

    /**
     * 是否展示本场直播的弹幕互动曲线图
     */
    private boolean showDanmuInteractionChart;

    /**
     * 是否展示本场直播的弹幕类型分布图
     */
    private boolean showDanmuTypeDistributionChart;

    /**
     * 是否展示本场直播的发送弹幕观众分布图
     */
    private boolean showDanmuSenderDistributionChart;

    /**
     * 是否展示本场直播弹幕词云
     */
    private boolean showDanmuWordCloud;

    // ================ 盲盒分析 ================

    /**
     * 是否启用盲盒分析模块
     */
    private boolean enableBoxAnalysis;

    /**
     * 是否展示本场直播收到盲盒数、送出盲盒人数
     */
    private boolean showBoxDetails;

    /**
     * 是否展示本场直播盲盒盈亏
     */
    private boolean showBoxProfitDetails;

    /**
     * 展示本场直播盲盒数量排行榜的前多少名，0 为不展示
     */
    private int boxRankingLimit;

    /**
     * 展示本场直播盲盒盈亏排行榜的前多少名，0 为不展示
     */
    private int boxProfitRankingLimit;

    /**
     * 是否展示本场直播的盲盒数量累计曲线图
     */
    private boolean showBoxGrowthChart;

    /**
     * 是否展示本场直播的盲盒数量互动曲线图
     */
    private boolean showBoxInteractionChart;

    /**
     * 是否展示本场直播的盲盒盈亏累计曲线图
     */
    private boolean showBoxProfitGrowthChart;

    /**
     * 是否展示本场直播的盲盒盈亏互动曲线图
     */
    private boolean showBoxProfitInteractionChart;

    /**
     * 是否展示本场直播的盲盒盈亏分布图
     */
    private boolean showBoxProfitDistributionChart;

    /**
     * 是否展示本场直播的盲盒爆出礼物分布图
     */
    private boolean showBoxGiftDistributionChart;

    // ================ 礼物分析 ================

    /**
     * 是否启用礼物分析模块
     */
    private boolean enableGiftAnalysis;

    /**
     * 是否展示本场直播礼物收益、送礼物人数
     */
    private boolean showGiftDetails;

    /**
     * 展示本场直播礼物排行榜的前多少名，0 为不展示
     */
    private int giftRankingLimit;

    /**
     * 是否展示本场直播的礼物累计曲线图
     */
    private boolean showGiftGrowthChart;

    /**
     * 是否展示本场直播的礼物互动曲线图
     */
    private boolean showGiftInteractionChart;

    /**
     * 是否展示本场直播的礼物类型分布图
     */
    private boolean showGiftTypeDistributionChart;

    // ================ SC（醒目留言）分析 ================

    /**
     * 是否启用 SC（醒目留言）分析模块
     */
    private boolean enableSuperChatAnalysis;

    /**
     * 是否展示本场直播 SC（醒目留言）收益、发送 SC（醒目留言）人数
     */
    private boolean showSuperChatDetails;

    /**
     * 展示本场直播 SC（醒目留言）排行榜的前多少名，0 为不展示
     */
    private int superChatRankingLimit;

    /**
     * 是否展示本场直播的 SC（醒目留言）累计曲线图
     */
    private boolean showSuperChatGrowthChart;

    /**
     * 是否展示本场直播的 SC（醒目留言）互动曲线图
     */
    private boolean showSuperChatInteractionChart;

    // ================ 大航海分析 ================

    /**
     * 是否启用大航海分析模块
     */
    private boolean enableGuardAnalysis;

    /**
     * 是否展示本场直播开通大航海数
     */
    private boolean showGuardDetails;

    /**
     * 是否展示本场直播开通大航海观众列表
     */
    private boolean showGuardList;

    // ================ 进房分析 ================

    /**
     * 是否启用进房分析模块
     */
    private boolean enableEnterRoomAnalysis;

    /**
     * 是否展示本场直播进房次数、进房人数
     */
    private boolean showEnterRoomDetails;

    /**
     * 是否展示本场直播的进房累计曲线图
     */
    private boolean showEnterRoomGrowthChart;

    /**
     * 是否展示本场直播的进房互动曲线图
     */
    private boolean showEnterRoomInteractionChart;

    // ================ 点赞分析 ================

    /**
     * 是否启用点赞分析模块
     */
    private boolean enableLikeAnalysis;

    /**
     * 是否展示本场直播点赞次数、点赞人数
     */
    private boolean showLikeDetails;

    /**
     * 展示本场直播点赞排行榜的前多少名，0 为不展示
     */
    private int likeRankingLimit;

    /**
     * 是否展示本场直播的点赞累计曲线图
     */
    private boolean showLikeGrowthChart;

    /**
     * 是否展示本场直播的点赞互动曲线图
     */
    private boolean showLikeInteractionChart;

    // ================ 分享分析 ================

    /**
     * 是否启用分享分析模块
     */
    private boolean enableShareAnalysis;

    /**
     * 是否展示本场直播分享次数、分享人数
     */
    private boolean showShareDetails;

    /**
     * 是否展示本场直播的分享累计曲线图
     */
    private boolean showShareGrowthChart;

    /**
     * 是否展示本场直播的分享互动曲线图
     */
    private boolean showShareInteractionChart;

    public List<String> getSequence() { return sequence; }
    public void setSequence(List<String> value) { sequence = value; }
    public boolean isEnableBasicInfo() { return enableBasicInfo; }
    public void setEnableBasicInfo(boolean value) { enableBasicInfo = value; }
    public boolean isShowLiveTime() { return showLiveTime; }
    public void setShowLiveTime(boolean value) { showLiveTime = value; }
    public boolean isEnableChangeInfo() { return enableChangeInfo; }
    public void setEnableChangeInfo(boolean value) { enableChangeInfo = value; }
    public boolean isShowFansChange() { return showFansChange; }
    public void setShowFansChange(boolean value) { showFansChange = value; }
    public boolean isShowFansMedalChange() { return showFansMedalChange; }
    public void setShowFansMedalChange(boolean value) { showFansMedalChange = value; }
    public boolean isShowGuardChange() { return showGuardChange; }
    public void setShowGuardChange(boolean value) { showGuardChange = value; }
    public boolean isEnableDanmuAnalysis() { return enableDanmuAnalysis; }
    public void setEnableDanmuAnalysis(boolean value) { enableDanmuAnalysis = value; }
    public boolean isShowDanmuDetails() { return showDanmuDetails; }
    public void setShowDanmuDetails(boolean value) { showDanmuDetails = value; }
    public int getDanmuRankingLimit() { return danmuRankingLimit; }
    public void setDanmuRankingLimit(int value) { danmuRankingLimit = value; }
    public boolean isShowDanmuGrowthChart() { return showDanmuGrowthChart; }
    public void setShowDanmuGrowthChart(boolean value) { showDanmuGrowthChart = value; }
    public boolean isShowDanmuInteractionChart() { return showDanmuInteractionChart; }
    public void setShowDanmuInteractionChart(boolean value) { showDanmuInteractionChart = value; }
    public boolean isShowDanmuTypeDistributionChart() { return showDanmuTypeDistributionChart; }
    public void setShowDanmuTypeDistributionChart(boolean value) { showDanmuTypeDistributionChart = value; }
    public boolean isShowDanmuSenderDistributionChart() { return showDanmuSenderDistributionChart; }
    public void setShowDanmuSenderDistributionChart(boolean value) { showDanmuSenderDistributionChart = value; }
    public boolean isShowDanmuWordCloud() { return showDanmuWordCloud; }
    public void setShowDanmuWordCloud(boolean value) { showDanmuWordCloud = value; }
    public boolean isEnableBoxAnalysis() { return enableBoxAnalysis; }
    public void setEnableBoxAnalysis(boolean value) { enableBoxAnalysis = value; }
    public boolean isShowBoxDetails() { return showBoxDetails; }
    public void setShowBoxDetails(boolean value) { showBoxDetails = value; }
    public boolean isShowBoxProfitDetails() { return showBoxProfitDetails; }
    public void setShowBoxProfitDetails(boolean value) { showBoxProfitDetails = value; }
    public int getBoxRankingLimit() { return boxRankingLimit; }
    public void setBoxRankingLimit(int value) { boxRankingLimit = value; }
    public int getBoxProfitRankingLimit() { return boxProfitRankingLimit; }
    public void setBoxProfitRankingLimit(int value) { boxProfitRankingLimit = value; }
    public boolean isShowBoxGrowthChart() { return showBoxGrowthChart; }
    public void setShowBoxGrowthChart(boolean value) { showBoxGrowthChart = value; }
    public boolean isShowBoxInteractionChart() { return showBoxInteractionChart; }
    public void setShowBoxInteractionChart(boolean value) { showBoxInteractionChart = value; }
    public boolean isShowBoxProfitGrowthChart() { return showBoxProfitGrowthChart; }
    public void setShowBoxProfitGrowthChart(boolean value) { showBoxProfitGrowthChart = value; }
    public boolean isShowBoxProfitInteractionChart() { return showBoxProfitInteractionChart; }
    public void setShowBoxProfitInteractionChart(boolean value) { showBoxProfitInteractionChart = value; }
    public boolean isShowBoxProfitDistributionChart() { return showBoxProfitDistributionChart; }
    public void setShowBoxProfitDistributionChart(boolean value) { showBoxProfitDistributionChart = value; }
    public boolean isShowBoxGiftDistributionChart() { return showBoxGiftDistributionChart; }
    public void setShowBoxGiftDistributionChart(boolean value) { showBoxGiftDistributionChart = value; }
    public boolean isEnableGiftAnalysis() { return enableGiftAnalysis; }
    public void setEnableGiftAnalysis(boolean value) { enableGiftAnalysis = value; }
    public boolean isShowGiftDetails() { return showGiftDetails; }
    public void setShowGiftDetails(boolean value) { showGiftDetails = value; }
    public int getGiftRankingLimit() { return giftRankingLimit; }
    public void setGiftRankingLimit(int value) { giftRankingLimit = value; }
    public boolean isShowGiftGrowthChart() { return showGiftGrowthChart; }
    public void setShowGiftGrowthChart(boolean value) { showGiftGrowthChart = value; }
    public boolean isShowGiftInteractionChart() { return showGiftInteractionChart; }
    public void setShowGiftInteractionChart(boolean value) { showGiftInteractionChart = value; }
    public boolean isShowGiftTypeDistributionChart() { return showGiftTypeDistributionChart; }
    public void setShowGiftTypeDistributionChart(boolean value) { showGiftTypeDistributionChart = value; }
    public boolean isEnableSuperChatAnalysis() { return enableSuperChatAnalysis; }
    public void setEnableSuperChatAnalysis(boolean value) { enableSuperChatAnalysis = value; }
    public boolean isShowSuperChatDetails() { return showSuperChatDetails; }
    public void setShowSuperChatDetails(boolean value) { showSuperChatDetails = value; }
    public int getSuperChatRankingLimit() { return superChatRankingLimit; }
    public void setSuperChatRankingLimit(int value) { superChatRankingLimit = value; }
    public boolean isShowSuperChatGrowthChart() { return showSuperChatGrowthChart; }
    public void setShowSuperChatGrowthChart(boolean value) { showSuperChatGrowthChart = value; }
    public boolean isShowSuperChatInteractionChart() { return showSuperChatInteractionChart; }
    public void setShowSuperChatInteractionChart(boolean value) { showSuperChatInteractionChart = value; }
    public boolean isEnableGuardAnalysis() { return enableGuardAnalysis; }
    public void setEnableGuardAnalysis(boolean value) { enableGuardAnalysis = value; }
    public boolean isShowGuardDetails() { return showGuardDetails; }
    public void setShowGuardDetails(boolean value) { showGuardDetails = value; }
}
