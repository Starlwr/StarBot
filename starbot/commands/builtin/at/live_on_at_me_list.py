from graia.ariadne import Ariadne
from graia.ariadne.event.message import GroupMessage
from graia.ariadne.message.chain import MessageChain
from graia.ariadne.message.element import Source, At
from graia.ariadne.message.parser.twilight import Twilight, FullMatch, UnionMatch, ElementMatch
from graia.ariadne.model import Group
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
            UnionMatch("开播@列表", "直播@列表", "开播@名单", "直播@名单")
        )],
    )
)
async def live_on_at_me_list(app: Ariadne, source: Source, sender: Group):
    logger.info(f"群[{sender.id}] 触发命令 : 开播@列表")

    datasource: DataSource = app.options["StarBotDataSource"]
    ups = datasource.get_ups_by_target(sender.id, PushType.Group if isinstance(sender, Group) else PushType.Friend)

    if not ups:
        return

    if not await redis.len_live_on_at(sender.id):
        await app.send_message(sender, MessageChain("本群的开播@列表为空~"), quote=source)
        return

    ats = "\n".join({str(x) for x in await redis.range_live_on_at(sender.id)})
    await app.send_message(sender, MessageChain(f"本群的开播@列表如下:\n{ats}"), quote=source)
