from graia.ariadne import Ariadne
from graia.ariadne.event.message import GroupMessage
from graia.ariadne.message.chain import MessageChain
from graia.ariadne.message.element import Source, At
from graia.ariadne.message.parser.twilight import Twilight, FullMatch, UnionMatch, ParamMatch, ResultValue, ElementMatch
from graia.ariadne.model import Group, Member, MemberPerm
from graia.saya import Channel
from graia.saya.builtins.broadcast import ListenerSchema
from loguru import logger

from ...utils import config, redis
from ...utils.utils import remove_command_param_placeholder

prefix = config.get("COMMAND_PREFIX")
master = config.get("MASTER_QQ")

disable_map = {
    "绑定": "DenyBind",
    "直播间数据": "DenyRoomData",
    "直播间总数据": "DenyRoomDataTotal",
    "我的数据": "DenyUserData",
    "我的总数据": "DenyUserDataTotal",
    "直播报告": "DenyLiveReport",
}

channel = Channel.current()


@channel.use(
    ListenerSchema(
        listening_events=[GroupMessage],
        inline_dispatchers=[Twilight(
            ElementMatch(At, optional=True),
            FullMatch(prefix),
            UnionMatch("启用", "enable"),
            "name" @ ParamMatch()
        )],
    )
)
async def enable_command(app: Ariadne,
                         source: Source,
                         group: Group,
                         member: Member,
                         name: MessageChain = ResultValue()):
    if member.permission == MemberPerm.Member and member.id != master:
        await app.send_message(group, MessageChain("您没有执行此命令的权限~"), quote=source)
        return

    name = remove_command_param_placeholder(name.display)
    if name not in disable_map:
        await app.send_message(
            group, MessageChain(f"输入的命令名称不正确~\n支持的命令: {'、'.join(disable_map.keys())}"), quote=source
        )
        return

    logger.info(f"群[{group.id}] 触发命令 : 启用{name}")

    if not (await redis.exists_disable_command(disable_map[name], group.id)):
        await app.send_message(group, "此命令已经是启用状态~", quote=source)
        return

    await redis.delete_disable_command(disable_map[name], group.id)
    await app.send_message(group, "命令已启用~", quote=source)
