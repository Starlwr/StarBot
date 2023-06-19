from graia.ariadne import Ariadne
from graia.ariadne.event.message import FriendMessage
from graia.ariadne.message.chain import MessageChain
from graia.ariadne.message.parser.twilight import Twilight, FullMatch, UnionMatch
from graia.ariadne.model import Friend
from graia.saya import Channel
from graia.saya.builtins.broadcast import ListenerSchema

from ...core.datasource import DataSource
from ...utils import config

prefix = config.get("COMMAND_PREFIX")
master = config.get("MASTER_QQ")

channel = Channel.current()


@channel.use(
    ListenerSchema(
        listening_events=[FriendMessage],
        inline_dispatchers=[Twilight(
            FullMatch(prefix),
            UnionMatch("补发", "resend")
        )],
    )
)
async def resend(app: Ariadne, friend: Friend):
    if friend.id != master:
        return

    if not config.get("BAN_RESEND"):
        await app.send_friend_message(friend, MessageChain("补发功能未启用~"))

    datasource: DataSource = app.options["StarBotDataSource"]
    bot = datasource.get_bot(app.account)
    await bot.resend()
