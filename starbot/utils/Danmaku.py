"""
弹幕类
"""

import time
from enum import Enum


class FontSize(Enum):
    """
    字体大小枚举
    """
    EXTREME_SMALL = 12
    SUPER_SMALL = 16
    SMALL = 18
    NORMAL = 25
    BIG = 36
    SUPER_BIG = 45
    EXTREME_BIG = 64


class Mode(Enum):
    """
    弹幕模式枚举
    """
    FLY = 1
    TOP = 5
    BOTTOM = 4
    REVERSE = 6


class Danmaku:
    """
    弹幕类
    """

    def __init__(self,
                 text: str,
                 dm_time: float = 0.0,
                 send_time: float = time.time(),
                 crc32_id: str = None,
                 color: str = 'ffffff',
                 weight: int = -1,
                 id_: int = -1,
                 id_str: str = "",
                 action: str = "",
                 mode: Mode = Mode.FLY,
                 font_size: FontSize = FontSize.NORMAL,
                 is_sub: bool = False,
                 pool: int = -1,
                 attr: int = -1):
        """
        Args:
            text: 弹幕文本
            dm_time: 弹幕在视频中的位置，单位为秒。默认：0.0
            send_time: 弹幕发送的时间。默认：time.time()
            crc32_id: 弹幕发送者 UID 经 CRC32 算法取摘要后的值。默认：None
            color: 弹幕十六进制颜色。默认："ffffff"
            weight: 弹幕在弹幕列表显示的权重。默认：-1
            id_: 弹幕 ID。默认：-1
            id_str: 弹幕字符串 ID。默认：""
            action: 暂不清楚。默认：""
            mode: 弹幕模式。默认：Mode.FLY
            font_size: 弹幕字体大小。默认：FontSize.NORMAL
            is_sub: 是否为字幕弹幕。默认：False
            pool: 暂不清楚。默认：-1
            attr: 暂不清楚。默认：-1
        """
        self.text = text
        self.dm_time = dm_time
        self.send_time = send_time
        self.crc32_id = crc32_id
        self.color = color
        self.weight = weight
        self.id = id_
        self.id_str = id_str
        self.action = action
        self.mode = mode
        self.font_size = font_size
        self.is_sub = is_sub
        self.pool = pool
        self.attr = attr

        self.uid = None

    def __str__(self):
        ret = "%s, %s, %s" % (self.send_time, self.dm_time, self.text)
        return ret

    def __len__(self):
        return len(self.text)
