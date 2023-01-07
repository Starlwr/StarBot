import asyncio
import base64
import os
import time
import typing
from asyncio import AbstractEventLoop
from collections import Counter
from io import BytesIO
from typing import Optional, Any, Union, List

import jieba
from loguru import logger
from pydantic import BaseModel, PrivateAttr
from wordcloud import WordCloud

from .live import LiveDanmaku, LiveRoom
from .model import PushTarget
from .user import User
from ..exception import LiveException
from ..utils import config, redis
from ..utils.Painter import DynamicPicGenerator
from ..utils.utils import get_credential, timestamp_format

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

    __user: Optional[User] = PrivateAttr()
    """用户实例，用于获取用户相关信息"""

    __live_room: Optional[LiveRoom] = PrivateAttr()
    """直播间实例，用于获取直播间相关信息"""

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
        self.__user = None
        self.__live_room = None
        self.__room = None
        self.__is_reconnect = False
        self.__loop = asyncio.get_event_loop()
        self.__bot = None

    def inject_bot(self, bot):
        self.__bot = bot

    def dispatch(self, name, data):
        self.__room.dispatch(name, data)

    def __any_live_on_enabled(self):
        return any(map(lambda conf: conf.enabled, map(lambda group: group.live_on, self.targets)))

    def __any_live_off_enabled(self):
        return any(map(lambda conf: conf.enabled, map(lambda group: group.live_off, self.targets)))

    def __any_live_report_enabled(self):
        return any(map(lambda conf: conf.enabled, map(lambda group: group.live_report, self.targets)))

    def __any_live_report_item_enabled(self, attribute: Union[str, List[str]]):
        if isinstance(attribute, list):
            return any([self.__any_live_report_item_enabled(a) for a in attribute])
        return any(map(lambda t: t.live_report.enabled and t.live_report.__getattribute__(attribute), self.targets))

    def __any_dynamic_update_enabled(self):
        return any(map(lambda conf: conf.enabled, map(lambda group: group.dynamic_update, self.targets)))

    async def connect(self):
        """
        连接直播间
        """
        self.__user = User(self.uid, get_credential())

        if not all([self.uname, self.room_id]):
            user_info = await self.__user.get_user_info()
            self.uname = user_info["name"]
            if user_info["live_room"] is None:
                raise LiveException(f"UP 主 {self.uname} ( UID: {self.uid} ) 还未开通直播间")
            self.room_id = user_info["live_room"]["roomid"]
        self.__live_room = LiveRoom(self.room_id, get_credential())
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
            """
            连接成功事件
            """
            logger.debug(f"{self.uname} (VERIFICATION_SUCCESSFUL): {event}")

            if self.__is_reconnect:
                logger.success(f"已重新连接到 {self.uname} 的直播间 {self.room_id}")

                room_info = await self.__live_room.get_room_play_info()
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
                logger.success(f"已成功连接到 {self.uname} 的直播间 {self.room_id}")

                self.__is_reconnect = True

        @self.__room.on("LIVE")
        async def live_on(event):
            """
            开播事件
            """
            logger.debug(f"{self.uname} (LIVE): {event}")

            # 是否为真正开播
            if "live_time" in event["data"]:
                room_info = await self.__live_room.get_room_info()
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

                    live_start_time = room_info["room_info"]["live_start_time"]
                    fans_count = room_info["anchor_info"]["relation_info"]["attention"]
                    if room_info["anchor_info"]["medal_info"] is None:
                        fans_medal_count = 0
                    else:
                        fans_medal_count = room_info["anchor_info"]["medal_info"]["fansclub"]
                    guard_count = room_info["guard_info"]["count"]
                    await redis.hset("StartTime", self.room_id, live_start_time)
                    await redis.hset(f"FansCount:{self.room_id}", live_start_time, fans_count)
                    await redis.hset(f"FansMedalCount:{self.room_id}", live_start_time, fans_medal_count)
                    await redis.hset(f"GuardCount:{self.room_id}", live_start_time, guard_count)

                    await self.__accumulate_data()
                    await self.__reset_data()

                    # 推送开播消息
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
            """
            下播事件
            """
            logger.debug(f"{self.uname} (PREPARING): {event}")

            await redis.hset("LiveStatus", self.room_id, 0)
            await redis.hset("EndTime", self.room_id, int(time.time()))

            logger.opt(colors=True).info(f"<magenta>[下播] {self.uname} ({self.room_id})</>")

            # 生成下播消息和直播报告占位符参数
            live_off_args = {
                "{uname}": self.uname
            }
            live_report_param = await self.__generate_live_report_param()

            # 推送下播消息和直播报告
            self.__bot.send_live_off(self, live_off_args)
            self.__bot.send_live_report(self, live_report_param)

        danmu_items = ["danmu", "danmu_cloud"]
        if not config.get("ONLY_HANDLE_NECESSARY_EVENT") or self.__any_live_report_item_enabled(danmu_items):
            @self.__room.on("DANMU_MSG")
            async def on_danmu(event):
                """
                弹幕事件
                """
                logger.debug(f"{self.uname} (DANMU_MSG): {event}")

                base = event["data"]["info"]
                uid = base[2][0]
                content = base[1]

                # 弹幕统计
                await redis.hincrby("RoomDanmuCount", self.room_id)
                await redis.zincrby(f"UserDanmuCount:{self.room_id}", uid)

                # 弹幕词云所需弹幕记录
                if isinstance(base[0][13], str):
                    await redis.rpush(f"RoomDanmu:{self.room_id}", content)

        gift_items = ["box", "gift"]
        if not config.get("ONLY_HANDLE_NECESSARY_EVENT") or self.__any_live_report_item_enabled(gift_items):
            @self.__room.on("SEND_GIFT")
            async def on_gift(event):
                """
                礼物事件
                """
                logger.debug(f"{self.uname} (SEND_GIFT): {event}")

                base = event["data"]["data"]
                uid = base["uid"]
                num = base["num"]
                price = float("{:.1f}".format((base["discount_price"] / 1000) * num))

                # 礼物统计
                if base["total_coin"] != 0 and base["discount_price"] != 0:
                    await redis.hincrbyfloat("RoomGiftProfit", self.room_id, price)
                    await redis.zincrby(f"UserGiftProfit:{self.room_id}", uid, price)

                # 盲盒统计
                if base["blind_gift"] is not None:
                    box_price = base["total_coin"] / 1000
                    gift_num = base["num"]
                    gift_price = base["discount_price"] / 1000
                    profit = float("{:.1f}".format((gift_price * gift_num) - box_price))

                    await redis.hincrby("RoomBoxCount", self.room_id, gift_num)
                    await redis.zincrby(f"UserBoxCount:{self.room_id}", uid, gift_num)
                    await redis.hincrbyfloat("RoomBoxProfit", self.room_id, profit)
                    await redis.zincrby(f"UserBoxProfit:{self.room_id}", uid, profit)

        if not config.get("ONLY_HANDLE_NECESSARY_EVENT") or self.__any_live_report_item_enabled("sc"):
            @self.__room.on("SUPER_CHAT_MESSAGE")
            async def on_sc(event):
                """
                SC（醒目留言）事件
                """
                logger.debug(f"{self.uname} (SUPER_CHAT_MESSAGE): {event}")

                base = event["data"]["data"]
                uid = base["uid"]
                price = base["price"]

                # SC 统计
                await redis.hincrby("RoomScProfit", self.room_id, price)
                await redis.zincrby(f"UserScProfit:{self.room_id}", uid, price)

        if not config.get("ONLY_HANDLE_NECESSARY_EVENT") or self.__any_live_report_item_enabled("guard"):
            @self.__room.on("GUARD_BUY")
            async def on_guard(event):
                """
                大航海事件
                """
                logger.debug(f"{self.uname} (GUARD_BUY): {event}")

                base = event["data"]["data"]
                uid = base["uid"]
                guard_type = base["gift_name"]
                month = base["num"]

                # 上舰统计
                type_mapping = {
                    "舰长": "Captain",
                    "提督": "Commander",
                    "总督": "Governor"
                }
                await redis.hincrby(f"Room{type_mapping[guard_type]}Count", self.room_id, month)
                await redis.zincrby(f"User{type_mapping[guard_type]}Count:{self.room_id}", uid, month)

        if self.__any_dynamic_update_enabled():
            @self.__room.on("DYNAMIC_UPDATE")
            async def dynamic_update(event):
                """
                动态更新事件
                """
                logger.debug(f"{self.uname} (DYNAMIC_UPDATE): {event}")

                dynamic_id = event["desc"]["dynamic_id"]
                dynamic_type = event["desc"]["type"]
                bvid = event['desc']['bvid'] if dynamic_type == 8 else ""
                rid = event['desc']['rid'] if dynamic_type in (64, 256) else ""

                action_map = {
                    1: "转发了动态",
                    2: "发表了新动态",
                    4: "发表了新动态",
                    8: "投稿了新视频",
                    64: "投稿了新专栏",
                    256: "投稿了新音频",
                    2048: "发表了新动态"
                }
                url_map = {
                    1: f"https://t.bilibili.com/{dynamic_id}",
                    2: f"https://t.bilibili.com/{dynamic_id}",
                    4: f"https://t.bilibili.com/{dynamic_id}",
                    8: f"https://www.bilibili.com/video/{bvid}",
                    64: f"https://www.bilibili.com/read/cv{rid}",
                    256: f"https://www.bilibili.com/audio/au{rid}",
                    2048: f"https://t.bilibili.com/{dynamic_id}"
                }
                base64str = await DynamicPicGenerator.generate(event)

                # 推送动态消息
                dynamic_update_args = {
                    "{uname}": self.uname,
                    "{action}": action_map.get(dynamic_type, "发表了新动态"),
                    "{url}": url_map.get(dynamic_type, f"https://t.bilibili.com/{dynamic_id}"),
                    "{picture}": "".join(["{base64pic=", base64str, "}"])
                }
                self.__bot.send_dynamic_update(self, dynamic_update_args)

    async def __accumulate_data(self):
        """
        累计直播间数据
        """

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

    async def __generate_live_report_param(self):
        """
        计算直播报告所需数据
        """
        live_report_param = {}

        # 主播信息
        live_report_param.update({
            "uname": self.uname,
            "room_id": self.room_id
        })

        # 直播时间段和直播时长
        start_time = await redis.hgeti("StartTime", self.room_id)
        end_time = await redis.hgeti("EndTime", self.room_id)
        seconds = end_time - start_time
        minute, second = divmod(seconds, 60)
        hour, minute = divmod(minute, 60)

        live_report_param.update({
            "start_time": timestamp_format(start_time, "%m/%d %H:%M:%S"),
            "end_time": timestamp_format(end_time, "%m/%d %H:%M:%S"),
            "hour": hour,
            "minute": minute,
            "second": second
        })

        # 基础数据变动
        if self.__any_live_report_item_enabled(["fans_change", "fans_medal_change", "guard_change"]):
            room_info = await self.__live_room.get_room_info()

            live_start_time = await redis.hgeti("StartTime", self.room_id)
            if await redis.hexists(f"FansCount:{self.room_id}", live_start_time):
                fans_count = await redis.hgeti(f"FansCount:{self.room_id}", live_start_time)
            else:
                fans_count = -1
            if await redis.hexists(f"FansMedalCount:{self.room_id}", live_start_time):
                fans_medal_count = await redis.hgeti(f"FansMedalCount:{self.room_id}", live_start_time)
            else:
                fans_medal_count = -1
            if await redis.hexists(f"GuardCount:{self.room_id}", live_start_time):
                guard_count = await redis.hgeti(f"GuardCount:{self.room_id}", live_start_time)
            else:
                guard_count = -1

            if room_info["anchor_info"]["medal_info"] is None:
                fans_medal_count_after = 0
            else:
                fans_medal_count_after = room_info["anchor_info"]["medal_info"]["fansclub"]

            live_report_param.update({
                # 粉丝变动
                "fans_before": fans_count,
                "fans_after": room_info["anchor_info"]["relation_info"]["attention"],
                # 粉丝团（粉丝勋章数）变动
                "fans_medal_before": fans_medal_count,
                "fans_medal_after": fans_medal_count_after,
                # 大航海变动
                "guard_before": guard_count,
                "guard_after": room_info["guard_info"]["count"]
            })

        # 直播数据
        box_profit = await redis.hgetf1("RoomBoxProfit", self.room_id)
        count = await redis.zcard("BoxProfit")
        await redis.zadd("BoxProfit", f"{start_time}-{self.uid}-{self.uname}", box_profit)
        rank = await redis.zrank("BoxProfit", f"{start_time}-{self.uid}-{self.uname}")
        percent = float("{:.2f}".format(float("{:.4f}".format(rank / count)) * 100)) if count != 0 else 100

        live_report_param.update({
            # 弹幕相关
            "danmu_count": await redis.hgeti("RoomDanmuCount", self.room_id),
            "danmu_person_count": await redis.zcard(f"UserDanmuCount:{self.room_id}"),
            # 盲盒相关
            "box_count": await redis.hgeti("RoomBoxCount", self.room_id),
            "box_person_count": await redis.zcard(f"UserBoxCount:{self.room_id}"),
            "box_profit": box_profit,
            "box_beat_percent": percent,
            # 礼物相关
            "gift_profit": await redis.hgetf1("RoomGiftProfit", self.room_id),
            "gift_person_count": await redis.zcard(f"UserGiftProfit:{self.room_id}"),
            # SC（醒目留言）相关
            "sc_profit": await redis.hgeti("RoomScProfit", self.room_id),
            "sc_person_count": await redis.zcard(f"UserScProfit:{self.room_id}"),
            # 大航海相关
            "captain_count": await redis.hgeti("RoomCaptainCount", self.room_id),
            "commander_count": await redis.hgeti("RoomCommanderCount", self.room_id),
            "governor_count": await redis.hgeti("RoomGovernorCount", self.room_id)
        })

        # 弹幕词云
        if self.__any_live_report_item_enabled("danmu_cloud"):
            all_danmu = " ".join(await redis.lrange(f"RoomDanmu:{self.room_id}", 0, -1))

            if len(all_danmu) == 0:
                live_report_param.update({
                    "danmu_cloud": ""
                })
            else:
                jieba.setLogLevel(jieba.logging.INFO)
                words = list(jieba.cut(all_danmu))
                counts = dict(Counter(words))

                font_base_path = os.path.dirname(os.path.dirname(__file__))
                io = BytesIO()
                word_cloud = WordCloud(width=900,
                                       height=450,
                                       font_path=f"{font_base_path}/resource/{config.get('DANMU_CLOUD_FONT')}",
                                       background_color=config.get("DANMU_CLOUD_BACKGROUND_COLOR"),
                                       max_font_size=config.get("DANMU_CLOUD_MAX_FONT_SIZE"),
                                       max_words=config.get("DANMU_CLOUD_MAX_WORDS"))
                word_cloud.generate_from_frequencies(counts)
                word_cloud.to_image().save(io, format="png")
                base64_str = base64.b64encode(io.getvalue()).decode()

                live_report_param.update({
                    "danmu_cloud": base64_str
                })

        return live_report_param

    def __eq__(self, other):
        if isinstance(other, Up):
            return self.uid == other.uid
        elif isinstance(other, int):
            return self.uid == other
        return False

    def __hash__(self):
        return hash(self.uid)
