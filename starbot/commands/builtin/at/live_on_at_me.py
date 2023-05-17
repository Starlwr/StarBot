from graia.ariadne import Ariadne
from graia.ariadne.event.message import GroupMessage
from graia.ariadne.message.chain import MessageChain
from graia.ariadne.message.element import Source, At
from graia.ariadne.message.parser.twilight import Twilight, FullMatch, UnionMatch, ElementMatch
from graia.ariadne.model import Member, Group
from graia.saya import Channel
from graia.saya.builtins.broadcast import ListenerSchema

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
            UnionMatch("开播@我", "直播@我")
        )],
    )
)
async def live_on_at_me(app: Ariadne, source: Source, sender: Group, member: Member):
    datasource: DataSource = app.options["StarBotDataSource"]
    ups = datasource.get_ups_by_target(sender.id, PushType.Group if isinstance(sender, Group) else PushType.Friend)

    if not ups:
        return

    if await redis.exists_live_on_at(sender.id, member.id):
        await app.send_message(sender, MessageChain("您已经在开播@名单中了~"), quote=source)
        return

    at_length_limit = config.get("COMMAND_LIVE_ON_AT_ME_LIMIT")
    now_length = await redis.len_live_on_at(sender.id)
    if now_length >= at_length_limit:
        await app.send_message(sender, MessageChain("本群的开播@名额已满~"), quote=source)
        return

    await redis.add_live_on_at(sender.id, member.id)
    await app.send_message(
        sender,
        MessageChain(f"您已经成功加入开播@名单~\n本群开播@名额还剩余 {max(0, at_length_limit - now_length - 1)} 个"),
        quote=source
    )
