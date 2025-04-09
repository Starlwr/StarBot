import time
from typing import Union, Optional

from graia.ariadne import Ariadne
from graia.ariadne.event.message import FriendMessage, GroupMessage
from graia.ariadne.message.chain import MessageChain
from graia.ariadne.message.element import Image, Source, At
from graia.ariadne.message.parser.twilight import Twilight, FullMatch, ElementMatch
from graia.ariadne.model import Friend, Group, Member
from graia.saya import Channel
from graia.saya.builtins.broadcast import ListenerSchema
from loguru import logger

from ....core.datasource import DataSource
from ....core.model import PushType
from ....painter.PicGenerator import PicGenerator, Color
from ....utils import config, redis
from ....utils.utils import timestamp_format, get_unames_and_faces_by_uids, mask_round, get_parallel_ranking, get_ratio

prefix = config.get("COMMAND_PREFIX")

channel = Channel.current()


@channel.use(
    ListenerSchema(
        listening_events=[FriendMessage, GroupMessage],
        inline_dispatchers=[Twilight(
            ElementMatch(At, optional=True),
            FullMatch(prefix),
            FullMatch("我的总数据")
        )],
    )
)
async def user_data_total(app: Ariadne, source: Source, sender: Union[Friend, Group], member: Optional[Member]):
    logger.info(f"{'群' if isinstance(sender, Group) else '好友'}[{sender.id}] 触发命令 : 我的总数据")

    if isinstance(sender, Group) and await redis.exists_disable_command("DenyUserDataTotal", sender.id):
        await app.send_message(sender, MessageChain("此命令已被禁用~"), quote=source)
        return

    datasource: DataSource = app.options["StarBotDataSource"]
    ups = datasource.get_ups_by_target(sender.id, PushType.Group if isinstance(sender, Group) else PushType.Friend)

    if not ups:
        if isinstance(sender, Group):
            await app.send_message(sender, MessageChain("本群未关联直播间~"), quote=source)
        else:
            await app.send_message(sender, MessageChain("此处未关联直播间~"), quote=source)
        return

    if isinstance(sender, Friend):
        qq = sender.id
    else:
        qq = member.id

    uid = await redis.get_bind_uid(qq)

    if not uid:
        await app.send_message(
            sender, MessageChain(f"请先使用\"{prefix}绑定 [UID]\"命令绑定B站UID后再查询~\n命令示例:{prefix}绑定 114514")
        )
        return

    uname, face = await get_unames_and_faces_by_uids([str(uid)])
    uname = uname[0]
    face = face[0]

    if not uname:
        await app.send_message(
            sender, MessageChain(f"当前绑定的UID不正确\n请重新使用\"{prefix}绑定 [UID]\"命令绑定B站UID后再查询~")
        )
        return

    for up in ups:
        if up.room_id is None:
            continue

        width = 1000
        height = 100000
        face_size = 100
        pic = PicGenerator(width, height)
        pic.set_pos(175, 80).draw_rounded_rectangle(0, 0, width, height, 35, Color.WHITE).copy_bottom(35)

        pic.draw_img_alpha(mask_round(face.resize((face_size, face_size)).convert("RGBA")), (50, 50))
        pic.draw_section(f"{uname} 的总数据").set_pos(50, 150 + pic.row_space)
        pic.draw_tip(f"数据自主播注册之日起开始累计")
        pic.draw_tip(f"查询时间: {timestamp_format(int(time.time()), '%Y/%m/%d %H:%M:%S')}")
        pic.draw_text("")

        user_danmu_count = await redis.get_user_danmu_all(up.room_id, uid)
        user_box_count = await redis.get_user_box_all(up.room_id, uid)
        user_box_profit = await redis.get_user_box_profit_all(up.room_id, uid)
        user_gift_profit = await redis.get_user_gift_all(up.room_id, uid)
        user_sc_profit = await redis.get_user_sc_all(up.room_id, uid)
        user_captain_count = await redis.get_user_captain_all(up.room_id, uid)
        user_commander_count = await redis.get_user_commander_all(up.room_id, uid)
        user_governor_count = await redis.get_user_governor_all(up.room_id, uid)

        if not any([user_danmu_count, user_box_count, user_gift_profit, user_sc_profit,
                    user_captain_count, user_commander_count, user_governor_count]):
            pic.draw_text_multiline(50, f"未查询到 {uname} 在 {up.uname} 房间的数据")
            pic.draw_text("请先在直播间中互动后再来查询")
        else:
            pic.draw_text_multiline(50, f"{uname} 在 {up.uname} 房间的总数据:")
            if user_danmu_count:
                pic.draw_text("")
                user_danmu_counts = [x[1] for x in await redis.range_user_danmu_all(up.room_id)]
                room_danmu_count = await redis.get_room_danmu_all(up.room_id)
                ratio = get_ratio(user_danmu_count, room_danmu_count)
                rank, total, diff = get_parallel_ranking(user_danmu_count, user_danmu_counts)
                pic.draw_text(
                    ["发送弹幕总数: ", str(user_danmu_count), " 条   排名: ", f"{rank}/{total}"],
                    [Color.BLACK, Color.LINK, Color.BLACK, Color.LINK]
                )
                pic.draw_text(["占据了弹幕总数的 ", ratio], [Color.BLACK, Color.LINK])
                if diff is not None:
                    pic.draw_text(["距离上一名还需: ", str(diff), " 条"], [Color.BLACK, Color.LINK, Color.BLACK])

            if user_box_count:
                pic.draw_text("")
                user_box_counts = [x[1] for x in await redis.range_user_box_all(up.room_id)]
                room_box_count = await redis.get_room_box_all(up.room_id)
                ratio = get_ratio(user_box_count, room_box_count)
                rank, total, diff = get_parallel_ranking(user_box_count, user_box_counts)
                pic.draw_text(
                    ["开启盲盒总数: ", str(user_box_count), " 个   排名: ", f"{rank}/{total}"],
                    [Color.BLACK, Color.LINK, Color.BLACK, Color.LINK]
                )
                pic.draw_text(["占据了盲盒总数的 ", ratio], [Color.BLACK, Color.LINK])
                if diff is not None:
                    pic.draw_text(["距离上一名还需: ", str(diff), " 个"], [Color.BLACK, Color.LINK, Color.BLACK])

                pic.draw_text("")
                user_box_profits = [x[1] for x in await redis.range_user_box_profit_all(up.room_id)]
                room_box_profit = await redis.get_room_box_profit_all(up.room_id)
                rank, total, diff = get_parallel_ranking(user_box_profit, user_box_profits)
                color = Color.RED if user_box_profit > 0 else (Color.GREEN if user_box_profit < 0 else Color.GRAY)
                room_color = Color.RED if room_box_profit > 0 else (Color.GREEN if room_box_profit < 0 else Color.GRAY)
                pic.draw_text(
                    ["盲盒总盈亏: ", str(user_box_profit), " 元   排名: ", f"{rank}/{total}"],
                    [Color.BLACK, color, Color.BLACK, Color.LINK]
                )
                pic.draw_text(["直播间盲盒总盈亏: ", str(room_box_profit), " 元"], [Color.BLACK, room_color, Color.BLACK])
                if diff is not None:
                    pic.draw_text(["距离上一名还需: ", str(diff), " 元"], [Color.BLACK, Color.LINK, Color.BLACK])

            if user_gift_profit:
                pic.draw_text("")
                user_gift_profits = [x[1] for x in await redis.range_user_gift_all(up.room_id)]
                room_gift_profit = await redis.get_room_gift_all(up.room_id)
                ratio = get_ratio(user_gift_profit, room_gift_profit)
                rank, total, diff = get_parallel_ranking(user_gift_profit, user_gift_profits)
                pic.draw_text(
                    ["送出礼物总价值: ", str(user_gift_profit), " 元   排名: ", f"{rank}/{total}"],
                    [Color.BLACK, Color.LINK, Color.BLACK, Color.LINK]
                )
                pic.draw_text(["占据了礼物总价值的 ", ratio], [Color.BLACK, Color.LINK])
                if diff is not None:
                    pic.draw_text(["距离上一名还需: ", str(diff), " 元"], [Color.BLACK, Color.LINK, Color.BLACK])

            if user_sc_profit:
                pic.draw_text("")
                user_sc_profits = [x[1] for x in await redis.range_user_sc_all(up.room_id)]
                room_sc_profit = await redis.get_room_sc_all(up.room_id)
                ratio = get_ratio(user_sc_profit, room_sc_profit)
                rank, total, diff = get_parallel_ranking(user_sc_profit, user_sc_profits)
                pic.draw_text(
                    ["发送 SC (醒目留言) 总价值: ", str(user_sc_profit), " 元   排名: ", f"{rank}/{total}"],
                    [Color.BLACK, Color.LINK, Color.BLACK, Color.LINK]
                )
                pic.draw_text(["占据了 SC (醒目留言) 总价值的 ", ratio], [Color.BLACK, Color.LINK])
                if diff is not None:
                    pic.draw_text(["距离上一名还需: ", str(diff), " 元"], [Color.BLACK, Color.LINK, Color.BLACK])

            if any([user_captain_count, user_commander_count, user_governor_count]):
                pic.draw_text("")
                if user_captain_count:
                    pic.draw_text(f"开通舰长: {user_captain_count} 月", Color.DEEPSKYBLUE)
                if user_commander_count:
                    pic.draw_text(f"开通提督: {user_commander_count} 月", Color.FUCHSIA)
                if user_governor_count:
                    pic.draw_text(f"开通总督: {user_governor_count} 月", Color.CRIMSON)

        # 底部版权信息，请务必保留此处
        pic.draw_text("")
        pic.draw_text_right(25, "Designed By StarBot", Color.GRAY)
        pic.draw_text_right(25, "https://github.com/Starlwr/StarBot", Color.LINK)
        pic.crop_and_paste_bottom()

        await app.send_message(sender, MessageChain(Image(base64=pic.base64())))
