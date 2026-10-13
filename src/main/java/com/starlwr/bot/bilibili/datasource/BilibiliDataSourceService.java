package com.starlwr.bot.bilibili.datasource;

import com.starlwr.bot.bilibili.api.BilibiliApiService;
import com.starlwr.bot.bilibili.api.model.Room;
import com.starlwr.bot.bilibili.api.model.Up;
import com.starlwr.bot.core.event.model.LivePlatform;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.push.datasource.DataSourceService;
import com.starlwr.bot.core.push.model.PushUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Bilibili 数据源服务
 */
@Slf4j
@StarBotComponent
public class BilibiliDataSourceService implements DataSourceService {
    private final BilibiliApiService bilibili;

    @Autowired
    public BilibiliDataSourceService(BilibiliApiService bilibili) {
        this.bilibili = bilibili;
    }

    /**
     * 直播平台名称
     * @return 直播平台名称
     */
    @Override
    public String getPlatform() {
        return LivePlatform.BILIBILI.getName();
    }

    /**
     * 补全推送用户信息
     * @param user 推送用户
     */
    @Override
    public void completePushUser(PushUser user) {
        Up up = bilibili.getUpInfoByUid(user.getUid());
        user.setUname(up.getUname());
        user.setRoomId(up.getRoomId());
        user.setFace(up.getFace());
    }

    /**
     * 批量补全推送用户信息
     * @param users 推送用户列表
     */
    @Override
    public void completePushUsers(List<PushUser> users) {
        if (users.size() == 1) {
            completePushUser(users.get(0));
            return;
        }

        Map<Long, Room> rooms = bilibili.getLiveInfoByUids(users.stream().map(PushUser::getUid).collect(Collectors.toSet()));
        for (PushUser user: users) {
            Room room = rooms.get(user.getUid());
            if (room == null) {
                completePushUser(user);
            } else {
                user.setUname(room.getUname());
                user.setRoomId(room.getRoomId());
                user.setFace(room.getFace());
            }
        }
    }
}
