from typing import Union, Optional

from graia.ariadne import Ariadne
from graia.ariadne.event.message import FriendMessage, GroupMessage
from graia.ariadne.message.chain import MessageChain
from graia.ariadne.message.element import Source, At
from graia.ariadne.message.parser.twilight import Twilight, FullMatch, UnionMatch, ParamMatch, ResultValue, ElementMatch
from graia.ariadne.model import Friend, Member, Group
from graia.ariadne.util.interrupt import FunctionWaiter
from graia.saya import Channel
from graia.saya.builtins.broadcast import ListenerSchema
from loguru import logger

from ....utils import config, redis
from ....utils.network import request
from ....utils.utils import remove_command_param_placeholder

prefix = config.get("COMMAND_PREFIX")

channel = Channel.current()


@channel.use(
    ListenerSchema(
        listening_events=[FriendMessage, GroupMessage],
        inline_dispatchers=[Twilight(
            ElementMatch(At, optional=True),
            FullMatch(prefix),
            UnionMatch("绑定", "bind"),
            "uid" @ ParamMatch()
        )],
    )
)
async def bind(app: Ariadne,
               source: Source,
               sender: Union[Friend, Group],
               member: Optional[Member],
               uid: MessageChain = ResultValue()):
    logger.info(f"{'群' if isinstance(sender, Group) else '好友'}[{sender.id}] 触发命令 : 绑定")

    if isinstance(sender, Group) and await redis.exists_disable_command("DenyBind", sender.id):
        await app.send_message(sender, MessageChain("此命令已被禁用~"), quote=source)
        return

    uid = remove_command_param_placeholder(uid.display)

    # Replace
    uid = uid.replace("UID:", "")

    if not uid.isdigit() or int(uid) == 0:
        await app.send_message(sender, MessageChain(f"请输入正确的UID~\n命令示例:{prefix}绑定 114514"), quote=source)
        return

    if isinstance(sender, Friend):
        qq = sender.id
    else:
        qq = member.id

    uid = int(uid)
    info = await request("GET", f"https://api.live.bilibili.com/live_user/v1/Master/info?uid={uid}")
    uname = info["info"]["uname"]

    if not uname:
        await app.send_message(
            sender, MessageChain("要绑定的UID不正确\n请检查后重新绑定~"), quote=source
        )
        return

    await app.send_message(
        sender,
        MessageChain(f"即将绑定B站账号: {uname}\n请在1分钟内发送\"{prefix}确认绑定\"以继续\n发送其他消息会取消本次绑定"),
        quote=source
    )

    async def waiter(wait_source: Source,
                     wait_sender: Union[Friend, Group],
                     wait_member: Optional[Member],
                     wait_msg: MessageChain) -> bool:
        if isinstance(wait_sender, Friend):
            wait_qq = wait_sender.id
        else:
            wait_qq = wait_member.id

        if wait_qq == qq:
            nonlocal source
            source = wait_source
            msg = wait_msg.exclude(At).display.removeprefix(prefix).strip()
            if msg == "确认绑定" or msg == "确定绑定":
                return True
            else:
                return False

    result = await FunctionWaiter(waiter, [FriendMessage, GroupMessage]).wait(timeout=60)

    if result:
        await redis.bind_uid(qq, uid)
        await app.send_message(sender, MessageChain("绑定成功~"), quote=source)
