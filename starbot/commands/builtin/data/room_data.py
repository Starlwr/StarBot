import time
from typing import Union

from graia.ariadne import Ariadne
from graia.ariadne.event.message import FriendMessage, GroupMessage
from graia.ariadne.message.chain import MessageChain
from graia.ariadne.message.element import Image, Source, At
from graia.ariadne.message.parser.twilight import Twilight, FullMatch, ElementMatch
from graia.ariadne.model import Friend, Group
from graia.saya import Channel
from graia.saya.builtins.broadcast import ListenerSchema
from loguru import logger

from ....core.datasource import DataSource
from ....core.model import PushType
from ....painter.PicGenerator import PicGenerator, Color
from ....utils import config, redis
from ....utils.utils import timestamp_format, get_unames_and_faces_by_uids, mask_round

prefix = config.get("COMMAND_PREFIX")

channel = Channel.current()


@channel.use(
    ListenerSchema(
        listening_events=[FriendMessage, GroupMessage],
        inline_dispatchers=[Twilight(
            ElementMatch(At, optional=True),
            FullMatch(prefix),
            FullMatch("直播间数据")
        )],
    )
)
async def room_data(app: Ariadne, source: Source, sender: Union[Friend, Group]):
    logger.info(f"{'群' if isinstance(sender, Group) else '好友'}[{sender.id}] 触发命令 : 直播间数据")

    if isinstance(sender, Group) and await redis.exists_disable_command("DenyRoomData", sender.id):
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

    for up in ups:
        if up.room_id is None:
            continue

        width = 1000
        height = 100000
        face_size = 100

        uname, face = await get_unames_and_faces_by_uids([str(up.uid)])
        uname = uname[0]
        face = face[0]

        pic = PicGenerator(width, height)
        pic.set_pos(175, 80).draw_rounded_rectangle(0, 0, width, height, 35, Color.WHITE).copy_bottom(35)

        pic.draw_img_alpha(mask_round(face.resize((face_size, face_size)).convert("RGBA")), (50, 50))
        pic.draw_section(f"{uname} 的直播间数据").set_pos(50, 150 + pic.row_space)
        pic.draw_tip(f"UID: {up.uid}   房间号: {up.room_id}")
        pic.draw_tip("此处为本直播间最近一场直播的数据")
        pic.draw_tip("使用\"直播间总数据\"命令可查询本直播间累计总数据")
        pic.draw_tip(f"查询时间: {timestamp_format(int(time.time()), '%Y/%m/%d %H:%M:%S')}")

        status = await redis.get_live_status(up.room_id)
        status_str = "正在直播" if status == 1 else "未开播"
        status_color = Color.RED if status == 1 else Color.GREEN
        pic.draw_text(["直播间状态: ", status_str], [Color.BLACK, status_color])
        pic.draw_text("")
        note_str = "本次直播数据: " if status == 1 else "上次直播数据: "
        pic.draw_text(note_str)

        danmu_count = await redis.get_room_danmu_count(up.room_id)
        danmu_person_count = await redis.len_user_danmu_count(up.room_id)
        box_count = await redis.get_room_box_count(up.room_id)
        box_person_count = await redis.len_user_box_count(up.room_id)
        box_profit = await redis.get_room_box_profit(up.room_id)
        box_profit_color = Color.RED if box_profit > 0 else (Color.GREEN if box_profit < 0 else Color.GRAY)
        gift_profit = await redis.get_room_gift_profit(up.room_id)
        gift_person_count = await redis.len_user_gift_profit(up.room_id)
        sc_profit = await redis.get_room_sc_profit(up.room_id)
        sc_person_count = await redis.len_user_sc_profit(up.room_id)
        captain_count = await redis.get_room_captain_count(up.room_id)
        commander_count = await redis.get_room_commander_count(up.room_id)
        governor_count = await redis.get_room_governor_count(up.room_id)
        guard_person_count = await redis.len_user_guard_count(up.room_id)
        pic.draw_text(
            ["收获弹幕数: ", str(danmu_count), " 条 (", str(danmu_person_count), " 人)"],
            [Color.BLACK, Color.LINK, Color.BLACK, Color.LINK, Color.BLACK]
        )
        pic.draw_text(
            ["收到盲盒数: ", str(box_count), " 个 (", str(box_person_count), " 人)"],
            [Color.BLACK, Color.LINK, Color.BLACK, Color.LINK, Color.BLACK]
        )
        pic.draw_text(["盲盒盈亏: ", str(box_profit), " 元"], [Color.BLACK, box_profit_color, Color.BLACK])
        pic.draw_text(
            ["收获礼物价值: ", str(gift_profit), " 元 (", str(gift_person_count), " 人)"],
            [Color.BLACK, Color.LINK, Color.BLACK, Color.LINK, Color.BLACK]
        )
        pic.draw_text(
            ["收获 SC (醒目留言) 价值: ", str(sc_profit), " 元 (", str(sc_person_count), " 人)"],
            [Color.BLACK, Color.LINK, Color.BLACK, Color.LINK, Color.BLACK]
        )
        pic.draw_text(
            ["收获大航海: ", f"舰长×{captain_count}   ", f"提督×{commander_count}   ", f"总督×{governor_count}"],
            [Color.BLACK, Color.DEEPSKYBLUE, Color.FUCHSIA, Color.CRIMSON]
        )
        pic.draw_text(["开通大航海人数: ", str(guard_person_count)], [Color.BLACK, Color.LINK])

        # 底部版权信息，请务必保留此处
        pic.draw_text("")
        pic.draw_text_right(25, "Designed By StarBot", Color.GRAY)
        pic.draw_text_right(25, "https://github.com/Starlwr/StarBot", Color.LINK)
        pic.crop_and_paste_bottom()

        await app.send_message(sender, MessageChain(Image(base64=pic.base64())))
