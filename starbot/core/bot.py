import asyncio
import sys

from creart import create
from graia.ariadne import Ariadne
from graia.broadcast import Broadcast
from loguru import logger

from .datasource import DataSource
from .server import http_init
from ..exception import LiveException
from ..exception.DataSourceException import DataSourceException
from ..exception.RedisException import RedisException
from ..utils import redis, config
from ..utils.network import request


class StarBot:
    """
    StarBot 类
    """
    STARBOT_ASCII_LOGO = "\n".join(
        (
            r"    _____ _             ____        _   ",
            r"   / ____| |           |  _ \      | |  ",
            r"  | (___ | |_ __ _ _ __| |_) | ___ | |_ ",
            r"   \___ \| __/ _` | '__|  _ < / _ \| __|",
            r"   ____) | || (_| | |  | |_) | (_) | |_ ",
            r"  |_____/ \__\__,_|_|  |____/ \___/ \__|",
            r"      StarBot - (v1.0.0)  2022-10-29",
            r" Github: https://github.com/Starlwr/StarBot",
            r"",
            r"",
        )
    )

    def __init__(self, datasource: DataSource):
        """
        Args:
            datasource: 推送配置数据源
        """
        self.__datasource = datasource

    async def __main(self):
        """
        StarBot 入口
        """

        logger.opt(colors=True, raw=True).info(f"<yellow>{self.STARBOT_ASCII_LOGO}</>")
        logger.info("开始启动 StarBot")

        # 从数据源中加载配置
        try:
            await self.__datasource.load()
        except DataSourceException as ex:
            logger.error(ex.msg)
            return

        # 连接 Redis
        try:
            await redis.init()
        except RedisException as ex:
            logger.error(ex.msg)
            return

        # 通过 UID 列表批量获取信息
        uids = list(map(lambda u: str(u), self.__datasource.get_uid_list()))
        info_url = "https://api.live.bilibili.com/room/v1/Room/get_status_info_by_uids?uids[]=" + "&uids[]=".join(uids)
        info = await request("GET", info_url)
        for uid in info:
            base = info[uid]
            uid = int(uid)
            up = self.__datasource.get_up(uid)
            up.uname = base["uname"]
            up.room_id = base["room_id"]
            status = base["live_status"]
            logger.opt(colors=True).info(f"初始化 <cyan>{up.uname}</> "
                                         f"(UID: <cyan>{up.uid}</>, "
                                         f"房间号: <cyan>{up.room_id}</>) 的直播间状态: "
                                         f"{'<green>直播中</>' if status == 1 else '<red>未开播</>'}")

            await redis.hset("LiveStatus", up.room_id, status)
            await redis.hset("StartTime", up.room_id, base["live_time"])
            await redis.hset_ifnotexists("EndTime", up.room_id, 0)
            await redis.hset_ifnotexists("RoomDanmuCount", up.room_id, 0)
            await redis.hset_ifnotexists("RoomDanmuTotal", up.room_id, 0)
            await redis.hset_ifnotexists("RoomBoxCount", up.room_id, 0)
            await redis.hset_ifnotexists("RoomBoxTotal", up.room_id, 0)
            await redis.hset_ifnotexists("RoomBoxProfit", up.room_id, 0)
            await redis.hset_ifnotexists("RoomBoxProfitTotal", up.room_id, 0)
            await redis.hset_ifnotexists("RoomGiftProfit", up.room_id, 0)
            await redis.hset_ifnotexists("RoomGiftTotal", up.room_id, 0)
            await redis.hset_ifnotexists("RoomScProfit", up.room_id, 0)
            await redis.hset_ifnotexists("RoomScTotal", up.room_id, 0)
            await redis.hset_ifnotexists("RoomGuardCount", up.room_id, "0-0-0")
            await redis.hset_ifnotexists("RoomGuardTotal", up.room_id, "0-0-0")

        for up in self.__datasource.get_up_list():
            try:
                await up.connect()
            except LiveException as ex:
                logger.error(ex.msg)

        # 启动 HTTP API 服务
        if config.get("USE_HTTP_API"):
            asyncio.get_event_loop().create_task(http_init(self.__datasource))

        # 启动 Bot
        if not self.__datasource.bots:
            logger.error("不存在需要启动的 Bot 账号, 请先在数据源中配置完毕后再重新运行")
            return

        Ariadne.options["default_account"] = self.__datasource.bots[0].qq

        logger.info("开始运行 Ariadne 消息推送模块")
        logger.disable("graia.ariadne.service")
        logger.disable("launart")

        for bot in self.__datasource.bots:
            bot.start_sender()

        try:
            Ariadne.launch_blocking()
        except RuntimeError as ex:
            if "This event loop is already running" in str(ex):
                pass
            else:
                logger.error(ex)
                return

    def run(self):
        """
        启动 StarBot
        """

        logger_format = (
            "<green>{time:YYYY-MM-DD HH:mm:ss.SSS}</green> | "
            "<level>{level: <8}</level> | "
            "<cyan>{name}</cyan>:<cyan>{line}</cyan> | "
            "<level>{message}</level>"
        )
        logger.remove()
        logger.add(sys.stderr, format=logger_format, level="INFO")

        bcc = create(Broadcast)
        loop = bcc.loop
        loop.create_task(self.__main())
        loop.run_forever()
