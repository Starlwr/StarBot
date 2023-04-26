import asyncio
import json
import sys

from creart import create
from graia.ariadne import Ariadne
from graia.broadcast import Broadcast
from graia.saya import Saya
from loguru import logger

from .datasource import DataSource
from .dynamic import dynamic_spider
from .server import http_init
from .user import User, RelationType
from ..exception import LiveException
from ..exception.DataSourceException import DataSourceException
from ..exception.RedisException import RedisException
from ..utils import redis, config
from ..utils.network import request, get_session
from ..utils.utils import split_list, get_credential


class StarBot:
    """
    StarBot 类
    """
    VERSION = "1.0.0"
    STARBOT_ASCII_LOGO = "\n".join(
        (
            r"    _____ _             ____        _   ",
            r"   / ____| |           |  _ \      | |  ",
            r"  | (___ | |_ __ _ _ __| |_) | ___ | |_ ",
            r"   \___ \| __/ _` | '__|  _ < / _ \| __|",
            r"   ____) | || (_| | |  | |_) | (_) | |_ ",
            r"  |_____/ \__\__,_|_|  |____/ \___/ \__|",
            f"      StarBot - (v{VERSION})  2022-10-29",
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
        Ariadne.options["StarBotDataSource"] = datasource

    async def __main(self):
        """
        StarBot 入口
        """
        logger.opt(colors=True, raw=True).info(f"<yellow>{self.STARBOT_ASCII_LOGO}</>")
        if config.get("CHECK_VERSION"):
            try:
                response = await get_session().get("https://mirrors.aliyun.com/pypi/web/json/starbot-bilibili")
                data = await response.text()
                latest_version = json.loads(data)["info"]["version"]
                if latest_version != self.VERSION:
                    logger.warning(f"检测到 StarBot 新版本 v{latest_version}, 建议升级到最新版本, "
                                   f"升级内容和版本间不兼容说明请参阅官网或 Github 页的迁移指南")
            except Exception:
                logger.error("获取 StarBot 最新版本失败")
        logger.info("开始启动 StarBot")

        # 从数据源中加载配置
        try:
            await self.__datasource.load()
        except DataSourceException as ex:
            logger.error(ex.msg)
            return

        if not self.__datasource.bots:
            logger.error("数据源配置为空, 请先在数据源中配置完毕后再重新运行")
            return

        # 连接 Redis
        try:
            await redis.init()
        except RedisException as ex:
            logger.error(ex.msg)
            return

        # 通过 UID 列表批量获取信息
        info = {}
        info_url = "https://api.live.bilibili.com/room/v1/Room/get_status_info_by_uids?uids[]="
        uids = [str(u) for u in self.__datasource.get_uid_list()]
        uid_lists = split_list(uids, 100)
        for lst in uid_lists:
            info.update(await request("GET", info_url + "&uids[]=".join(lst)))
        for uid in info:
            base = info[uid]
            uid = int(uid)
            up = self.__datasource.get_up(uid)
            up.uname = base["uname"]
            up.room_id = base["room_id"]
            status = base["live_status"]
            start_time = base["live_time"]
            logger.opt(colors=True).info(f"初始化 <cyan>{up.uname}</> "
                                         f"(UID: <cyan>{up.uid}</>, "
                                         f"房间号: <cyan>{up.room_id}</>) 的直播间状态: "
                                         f"{'<green>直播中</>' if status == 1 else '<red>未开播</>'}")

            if status == 1 and start_time != await redis.get_live_start_time(up.room_id):
                await up.accumulate_and_reset_data()

            await redis.set_live_status(up.room_id, status)
            await redis.set_live_start_time(up.room_id, start_time)

        # 连接直播间
        for up in self.__datasource.get_up_list():
            try:
                await up.connect()
                await asyncio.sleep(0.2)
            except LiveException as ex:
                logger.error(ex.msg)
        try:
            wait_time = config.get("WAIT_FOR_ALL_CONNECTION_TIMEOUT")
            if wait_time == 0:
                wait_time = len(self.__datasource.get_up_list()) // 5 * 2
            await asyncio.wait_for(self.__datasource.wait_for_connects(), wait_time)
        except asyncio.exceptions.TimeoutError:
            logger.warning("等待连接所有直播间超时, 请检查是否存在未连接成功的直播间")

        # 启动动态推送模块
        asyncio.get_event_loop().create_task(dynamic_spider(self.__datasource))

        # 启动 HTTP API 服务
        if config.get("USE_HTTP_API"):
            asyncio.get_event_loop().create_task(http_init(self.__datasource))

        # 载入命令
        logger.info("开始载入命令模块")
        custom_commands = config.get("CUSTOM_COMMANDS_PACKAGE")

        saya = create(Saya)
        with saya.module_context():
            saya.require(f"starbot.commands.builtin")
            logger.success("内置命令模块载入完毕")
            if custom_commands:
                saya.require(custom_commands)
                logger.success("用户自定义命令模块载入完毕")

        # 自动关注打开了动态推送的未关注 UP 主
        if config.get("AUTO_FOLLOW_OPENED_DYNAMIC_UPDATE_UP"):
            if config.get("ACCOUNT_UID") is None:
                logger.warning("未填写 ACCOUNT_UID 配置项, 无法自动关注打开了动态推送的未关注 UP 主, "
                               "请使用 config.set('ACCOUNT_UID', 您的UID) 设置, 或手动关注所有需要动态推送的 UP 主, "
                               "否则无法获取未关注用户的动态更新信息, "
                               "使用 config.set('AUTO_FOLLOW_OPENED_DYNAMIC_UPDATE_UP', False) 可禁用自动关注功能和此警告")
                return

            uid = int(config.get("ACCOUNT_UID"))
            me = User(uid, get_credential())
            follows = set()
            page = 1
            while True:
                res = await me.get_followings(page)
                follows = follows.union(set(map(lambda x: x["mid"], res["list"])))
                if len(res["list"]) < 20:
                    break
                page += 1

            need_follow_uids = set()
            for up in self.__datasource.get_up_list():
                if any(map(lambda d: d.enabled, map(lambda t: t.dynamic_update, up.targets))):
                    need_follow_uids.add(up.uid)
            need_follow_uids.difference_update(follows)

            async def follow_task(uid_set):
                logger.info(f"检测到 {len(uid_set)} 个打开了动态推送但未关注的 UP 主, 启动自动关注任务")
                for u in uid_set:
                    follow_user = User(u, get_credential())
                    await follow_user.modify_relation(RelationType.SUBSCRIBE)
                    await asyncio.sleep(10)
                logger.success(f"已成功关注了 {len(uid_set)} 个 UP 主")

            asyncio.create_task(follow_task(need_follow_uids))

        # 启动消息推送模块
        Ariadne.options["default_account"] = self.__datasource.bots[0].qq

        logger.info("开始运行 Ariadne 消息推送模块")

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
        if config.get("LOG_TO_FILE"):
            logger.add("logs/{time:YYYY-MM}/starbot-{time:YYYY-MM-DD}.log", level="INFO",
                       format="{time:YYYY-MM-DD HH:mm:ss.SSS} | {level: <8} | {name}:{line} | {message}")
        logger.disable("graia.ariadne.model")
        logger.disable("graia.ariadne.service")
        logger.disable("graia.saya")
        logger.disable("launart")

        bcc = create(Broadcast)
        loop = bcc.loop
        loop.create_task(self.__main())
        loop.run_forever()
