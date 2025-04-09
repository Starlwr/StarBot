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
from ....painter.LiveReportGenerator import LiveReportGenerator
from ....utils import config, redis

prefix = config.get("COMMAND_PREFIX")

channel = Channel.current()


@channel.use(
    ListenerSchema(
        listening_events=[FriendMessage, GroupMessage],
        inline_dispatchers=[Twilight(
            ElementMatch(At, optional=True),
            FullMatch(prefix),
            FullMatch("直播报告")
        )],
    )
)
async def live_report(app: Ariadne, source: Source, sender: Union[Friend, Group]):
    logger.info(f"{'群' if isinstance(sender, Group) else '好友'}[{sender.id}] 触发命令 : 直播报告")

    if isinstance(sender, Group) and await redis.exists_disable_command("DenyLiveReport", sender.id):
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

        live_report_param = await up.generate_live_report_param()

        target = list(filter(
            lambda t: t.id == sender.id and t.type == (PushType.Group if isinstance(sender, Group) else PushType.Friend)
            , up.targets
        ))[0]

        base64str = LiveReportGenerator.generate(live_report_param, target.live_report)
        await app.send_message(sender, MessageChain(Image(base64=base64str)))
