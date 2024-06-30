import math
import time
from typing import Union

from graia.ariadne import Ariadne
from graia.ariadne.event.message import FriendMessage, GroupMessage
from graia.ariadne.message.chain import MessageChain
from graia.ariadne.message.element import Source, Image, At
from graia.ariadne.message.parser.twilight import Twilight, FullMatch, UnionMatch, ParamMatch, RegexResult, ResultValue, \
    ElementMatch
from graia.ariadne.model import Friend, Group
from graia.saya import Channel
from graia.saya.builtins.broadcast import ListenerSchema
from loguru import logger

from ....core.datasource import DataSource
from ....core.model import PushType
from ....painter.PicGenerator import PicGenerator, Color
from ....painter.RankingGenerator import RankingGenerator
from ....utils import config, redis
from ....utils.utils import remove_command_param_placeholder, get_unames_and_faces_by_uids, timestamp_format

prefix = config.get("COMMAND_PREFIX")

type_map = {
    "弹幕": (redis.len_user_danmu_count, redis.rev_range_user_danmu_count, "弹幕", "弹幕"),
    "弹幕总": (redis.len_user_danmu_all, redis.rev_range_user_danmu_all, "弹幕", "弹幕总"),
    "盲盒": (redis.len_user_box_count, redis.rev_range_user_box_count, "盲盒", "盲盒"),
    "宝盒": (redis.len_user_box_count, redis.rev_range_user_box_count, "盲盒", "盲盒"),
    "盲盒总": (redis.len_user_box_all, redis.rev_range_user_box_all, "盲盒", "盲盒总"),
    "宝盒总": (redis.len_user_box_all, redis.rev_range_user_box_all, "盲盒", "盲盒总"),
    "礼物": (redis.len_user_gift_profit, redis.rev_range_user_gift_profit, "礼物", "礼物"),
    "礼物总": (redis.len_user_gift_all, redis.rev_range_user_gift_all, "礼物", "礼物总"),
    "SC": (redis.len_user_sc_profit, redis.rev_range_user_sc_profit, "SC", "SC"),
    "sc": (redis.len_user_sc_profit, redis.rev_range_user_sc_profit, "SC", "SC"),
    "醒目留言": (redis.len_user_sc_profit, redis.rev_range_user_sc_profit, "SC", "SC"),
    "SC总": (redis.len_user_sc_all, redis.rev_range_user_sc_all, "SC", "SC总"),
    "sc总": (redis.len_user_sc_all, redis.rev_range_user_sc_all, "SC", "SC总"),
    "醒目留言总": (redis.len_user_sc_all, redis.rev_range_user_sc_all, "SC", "SC总")
}

channel = Channel.current()


@channel.use(
    ListenerSchema(
        listening_events=[FriendMessage, GroupMessage],
        inline_dispatchers=[Twilight(
            ElementMatch(At, optional=True),
            FullMatch(prefix),
            "_type" @ ParamMatch(),
            UnionMatch("榜", "排行", "排行榜"),
            "page" @ ParamMatch(optional=True)
        )],
    )
)
async def ranking(app: Ariadne,
                  source: Source,
                  sender: Union[Friend, Group],
                  page: RegexResult,
                  _type: MessageChain = ResultValue()):
    _type = _type.display
    if _type not in type_map:
        return

    logger.info(f"{'群' if isinstance(sender, Group) else '好友'}[{sender.id}] 触发命令 : {_type}排行")

    if page.matched:
        page = remove_command_param_placeholder(page.result.display)
        if not page.isdigit() or int(page) <= 0:
            await app.send_message(sender, MessageChain("请输入正确的页码~"), quote=source)
            return
        page = int(page)
    else:
        page = 1

    size = 10
    start = size * (page - 1)
    end = start + size - 1

    datasource: DataSource = app.options["StarBotDataSource"]
    ups = datasource.get_ups_by_target(sender.id, PushType.Group if isinstance(sender, Group) else PushType.Friend)

    if not ups:
        return

    for up in ups:
        length = await type_map[_type][0](up.room_id)
        page_length = math.ceil(length / size)

        if length == 0:
            await app.send_message(sender, MessageChain(f"{up.uname} 的房间暂无{type_map[_type][2]}数据~"), quote=source)
            continue

        if page > page_length:
            await app.send_message(
                sender,
                MessageChain(f"{up.uname} 的房间{type_map[_type][3]}榜页码范围为 1 ~ {page_length}\n请重新输入正确的页码~"),
                quote=source
            )
            continue

        data = await type_map[_type][1](up.room_id, start, end)
        top_count = (await type_map[_type][1](up.room_id, 0, 0))[0][1]

        uids = [x[0] for x in data]
        counts = [x[1] for x in data]
        unames, faces = await get_unames_and_faces_by_uids(uids)

        width = 1000
        height = 100000
        margin = 50
        pic = PicGenerator(width, height)
        pic.set_pos(margin, margin).draw_rounded_rectangle(0, 0, width, height, 35, Color.WHITE).copy_bottom(35)

        pic.draw_section(f"{up.uname} 房间的{type_map[_type][3]}排行")
        pic.draw_text(f"第 {start + 1} 名 ~ 第 {start + len(uids)} 名   ( 第 {page} 页 / 共 {page_length} 页 )")
        pic.draw_tip(f"UID: {up.uid}   房间号: {up.room_id}")
        pic.draw_tip(f"查询时间: {timestamp_format(int(time.time()), '%Y/%m/%d %H:%M:%S')}")
        if page != page_length:
            pic.draw_tip(f"继续查看下一页请发送 \"{prefix}{type_map[_type][3]}榜 {page + 1}\"")
        pic.draw_text("")

        pic.draw_img_alpha(
            RankingGenerator.get_ranking(pic.row_space, faces, unames, counts, pic.width - (margin * 2), top_count)
        )

        # 底部版权信息，请务必保留此处
        pic.draw_text("")
        pic.draw_text_right(25, "Designed By StarBot", Color.GRAY)
        pic.draw_text_right(25, "https://github.com/Starlwr/StarBot", Color.LINK)
        pic.crop_and_paste_bottom()

        await app.send_message(sender, MessageChain(Image(base64=pic.base64())))
