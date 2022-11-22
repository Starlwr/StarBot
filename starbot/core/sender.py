import asyncio
from asyncio import AbstractEventLoop
from typing import Optional, List, Dict, Any, Union, Callable

from graia.ariadne import Ariadne
from graia.ariadne.connection.config import config as AriadneConfig, HttpClientConfig, WebsocketClientConfig
from graia.ariadne.message.chain import MessageChain
from graia.ariadne.message.element import At, AtAll
from graia.ariadne.model import LogConfig, MemberPerm
from loguru import logger
from pydantic import BaseModel, PrivateAttr

from .model import LiveOn, LiveOff, DynamicUpdate, Message, PushType, PushTarget
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

    def start_sender(self):
        self.__loop.create_task(self.__sender())
        logger.success(f"Bot [{self.qq}] 已启动")

    def send_message(self, msg: Message):
        self.__queue.append(msg)

    async def __sender(self):
        """
        消息发送模块
        """
        interval = config.get("MESSAGE_SEND_INTERVAL")

        while True:
            if self.__queue:
                msg = self.__queue[0]
                if msg.type == PushType.Friend:
                    for message in msg.get_message_chains():
                        logger.info(f"{self.qq} -> 好友[{msg.id}] : {message.safe_display}")
                        await self.__bot.send_friend_message(msg.id, message)
                else:
                    for message in await self.group_message_filter(msg):
                        logger.info(f"{self.qq} -> 群[{msg.id}] : {message.safe_display}")
                        await self.__bot.send_group_message(msg.id, message)
                self.__queue.pop(0)
                await asyncio.sleep(interval)
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

            if len(chain) != 0:
                new_chains.append(chain)

        return new_chains

    def send_to_all_target(self, up: Up, msg: str, target_filter: Callable[[PushTarget], bool] = lambda t: True):
        """
        发送消息至 UP 主下所有推送目标

        Args:
            up: 要发送的 UP 主实例
            msg: 要发送的消息内容，可使用占位符
            target_filter: 推送目标过滤器，如传入 lambda t: t.live_on.enabled 代表发送所有启用开播推送的群。默认：lambda t: True
        """
        if not isinstance(up, Up):
            return

        for target in up.targets:
            if target_filter(target):
                self.send_message(Message(id=target.id, content=msg, type=target.type))

    def __send_push_message(self, up: Up,
                            type_selector: Callable[[PushTarget], Union[LiveOn, LiveOff, DynamicUpdate]],
                            args: Dict):
        """
        发送推送消息至 UP 主下启用此推送类型的推送目标

        Args:
            up: 要发送的 UP 主实例
            type_selector: 推送类型选择器，如传入 lambda t: t.live_on 代表推送开播推送消息
            args: 占位符参数
        """
        if not isinstance(up, Up):
            return

        for target in up.targets:
            select = type_selector(target)
            if not isinstance(select, (LiveOn, LiveOff, DynamicUpdate)):
                return

            if select.enabled:
                for arg, val in args.items():
                    select.message = select.message.replace(arg, str(val))
                self.send_message(Message(id=target.id, content=select.message, type=target.type))

    def send_live_on(self, up: Up, args: Dict):
        """
        发送开播消息至 UP 主下启用开播推送的推送目标

        Args:
            up: 要发送的 UP 主实例
            args: 占位符参数
        """
        self.__send_push_message(up, lambda t: t.live_on, args)

    def send_live_off(self, up: Up, args: Dict):
        """
        发送下播消息至 UP 主下启用下播推送的推送目标

        Args:
            up: 要发送的 UP 主实例
            args: 占位符参数
        """
        self.__send_push_message(up, lambda t: t.live_off, args)

    def send_dynamic_update(self, up: Up, args: Dict):
        """
        发送动态消息至 UP 主下启用动态推送的推送目标

        Args:
            up: 要发送的 UP 主实例
            args: 占位符参数
        """
        self.__send_push_message(up, lambda t: t.dynamic_update, args)

    def __eq__(self, other):
        if isinstance(other, Bot):
            return self.qq == other.qq
        elif isinstance(other, int):
            return self.qq == other
        return False

    def __hash__(self):
        return hash(self.qq)
