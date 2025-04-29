import asyncio
import time
from asyncio import AbstractEventLoop
from typing import Optional, List, Dict, Any, Union, Callable, Tuple

from graia.ariadne import Ariadne
from graia.ariadne.connection.config import config as AriadneConfig, HttpClientConfig, WebsocketClientConfig
from graia.ariadne.exception import RemoteException, AccountMuted, UnknownTarget
from graia.ariadne.message.chain import MessageChain
from graia.ariadne.message.element import At, AtAll
from graia.ariadne.model import LogConfig, MemberPerm
from loguru import logger
from pydantic import BaseModel, PrivateAttr

from .model import LiveOn, LiveOff, DynamicUpdate, Message, PushType, PushTarget
from .room import Up
from ..exception import NoPermissionException
from ..exception.AtAllLimitedException import AtAllLimitedException
from ..painter.LiveReportGenerator import LiveReportGenerator
from ..utils import config, redis


class Bot(BaseModel):
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

    __at_all_limited: Optional[int] = PrivateAttr()
    """@全体成员次数用尽时所在日期"""

    __banned: Optional[bool] = PrivateAttr()
    """当前是否被风控"""

    __queue: Optional[List[Tuple[int, MessageChain, int]]] = PrivateAttr()
    """消息补发队列"""

    def __init__(self, **data: Any):
        super().__init__(**data)
        self.__loop = asyncio.get_event_loop()
        self.__bot = Ariadne(
            connection=AriadneConfig(
                self.qq,
                config.get('MIRAI_KEY'),
                HttpClientConfig(host=f"http://{config.get('MIRAI_HOST')}:{config.get('MIRAI_PORT')}"),
                WebsocketClientConfig(host=f"http://{config.get('MIRAI_HOST')}:{config.get('MIRAI_PORT')}"),
            ),
            log_config=LogConfig(log_level="DEBUG")
        )
        self.__at_all_limited = time.localtime(time.time() - 86400).tm_yday
        self.__banned = False
        self.__queue = []

        # 注入 Bot 实例引用
        for up in self.ups:
            up.inject_bot(self)

    def clear_resend_queue(self):
        """
        清空补发队列
        """
        self.__queue.clear()

    async def resend(self):
        """
        风控消息补发
        """
        if len(self.__queue) == 0:
            self.__banned = False
            if config.get("MASTER_QQ"):
                await self.__bot.send_friend_message(config.get("MASTER_QQ"), "补发队列为空~")
            return

        interval = config.get("RESEND_INTERVAL")
        task_start_tip = f"补发任务已启动, 补发队列长度: {len(self.__queue)}"
        logger.info(task_start_tip)
        if config.get("MASTER_QQ"):
            await self.__bot.send_friend_message(config.get("MASTER_QQ"), f"{task_start_tip}~")

        while len(self.__queue) > 0:
            msg_id, message, timestamp = self.__queue[0]
            resend_time_limit = config.get("RESEND_TIME_LIMIT")
            if resend_time_limit != 0 and int(time.time()) - timestamp > resend_time_limit:
                logger.info(f"消息已超时, 跳过补发, 群({msg_id}) : {message.safe_display}")
                self.__queue.pop(0)
                continue
            try:
                await self.__bot.send_group_message(msg_id, message)
                self.__banned = False
                logger.info(f"{self.qq} -> 群[{msg_id}] : {message.safe_display}")
                self.__queue.pop(0)
                await asyncio.sleep(interval)
            except AccountMuted:
                logger.warning(f"Bot({self.qq}) 在群 {msg_id} 中被禁言")
                self.__queue.pop(0)
                continue
            except UnknownTarget:
                self.__queue.pop(0)
                continue
            except RemoteException as ex:
                if "AT_ALL_LIMITED" in str(ex):
                    logger.warning(f"Bot({self.qq}) 今日的@全体成员次数已达到上限")
                    self.__queue.pop(0)
                    self.__at_all_limited = time.localtime(time.time()).tm_yday
                    continue
                elif "LIMITED_MESSAGING" in str(ex):
                    self.__banned = True
                    logger.error(f"消息补发期间再次触发风控, 需人工通过验证码验证")
                    if not config.get("BAN_CONTINUE_SEND_MESSAGE"):
                        logger.warning("已停止尝试消息推送, 后续消息将会被暂存, 请人工通过验证码验证后使用 \"补发\" 命令恢复")
                    if config.get("MASTER_QQ"):
                        notice = "消息补发期间再次触发风控, 请手动通过验证码验证后重新发送 \"补发\" 命令~"
                        await self.__bot.send_friend_message(config.get("MASTER_QQ"), notice)
                    else:
                        logger.warning("未设置主人 QQ, 无法发送提醒消息, 可使用 config.set(\"MASTER_QQ\", QQ号) 进行设置")
                    return
                else:
                    logger.exception("消息推送模块异常", ex)
                    if config.get("MASTER_QQ"):
                        await self.__bot.send_friend_message(config.get("MASTER_QQ"), "补发任务期间出现异常, 详细请查看日志~")
                    return
            except Exception as ex:
                logger.exception("消息推送模块异常", ex)
                if config.get("MASTER_QQ"):
                    await self.__bot.send_friend_message(config.get("MASTER_QQ"), "补发任务期间出现异常, 详细请查看日志~")
                return

        logger.success(f"补发任务已完成")
        if config.get("MASTER_QQ"):
            await self.__bot.send_friend_message(config.get("MASTER_QQ"), "补发任务已完成~")

    async def send_message(self, msg: Message):
        """
        消息发送

        Args:
            msg: Message 实例
        """
        if msg.type == PushType.Friend:
            for message in msg.get_message_chains():
                try:
                    await self.__bot.send_friend_message(msg.id, message)
                    logger.info(f"{self.qq} -> 好友[{msg.id}] : {message.safe_display}")
                except RemoteException as ex:
                    logger.exception("消息推送模块异常", ex)
                    continue
        else:
            msgs, exception = await self.group_message_filter(msg)

            for message in msgs:
                try:
                    if self.__banned and config.get("BAN_RESEND") and not config.get("BAN_CONTINUE_SEND_MESSAGE"):
                        if not config.get("RESEND_AT_MESSAGE"):
                            message = message.exclude(At, AtAll)
                        if len(message) > 0:
                            self.__queue.append((msg.id, message, msg.get_time()))
                        logger.error(f"受风控影响, 要发送的消息已暂存, 请解除风控后使用 \"补发\" 命令恢复, 群号: {msg.id}")
                        continue
                    await self.__bot.send_group_message(msg.id, message)
                    self.__banned = False
                    logger.info(f"{self.qq} -> 群[{msg.id}] : {message.safe_display}")
                except AccountMuted:
                    logger.warning(f"Bot({self.qq}) 在群 {msg.id} 中被禁言")
                    return
                except UnknownTarget:
                    return
                except RemoteException as ex:
                    if "AT_ALL_LIMITED" in str(ex):
                        logger.warning(f"Bot({self.qq}) 今日的@全体成员次数已达到上限")
                        exception = AtAllLimitedException()
                        self.__at_all_limited = time.localtime(time.time()).tm_yday
                        continue
                    elif "LIMITED_MESSAGING" in str(ex):
                        self.__banned = True
                        logger.error(f"受风控影响, 发送群消息失败, 需人工通过验证码验证, 群号: {msg.id}")
                        if config.get("BAN_RESEND"):
                            if not config.get("RESEND_AT_MESSAGE"):
                                message = message.exclude(At, AtAll)
                            if len(message) > 0:
                                self.__queue.append((msg.id, message, msg.get_time()))
                            if not config.get("BAN_CONTINUE_SEND_MESSAGE"):
                                logger.warning("已停止尝试消息推送, 后续消息将会被暂存, 请人工通过验证码验证后使用 \"补发\" 命令恢复")
                        if config.get("MASTER_QQ"):
                            await self.__bot.send_friend_message(config.get("MASTER_QQ"), config.get("BAN_NOTICE"))
                        else:
                            logger.warning("未设置主人 QQ, 无法发送提醒消息, 可使用 config.set(\"MASTER_QQ\", QQ号) 进行设置")
                    else:
                        logger.exception("消息推送模块异常", ex)
                        continue
                except Exception as ex:
                    logger.exception("消息推送模块异常", ex)
                    continue

            if exception is not None and not self.__banned:
                message = ""
                if isinstance(exception, AtAllLimitedException):
                    message = config.get("AT_ALL_LIMITED_MESSAGE")
                elif isinstance(exception, NoPermissionException):
                    message = config.get("NO_PERMISSION_MESSAGE")
                if len(message) > 0:
                    try:
                        await self.__bot.send_group_message(msg.id, MessageChain(message))
                    except Exception:
                        pass

    async def group_message_filter(self, message: Message) -> Tuple[List[MessageChain], Optional[Exception]]:
        """
        过滤群消息中的非法元素

        Args:
            message: 源消息

        Returns:
            处理后的消息链和引发的异常
        """
        exception = None

        if message.type == PushType.Friend:
            return message.get_message_chains(), exception

        new_chains = []

        # 过滤 Bot 不在群内的消息
        group = await self.__bot.get_group(message.id)
        if group is None:
            return new_chains, exception

        for chain in message.get_message_chains():
            if AtAll in chain:
                # 过滤已超出当日次数上限的 @全体成员 消息
                if time.localtime(time.time()).tm_yday == self.__at_all_limited:
                    exception = AtAllLimitedException()
                    chain = chain.exclude(AtAll)

                # 过滤 Bot 不是群管理员时的 @全体成员 消息
                try:
                    bot_info = await self.__bot.get_member(message.id, self.qq)
                except UnknownTarget:
                    return new_chains, exception
                if bot_info.permission < MemberPerm.Administrator:
                    exception = NoPermissionException()
                    chain = chain.exclude(AtAll)

                # 过滤多余的 @全体成员 消息
                if chain.count(AtAll) > 1:
                    elements = [e for e in chain.exclude(AtAll)]
                    elements.insert(chain.index(AtAll), AtAll())
                    chain = MessageChain(elements)

            # 过滤已不在群内的群成员的 @ 消息
            elements = []
            for element in chain.content:
                if isinstance(element, At):
                    try:
                        if await self.__bot.get_member(message.id, element.target):
                            elements.append(element)
                    except UnknownTarget:
                        # 移除开播 @ 列表和动态 @ 列表中的元素
                        logger.debug(f"群成员({element.target})不在群({message.id})内，移除该失效at")
                        if await redis.exists_live_on_at(message.id, element.target):
                            await redis.delete_live_on_at(message.id, element.target)
                        if await redis.exists_dynamic_at(message.id, element.target):
                            await redis.delete_dynamic_at(message.id, element.target)
                else:
                    elements.append(element)

                chain = MessageChain(elements)

            if len(chain) != 0:
                new_chains.append(chain)

        return new_chains, exception

    async def send_to_all_target(self, up: Up, msg: str, target_filter: Callable[[PushTarget], bool] = lambda t: True):
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
                await self.send_message(Message(id=target.id, content=msg, type=target.type))

    async def __send_push_message(self, up: Up,
                                  type_selector: Callable[[PushTarget], Union[LiveOn, LiveOff, DynamicUpdate]],
                                  args: Dict[str, Any]):
        """
        发送推送消息至 UP 主下启用此推送类型的推送目标

        Args:
            up: 要发送的 UP 主实例
            type_selector: 推送类型选择器，如传入 lambda t: t.live_on 代表推送开播推送消息
            args: 占位符参数
        """
        if not isinstance(up, Up):
            return

        logger.debug(f"{up.uname} 已配置推送群: ({', '.join(map(lambda t: str(t.id), up.targets))})")
        for target in up.targets:
            select = type_selector(target)
            if not isinstance(select, (LiveOn, LiveOff, DynamicUpdate)):
                return

            logger.debug(f"群 {target.id}: ({select.enabled}) ({select.message})")
            if select.enabled:
                message = select.message
                for arg, val in args.items():
                    message = message.replace(arg, str(val))
                await self.send_message(Message(id=target.id, content=message, type=target.type))

    async def send_live_on(self, up: Up, args: Dict[str, Any]):
        """
        发送开播消息至 UP 主下启用开播推送的推送目标

        Args:
            up: 要发送的 UP 主实例
            args: 占位符参数
        """
        await self.__send_push_message(up, lambda t: t.live_on, args)

    async def send_live_on_at(self, up: Up):
        """
        发送开播 @ 我列表中的 @ 消息

        Args:
            up: 要发送的 UP 主实例
        """
        if not isinstance(up, Up):
            return

        limited = self.__at_all_limited == time.localtime(time.time()).tm_yday
        for target in filter(lambda t: t.type == PushType.Group, up.targets):
            group = await self.__bot.get_group(target.id)
            if group is None:
                continue
            bot_info = await self.__bot.get_member(target.id, self.qq)
            not_admin = bot_info.permission < MemberPerm.Administrator
            if target.live_on.enabled and (limited or "{atall}" not in target.live_on.message or not_admin):
                ats = " ".join(["{at" + str(x) + "}" for x in await redis.range_live_on_at(target.id)])
                await self.send_message(Message(id=target.id, content=ats, type=target.type))

    async def send_live_off(self, up: Up, args: Dict[str, Any]):
        """
        发送下播消息至 UP 主下启用下播推送的推送目标

        Args:
            up: 要发送的 UP 主实例
            args: 占位符参数
        """
        await self.__send_push_message(up, lambda t: t.live_off, args)

    async def send_live_report(self, up: Up, param: Dict[str, Any]):
        """
        发送直播报告消息至 UP 主下启用直播报告推送的推送目标

        Args:
            up: 要发送的 UP 主实例
            param: 直播报告参数
        """
        for target in filter(lambda t: t.live_report.enabled, up.targets):
            base64str = LiveReportGenerator.generate(param, target.live_report)
            await self.send_message(
                Message(id=target.id, content="".join(["{base64pic=", base64str, "}"]), type=target.type)
            )

    async def send_dynamic_update(self, up: Up, args: Dict[str, Any]):
        """
        发送动态消息至 UP 主下启用动态推送的推送目标

        Args:
            up: 要发送的 UP 主实例
            args: 占位符参数
        """
        try:
            await self.__send_push_message(up, lambda t: t.dynamic_update, args)
        except AtAllLimitedException:
            await self.send_dynamic_at(up, True)

    async def send_dynamic_at(self, up: Up, limited: bool = False):
        """
        发送动态 @ 我列表中的 @ 消息

        Args:
            up: 要发送的 UP 主实例
            limited: 是否为 @全体成员次数达到上限时发送。默认：False
        """
        if not isinstance(up, Up):
            return

        for target in filter(lambda t: t.type == PushType.Group, up.targets):
            if target.dynamic_update.enabled and (limited or "{atall}" not in target.dynamic_update.message):
                ats = " ".join(["{at" + str(x) + "}" for x in await redis.range_dynamic_at(target.id)])
                await self.send_message(Message(id=target.id, content=ats, type=target.type))

    def __eq__(self, other):
        if isinstance(other, Bot):
            return self.qq == other.qq
        elif isinstance(other, int):
            return self.qq == other
        return False

    def __hash__(self):
        return hash(self.qq)
