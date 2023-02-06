from graia.ariadne import Ariadne
from graia.ariadne.event.message import GroupMessage
from graia.ariadne.message.chain import MessageChain
from graia.ariadne.message.element import Source
from graia.ariadne.message.parser.twilight import Twilight, FullMatch
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
            FullMatch(prefix),
            FullMatch("动态@我")
        )],
    )
)
async def dynamic_at_me(app: Ariadne, source: Source, sender: Group, member: Member):
    datasource: DataSource = app.options["StarBotDataSource"]
    ups = datasource.get_ups_by_target(sender.id, PushType.Group if isinstance(sender, Group) else PushType.Friend)

    if not ups:
        return

    if await redis.exists_dynamic_at(sender.id, member.id):
        await app.send_message(sender, MessageChain("您已经在动态@名单中了~"), quote=source)
        return

    at_length_limit = config.get("COMMAND_DYNAMIC_AT_ME_LIMIT")
    now_length = await redis.len_dynamic_at(sender.id)
    if now_length >= at_length_limit:
        await app.send_message(sender, MessageChain("本群的动态@名额已满~"), quote=source)
        return

    await redis.add_dynamic_at(sender.id, member.id)
    await app.send_message(
        sender,
        MessageChain(f"您已经成功加入动态@名单~\n本群动态@名额还剩余 {max(0, at_length_limit - now_length - 1)} 个"),
        quote=source
    )
