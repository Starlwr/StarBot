import asyncio
import time
import typing
from asyncio import AbstractEventLoop
from typing import Optional, List, Any

from loguru import logger
from pydantic import BaseModel, PrivateAttr

from .live import LiveDanmaku, LiveRoom
from .model import PushTarget
from .user import User
from ..exception import LiveException
from ..utils import config, redis
from ..utils.utils import get_credential

if typing.TYPE_CHECKING:
    from .sender import Bot


class Up(BaseModel):
    """
    主播类
    """

    uid: int
    """主播 UID"""

    targets: List[PushTarget]
    """主播所需推送的所有好友或群"""

    uname: Optional[str] = None
    """主播昵称，无需手动传入，会自动获取"""

    room_id: Optional[int] = None
    """主播直播间房间号，无需手动传入，会自动获取"""

    __room: Optional[LiveDanmaku] = PrivateAttr()
    """直播间连接实例"""

    __is_reconnect: Optional[bool] = PrivateAttr()
    """是否为重新连接直播间"""

    __loop: Optional[AbstractEventLoop] = PrivateAttr()
    """asyncio 事件循环"""

    __bot: Optional["Bot"] = PrivateAttr()
    """主播所关联 Bot 实例"""

    def __init__(self, **data: Any):
        super().__init__(**data)
        self.__room = None
        self.__is_reconnect = False
        self.__loop = asyncio.get_event_loop()
        self.__bot = None

    def inject_bot(self, bot):
        self.__bot = bot

    def __any_live_on_enabled(self):
        return any(map(lambda conf: conf.enabled, map(lambda group: group.live_on, self.targets)))

    def __any_live_off_enabled(self):
        return any(map(lambda conf: conf.enabled, map(lambda group: group.live_off, self.targets)))

    def __any_live_report_enabled(self):
        return any(map(lambda conf: conf.enabled, map(lambda group: group.live_report, self.targets)))

    async def connect(self):
        """
        连接直播间
        """
        if not all([self.uname, self.room_id]):
            user = User(self.uid, get_credential())
            user_info = await user.get_user_info()
            self.uname = user_info["name"]
            if user_info["live_room"] is None:
                raise LiveException(f"UP 主 {self.uname} ( UID: {self.uid} ) 还未开通直播间")
            self.room_id = user_info["live_room"]["roomid"]
        self.__room = LiveDanmaku(self.room_id, credential=get_credential())

        # 开播推送开关和下播推送开关均处于关闭状态时跳过连接直播间，以节省性能
        if config.get("ONLY_CONNECT_NECESSARY_ROOM"):
            if not any([self.__any_live_on_enabled(), self.__any_live_off_enabled(), self.__any_live_report_enabled()]):
                logger.warning(f"{self.uname} 的开播, 下播和直播报告开关均处于关闭状态, 跳过连接直播间")
                return

        logger.opt(colors=True).info(f"准备连接到 <cyan>{self.uname}</> 的直播间 <cyan>{self.room_id}</>")

        self.__loop.create_task(self.__room.connect())

        @self.__room.on("VERIFICATION_SUCCESSFUL")
        async def on_link(event):
            logger.debug(f"{self.uname} (VERIFICATION_SUCCESSFUL): {event}")

            if self.__is_reconnect:
                logger.success(f"直播间 {self.room_id} 重新连接成功")

                live_room = LiveRoom(self.room_id, get_credential())
                room_info = await live_room.get_room_play_info()
                last_status = await redis.hgeti("LiveStatus", self.room_id)
                now_status = room_info["live_status"]

                if now_status != last_status:
                    await redis.hset("LiveStatus", self.room_id, now_status)
                    if now_status == 1:
                        logger.warning(f"直播间 {self.room_id} 断线期间开播")
                        param = {
                            "data": {
                                "live_time": 0
                            }
                        }
                        await live_on(param)
                    if last_status == 1:
                        logger.warning(f"直播间 {self.room_id} 断线期间下播")
                        param = {}
                        await live_off(param)
            else:
                self.__is_reconnect = True

            logger.success(f"已成功连接到 {self.uname} 的直播间 {self.room_id}")

        @self.__room.on("LIVE")
        async def live_on(event):
            logger.debug(f"{self.uname} (LIVE): {event}")

            # 是否为真正开播
            if "live_time" in event["data"]:
                live_room = LiveRoom(self.room_id, get_credential())
                room_info = await live_room.get_room_info()
                self.uname = room_info["anchor_info"]["base_info"]["uname"]

                await redis.hset("LiveStatus", self.room_id, 1)

                # 是否为主播网络波动断线重连
                now = int(time.time())
                last = await redis.hgeti("EndTime", self.room_id)
                is_reconnect = (now - last) <= config.get("UP_DISCONNECT_CONNECT_INTERVAL")
                if is_reconnect:
                    logger.opt(colors=True).info(f"<magenta>[断线重连] {self.uname} ({self.room_id})</>")
                    if config.get("UP_DISCONNECT_CONNECT_MESSAGE"):
                        self.__bot.send_to_all_target(self, config.get("UP_DISCONNECT_CONNECT_MESSAGE"),
                                                      lambda t: t.live_on.enabled)
                else:
                    logger.opt(colors=True).info(f"<magenta>[开播] {self.uname} ({self.room_id})</>")
                    await redis.hset("StartTime", self.room_id, room_info["room_info"]["live_start_time"])

                    await self.__accumulate_data()
                    await self.__reset_data()

                    # 推送消息
                    arg_base = room_info["room_info"]
                    args = {
                        "{uname}": self.uname,
                        "{title}": arg_base["title"],
                        "{url}": f"https://live.bilibili.com/{self.room_id}",
                        "{cover}": "".join(["{urlpic=", arg_base["cover"], "}"])
                    }
                    self.__bot.send_live_on(self, args)

        @self.__room.on("PREPARING")
        async def live_off(event):
            logger.debug(f"{self.uname} (PREPARING): {event}")

            await redis.hset("LiveStatus", self.room_id, 0)
            await redis.hset("EndTime", self.room_id, int(time.time()))

            logger.opt(colors=True).info(f"<magenta>[下播] {self.uname} ({self.room_id})</>")

            # 推送消息
            args = {
                "{uname}": self.uname
            }
            self.__bot.send_live_off(self, args)

    async def __accumulate_data(self):
        """
        累计直播间数据
        """

        # 盲盒记录，用于统计击败直播间百分比
        if await redis.hgeti("RoomBoxCount", self.room_id) > 0:
            await redis.rpush("BoxProfit", await redis.hgetf1("RoomBoxProfit", self.room_id))

        # 累计弹幕数
        await redis.hincrby("RoomDanmuTotal", self.room_id, await redis.hgeti("RoomDanmuCount", self.room_id))
        await redis.zunionstore(f"UserDanmuTotal:{self.room_id}", f"UserDanmuCount:{self.room_id}")

        # 累计盲盒数
        await redis.hincrby("RoomBoxTotal", self.room_id, await redis.hgeti("RoomBoxCount", self.room_id))
        await redis.zunionstore(f"UserBoxTotal:{self.room_id}", f"UserBoxCount:{self.room_id}")

        # 累计盲盒盈亏
        await redis.hincrbyfloat("RoomBoxProfitTotal", self.room_id, await redis.hgetf1("RoomBoxProfit", self.room_id))
        await redis.zunionstore(f"UserBoxProfitTotal:{self.room_id}", f"UserBoxProfit:{self.room_id}")

        # 累计礼物收益
        await redis.hincrbyfloat("RoomGiftTotal", self.room_id, await redis.hgetf1("RoomGiftProfit", self.room_id))
        await redis.zunionstore(f"UserGiftTotal:{self.room_id}", f"UserGiftProfit:{self.room_id}")

        # 累计 SC 收益
        await redis.hincrby("RoomScTotal", self.room_id, await redis.hgeti("RoomScProfit", self.room_id))
        await redis.zunionstore(f"UserScTotal:{self.room_id}", f"UserScProfit:{self.room_id}")

        # 累计舰长数
        await redis.hincrby("RoomCaptainTotal", self.room_id, await redis.hgeti("RoomCaptainCount", self.room_id))
        await redis.zunionstore(f"UserCaptainTotal:{self.room_id}", f"UserCaptainCount:{self.room_id}")

        # 累计提督数
        await redis.hincrby("RoomCommanderTotal", self.room_id, await redis.hgeti("RoomCommanderCount", self.room_id))
        await redis.zunionstore(f"UserCommanderTotal:{self.room_id}", f"UserCommanderCount:{self.room_id}")

        # 累计总督数
        await redis.hincrby("RoomGovernorTotal", self.room_id, await redis.hgeti("RoomGovernorCount", self.room_id))
        await redis.zunionstore(f"UserGovernorTotal:{self.room_id}", f"UserGovernorCount:{self.room_id}")

    async def __reset_data(self):
        """
        重置直播间数据
        """

        # 清空弹幕记录
        await redis.delete(f"RoomDanmu:{self.room_id}")

        # 重置弹幕数
        await redis.hset(f"RoomDanmuCount", self.room_id, 0)
        await redis.delete(f"UserDanmuCount:{self.room_id}")

        # 重置盲盒数
        await redis.hset(f"RoomBoxCount", self.room_id, 0)
        await redis.delete(f"UserBoxCount:{self.room_id}")

        # 重置盲盒盈亏
        await redis.hset(f"RoomBoxProfit", self.room_id, 0)
        await redis.delete(f"UserBoxProfit:{self.room_id}")

        # 重置礼物收益
        await redis.hset(f"RoomGiftProfit", self.room_id, 0)
        await redis.delete(f"UserGiftProfit:{self.room_id}")

        # 重置 SC 收益
        await redis.hset(f"RoomScProfit", self.room_id, 0)
        await redis.delete(f"UserScProfit:{self.room_id}")

        # 重置舰长数
        await redis.hset(f"RoomCaptainCount", self.room_id, 0)
        await redis.delete(f"UserCaptainCount:{self.room_id}")

        # 重置提督数
        await redis.hset(f"RoomCommanderCount", self.room_id, 0)
        await redis.delete(f"UserCommanderCount:{self.room_id}")

        # 重置总督数
        await redis.hset(f"RoomGovernorCount", self.room_id, 0)
        await redis.delete(f"UserGovernorCount:{self.room_id}")

    def __eq__(self, other):
        if isinstance(other, Up):
            return self.uid == other.uid
        elif isinstance(other, int):
            return self.uid == other
        return False

    def __hash__(self):
        return hash(self.uid)
