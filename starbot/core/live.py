"""
由 bilibili-api 二次开发
源仓库: https://github.com/MoyuScript/bilibili-api
"""

import asyncio
import base64
import json
import random
import struct
import time
from enum import Enum
from typing import List

import aiohttp
import brotli
from aiohttp.client_ws import ClientWebSocketResponse
from loguru import logger

from ..exception.LiveException import LiveException
from ..utils import config
from ..utils.AsyncEvent import AsyncEvent
from ..utils.Credential import Credential
from ..utils.Danmaku import Danmaku
from ..utils.network import get_session, request
from ..utils.utils import get_api, get_credential

API = get_api("live")


class ScreenResolution(Enum):
    """
    直播源清晰度

    清晰度编号，4K 20000，原画 10000，蓝光（杜比）401，蓝光 400，超清 250，高清 150，流畅 80
    + FOUR_K        : 4K
    + ORIGINAL      : 原画
    + BLU_RAY_DOLBY : 蓝光（杜比）
    + BLU_RAY       : 蓝光
    + ULTRA_HD      : 超清
    + HD            : 高清
    + FLUENCY       : 流畅
    """
    FOUR_K = 20000
    ORIGINAL = 10000
    BLU_RAY_DOLBY = 401
    BLU_RAY = 400
    ULTRA_HD = 250
    HD = 150
    FLUENCY = 80


class LiveProtocol(Enum):
    """
    直播源流协议

    流协议，0 为 FLV 流，1 为 HLS 流。默认：0,1
    + FLV     : 0
    + HLS     : 1
    + DEFAULT : 0,1
    """
    FLV = 0
    HLS = 1
    DEFAULT = '0,1'


class LiveFormat(Enum):
    """
    直播源容器格式

    容器格式，0 为 flv 格式；1 为 ts 格式（仅限 hls 流）；2 为 fmp4 格式（仅限 hls 流）。默认：0,1,2
    + FLV     : 0
    + TS      : 1
    + FMP4    : 2
    + DEFAULT : 2
    """
    FLV = 0
    TS = 1
    FMP4 = 2
    DEFAULT = '0,1,2'


class LiveCodec(Enum):
    """
    直播源视频编码

    视频编码，0 为 avc 编码，1 为 hevc 编码。默认：0,1
    + AVC     : 0
    + HEVC    : 1
    + DEFAULT : 0,1
    """
    AVC = 0
    HEVC = 1
    DEFAULT = '0,1'


class LiveRoom:
    """
    直播类，获取各种直播间的操作均在里边
    """

    def __init__(self, room_display_id: int, credential: Credential = None):
        """
        Args:
            room_display_id: 房间展示 ID（即 URL 中的 ID）
            credential: 凭据。默认：None
        """
        self.room_display_id = room_display_id

        if credential is None:
            self.credential = Credential()
        else:
            self.credential = credential

        self.__ruid = None

    async def get_room_play_info(self):
        """
        获取房间信息（真实房间号，封禁情况等）
        """
        api = API["info"]["room_play_info"]
        params = {
            "room_id": self.room_display_id,
        }
        resp = await request(api['method'], api['url'], params=params, credential=self.credential)

        # 缓存真实房间 ID
        self.__ruid = resp['uid']
        return resp

    async def __get_ruid(self):
        """
        获取真实房间 ID，若有缓存则使用缓存
        """
        if self.__ruid is None:
            await self.get_room_play_info()

        return self.__ruid

    async def get_chat_conf(self):
        """
        获取聊天弹幕服务器配置信息(websocket)
        """
        api = API["info"]["chat_conf"]
        params = {
            "room_id": self.room_display_id
        }
        return await request(api['method'], api["url"], params, credential=self.credential)

    async def get_chat_conf_new(self):
        """
        获取新版聊天弹幕服务器配置信息(websocket)
        """
        api = API["info"]["chat_conf_new"]
        params = {
            "id": self.room_display_id
        }
        return await request(api['method'], api["url"], params, credential=self.credential)

    async def get_room_info(self):
        """
        获取直播间信息（标题，简介等）
        """
        api = API["info"]["room_info"]
        params = {
            "room_id": self.room_display_id
        }
        return await request(api['method'], api["url"], params, credential=self.credential)

    async def get_room_info_v2(self):
        """
        获取直播间信息（标题，简介等）
        """
        api = "https://api.live.bilibili.com/room/v1/Room/get_info"
        params = {
            "room_id": self.room_display_id
        }
        return await request("GET", api, params, credential=self.credential)

    async def get_fans_medal_info(self, uid: int):
        """
        获取粉丝勋章信息

        Args:
            uid: 用户 UID
        """
        api = "https://api.live.bilibili.com/xlive/app-ucenter/v1/fansMedal/fans_medal_info"
        params = {
            "target_id": uid,
            "room_id": self.room_display_id
        }
        return await request("GET", api, params, credential=self.credential)

    async def get_guards_info(self, uid: int):
        """
        获取大航海信息

        Args:
            uid: 用户 UID
        """
        api = "https://api.live.bilibili.com/xlive/app-room/v2/guardTab/topListNew"
        params = {
            "roomid": self.room_display_id,
            "page": 1,
            "ruid": uid,
            "page_size": 20,
            "typ": 5,
            "platform": "web"
        }
        return await request("GET", api, params, credential=self.credential)

    async def get_user_info_in_room(self):
        """
        获取自己在直播间的信息（粉丝勋章等级，直播用户等级等）
        """
        self.credential.raise_for_no_sessdata()

        api = API["info"]["user_info_in_room"]
        params = {
            "room_id": self.room_display_id
        }
        return await request(api['method'], api["url"], params, credential=self.credential)

    async def get_dahanghai(self, page: int = 1):
        """
        获取大航海列表

        Args:
            page: 页码。默认：1
        """
        api = API["info"]["dahanghai"]
        params = {
            "roomid": self.room_display_id,
            "ruid": await self.__get_ruid(),
            "page_size": 30,
            "page": page
        }
        return await request(api['method'], api["url"], params, credential=self.credential)

    async def get_gaonengbang(self, page: int = 1):
        """
        获取高能榜列表

        Args:
            page: 页码。默认：1
        """
        api = API["info"]["gaonengbang"]
        params = {
            "roomId": self.room_display_id,
            "ruid": await self.__get_ruid(),
            "pageSize": 50,
            "page": page
        }
        return await request(api['method'], api["url"], params, credential=self.credential)

    async def get_seven_rank(self):
        """
        获取七日榜
        """
        api = API["info"]["seven_rank"]
        params = {
            "roomid": self.room_display_id,
            "ruid": await self.__get_ruid(),
        }
        return await request(api['method'], api["url"], params, credential=self.credential)

    async def get_fans_medal_rank(self):
        """
        获取粉丝勋章排行
        """
        api = API["info"]["fans_medal_rank"]
        params = {
            "roomid": self.room_display_id,
            "ruid": await self.__get_ruid()
        }
        return await request(api['method'], api["url"], params, credential=self.credential)

    async def get_black_list(self):
        """
        获取黑名单列表
        """
        api = API["info"]["black_list"]
        params = {
            "room_id": self.room_display_id,
            "ps": 1
        }

        return await request(api['method'], api["url"], params, credential=self.credential)

    async def get_room_play_url(self, screen_resolution: ScreenResolution = ScreenResolution.ORIGINAL):
        """
        获取房间直播流列表

        Args:
            screen_resolution: 清晰度。默认：ScreenResolution.ORIGINAL
        """
        api = API["info"]["room_play_url"]
        params = {
            "cid": self.room_display_id,
            "platform": "web",
            "qn": screen_resolution.value,
            "https_url_req": "1",
            "ptype": "16"
        }
        return await request(api['method'], api["url"], params, credential=self.credential)

    async def get_room_play_info_v2(self, live_protocol: LiveProtocol = LiveProtocol.DEFAULT,
                                    live_format: LiveFormat = LiveFormat.DEFAULT,
                                    live_codec: LiveCodec = LiveCodec.DEFAULT,
                                    live_qn: ScreenResolution = ScreenResolution.ORIGINAL):
        """
        获取房间信息及可用清晰度列表

        Args:
            live_protocol: 直播源流协议。默认：LiveProtocol.DEFAULT
            live_format: 直播源容器格式。默认：LiveFormat.DEFAULT
            live_codec: 直播源视频编码。默认：LiveCodec.DEFAULT
            live_qn: 直播源清晰度。默认：ScreenResolution.ORIGINAL
        """
        api = API["info"]["room_play_info_v2"]
        params = {
            "room_id": self.room_display_id,
            "platform": "web",
            "ptype": "16",
            "protocol": live_protocol.value,
            "format": live_format.value,
            "codec": live_codec.value,
            "qn": live_qn.value
        }
        return await request(api['method'], api['url'], params=params, credential=self.credential)

    async def ban_user(self, uid: int):
        """
        封禁用户

        Args:
            uid: 用户 UID
        """
        self.credential.raise_for_no_sessdata()

        api = API["operate"]["add_block"]
        data = {
            "room_id": self.room_display_id,
            "tuid": uid,
            "mobile_app": "web",
            "visit_id": ""
        }
        return await request(api['method'], api["url"], data=data, credential=self.credential)

    async def unban_user(self, block_id: int):
        """
        解封用户

        Args:
            block_id: 封禁用户时会返回该封禁事件的 ID，使用该值
        """
        self.credential.raise_for_no_sessdata()
        api = API["operate"]["del_block"]
        data = {
            "roomid": self.room_display_id,
            "id": block_id,
            "visit_id": "",
        }
        return await request(api['method'], api["url"], data=data, credential=self.credential)

    async def send_danmaku(self, danmaku: Danmaku):
        """
        直播间发送弹幕

        Args:
            danmaku: 弹幕类 Danmaku 实例
        """
        self.credential.raise_for_no_sessdata()

        api = API["operate"]["send_danmaku"]
        data = {
            "mode": danmaku.mode.value,
            "msg": danmaku.text,
            "roomid": self.room_display_id,
            "bubble": 0,
            "rnd": int(time.time()),
            "color": int(danmaku.color, 16),
            "fontsize": danmaku.font_size.value
        }
        return await request(api['method'], api["url"], data=data, credential=self.credential)

    async def sign_up_dahanghai(self, task_id: int = 1447):
        """
        大航海签到

        Args:
            task_id: 签到任务 ID。默认：1447
        """
        self.credential.raise_for_no_sessdata()
        self.credential.raise_for_no_bili_jct()

        api = API["operate"]["sign_up_dahanghai"]
        data = {
            "task_id": task_id,
            "uid": await self.__get_ruid(),
        }
        return await request(api['method'], api["url"], data=data, credential=self.credential)

    async def send_gift_from_bag(self,
                                 uid: int,
                                 bag_id: int,
                                 gift_id: int,
                                 gift_num: int,
                                 storm_beat_id: int = 0,
                                 price: int = 0):
        """
        赠送包裹中的礼物，获取包裹信息可以使用 get_self_bag 方法

        Args:
            uid: 赠送用户的 UID
            bag_id: 礼物背包 ID
            gift_id: 礼物 ID
            gift_num: 礼物数量
            storm_beat_id: 未知。默认：0
            price: 礼物单价。默认：0
        """
        self.credential.raise_for_no_sessdata()
        self.credential.raise_for_no_bili_jct()

        api = API["operate"]["send_gift_from_bag"]
        data = {
            "uid": uid,
            "bag_id": bag_id,
            "gift_id": gift_id,
            "gift_num": gift_num,
            "platform": "pc",
            "send_ruid": 0,
            "storm_beat_id": storm_beat_id,
            "price": price,
            "biz_code": "live",
            "biz_id": self.room_display_id,
            "ruid": await self.__get_ruid(),

        }
        return await request(api['method'], api["url"], data=data, credential=self.credential)

    async def receive_reward(self, receive_type: int = 2):
        """
        领取自己在某个直播间的航海日志奖励

        Args:
            receive_type: 领取类型。默认：2
        """
        self.credential.raise_for_no_sessdata()

        api = API["operate"]["receive_reward"]
        data = {
            "ruid": await self.__get_ruid(),
            "receive_type": receive_type,
        }
        return await request(api['method'], api["url"], data=data, credential=self.credential)

    async def get_general_info(self, act_id: int = 100061):
        """
        获取自己在该房间的大航海信息, 比如是否开通, 等级等

        Args:
            act_id: 未知。默认：100061
        """
        self.credential.raise_for_no_sessdata()

        api = API["info"]["general_info"]
        params = {
            "actId": act_id,
            "roomId": self.room_display_id,
            "uid": await self.__get_ruid()
        }
        return await request(api['method'], api["url"], params=params, credential=self.credential)

    async def get_gift_common(self):
        """
        获取当前直播间内的普通礼物列表
        """
        api_room_info = API["info"]["room_info"]
        params_room_info = {
            "room_id": self.room_display_id,
        }
        res_room_info = await request(api_room_info['method'], api_room_info["url"],
                                      params=params_room_info,
                                      credential=self.credential)
        area_id = res_room_info["room_info"]["area_id"]
        area_parent_id = res_room_info["room_info"]["parent_area_id"]

        api = API["info"]["gift_common"]
        params = {
            "room_id": self.room_display_id,
            "area_id": area_id,
            "area_parent_id": area_parent_id,
            "platform": "pc",
            "source": "live"
        }
        return await request(api['method'], api["url"], params=params, credential=self.credential)

    async def get_gift_special(self, tab_id: int):
        """
        获取当前直播间内的特殊礼物列表

        Args:
            tab_id: 2：特权礼物，3：定制礼物
        """
        api_room_info = API["info"]["room_info"]
        params_room_info = {
            "room_id": self.room_display_id,
        }
        res_room_info = await request(api_room_info['method'], api_room_info["url"],
                                      params=params_room_info,
                                      credential=self.credential)
        area_id = res_room_info["room_info"]["area_id"]
        area_parent_id = res_room_info["room_info"]["parent_area_id"]

        api = API["info"]["gift_special"]

        params = {
            "tab_id": tab_id,
            "area_id": area_id,
            "area_parent_id": area_parent_id,
            "room_id": await self.__get_ruid(),
            "source": "live",
            "platform": "pc",
            "build": 1
        }
        return await request(api['method'], api["url"], params=params, credential=self.credential)

    async def send_gift_gold(self,
                             uid: int,
                             gift_id: int,
                             gift_num: int,
                             price: int,
                             storm_beat_id: int = 0):
        """
        赠送金瓜子礼物

        Args:
            uid: 赠送用户的 UID
            gift_id: 礼物 ID (可以通过 get_gift_common 或 get_gift_special 或 get_gift_config 获取)
            gift_num: 赠送礼物数量
            price: 礼物单价
            storm_beat_id: 未知。默认：0
        """
        self.credential.raise_for_no_sessdata()
        self.credential.raise_for_no_bili_jct()

        api = API["operate"]["send_gift_gold"]
        data = {
            "uid": uid,
            "gift_id": gift_id,
            "gift_num": gift_num,
            "price": price,
            "ruid": await self.__get_ruid(),
            "biz_code": "live",
            "biz_id": self.room_display_id,
            "platform": "pc",
            "storm_beat_id": storm_beat_id,
            "send_ruid": 0,
            "coin_type": "gold",
            "bag_id": "0",
            "rnd": int(time.time()),
            "visit_id": ""
        }
        return await request(api['method'], api["url"], data=data, credential=self.credential)

    async def send_gift_silver(self,
                               uid: int,
                               gift_id: int,
                               gift_num: int,
                               price: int,
                               storm_beat_id: int = 0, ):
        """
        赠送银瓜子礼物

        Args:
            uid: 赠送用户的 UID
            gift_id: 礼物 ID (可以通过 get_gift_common 或 get_gift_special 或 get_gift_config 获取)
            gift_num: 赠送礼物数量
            price: 礼物单价
            storm_beat_id: 未知。默认：0
        """
        self.credential.raise_for_no_sessdata()
        self.credential.raise_for_no_bili_jct()

        api = API["operate"]["send_gift_silver"]
        data = {
            "uid": uid,
            "gift_id": gift_id,
            "gift_num": gift_num,
            "price": price,
            "ruid": await self.__get_ruid(),
            "biz_code": "live",
            "biz_id": self.room_display_id,
            "platform": "pc",
            "storm_beat_id": storm_beat_id,
            "send_ruid": 0,
            "coin_type": "silver",
            "bag_id": 0,
            "rnd": int(time.time()),
            "visit_id": ""
        }
        return await request(api['method'], api["url"], data=data, credential=self.credential)


class LiveDanmaku(AsyncEvent):
    """
    Websocket 实时获取直播弹幕

    Events：
    + DANMU_MSG: 用户发送弹幕
    + SEND_GIFT: 礼物
    + COMBO_SEND：礼物连击
    + GUARD_BUY：续费大航海
    + SUPER_CHAT_MESSAGE：醒目留言（SC）
    + SUPER_CHAT_MESSAGE_JPN：醒目留言（带日语翻译？）
    + WELCOME: 老爷进入房间
    + WELCOME_GUARD: 房管进入房间
    + NOTICE_MSG: 系统通知（全频道广播之类的）
    + PREPARING: 直播准备中
    + LIVE: 直播开始
    + ROOM_REAL_TIME_MESSAGE_UPDATE: 粉丝数等更新
    + ENTRY_EFFECT: 进场特效
    + ROOM_RANK: 房间排名更新
    + INTERACT_WORD: 用户进入直播间
    + ACTIVITY_BANNER_UPDATE_V2: 好像是房间名旁边那个 xx 小时榜
    + WATCHED_CHANGE: 直播间 xx 人看过
    + ===========================
    + 本模块自定义事件：
    + ==========================
    + VIEW: 直播间人气更新
    + ALL: 所有事件
    + DISCONNECT: 断开连接（传入连接状态码参数）
    + TIMEOUT: 心跳响应超时
    + VERIFICATION_SUCCESSFUL: 认证成功
    """
    PROTOCOL_VERSION_RAW_JSON = 0
    PROTOCOL_VERSION_HEARTBEAT = 1
    PROTOCOL_VERSION_BROTLI_JSON = 3

    DATAPACK_TYPE_HEARTBEAT = 2
    DATAPACK_TYPE_HEARTBEAT_RESPONSE = 3
    DATAPACK_TYPE_NOTICE = 5
    DATAPACK_TYPE_VERIFY = 7
    DATAPACK_TYPE_VERIFY_SUCCESS_RESPONSE = 8

    STATUS_INIT = 0
    STATUS_CONNECTING = 1
    STATUS_ESTABLISHED = 2
    STATUS_CLOSING = 3
    STATUS_CLOSED = 4
    STATUS_ERROR = 5

    def __init__(self, room_display_id: int, credential: Credential = None, retry_after: float = 1):
        """
        Args:
            room_display_id: 房间展示 ID
            credential: 凭据。默认：None
            retry_after: 连接出错后重试间隔时间（秒）。默认：1
        """
        super().__init__()

        self.credential = credential if credential is not None else Credential()
        self.room_display_id = room_display_id
        self.live_time = 0
        self.retry_after = retry_after
        self.__uid = config.get("LOGIN_UID")
        self.__room_real_id = None
        self.__status = 0
        self.__ws = None
        self.__tasks = []
        self.__heartbeat_timer = 60.0
        self.err_reason = ""

    def get_status(self) -> int:
        """
        获取连接状态

        Returns:
            0 初始化，1 连接建立中，2 已连接，3 断开连接中，4 已断开，5 错误
        """
        return self.__status

    async def connect(self):
        """
        连接直播间
        """
        if self.get_status() == self.STATUS_CONNECTING:
            raise LiveException('正在建立连接中')

        if self.get_status() == self.STATUS_ESTABLISHED:
            raise LiveException('连接已建立, 不可重复调用')

        if self.get_status() == self.STATUS_CLOSING:
            raise LiveException('正在关闭连接, 不可调用')

        await self.__main()

    async def disconnect(self):
        """
        断开连接
        """
        if self.get_status() != self.STATUS_ESTABLISHED:
            raise LiveException('尚未连接服务器')

        self.__status = self.STATUS_CLOSING
        logger.debug(f'正在关闭直播间 {self.room_display_id} 的连接')

        # 取消所有任务
        while len(self.__tasks) > 0:
            self.__tasks.pop().cancel()

        self.__status = self.STATUS_CLOSED
        await self.__ws.close()

        logger.debug(f'直播间 {self.room_display_id} 的连接已关闭')

    async def __main(self):
        """
        入口
        """
        self.__status = self.STATUS_CONNECTING

        room = LiveRoom(self.room_display_id, self.credential)
        # 获取真实房间号和开播时间
        logger.debug(f"正在获取直播间 {self.room_display_id} 的真实房间号")
        info = await room.get_room_play_info()
        self.__room_real_id = info["room_id"]
        self.live_time = info["live_time"]
        logger.debug(f"获取成功, 真实房间号: {self.__room_real_id}")

        # 获取直播服务器配置
        logger.debug(f"正在获取直播间 {self.room_display_id} 的聊天服务器配置")
        conf = await room.get_chat_conf_new()
        logger.debug(f"直播间 {self.room_display_id} 的聊天服务器配置获取成功")

        # 连接直播间
        logger.debug(f"开始连接直播间 {self.room_display_id}")
        session = get_session()
        available_hosts: List[dict] = conf["host_list"]
        host = None

        @self.on('TIMEOUT')
        async def on_timeout(event):
            """
            连接超时
            """
            self.err_reason = '心跳响应超时'
            await self.__ws.close()

        while True:
            self.err_reason = ''
            # 重置心跳计时器
            self.__heartbeat_timer = 0
            if not available_hosts:
                self.err_reason = '已尝试所有主机但仍无法连接'
                break

            if host is None:
                host = available_hosts.pop()

            port = host['wss_port']
            protocol = "wss"
            uri = f"{protocol}://{host['host']}:{port}/sub"
            self.__status = self.STATUS_CONNECTING
            logger.debug(f"正在尝试连接直播间 {self.room_display_id} 的主机: {uri}")

            try:
                # 如果用户提供代理则设置代理
                proxy = None
                config_proxy = config.get("PROXY")
                if isinstance(config_proxy, str):
                    proxy = config_proxy
                elif isinstance(config_proxy, list):
                    proxy = random.choice(config_proxy)

                async with session.ws_connect(uri, headers={"User-Agent": "Mozilla/5.0"}, proxy=proxy) as ws:
                    @self.on('VERIFICATION_SUCCESSFUL')
                    async def on_verification_successful(data):
                        """
                        连接成功，新建心跳任务
                        """
                        self.__tasks.append(asyncio.create_task(self.__heartbeat(ws)))

                    self.__ws = ws
                    logger.debug(f"连接直播间 {self.room_display_id} 的主机成功, 准备发送认证信息")
                    await self.__send_verify_data(ws, conf['token'])

                    async for msg in ws:
                        if msg.type == aiohttp.WSMsgType.BINARY:
                            # logger.debug(f'收到直播间 {self.room_display_id} 的原始数据: {msg.data}')
                            await self.__handle_data(msg.data)

                        elif msg.type == aiohttp.WSMsgType.ERROR:
                            self.__status = self.STATUS_ERROR
                            logger.error(f'直播间 {self.room_display_id} 出现错误')

                        elif msg.type == aiohttp.WSMsgType.CLOSING:
                            logger.debug(f'正在关闭直播间 {self.room_display_id} 的连接')
                            self.__status = self.STATUS_CLOSING

                        elif msg.type == aiohttp.WSMsgType.CLOSED:
                            logger.debug(f'直播间 {self.room_display_id} 的连接已关闭')
                            self.__status = self.STATUS_CLOSED

                # 正常断开情况下跳出循环
                if self.__status != self.STATUS_CLOSED or self.err_reason:
                    # 非用户手动调用关闭，触发重连
                    raise LiveException('非正常关闭连接' if not self.err_reason else self.err_reason)
                else:
                    break

            except Exception as e:
                logger.warning(f'直播间 {self.room_display_id} 连接失败 : {e}')

                if len(available_hosts) == 0:
                    logger.error(f'无法连接直播间 {self.room_display_id} 的服务器')
                    self.err_reason = '无法连接服务器'
                    break

                logger.warning(f'将在 {self.retry_after} 秒后重新连接直播间 {self.room_display_id} ...')
                self.__status = self.STATUS_ERROR
                await asyncio.sleep(self.retry_after)

    async def __handle_data(self, data):
        """
        处理数据
        """
        data = self.__unpack(data)
        # logger.debug(f"直播间 {self.room_display_id} 收到信息: {data}")

        for info in data:
            callback_info = {
                'room_display_id': self.room_display_id,
                'room_real_id': self.__room_real_id
            }
            # 依次处理并调用用户指定函数
            if info["datapack_type"] == LiveDanmaku.DATAPACK_TYPE_VERIFY_SUCCESS_RESPONSE:
                # 认证反馈
                if info["data"]["code"] == 0:
                    # 认证成功反馈
                    self.__status = self.STATUS_ESTABLISHED
                    callback_info['type'] = 'VERIFICATION_SUCCESSFUL'
                    callback_info['data'] = None
                    self.dispatch('VERIFICATION_SUCCESSFUL', callback_info)
                    self.dispatch('ALL', callback_info)

            elif info["datapack_type"] == LiveDanmaku.DATAPACK_TYPE_HEARTBEAT_RESPONSE:
                # 心跳包反馈，返回直播间人气
                # logger.debug(f"直播间 {self.room_display_id} 收到心跳包反馈")
                # 重置心跳计时器
                self.__heartbeat_timer = 30.0
                callback_info["type"] = 'VIEW'
                callback_info["data"] = info["data"]["view"]
                self.dispatch('VIEW', callback_info)
                self.dispatch('ALL', callback_info)

            elif info["datapack_type"] == LiveDanmaku.DATAPACK_TYPE_NOTICE:
                # 直播间弹幕、礼物等信息
                callback_info["type"] = info["data"]["cmd"]

                # DANMU_MSG 事件名特殊：DANMU_MSG:4:0:2:2:2:0，需取出事件名，暂不知格式
                if callback_info["type"].find('DANMU_MSG') > -1:
                    callback_info["type"] = 'DANMU_MSG'
                    info["data"]["cmd"] = 'DANMU_MSG'

                callback_info["data"] = info["data"]
                self.dispatch(callback_info["type"], callback_info)
                self.dispatch('ALL', callback_info)

            else:
                logger.warning(f"直播间 {self.room_display_id} 检测到未知的数据包类型, 无法处理")

    async def __send_verify_data(self, ws: ClientWebSocketResponse, token: str):
        # uid = config.get("LOGIN_UID")
        verify_data = {"uid": self.__uid, "roomid": self.__room_real_id, "protover": 3,
                       "buvid": config.get("BUVID3"), "platform": "web", "type": 2, "key": token}
        data = json.dumps(verify_data).encode()
        await self.__send(data, self.PROTOCOL_VERSION_HEARTBEAT, self.DATAPACK_TYPE_VERIFY, ws)

    async def __heartbeat(self, ws: ClientWebSocketResponse):
        """
        定时发送心跳包
        """
        heartbeat = self.__pack(b'[object Object]', self.PROTOCOL_VERSION_HEARTBEAT, self.DATAPACK_TYPE_HEARTBEAT)
        heartbeat_url = "https://live-trace.bilibili.com/xlive/rdata-interface/v1/heartbeat/webHeartBeat?pf=web&hb="
        hb = str(base64.b64encode(f"60|{self.room_display_id}|1|0".encode("utf-8")), "utf-8")
        while True:
            if self.__heartbeat_timer == 0:
                # logger.debug(f"直播间 {self.room_display_id} 发送心跳包")
                await ws.send_bytes(heartbeat)
                try:
                    await request("GET", heartbeat_url, {"hb": hb, "pf": "web"}, credential=get_credential())
                except Exception as ex:
                    pass
            elif self.__heartbeat_timer <= -30:
                # 视为已异常断开连接，发布 TIMEOUT 事件
                self.dispatch('TIMEOUT')
                break

            await asyncio.sleep(1.0)
            self.__heartbeat_timer -= 1

    async def __send(self, data: bytes, protocol_version: int,
                     datapack_type: int, ws: ClientWebSocketResponse):
        """
        自动打包并发送数据
        """
        data = self.__pack(data, protocol_version, datapack_type)
        # logger.debug(f'直播间 {self.room_display_id} 发送原始数据: {data}')
        await ws.send_bytes(data)

    @staticmethod
    def __pack(data: bytes, protocol_version: int, datapack_type: int):
        """
        打包数据
        """
        send_data = bytearray()
        send_data += struct.pack(">H", 16)
        assert 0 <= protocol_version <= 2, LiveException("数据包协议版本错误, 范围 0~2")
        send_data += struct.pack(">H", protocol_version)
        assert datapack_type in [2, 7], LiveException("数据包类型错误, 可用类型：2, 7")
        send_data += struct.pack(">I", datapack_type)
        send_data += struct.pack(">I", 1)
        send_data += data
        send_data = struct.pack(">I", len(send_data) + 4) + send_data
        return bytes(send_data)

    @staticmethod
    def __unpack(data: bytes):
        """
        解包数据
        """
        ret = []
        offset = 0
        header = struct.unpack(">IHHII", data[:16])
        if header[2] == LiveDanmaku.PROTOCOL_VERSION_BROTLI_JSON:
            real_data = brotli.decompress(data[16:])
        else:
            real_data = data

        if header[2] == LiveDanmaku.PROTOCOL_VERSION_HEARTBEAT and \
                header[3] == LiveDanmaku.DATAPACK_TYPE_HEARTBEAT_RESPONSE:
            real_data = real_data[16:]
            # 心跳包协议特殊处理
            recv_data = {
                "protocol_version": header[2],
                "datapack_type": header[3],
                "data": {
                    "view": struct.unpack('>I', real_data[0:4])[0]
                }
            }
            ret.append(recv_data)
            return ret

        while offset < len(real_data):
            header = struct.unpack(">IHHII", real_data[offset:offset + 16])
            length = header[0]
            recv_data = {
                "protocol_version": header[2],
                "datapack_type": header[3],
                "data": None
            }
            chunk_data = real_data[(offset + 16):(offset + length)]
            if header[2] == 0:
                recv_data["data"] = json.loads(chunk_data.decode())
            elif header[2] == 2:
                recv_data["data"] = json.loads(chunk_data.decode())
            elif header[2] == 1:
                if header[3] == LiveDanmaku.DATAPACK_TYPE_HEARTBEAT_RESPONSE:
                    recv_data["data"] = {
                        "view": struct.unpack(">I", chunk_data)[0]}
                elif header[3] == LiveDanmaku.DATAPACK_TYPE_VERIFY_SUCCESS_RESPONSE:
                    recv_data["data"] = json.loads(chunk_data.decode())
            ret.append(recv_data)
            offset += length
        return ret


async def get_self_info(credential: Credential):
    """
    获取自己直播等级、排行等信息

    Args:
        credential: 凭据
    """
    credential.raise_for_no_sessdata()

    api = API["info"]["user_info"]
    return await request(api['method'], api["url"], credential=credential)


async def get_self_live_info(credential: Credential):
    """
    获取自己的粉丝牌、大航海等信息

    Args:
        credential: 凭据
    """
    credential.raise_for_no_sessdata()

    api = API["info"]["live_info"]
    return await request(api['method'], api["url"], credential=credential)


async def get_self_dahanghai_info(page: int = 1, page_size: int = 10, credential: Credential = None):
    """
    获取自己开通的大航海信息

    总页数取得方法:

    ```python
    import math

    info = live.get_self_live_info(credential)
    pages = math.ceil(info['data']['guards'] / 10)
    ```

    Args:
        page: 页数。默认：1
        page_size: 每页数量。默认：10
        credential: 凭据。默认：None
    """
    if credential is None:
        credential = Credential()

    credential.raise_for_no_sessdata()

    api = API["info"]["user_guards"]
    params = {
        "page": page,
        "page_size": page_size
    }
    return await request(api['method'], api["url"], params=params, credential=credential)


async def get_self_bag(credential: Credential):
    """
    获取自己的直播礼物包裹信息

    Args:
        credential: 凭据
    """
    credential.raise_for_no_sessdata()

    api = API["info"]["bag_list"]
    return await request(api['method'], api["url"], credential=credential)


async def get_gift_config(room_id: int = None,
                          area_id: int = None,
                          area_parent_id: int = None):
    """
    获取所有礼物的信息，包括礼物 id、名称、价格、等级等。
    同时填了 room_id、area_id、area_parent_id，则返回一个较小的 json，只包含该房间、该子区域、父区域的礼物。
    但即使限定了三个条件，仍然会返回约 1.5w 行的 json。不加限定则是 2.8w 行。

    Args:
        room_id: 房间显示 ID。默认：None
        area_id: 子分区 ID。默认：None
        area_parent_id: 父分区 ID。默认：None
    """
    api = API["info"]["gift_config"]
    params = {
        "platform": "pc",
        "source": "live",
        "room_id": room_id if room_id is not None else "",
        "area_id": area_id if area_id is not None else "",
        "area_parent_id": area_parent_id if area_parent_id is not None else ""
    }
    return await request(api['method'], api["url"], params=params)


async def get_area_info():
    """
    获取所有分区信息
    """
    api = API["info"]["area_info"]
    return await request(api['method'], api["url"])


async def get_live_followers_info(need_recommend: bool = True, credential: Credential = None):
    """
    获取关注列表中正在直播的直播间信息，包括房间直播热度，房间名称及标题，清晰度，是否官方认证等信息

    Args:
        need_recommend: 是否接受推荐直播间。默认：True
        credential: 凭据。默认：None
    """
    if credential is None:
        credential = Credential()

    credential.raise_for_no_sessdata()

    api = API["info"]["followers_live_info"]
    params = {
        "need_recommend": int(need_recommend),
        "filterRule": 0
    }
    return await request(api['method'], api["url"], params=params, credential=credential)


async def get_unlive_followers_info(page: int = 1, page_size: int = 30, credential: Credential = None):
    """
    获取关注列表中未在直播的直播间信息，包括上次开播时间，上次开播的类别，直播间公告，是否有录播等

    Args:
        page: 页码。默认：1
        page_size: 每页数量。默认：30
        credential: 凭据。默认：None
    """
    if credential is None:
        credential = Credential()

    credential.raise_for_no_sessdata()

    api = API["info"]["followers_unlive_info"]
    params = {
        "page": page,
        "pagesize": page_size,
    }
    return await request(api['method'], api["url"], params=params, credential=credential)
