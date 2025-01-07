import asyncio
import time
import typing
from asyncio import AbstractEventLoop
from datetime import datetime
from typing import Optional, Any, Union, List

from loguru import logger
from pydantic import BaseModel, PrivateAttr

from .live import LiveDanmaku, LiveRoom
from .model import PushTarget
from .user import User
from ..exception import LiveException, ResponseCodeException
from ..painter.DynamicPicGenerator import DynamicPicGenerator
from ..utils import config, redis
from ..utils.network import request
from ..utils.utils import get_credential, timestamp_format, get_unames_and_faces_by_uids

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

    __connecting: Optional[bool] = PrivateAttr()
    """是否正在连接中"""

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
        self.__connecting = False
        self.__is_reconnect = False
        self.__loop = asyncio.get_event_loop()
        self.__bot = None

    @property
    def status(self):
        return 6 if not self.__room else self.__room.get_status()

    def dispatch(self, event, data):
        if self.__room is not None:
            self.__room.dispatch(event, data)

    def inject_bot(self, bot):
        self.__bot = bot

    async def accumulate_and_reset_data(self):
        await redis.accumulate_data(self.room_id)
        await redis.reset_data(self.room_id)

    def is_connecting(self):
        return (self.__room is not None) and (self.__room.get_status() != 2)

    def is_need_connect(self):
        return any([self.__any_live_on_enabled(), self.__any_live_off_enabled(), self.__any_live_report_enabled()])

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

    async def connect(self):
        """
        连接直播间
        """
        if not all([self.uname, self.room_id]):
            user_info_url = f"https://api.live.bilibili.com/live_user/v1/Master/info?uid={self.uid}"
            user_info = await request("GET", user_info_url)
            self.uname = user_info["info"]["uname"]
            if user_info["room_id"] == 0:
                raise LiveException(f"UP 主 {self.uname} ( UID: {self.uid} ) 还未开通直播间")
            self.room_id = user_info["room_id"]

        # 开播推送开关和下播推送开关均处于关闭状态时跳过连接直播间，以节省性能
        if config.get("ONLY_CONNECT_NECESSARY_ROOM") and not self.is_need_connect():
            logger.warning(f"{self.uname} 的开播, 下播和直播报告开关均处于关闭状态, 跳过连接直播间")
            return False

        if self.__connecting:
            logger.warning(f"{self.uname} ( UID: {self.uid} ) 的直播间正在连接中, 跳过重复连接")
            return False
        self.__connecting = True

        self.__live_room = LiveRoom(self.room_id, get_credential())
        self.__room = LiveDanmaku(self.room_id, credential=get_credential())

        logger.opt(colors=True).info(f"准备连接到 <cyan>{self.uname}</> 的直播间 <cyan>{self.room_id}</>")

        self.__loop.create_task(self.__room.connect())

        @self.__room.on("VERIFICATION_SUCCESSFUL")
        async def on_link(event):
            """
            连接成功事件
            """
            logger.debug(f"{self.uname} (VERIFICATION_SUCCESSFUL): {event}")

            self.__connecting = False

            if self.__is_reconnect:
                logger.success(f"已重新连接到 {self.uname} 的直播间 {self.room_id}")

                room_info = await self.__live_room.get_room_play_info()
                last_status = await redis.get_live_status(self.room_id)
                now_status = room_info["live_status"]

                if now_status != last_status:
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

                if not await redis.exists_live_status(self.room_id):
                    room_info = await self.__live_room.get_room_play_info()
                    status = room_info["live_status"]
                    await redis.set_live_status(self.room_id, status)
                    if status == 1:
                        start_time = room_info["live_time"]
                        await redis.set_live_start_time(self.room_id, start_time)

                self.__is_reconnect = True

        @self.__room.on("LIVE")
        async def live_on(event):
            """
            开播事件
            """
            logger.debug(f"{self.uname} (LIVE): {event}")

            locked = False
            room_info = {}
            fans_medal_info = {}
            guards_info = {}

            # 是否为真正开播
            if "live_time" in event["data"]:
                if await redis.get_live_status(self.room_id) == 1:
                    return

                await redis.set_live_status(self.room_id, 1)

                # 是否为主播网络波动断线重连
                now = int(time.time())
                last = await redis.get_live_end_time(self.room_id)
                is_reconnect = (now - last) <= config.get("UP_DISCONNECT_CONNECT_INTERVAL")
                if is_reconnect:
                    logger.opt(colors=True).info(f"<magenta>[断线重连] {self.uname} ({self.room_id})</>")
                    if config.get("UP_DISCONNECT_CONNECT_MESSAGE"):
                        await self.__bot.send_to_all_target(self, config.get("UP_DISCONNECT_CONNECT_MESSAGE"),
                                                            lambda t: t.live_on.enabled)
                else:
                    logger.opt(colors=True).info(f"<magenta>[开播] {self.uname} ({self.room_id})</>")

                    try:
                        room_info = await self.__live_room.get_room_info_v2()
                        fans_medal_info = await self.__live_room.get_fans_medal_info(self.uid)
                        guards_info = await self.__live_room.get_guards_info(self.uid)
                    except ResponseCodeException as ex:
                        if ex.code == 19002005:
                            locked = True
                            logger.warning(f"{self.uname} ({self.room_id}) 的直播间已加密")
                        else:
                            logger.error(f"{self.uname} ({self.room_id}) 的直播间信息获取失败, 错误信息: {ex.code} ({ex.msg})")

                    if not locked:
                        # 此处若有合适 API 需更新一下最新昵称
                        pass

                    if locked:
                        live_start_time = int(time.time())
                    else:
                        if room_info["live_time"] != "0000-00-00 00:00:00":
                            time_format = "%Y-%m-%d %H:%M:%S"
                            live_start_time = int(datetime.strptime(room_info["live_time"], time_format).timestamp())
                        else:
                            live_start_time = int(time.time())
                    await redis.set_live_start_time(self.room_id, live_start_time)

                    if not locked:
                        fans_count = room_info["attention"]
                        fans_medal_count = fans_medal_info["fans_medal_light_count"]
                        guard_count = guards_info["info"]["num"]
                        await redis.set_fans_count(self.room_id, live_start_time, fans_count)
                        await redis.set_fans_medal_count(self.room_id, live_start_time, fans_medal_count)
                        await redis.set_guard_count(self.room_id, live_start_time, guard_count)

                    await self.accumulate_and_reset_data()

                    # 推送开播消息
                    if not locked:
                        args = {
                            "{uname}": self.uname,
                            "{title}": room_info["title"],
                            "{url}": f"https://live.bilibili.com/{self.room_id}",
                            "{cover}": "".join(["{urlpic=", room_info["user_cover"], "}"])
                        }
                    else:
                        args = {
                            "{uname}": self.uname,
                            "{title}": "加密直播间",
                            "{url}": f"https://live.bilibili.com/{self.room_id}",
                            "{cover}": ""
                        }

                    await self.__bot.send_live_on(self, args)
                    await self.__bot.send_live_on_at(self)

        @self.__room.on("PREPARING")
        async def live_off(event):
            """
            下播事件
            """
            logger.debug(f"{self.uname} (PREPARING): {event}")

            if await redis.get_live_status(self.room_id) == 0:
                return

            await redis.set_live_status(self.room_id, 0)
            await redis.set_live_end_time(self.room_id, int(time.time()))

            logger.opt(colors=True).info(f"<magenta>[下播] {self.uname} ({self.room_id})</>")

            # 生成下播消息和直播报告占位符参数
            live_off_args = {
                "{uname}": self.uname
            }
            live_report_param = await self.__generate_live_report_param()

            # 推送下播消息和直播报告
            await self.__bot.send_live_off(self, live_off_args)
            await self.__bot.send_live_report(self, live_report_param)

        danmu_items = ["danmu", "danmu_ranking", "danmu_diagram", "danmu_cloud"]
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
                await redis.incr_room_danmu_count(self.room_id)
                if uid != 0:
                    await redis.incr_user_danmu_count(self.room_id, uid)

                # 弹幕词云所需弹幕记录
                if isinstance(base[0][13], str):
                    await redis.add_room_danmu(self.room_id, content)
                    await redis.incr_room_danmu_time(self.room_id, int(time.time()))

        gift_items = [
            "box", "gift", "box_ranking", "box_profit_ranking", "gift_ranking",
            "box_profit_diagram", "box_diagram", "gift_diagram"
        ]
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

                # 幸运之钥主播收益为 1%
                if base["giftId"] == 31709:
                    price = price * 0.01

                # 礼物统计
                if base["total_coin"] != 0 and base["discount_price"] != 0:
                    await redis.incr_room_gift_profit(self.room_id, price)
                    await redis.incr_user_gift_profit(self.room_id, uid, price)

                    await redis.incr_room_gift_time(self.room_id, int(time.time()), price)

                # 盲盒统计
                if base["blind_gift"] is not None:
                    box_price = base["total_coin"] / 1000
                    gift_num = base["num"]
                    gift_price = base["discount_price"] / 1000
                    profit = float("{:.1f}".format((gift_price * gift_num) - box_price))

                    await redis.incr_room_box_count(self.room_id, gift_num)
                    await redis.incr_user_box_count(self.room_id, uid, gift_num)
                    box_profit_after = await redis.incr_room_box_profit(self.room_id, profit)
                    await redis.incr_user_box_profit(self.room_id, uid, profit)

                    await redis.add_room_box_profit_record(self.room_id, box_profit_after)
                    await redis.incr_room_box_time(self.room_id, int(time.time()))

        sc_items = ["sc", "sc_ranking", "sc_diagram"]
        if not config.get("ONLY_HANDLE_NECESSARY_EVENT") or self.__any_live_report_item_enabled(sc_items):
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
                await redis.incr_room_sc_profit(self.room_id, price)
                await redis.incr_user_sc_profit(self.room_id, uid, price)

                await redis.incr_room_sc_time(self.room_id, int(time.time()), price)

        guard_items = ["guard", "guard_list", "guard_diagram"]
        if not config.get("ONLY_HANDLE_NECESSARY_EVENT") or self.__any_live_report_item_enabled(guard_items):
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
                await redis.incr_room_guard_count(type_mapping[guard_type], self.room_id, month)
                await redis.incr_user_guard_count(type_mapping[guard_type], self.room_id, uid, month)

                await redis.incr_room_guard_time(self.room_id, int(time.time()), month)

        return True

    async def disconnect(self):
        """
        断开连接直播间
        """
        if self.__room is not None and self.__room.get_status() == 2:
            await self.__room.disconnect()
            self.__is_reconnect = False
            logger.success(f"已断开连接 {self.uname} 的直播间 {self.room_id}")

            await self.accumulate_and_reset_data()

    async def auto_reload_connect(self):
        """
        自动判断仅连接必要的直播间开启时，重载配置时自动处理直播间连接状态
        """
        if config.get("ONLY_CONNECT_NECESSARY_ROOM"):
            if any([self.__any_live_on_enabled(), self.__any_live_off_enabled(), self.__any_live_report_enabled()]):
                if self.__room is None or (self.__room.get_status() != 2 and self.__room.get_status() != 5):
                    await self.connect()
            else:
                if self.__room is not None and self.__room.get_status() == 2:
                    await self.disconnect()

    async def dynamic_update(self, event):
        """
        动态更新事件
        """
        logger.debug(f"{self.uname} (DYNAMIC_UPDATE): {event}")
        logger.opt(colors=True).info(f"<magenta>[动态更新] {self.uname}</>")

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
        await self.__bot.send_dynamic_at(self)
        await self.__bot.send_dynamic_update(self, dynamic_update_args)

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
        start_time = await redis.get_live_start_time(self.room_id)
        end_time = await redis.get_live_end_time(self.room_id)
        seconds = end_time - start_time
        minute, second = divmod(seconds, 60)
        hour, minute = divmod(minute, 60)

        live_report_param.update({
            "start_timestamp": start_time,
            "end_timestamp": end_time,
            "start_time": timestamp_format(start_time, "%m/%d %H:%M:%S"),
            "end_time": timestamp_format(end_time, "%m/%d %H:%M:%S"),
            "hour": hour,
            "minute": minute,
            "second": second
        })

        locked = False
        room_info = {}
        fans_medal_info = {}
        guards_info = {}

        # 基础数据变动
        if self.__any_live_report_item_enabled(["fans_change", "fans_medal_change", "guard_change"]):
            try:
                room_info = await self.__live_room.get_room_info_v2()
                fans_medal_info = await self.__live_room.get_fans_medal_info(self.uid)
                guards_info = await self.__live_room.get_guards_info(self.uid)
            except ResponseCodeException as ex:
                if ex.code == 19002005:
                    locked = True
                    logger.warning(f"{self.uname} ({self.room_id}) 的直播间已加密")

            if not locked:
                if await redis.exists_fans_count(self.room_id, start_time):
                    fans_count = await redis.get_fans_count(self.room_id, start_time)
                else:
                    fans_count = -1
                if await redis.exists_fans_medal_count(self.room_id, start_time):
                    fans_medal_count = await redis.get_fans_medal_count(self.room_id, start_time)
                else:
                    fans_medal_count = -1
                if await redis.exists_guard_count(self.room_id, start_time):
                    guard_count = await redis.get_guard_count(self.room_id, start_time)
                else:
                    guard_count = -1

                fans_medal_count_after = fans_medal_info["fans_medal_light_count"]

                live_report_param.update({
                    # 粉丝变动
                    "fans_before": fans_count,
                    "fans_after": room_info["attention"],
                    # 粉丝团（粉丝勋章数）变动
                    "fans_medal_before": fans_medal_count,
                    "fans_medal_after": fans_medal_count_after,
                    # 大航海变动
                    "guard_before": guard_count,
                    "guard_after": guards_info["info"]["num"]
                })

        # 直播数据
        box_profit = await redis.get_room_box_profit(self.room_id)
        count = await redis.len_box_profit_record()
        await redis.add_box_profit_record(start_time, self.uid, self.uname, box_profit)
        rank = await redis.rank_box_profit_record(start_time, self.uid, self.uname)
        percent = float("{:.2f}".format(float("{:.4f}".format(rank / count)) * 100)) if count != 0 else 100

        live_report_param.update({
            # 弹幕相关
            "danmu_count": await redis.get_room_danmu_count(self.room_id),
            "danmu_person_count": await redis.len_user_danmu_count(self.room_id),
            "danmu_diagram": await redis.get_room_danmu_time(self.room_id),
            # 盲盒相关
            "box_count": await redis.get_room_box_count(self.room_id),
            "box_person_count": await redis.len_user_box_count(self.room_id),
            "box_profit": box_profit,
            "box_beat_percent": percent,
            "box_profit_diagram": await redis.get_room_box_profit_record(self.room_id),
            "box_diagram": await redis.get_room_box_time(self.room_id),
            # 礼物相关
            "gift_profit": await redis.get_room_gift_profit(self.room_id),
            "gift_person_count": await redis.len_user_gift_profit(self.room_id),
            "gift_diagram": await redis.get_room_gift_time(self.room_id),
            # SC（醒目留言）相关
            "sc_profit": await redis.get_room_sc_profit(self.room_id),
            "sc_person_count": await redis.len_user_sc_profit(self.room_id),
            "sc_diagram": await redis.get_room_sc_time(self.room_id),
            # 大航海相关
            "captain_count": await redis.get_room_captain_count(self.room_id),
            "commander_count": await redis.get_room_commander_count(self.room_id),
            "governor_count": await redis.get_room_governor_count(self.room_id),
            "guard_diagram": await redis.get_room_guard_time(self.room_id)
        })

        # 弹幕排行
        if self.__any_live_report_item_enabled("danmu_ranking"):
            ranking_count = max(map(lambda t: t.live_report.danmu_ranking, self.targets))
            danmu_ranking = await redis.rev_range_user_danmu_count(self.room_id, 0, ranking_count - 1)

            if danmu_ranking:
                uids = [x[0] for x in danmu_ranking]
                counts = [x[1] for x in danmu_ranking]
                unames, faces = await get_unames_and_faces_by_uids(uids)

                live_report_param.update({
                    "danmu_ranking_faces": faces,
                    "danmu_ranking_unames": unames,
                    "danmu_ranking_counts": counts
                })

        # 盲盒数量排行
        if self.__any_live_report_item_enabled("box_ranking"):
            ranking_count = max(map(lambda t: t.live_report.box_ranking, self.targets))
            box_ranking = await redis.rev_range_user_box_count(self.room_id, 0, ranking_count - 1)

            if box_ranking:
                uids = [x[0] for x in box_ranking]
                counts = [x[1] for x in box_ranking]
                unames, faces = await get_unames_and_faces_by_uids(uids)

                live_report_param.update({
                    "box_ranking_faces": faces,
                    "box_ranking_unames": unames,
                    "box_ranking_counts": counts
                })

        # 盲盒盈亏排行
        if self.__any_live_report_item_enabled("box_profit_ranking"):
            ranking_count = max(map(lambda t: t.live_report.box_profit_ranking, self.targets))
            box_profit_ranking = await redis.rev_range_user_box_profit(self.room_id, 0, ranking_count - 1)

            if box_profit_ranking:
                uids = [x[0] for x in box_profit_ranking]
                counts = [x[1] for x in box_profit_ranking]
                unames, faces = await get_unames_and_faces_by_uids(uids)

                live_report_param.update({
                    "box_profit_ranking_faces": faces,
                    "box_profit_ranking_unames": unames,
                    "box_profit_ranking_counts": counts
                })

        # 礼物排行
        if self.__any_live_report_item_enabled("gift_ranking"):
            ranking_count = max(map(lambda t: t.live_report.gift_ranking, self.targets))
            gift_ranking = await redis.rev_range_user_gift_profit(self.room_id, 0, ranking_count - 1)

            if gift_ranking:
                uids = [x[0] for x in gift_ranking]
                counts = [x[1] for x in gift_ranking]
                unames, faces = await get_unames_and_faces_by_uids(uids)

                live_report_param.update({
                    "gift_ranking_faces": faces,
                    "gift_ranking_unames": unames,
                    "gift_ranking_counts": counts
                })

        # SC（醒目留言）排行
        if self.__any_live_report_item_enabled("sc_ranking"):
            ranking_count = max(map(lambda t: t.live_report.sc_ranking, self.targets))
            sc_ranking = await redis.rev_range_user_sc_profit(self.room_id, 0, ranking_count - 1)

            if sc_ranking:
                uids = [x[0] for x in sc_ranking]
                counts = [x[1] for x in sc_ranking]
                unames, faces = await get_unames_and_faces_by_uids(uids)

                live_report_param.update({
                    "sc_ranking_faces": faces,
                    "sc_ranking_unames": unames,
                    "sc_ranking_counts": counts
                })

        # 开通大航海观众列表
        if self.__any_live_report_item_enabled("guard_list"):
            captains = await redis.rev_range_user_captain_count(self.room_id)
            commanders = await redis.rev_range_user_commander_count(self.room_id)
            governors = await redis.rev_range_user_governor_count(self.room_id)

            if captains:
                uids = [x[0] for x in captains]
                counts = [x[1] for x in captains]
                unames, faces = await get_unames_and_faces_by_uids(uids)

                captain_infos = [[faces[i], unames[i], counts[i]] for i in range(len(counts))]
                live_report_param.update({
                    "captain_infos": captain_infos,
                })

            if commanders:
                uids = [x[0] for x in commanders]
                counts = [x[1] for x in commanders]
                unames, faces = await get_unames_and_faces_by_uids(uids)

                commander_infos = [[faces[i], unames[i], counts[i]] for i in range(len(counts))]
                live_report_param.update({
                    "commander_infos": commander_infos,
                })

            if governors:
                uids = [x[0] for x in governors]
                counts = [x[1] for x in governors]
                unames, faces = await get_unames_and_faces_by_uids(uids)

                governor_infos = [[faces[i], unames[i], counts[i]] for i in range(len(counts))]
                live_report_param.update({
                    "governor_infos": governor_infos,
                })

        # 弹幕词云
        if self.__any_live_report_item_enabled("danmu_cloud"):
            all_danmu = await redis.get_room_danmu(self.room_id)

            live_report_param.update({
                "all_danmu": all_danmu
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
