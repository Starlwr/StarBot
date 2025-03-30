from typing import Union

from graia.ariadne import Ariadne
from graia.ariadne.event.message import FriendMessage, GroupMessage
from graia.ariadne.message.chain import MessageChain
from graia.ariadne.message.element import Image, At
from graia.ariadne.message.parser.twilight import Twilight, FullMatch, UnionMatch, ElementMatch
from graia.ariadne.model import Friend, Group
from graia.saya import Channel
from graia.saya.builtins.broadcast import ListenerSchema
from loguru import logger

from ...painter.PicGenerator import PicGenerator, Color
from ...utils import config, redis

prefix = config.get("COMMAND_PREFIX")

channel = Channel.current()


@channel.use(
    ListenerSchema(
        listening_events=[FriendMessage, GroupMessage],
        inline_dispatchers=[Twilight(
            ElementMatch(At, optional=True),
            FullMatch(prefix),
            UnionMatch("帮助", "菜单", "功能", "命令", "指令", "help")
        )],
    )
)
async def _help(app: Ariadne, sender: Union[Friend, Group]):
    logger.info(f"{'群' if isinstance(sender, Group) else '好友'}[{sender.id}] 触发命令 : 帮助")

    disable_querys = ["DenyRoomData", "DenyRoomDataTotal", "DenyBind", "DenyUserData", "DenyUserDataTotal"]
    disabled = [False] * 5
    if isinstance(sender, Group):
        disabled = [await redis.exists_disable_command(x, sender.id) for x in disable_querys]

    width = 1000
    height = 100000
    pic = PicGenerator(width, height)
    pic.set_pos(50, 50).draw_rounded_rectangle(0, 0, width, height, 35, Color.WHITE).copy_bottom(35)

    pic.draw_chapter("StarBot 帮助")
    pic.draw_text("")

    pic.draw_section(f"1.{prefix}菜单")
    commands = "、".join([f"{prefix}{x}" for x in ["帮助", "菜单", "功能", "命令", "指令", "help"]])
    pic.draw_text_multiline(50, ["命令: ", commands], [Color.RED, Color.BLACK])
    pic.draw_tip("获取 Starbot 帮助和命令菜单")

    pic.draw_section(f"2.{prefix}禁用命令")
    commands = "、".join([f"{prefix}{x} [命令名]" for x in ["禁用", "disable"]])
    pic.draw_text_multiline(50, ["命令: ", commands], [Color.RED, Color.BLACK])
    pic.draw_text(["示例: ", "禁用 直播间总数据"], [Color.RED, Color.BLACK])
    pic.draw_tip("此命令仅群聊可用")
    pic.draw_tip("可禁用的命令名: 绑定、直播间数据、直播间总数据、我的数据、我的总数据、直播报告")
    pic.draw_tip("命令禁用只针对本群，如有多群需同时禁用命令请每个群进行一次配置")

    pic.draw_section(f"3.{prefix}启用命令")
    commands = "、".join([f"{prefix}{x} [命令名]" for x in ["启用", "enable"]])
    pic.draw_text_multiline(50, ["命令: ", commands], [Color.RED, Color.BLACK])
    pic.draw_text(["示例: ", "启用 直播间总数据"], [Color.RED, Color.BLACK])
    pic.draw_tip("此命令仅群聊可用")
    pic.draw_tip("用于禁用命令后解除禁用")

    pic.draw_section(f"4.{prefix}直播间数据")
    pic.draw_text_multiline(50, ["命令: ", f"{prefix}直播间数据"], [Color.RED, Color.BLACK])
    if disabled[0]:
        pic.draw_text("本群已禁用此命令", Color.RED)
    pic.draw_tip("查询本直播间最近一场直播的数据")

    pic.draw_section(f"5.{prefix}直播间总数据")
    pic.draw_text_multiline(50, ["命令: ", f"{prefix}直播间总数据"], [Color.RED, Color.BLACK])
    if disabled[1]:
        pic.draw_text("本群已禁用此命令", Color.RED)
    pic.draw_tip("查询本直播间自注册之日起的累计总数据")

    pic.draw_section(f"6.{prefix}绑定")
    commands = "、".join([f"{prefix}{x} [UID]" for x in ["绑定", "bind"]])
    pic.draw_text_multiline(50, ["命令: ", commands], [Color.RED, Color.BLACK])
    pic.draw_text(["示例: ", "绑定 114514"], [Color.RED, Color.BLACK])
    if disabled[2]:
        pic.draw_text("本群已禁用此命令", Color.RED)
    pic.draw_tip("将QQ号绑定至B站账号")
    pic.draw_tip("绑定成功后即可查询自己在本直播间的数据")
    pic.draw_tip("绑定后如需换绑直接使用新UID再次绑定即可")

    pic.draw_section(f"7.{prefix}我的数据")
    pic.draw_text_multiline(50, ["命令: ", f"{prefix}我的数据"], [Color.RED, Color.BLACK])
    if disabled[3]:
        pic.draw_text("本群已禁用此命令", Color.RED)
    pic.draw_tip("查询自己在本直播间最近一场直播的数据、排名")
    pic.draw_tip("需绑定B站账号后使用")

    pic.draw_section(f"8.{prefix}我的总数据")
    pic.draw_text_multiline(50, ["命令: ", f"{prefix}我的总数据"], [Color.RED, Color.BLACK])
    if disabled[4]:
        pic.draw_text("本群已禁用此命令", Color.RED)
    pic.draw_tip("查询自己在本直播间自注册之日起的累计总数据、排名、占比")
    pic.draw_tip("需绑定B站账号后使用")

    pic.draw_section(f"9.{prefix}数据排行榜")
    types = [
        "弹幕排行", "弹幕总排行",
        "盲盒排行", "盲盒总排行",
        "盲盒盈亏排行", "盲盒盈亏总排行",
        "礼物排行", "礼物总排行",
        "SC排行", "SC总排行"
    ]
    commands = "、".join([f"{prefix}{x} <页码>" for x in types])
    pic.draw_text_multiline(50, ["命令: ", commands], [Color.RED, Color.BLACK])
    pic.draw_text(["示例: ", "弹幕排行、礼物总排行 3"], [Color.RED, Color.BLACK])
    pic.draw_tip("查询本直播间的数据排行榜")
    pic.draw_tip("xx排行指本直播间最近一场直播的数据排行榜")
    pic.draw_tip("xx总排行指本直播间自注册之日起的累计总数据排行榜")
    pic.draw_tip("不输入页码参数默认查询第一页")

    pic.draw_section(f"10.{prefix}开播@我")
    commands = "、".join([f"{prefix}{x}" for x in ["开播@我", "直播@我"]])
    pic.draw_text_multiline(50, ["命令: ", commands], [Color.RED, Color.BLACK])
    pic.draw_tip("此命令仅群聊可用")
    pic.draw_tip("本直播间开播时单独@自己")
    pic.draw_tip("如配置了@全体成员会优先@全体成员")
    pic.draw_tip("当没有配置@全体成员或@全体成员失败时会单独@\"开播@我\"名单中的群成员")

    pic.draw_section(f"11.{prefix}取消开播@我")
    commands = "、".join([f"{prefix}{x}" for x in ["取消开播@我", "退出开播@我", "开播不@我", "开播别@我"]])
    pic.draw_text_multiline(50, ["命令: ", commands], [Color.RED, Color.BLACK])
    pic.draw_tip("此命令仅群聊可用")
    pic.draw_tip("退出本群的\"开播@我\"名单")

    pic.draw_section(f"12.{prefix}开播@名单")
    commands = "、".join([f"{prefix}{x}" for x in ["开播@名单", "开播@列表"]])
    pic.draw_text_multiline(50, ["命令: ", commands], [Color.RED, Color.BLACK])
    pic.draw_tip("此命令仅群聊可用")
    pic.draw_tip("查询本群的\"开播@我\"名单")

    pic.draw_section(f"13.{prefix}动态@我")
    pic.draw_text_multiline(50, ["命令: ", f"{prefix}动态@我"], [Color.RED, Color.BLACK])
    pic.draw_tip("此命令仅群聊可用")
    pic.draw_tip("主播发布新动态时单独@自己")
    pic.draw_tip("如配置了@全体成员会优先@全体成员")
    pic.draw_tip("当没有配置@全体成员或@全体成员失败时会单独@\"动态@我\"名单中的群成员")

    pic.draw_section(f"14.{prefix}取消动态@我")
    commands = "、".join([f"{prefix}{x}" for x in ["取消动态@我", "退出动态@我", "动态不@我", "动态别@我"]])
    pic.draw_text_multiline(50, ["命令: ", commands], [Color.RED, Color.BLACK])
    pic.draw_tip("此命令仅群聊可用")
    pic.draw_tip("退出本群的\"动态@我\"名单")

    pic.draw_section(f"15.{prefix}动态@名单")
    commands = "、".join([f"{prefix}{x}" for x in ["动态@名单", "动态@列表"]])
    pic.draw_text_multiline(50, ["命令: ", commands], [Color.RED, Color.BLACK])
    pic.draw_tip("此命令仅群聊可用")
    pic.draw_tip("查询本群的\"动态@我\"名单")

    # 底部版权信息，请务必保留此处
    pic.draw_text_right(25, "Designed By StarBot", Color.GRAY)
    pic.draw_text_right(25, "https://github.com/Starlwr/StarBot", Color.LINK)
    pic.crop_and_paste_bottom()

    await app.send_message(sender, MessageChain(Image(base64=pic.base64())))
