import asyncio
from asyncio import AbstractEventLoop
from typing import List, Optional, Any

from graia.ariadne import Ariadne
from graia.ariadne.connection.config import config as AriadneConfig, HttpClientConfig, WebsocketClientConfig
from graia.ariadne.event.lifecycle import ApplicationLaunched
from graia.ariadne.message.chain import MessageChain
from graia.ariadne.message.element import At, AtAll
from graia.ariadne.model import LogConfig, MemberPerm
from loguru import logger
from pydantic import BaseModel, PrivateAttr

from .model import PushType, Message
from .room import Up
from ..utils import config
from ..utils.AsyncEvent import AsyncEvent


class Bot(BaseModel, AsyncEvent):
    """
    Bot 类，每个实例为一个 QQ 号，可用于配置多 Bot 推送
    """

    qq: int
    """Bot 的 QQ 号"""

    ups: List[Up]
    """Bot 账号下运行的 UP 主列表"""

    __loop: Optional[AbstractEventLoop] = PrivateAttr()
    """asyncio 事件循环"""

    __bot: Optional[Ariadne] = PrivateAttr()
    """Ariadne 实例"""

    __queue: Optional[List[Message]] = PrivateAttr()
    """待发送消息队列"""

    def __init__(self, **data: Any):
        super().__init__(**data)
        self.__loop = asyncio.get_event_loop()
        self.__bot = Ariadne(
            connection=AriadneConfig(
                self.qq,
                "StarBot",
                HttpClientConfig(host=f"http://localhost:{config.get('MIRAI_PORT')}"),
                WebsocketClientConfig(host=f"http://localhost:{config.get('MIRAI_PORT')}"),
            ),
            log_config=LogConfig(log_level="DEBUG")
        )
        self.__queue = []

        # 注入 Bot 实例引用
        for up in self.ups:
            up.inject_bot(self)

        # 发送消息方法
        @self.on("SEND_MESSAGE")
        async def send_message(msg: Message):
            self.__queue.append(msg)

        # Ariadne 启动成功后启动消息发送模块
        @self.__bot.broadcast.receiver(ApplicationLaunched)
        async def start_sender():
            logger.success(f"Bot [{self.qq}] 已启动")
            self.__loop.create_task(self.__sender())

    async def __sender(self):
        """
        消息发送模块
        """
        while True:
            if self.__queue:
                msg = self.__queue[0]
                if msg.type == PushType.Friend:
                    for message in msg.get_message_chains():
                        logger.info(f"{self.qq} -> 好友[{msg.id}] : {message}")
                        await self.__bot.send_friend_message(msg.id, message)
                else:
                    for message in await self.group_message_filter(msg):
                        logger.info(f"{self.qq} -> 群[{msg.id}] : {message}")
                        await self.__bot.send_group_message(msg.id, message)
                self.__queue.pop(0)
            else:
                await asyncio.sleep(0.1)

    async def group_message_filter(self, message: Message) -> List[MessageChain]:
        """
        过滤群消息中的非法元素

        Args:
            message: 源消息链

        Returns:
            处理后的消息链
        """
        if message.type == PushType.Friend:
            return message.get_message_chains()

        new_chains = []

        # 过滤 Bot 不在群内的消息
        group = await self.__bot.get_group(message.id)
        if group is None:
            return new_chains

        for chain in message.get_message_chains():
            if AtAll in chain:
                # 过滤 Bot 不是群管理员时的 @全体成员 消息
                bot_info = await self.__bot.get_member(self.qq, message.id)
                if bot_info.permission < MemberPerm.Administrator:
                    chain = chain.exclude(AtAll)

                # 过滤多余的 @全体成员 消息
                if chain.count(AtAll) > 1:
                    elements = [e for e in chain.exclude(AtAll)]
                    elements.insert(chain.index(AtAll), AtAll())
                    chain = MessageChain(elements)

            if At in message:
                # 过滤已不在群内的群成员的 @ 消息
                member_list = [member.id for member in await self.__bot.get_member_list(message.id)]
                elements = [e for e in chain if (not isinstance(e, At)) or (e.target in member_list)]
                chain = MessageChain(elements)

            new_chains.append(chain)

        return new_chains

    def __eq__(self, other):
        if isinstance(other, Bot):
            return self.qq == other.qq
        elif isinstance(other, int):
            return self.qq == other
        return False

    def __hash__(self):
        return hash(self.qq)
