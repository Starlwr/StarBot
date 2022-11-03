import asyncio
import sys

from creart import create
from graia.ariadne import Ariadne
from graia.broadcast import Broadcast
from loguru import logger

from .datasource import DataSource
from .server import http_init
from ..exception.DataSourceException import DataSourceException
from ..exception.RedisException import RedisException
from ..utils import redis, config


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
