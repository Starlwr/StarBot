import asyncio
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
    """是否为断线重连"""

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

    def any_live_on_enabled(self):
        return any(map(lambda conf: conf.enabled, map(lambda group: group.live_on, self.targets)))

    def any_live_off_enabled(self):
        return any(map(lambda conf: conf.enabled, map(lambda group: group.live_off, self.targets)))

    def any_live_report_enabled(self):
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
                raise LiveException(f"UP 主 {self.uname} ( UID: {self.uid} ) 还未开通直播间~")
            self.room_id = user_info["live_room"]["roomid"]
        self.__room = LiveDanmaku(self.room_id, credential=get_credential())

        # 开播推送开关和下播推送开关均处于关闭状态时跳过连接直播间，以节省性能
        if config.get("ONLY_CONNECT_NECESSARY_ROOM"):
            if not any([self.any_live_on_enabled(), self.any_live_off_enabled(), self.any_live_report_enabled()]):
                logger.warning(f"{self.uname} 的开播, 下播和直播报告开关均处于关闭状态, 跳过连接直播间")
                return

        logger.opt(colors=True).info(f"准备连接到 <cyan>{self.uname}</> 的直播间 <cyan>{self.room_id}</>")

        self.__loop.create_task(self.__room.connect())

        @self.__room.on("VERIFICATION_SUCCESSFUL")
        async def on_link(event):
            if self.__is_reconnect:
                logger.success(f"直播间 {self.room_id} 断线重连成功")

                live_room = LiveRoom(self.room_id, get_credential())
                room_info = await live_room.get_room_play_info()
                last_status = await redis.hgeti("LiveStatus", self.room_id)
                now_status = room_info["live_status"]

                if now_status != last_status:
                    await redis.hset("LiveStatus", self.room_id, now_status)
                    if last_status == 1:
                        logger.warning(f"直播间 {self.room_id} 断线期间下播")
                        pass
                    if now_status == 1:
                        logger.warning(f"直播间 {self.room_id} 断线期间开播")
                        pass
            else:
                self.__is_reconnect = True

            logger.success(f"已成功连接到 {self.uname} 的直播间 {self.room_id}")

    def __eq__(self, other):
        if isinstance(other, Up):
            return self.uid == other.uid
        elif isinstance(other, int):
            return self.uid == other
        return False

    def __hash__(self):
        return hash(self.uid)
