import asyncio
import json
import signal
import sys
from json import JSONDecodeError

from creart import create
from graia.ariadne import Ariadne
from graia.broadcast import Broadcast
from graia.saya import Saya
from loguru import logger

from .datasource import DataSource
from .dynamic import dynamic_spider
from .server import http_init
from .user import User, RelationType
from ..exception import LiveException, ResponseCodeException
from ..exception.DataSourceException import DataSourceException
from ..exception.RedisException import RedisException
from ..utils import redis, config
from ..utils.network import request, get_session
from ..utils.utils import get_credential, get_live_info_by_uids


class StarBot:
    """
    StarBot 类
    """
    VERSION = "2.0.17"
    STARBOT_ASCII_LOGO = "\n".join(
        (
            r"    _____ _             ____        _   ",
            r"   / ____| |           |  _ \      | |  ",
            r"  | (___ | |_ __ _ _ __| |_) | ___ | |_ ",
            r"   \___ \| __/ _` | '__|  _ < / _ \| __|",
            r"   ____) | || (_| | |  | |_) | (_) | |_ ",
            r"  |_____/ \__\__,_|_|  |____/ \___/ \__|",
            f"      StarBot - (v{VERSION})  2025-04-30",
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

    async def __backup_live_push(self):
        infos_before = await get_live_info_by_uids(self.__datasource.get_uid_list())
        status_before = {}
        for uid in infos_before:
            status = infos_before[uid]["live_status"]
            status_before[int(uid)] = status

        logger.success("备用直播推送模块已启动")

        while True:
            await asyncio.sleep(10)
            try:
                infos_after = await get_live_info_by_uids(self.__datasource.get_uid_list())
            except Exception as ex:
                logger.warning(f"备用直播推送模块数据抓取异常, 已忽略并继续 {ex}")
                continue
            for uid in infos_after:
                now_status = infos_after[uid]["live_status"]
                uid = int(uid)
                if uid not in status_before:
                    status_before[uid] = now_status
                    continue
                last_status = status_before[uid]
                status_before[uid] = now_status
                if now_status != last_status:
                    up = self.__datasource.get_up(uid)
                    if (not config.get("ONLY_CONNECT_NECESSARY_ROOM")) or up.is_need_connect():
                        if now_status == 1:
                            # logger.warning(f"备用: {up.uname}({up.room_id}) 开播")
                            param = {
                                "data": {
                                    "live_time": 0
                                }
                            }
                            up.dispatch("LIVE", param)
                        if last_status == 1:
                            # logger.warning(f"备用: {up.uname}({up.room_id}) 下播")
                            param = {}
                            up.dispatch("PREPARING", param)

    async def __main(self):
        """
        StarBot 入口
        """
        core_tasks = set()

        logger.opt(colors=True, raw=True).info(f"<yellow>{self.STARBOT_ASCII_LOGO}</>")
        if config.get("CHECK_VERSION"):
            try:
                response = await get_session().get("https://mirrors.cloud.tencent.com/pypi/json/starbot-bilibili")
                data = await response.text()
                latest_version = json.loads(data)["info"]["version"]
                if latest_version != self.VERSION:
                    logger.warning(f"检测到 StarBot 新版本 v{latest_version}, 建议升级到最新版本, "
                                   "升级内容和升级注意事项请参阅官网或 Github 页的迁移指南")
                    cmd = "pip install -i https://mirrors.cloud.tencent.com/pypi/simple --upgrade starbot-bilibili=="
                    logger.warning(f"升级命令: {cmd}{latest_version}")
            except Exception:
                logger.error("获取 StarBot 最新版本失败")
        logger.info("开始启动 StarBot")

        # 检查登录凭据完整性
        logger.info("开始尝试使用登录凭据登录 B 站账号")
        if config.get("SESSDATA") is None or config.get("BILI_JCT") is None or config.get("BUVID3") is None:
            logger.info("未配置 B 站登录凭据, 尝试从 credential.json 文件中读取")
            try:
                with open("credential.json", "r", encoding="utf-8") as file:
                    credential = json.loads(file.read())
                    config.set("SESSDATA", credential["sessdata"])
                    config.set("BILI_JCT", credential["bili_jct"])
                    config.set("BUVID3", credential["buvid3"])
                    logger.success("成功从 JSON 文件中读取了 B 站登录凭据")
            except FileNotFoundError:
                logger.warning("登录凭据 JSON 文件不存在")
            except UnicodeDecodeError:
                logger.warning("登录凭据 JSON 文件编码不正确, 请将其转换为 UTF-8 格式编码")
            except (JSONDecodeError, KeyError):
                logger.warning("登录凭据 JSON 文件格式不正确")
            except Exception as ex:
                logger.warning(f"读取登录凭据 JSON 文件异常 {ex}")

            if config.get("SESSDATA") is None or config.get("BILI_JCT") is None or config.get("BUVID3") is None:
                logger.error("未配置 B 站登录凭据, 请使用 config.set_credential(sessdata=\"B站账号的sessdata\", "
                             "bili_jct=\"B站账号的bili_jct\", buvid3=\"B站账号的buvid3\") 配置登录 B 站账号所需的登录凭据")
                return 1

        # 获取账号信息
        try:
            response = await request("GET", "https://api.bilibili.com/x/space/v2/myinfo", credential=get_credential())
            profile = response["profile"]
            uid = profile["mid"]
            uname = profile["name"]
            config.set("LOGIN_UID", uid)
            logger.opt(colors=True).info(f"<green>B 站账号登录成功, UID: <cyan>{uid}</>, 昵称: <cyan>{uname}</></>")
        except ResponseCodeException as ex:
            if ex.code == -101:
                logger.error("尝试登录 B 站账号失败, 可能的原因为登录凭据填写不正确或已失效, 请检查后重试")
                return 2
        except Exception as ex:
            logger.exception(f"尝试登录 B 站账号失败", ex)
            return 2

        # 从数据源中加载配置
        try:
            await self.__datasource.load()
        except DataSourceException as ex:
            logger.error(ex.msg)
            return 3

        if not self.__datasource.bots:
            logger.error("数据源配置为空, 请先在数据源中配置完毕后再重新运行")
            return 4

        # 连接 Redis
        try:
            await redis.init()
        except RedisException as ex:
            logger.error(ex.msg)
            return 5

        # 通过 UID 列表批量获取信息
        infos = await get_live_info_by_uids(self.__datasource.get_uid_list())
        for uid in infos:
            base = infos[uid]
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
        async def connect_room_task():
            interval = config.get("CONNECTION_INTERVAL")
            for u in self.__datasource.get_up_list():
                try:
                    if await u.connect():
                        await asyncio.sleep(interval)
                except LiveException as e:
                    logger.error(e.msg)

        asyncio.create_task(connect_room_task())
        if len(self.__datasource.get_up_list()) > 0:
            try:
                wait_time = config.get("WAIT_FOR_ALL_CONNECTION_TIMEOUT")
                if wait_time == 0:
                    wait_time = (len(self.__datasource.get_up_list()) + 5) // 5 * 2
                await asyncio.wait_for(self.__datasource.wait_for_connects(), wait_time)
            except asyncio.exceptions.TimeoutError:
                logger.warning("等待连接所有直播间超时, 请检查是否存在未连接成功的直播间")

        # 启动备用直播推送
        if config.get("BACKUP_LIVE_PUSH"):
            core_tasks.add(asyncio.get_event_loop().create_task(self.__backup_live_push()))

        # 启动动态推送模块
        core_tasks.add(asyncio.get_event_loop().create_task(dynamic_spider(self.__datasource)))

        # 启动 HTTP API 服务
        if config.get("USE_HTTP_API"):
            core_tasks.add(asyncio.get_event_loop().create_task(http_init(self.__datasource)))

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
            uid = config.get("LOGIN_UID")
            me = User(uid, get_credential())

            async def auto_follow_task():
                try:
                    follows = set()
                    page = 1
                    while True:
                        res = await me.get_followings(page)
                        follows = follows.union(set(map(lambda x: x["mid"], res["list"])))
                        if len(res["list"]) < 20:
                            break
                        page += 1

                    need_follow_uids = set()
                    for u in self.__datasource.get_up_list():
                        if u.uid != uid and any(map(lambda t: t.dynamic_update.enabled, u.targets)):
                            need_follow_uids.add(u.uid)
                    need_follow_uids.difference_update(follows)

                    if len(need_follow_uids) == 0:
                        logger.success(f"不存在打开了动态推送但未关注的 UP 主")
                        return

                    logger.info(f"检测到 {len(need_follow_uids)} 个打开了动态推送但未关注的 UP 主, 启动自动关注任务")
                    for i, u in enumerate(need_follow_uids):
                        follow_user = User(u, get_credential())
                        await follow_user.modify_relation(RelationType.SUBSCRIBE)
                        await asyncio.sleep(10)
                        logger.success(f"已关注: {i + 1} / {len(need_follow_uids)}")
                    logger.success(f"已成功关注了 {len(need_follow_uids)} 个 UP 主")
                except ResponseCodeException as e:
                    if e.code == 22115 or e.code == 22007:
                        logger.warning(f"读取登录账号的关注列表失败, 请检查登录凭据是否已失效, 错误信息: {e.msg}")
                except Exception as e:
                    logger.exception(f"自动关注任务异常", e)

            follow_task = asyncio.create_task(auto_follow_task())
            core_tasks.add(follow_task)
            follow_task.add_done_callback(lambda t: core_tasks.remove(t))

        # 检测消息补发配置完整性
        if config.get("BAN_RESEND") and config.get("MASTER_QQ") is None:
            logger.warning("检测到风控消息补发功能已开启, 但未配置机器人主人 QQ, 将会导致 \"补发\" 命令无法使用, 请使用 config.set(\"MASTER_QQ\", QQ号) 设置")

        # 受 B 站新风控机制影响，取到了 UID 为 0 的弹幕数据，自动删除掉这一批污染数据
        for key in await redis.keys("UserDanmuCount:*"):
            room_id = key.split(":")[1]
            count = int(await redis.zscore(key, 0))
            await redis.zrem(key, 0)
            await redis.hincrby("RoomDanmuCount", room_id, -count)
        for key in await redis.keys("UserDanmuTotal:*"):
            room_id = key.split(":")[1]
            count = int(await redis.zscore(key, 0))
            await redis.zrem(key, 0)
            await redis.hincrby("RoomDanmuTotal", room_id, -count)

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
                return 6

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
            logger.add("logs/{time:YYYY-MM}/starbot-{time:YYYY-MM-DD}.log", level="INFO", rotation="00:00",
                       format="{time:YYYY-MM-DD HH:mm:ss.SSS} | {level: <8} | {name}:{line} | {message}")
        logger.disable("graia.ariadne.model")
        logger.disable("graia.ariadne.service")
        logger.disable("graia.saya")
        logger.disable("launart")

        bcc = create(Broadcast)
        loop = bcc.loop
        if loop.run_until_complete(self.__main()):
            return
        try:
            loop.add_signal_handler(signal.SIGINT, lambda: loop.stop())
        except NotImplementedError:
            pass
        loop.run_forever()
        loop.close()
