import asyncio
import typing
from asyncio import AbstractEventLoop
from typing import Optional, Union, List, Dict, Any

from pydantic import BaseModel, PrivateAttr

from .live import LiveDanmaku
from .model import PushTarget

if typing.TYPE_CHECKING:
    from .sender import Bot


class Up(BaseModel):
    """
    主播类
    """

    uid: int
    """主播 UID"""

    targets: Union[List[PushTarget], Dict[int, PushTarget]]
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
        if isinstance(self.targets, list):
            self.targets = dict(zip(map(lambda t: t.id, self.targets), self.targets))
        self.__room = None
        self.__is_reconnect = False
        self.__loop = asyncio.get_event_loop()
        self.__bot = None

    def inject_bot(self, bot):
        self.__bot = bot

    def __eq__(self, other):
        if isinstance(other, Up):
            return self.uid == other.uid
        elif isinstance(other, int):
            return self.uid == other
        return False

    def __hash__(self):
        return hash(self.uid)
