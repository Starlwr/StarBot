import abc
import asyncio
from typing import Union, Tuple, List, Dict, Optional

import aiomysql
import pymysql
from loguru import logger
from pydantic import ValidationError

from .model import LiveOn, LiveOff, LiveReport, DynamicUpdate, PushTarget
from .room import Up
from .sender import Bot
from ..exception.DataSourceException import DataSourceException
from ..utils import config


class DataSource(metaclass=abc.ABCMeta):
    """
    Bot 推送配置数据源基类
    """

    def __init__(self):
        self.bots: List[Bot] = []
        self.__up_list: List[Up] = []
        self.__up_map: Dict[int, Up] = {}
        self.__uid_list: List[int] = []
        self.__target_list: List[PushTarget] = []
        self.__target_key_map: Dict[str, PushTarget] = {}
        self.__target_bot_map: Dict[str, Bot] = {}

    @abc.abstractmethod
    async def load(self):
        """
        读取配置，基类空实现
        """
        pass

    def format_data(self):
        """
        处理读取后的配置

        Raises:
            DataSourceException: 配置中包含重复 uid
        """
        self.__up_list = [x for up in map(lambda b: b.ups, self.bots) for x in up]
        self.__up_map = dict(zip(map(lambda up: up.uid, self.__up_list), self.__up_list))
        self.__uid_list = list(self.__up_map.keys())
        if len(set(self.__uid_list)) < len(self.__uid_list):
            raise DataSourceException("配置中不可含有重复的 UID")
        self.__target_list = [x for target in map(lambda up: up.targets, self.__up_list) for x in target]
        self.__target_key_map = dict(zip(map(lambda target: target.key, self.__target_list), self.__target_list))

        for bot in self.bots:
            for up in bot.ups:
                for target in up.targets:
                    self.__target_bot_map[target.key] = bot

    def get_up_list(self) -> List[Up]:
        """
        获取数据源中所有的 UP 实例

        Returns:
            数据源中所有的 UP 实例列表
        """
        return self.__up_list

    def get_uid_list(self) -> List[int]:
        """
        获取数据源中所有的 UID

        Returns:
            数据源中所有的 UID 列表
        """
        return self.__uid_list

    def get_up(self, uid: int) -> Up:
        """
        根据 UID 获取 Up 实例

        Args:
            uid: 需要获取 Up 的 UID

        Returns:
            Up 实例

        Raises:
            DataSourceException: uid 不存在
        """
        up = self.__up_map.get(uid)
        if up is None:
            raise DataSourceException(f"不存在的 UID: {uid}")
        return up

    def get_target_by_key(self, key: str) -> PushTarget:
        """
        根据推送 key 获取 PushTarget 实例，用于 HTTP API 推送

        Args:
            key: 需要获取 PushTarget 的推送 key

        Returns:
            PushTarget 实例

        Raises:
            DataSourceException: key 不存在
        """
        target = self.__target_key_map.get(key)
        if target is None:
            raise DataSourceException(f"不存在的推送 key: {key}")
        return target

    def get_bot_by_key(self, key: str) -> Bot:
        """
        根据推送 key 获取其所在的 Bot 实例，用于 HTTP API 推送

        Args:
            key: 需要获取所在 Bot 的推送 key

        Returns:
            Bot 实例

        Raises:
            DataSourceException: key 不存在
        """
        bot = self.__target_bot_map.get(key)
        if bot is None:
            raise DataSourceException(f"不存在的推送 key: {key}")
        return bot


class DictDataSource(DataSource):
    """
    使用字典结构初始化的 Bot 推送配置数据源
    """

    def __init__(self, dict_config: Union[List[Dict], Dict]):
        """
        Args:
            dict_config: 配置字典
        """
        super().__init__()
        self.__config = dict_config

        if isinstance(self.__config, dict):
            self.__config = [self.__config]

    async def load(self):
        """
        从配置字典中初始化配置

        Raises:
            DataSourceException: 配置字典格式错误或缺少必要参数
        """
        if self.bots:
            return

        logger.info("已选用 Dict 作为 Bot 数据源")
        logger.info("开始从 Dict 中初始化 Bot 配置")

        for bot in self.__config:
            if "qq" not in bot:
                raise DataSourceException("提供的配置字典中未提供 Bot 的 QQ 号参数")
            try:
                self.bots.append(Bot(**bot))
            except ValidationError as ex:
                raise DataSourceException(f"提供的配置字典中缺少必须的 {ex.errors()[0].get('loc')[-1]} 参数")

        super().format_data()
        logger.success(f"成功从 Dict 中导入了 {len(self.get_up_list())} 个 UP 主")


class MySQLDataSource(DataSource):
    """
    从 MySQL 初始化的 Bot 推送配置数据源
    """

    def __init__(self,
                 username: str = None,
                 password: str = None,
                 host: str = None,
                 port: int = None,
                 db: str = None):
        """
        Args:
            username: MySQL 用户名。默认：config.get("MYSQL_USERNAME") = "root"
            password: MySQL 密码。默认：config.get("MYSQL_PASSWORD") = "123456"
            host: MySQL 连接地址。默认：config.get("MYSQL_HOST") = "localhost"
            port: MySQL 连接端口。默认：config.get("MYSQL_PORT") = 3306
            db: MySQL 数据库名。默认：config.get("MYSQL_DB") = "starbot"
        """
        super().__init__()
        self.__username = username or config.get("MYSQL_USERNAME")
        self.__password = password or str(config.get("MYSQL_PASSWORD"))
        self.__host = host or config.get("MYSQL_HOST")
        self.__port = port or int(config.get("MYSQL_PORT"))
        self.__db = db or config.get("MYSQL_DB")
        self.__pool: Optional[aiomysql.pool.Pool] = None

    async def __connect(self):
        """
        连接 MySQL

        Raises:
            DataSourceException: 连接数据库失败
        """
        try:
            self.__loop = asyncio.get_event_loop()
            self.__pool = await aiomysql.create_pool(host=self.__host,
                                                     port=self.__port,
                                                     user=self.__username,
                                                     password=self.__password,
                                                     db=self.__db,
                                                     loop=self.__loop,
                                                     autocommit=True,
                                                     minsize=3,
                                                     pool_recycle=25200)
        except pymysql.err.Error as ex:
            raise DataSourceException(f"连接 MySQL 数据库失败, 请检查是否启动了 MySQL 服务或提供的配置中连接参数是否正确 {ex}")

    async def __query(self, sql: str, args: Union[Tuple, List] = None) -> Dict:
        """
        执行 MySQL 查询

        Args:
            sql: 要执行的 SQL 语句
            args: 参数。默认：None

        Returns:
            结果集字典

        Raises:
            DataSourceException: SQL 语句执行失败
        """
        async with self.__pool.acquire() as conn:
            async with conn.cursor(aiomysql.DictCursor) as cursor:
                try:
                    await cursor.execute(sql, args)
                    res = await cursor.fetchall()
                    return res
                except pymysql.err.Error as ex:
                    raise DataSourceException(f"从 MySQL 中读取配置时发生了错误 {ex}")

    async def load(self):
        """
        从 MySQL 中初始化配置
        """
        if self.bots:
            return

        logger.info("已选用 MySQL 作为 Bot 数据源")
        logger.info("开始从 MySQL 中初始化 Bot 配置")

        if not self.__pool:
            await self.__connect()

        users = await self.__query("SELECT * FROM `bot`")

        bots = set(map(lambda u: u.get("bot"), users))

        for bot in bots:
            bot_users = list(filter(lambda u: u.get("bot") == bot, users))
            ups = []

            for now_user in bot_users:
                uid = now_user.get("uid")

                live_on = await self.__query(
                    "SELECT g.`uid`, g.`uname`, g.`room_id`, `key`, `type`, `num`, `enabled`, `message` "
                    "FROM `groups` AS `g` LEFT JOIN `live_on` AS `l` "
                    "ON g.`uid` = l.`uid` AND g.`index` = l.`index` "
                    f"WHERE g.`uid` = {uid} "
                    "ORDER BY g.`index`"
                )
                live_off = await self.__query(
                    "SELECT g.`uid`, g.`uname`, g.`room_id`, `key`, `type`, `num`, `enabled`, `message` "
                    "FROM `groups` AS `g` LEFT JOIN `live_off` AS `l` "
                    "ON g.`uid` = l.`uid` AND g.`index` = l.`index` "
                    f"WHERE g.`uid` = {uid} "
                    "ORDER BY g.`index`"
                )
                live_report = await self.__query(
                    "SELECT g.`uid`, g.`uname`, g.`room_id`, `key`, `type`, `num`, "
                    "`enabled`, `logo`, `time`, `fans_change`, `fans_medal_change`, `guard_change`, "
                    "`danmu`, `box`, `gift`, `sc`, `guard`, `danmu_cloud` "
                    "FROM `groups` AS `g` LEFT JOIN `live_report` AS `l` "
                    "ON g.`uid` = l.`uid` AND g.`index` = l.`index` "
                    f"WHERE g.`uid` = {uid} "
                    "ORDER BY g.`index`"
                )
                dynamic_update = await self.__query(
                    "SELECT g.`uid`, g.`uname`, g.`room_id`, `key`, `type`, `num`, `enabled`, `message` "
                    "FROM `groups` AS `g` LEFT JOIN `dynamic_update` AS `d` "
                    "ON g.`uid` = d.`uid` AND g.`index` = d.`index` "
                    f"WHERE g.`uid` = {uid} "
                    "ORDER BY g.`index`"
                )

                targets = []
                for i, target in enumerate(live_on):
                    if all((live_on[i]["enabled"], live_on[i]["message"])):
                        on = LiveOn(enabled=live_on[i]["enabled"],
                                    message=live_on[i]["message"])
                    else:
                        on = LiveOn()
                    if all((live_off[i]["enabled"], live_off[i]["message"])):
                        off = LiveOff(enabled=live_off[i]["enabled"],
                                      message=live_off[i]["message"])
                    else:
                        off = LiveOff()
                    if live_report[i]["enabled"]:
                        report = LiveReport(enabled=live_report[i]["enabled"],
                                            logo=live_report[i]["logo"],
                                            time=live_report[i]["time"],
                                            fans_change=live_report[i]["fans_change"],
                                            fans_medal_change=live_report[i]["fans_medal_change"],
                                            guard_change=live_report[i]["guard_change"],
                                            danmu=live_report[i]["danmu"],
                                            box=live_report[i]["box"],
                                            gift=live_report[i]["gift"],
                                            sc=live_report[i]["sc"],
                                            guard=live_report[i]["guard"],
                                            danmu_cloud=live_report[i]["danmu_cloud"])
                    else:
                        report = LiveReport()
                    if all((dynamic_update[i]["enabled"], dynamic_update[i]["message"])):
                        update = DynamicUpdate(enabled=dynamic_update[i]["enabled"],
                                               message=dynamic_update[i]["message"])
                    else:
                        update = DynamicUpdate()

                    targets.append(
                        PushTarget(
                            id=target["num"],
                            type=target["type"],
                            live_on=on,
                            live_off=off,
                            live_report=report,
                            dynamic_update=update
                        )
                    )

                ups.append(Up(uid=uid, targets=targets))

            self.bots.append(Bot(qq=bot, ups=ups))

        super().format_data()
        logger.success(f"成功从 MySQL 中导入了 {len(self.get_up_list())} 个 UP 主")
