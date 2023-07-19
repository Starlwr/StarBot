from graia.ariadne import Ariadne
from graia.ariadne.event.message import GroupMessage
from graia.ariadne.message.chain import MessageChain
from graia.ariadne.message.element import Source, At
from graia.ariadne.message.parser.twilight import Twilight, FullMatch, UnionMatch, ElementMatch
from graia.ariadne.model import Member, Group
from graia.saya import Channel
from graia.saya.builtins.broadcast import ListenerSchema
from loguru import logger

from ....core.datasource import DataSource
from ....core.model import PushType
from ....utils import config, redis

prefix = config.get("COMMAND_PREFIX")

channel = Channel.current()


@channel.use(
    ListenerSchema(
        listening_events=[GroupMessage],
        inline_dispatchers=[Twilight(
            ElementMatch(At, optional=True),
            FullMatch(prefix),
            UnionMatch(
                "取消动态@我", "退出动态@我", "动态不@我", "动态别@我"
            )
        )],
    )
)
async def dynamic_at_me_cancel(app: Ariadne, source: Source, sender: Group, member: Member):
    logger.info(f"群[{sender.id}] 触发命令 : 取消动态@我")

    datasource: DataSource = app.options["StarBotDataSource"]
    ups = datasource.get_ups_by_target(sender.id, PushType.Group if isinstance(sender, Group) else PushType.Friend)

    if not ups:
        return

    if not await redis.exists_dynamic_at(sender.id, member.id):
        await app.send_message(sender, MessageChain("您不在动态@名单中~"), quote=source)
        return

    at_length_limit = config.get("COMMAND_DYNAMIC_AT_ME_LIMIT")
    now_length = await redis.len_dynamic_at(sender.id)

    await redis.delete_dynamic_at(sender.id, member.id)
    await app.send_message(
        sender,
        MessageChain(f"您已成功退出动态@名单~\n本群动态@名额还剩余 {max(0, at_length_limit - now_length + 1)} 个"),
        quote=source
    )
