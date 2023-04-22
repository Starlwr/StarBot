import abc
import asyncio
import json
from typing import Union, Tuple, List, Dict, Optional

import aiomysql
import pymysql
from loguru import logger
from pydantic import ValidationError

from .model import LiveOn, LiveOff, LiveReport, DynamicUpdate, PushTarget, PushType
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

    def get_bot(self, qq: Optional[int] = None) -> Bot:
        """
        根据 QQ 获取 Bot 实例

        Args:
            qq: 需要获取 Bot 的 QQ，单 Bot 推送时可不传入

        Returns:
            Bot 实例

        Raises:
            DataSourceException: QQ 不存在
        """
        if qq is None:
            if len(self.bots) != 1:
                raise DataSourceException(f"多 Bot 推送时需明确指定要获取的 Bot QQ")
            return self.bots[0]

        bot = next((b for b in self.bots if b.qq == qq), None)
        if bot is None:
            raise DataSourceException(f"不存在的 QQ: {qq}")
        return bot

    def get_ups_by_target(self, target_id: int, target_type: PushType) -> List[Up]:
        """
        根据推送目标号码和推送目标类型获取 Up 实例列表

        Args:
            target_id: 需要获取 Up 的推送目标号码
            target_type: 需要获取 Up 的推送目标类型

        Returns:
            Up 实例列表
        """
        ups = []

        for up in self.__up_list:
            for target in up.targets:
                if target_id == target.id and target_type == target.type:
                    ups.append(up)
                    break

        return ups

    async def wait_for_connects(self):
        """
        等待所有 Up 实例连接直播间完毕
        """
        while True:
            await asyncio.sleep(1)

            flags = [u.is_connecting() for u in self.__up_list]
            if not any(flags):
                break


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


class JsonDataSource(DataSource):
    """
    从 JSON 字符串初始化的 Bot 推送配置数据源
    """
    def __init__(self, json_file: Optional[str] = None, json_str: Optional[str] = None):
        """
        Args:
            json_file: JSON 文件路径，两个参数任选其一传入，全部传入优先使用 json_str
            json_str: JSON 配置字符串，两个参数任选其一传入，全部传入优先使用 json_str
        """
        super().__init__()
        self.__config = None

        self.__json_file = json_file
        self.__json_str = json_str

    async def load(self):
        """
        从 JSON 字符串中初始化配置

        Raises:
            DataSourceException: JSON 格式错误或缺少必要参数
        """
        if self.bots:
            return

        if self.__json_file is None and self.__json_str is None:
            raise DataSourceException("JSON 文件路径和 JSON 字符串参数需至少传入一个")

        logger.info("已选用 JSON 作为 Bot 数据源")
        logger.info("开始从 JSON 中初始化 Bot 配置")

        if self.__json_str is None:
            try:
                with open(self.__json_file, "r") as file:
                    self.__json_str = file.read()
            except Exception:
                raise DataSourceException("JSON 文件不存在, 请检查文件路径是否正确")

        try:
            self.__config = json.loads(self.__json_str)
        except Exception:
            raise DataSourceException("提供的 JSON 字符串格式不正确")

        for bot in self.__config:
            if "qq" not in bot:
                raise DataSourceException("提供的 JSON 字符串中未提供 Bot 的 QQ 号参数")
            try:
                self.bots.append(Bot(**bot))
            except ValidationError as ex:
                raise DataSourceException(f"提供的 JSON 字符串中缺少必须的 {ex.errors()[0].get('loc')[-1]} 参数")

        super().format_data()
        logger.success(f"成功从 JSON 中导入了 {len(self.get_up_list())} 个 UP 主")


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

    async def __load_targets(self, uid: int) -> List[PushTarget]:
        """
        从 MySQL 中读取指定 UID 的推送配置

        Args:
            uid: 要读取配置的 UID

        Returns:
            推送目标列表
        """
        live_on = await self.__query(
            "SELECT t.`uid`, t.`uname`, t.`room_id`, `type`, `num`, `enabled`, `message` "
            "FROM `targets` AS `t` LEFT JOIN `live_on` AS `l` "
            "ON t.`uid` = l.`uid` AND t.`id` = l.`id` "
            f"WHERE t.`uid` = {uid} "
            "ORDER BY t.`id`"
        )
        live_off = await self.__query(
            "SELECT t.`uid`, t.`uname`, t.`room_id`, `type`, `num`, `enabled`, `message` "
            "FROM `targets` AS `t` LEFT JOIN `live_off` AS `l` "
            "ON t.`uid` = l.`uid` AND t.`id` = l.`id` "
            f"WHERE t.`uid` = {uid} "
            "ORDER BY t.`id`"
        )
        live_report = await self.__query(
            "SELECT t.`uid`, t.`uname`, t.`room_id`, `type`, `num`, "
            "`enabled`, `logo`, `logo_base64`, `time`, `fans_change`, `fans_medal_change`, `guard_change`, "
            "`danmu`, `box`, `gift`, `sc`, `guard`, "
            "`danmu_ranking`, `box_ranking`, `box_profit_ranking`, `gift_ranking`, `sc_ranking`, "
            "`guard_list`, `box_profit_diagram`, `danmu_diagram`, `box_diagram`, `gift_diagram`, "
            "`sc_diagram`, `guard_diagram`, `danmu_cloud` "
            "FROM `targets` AS `t` LEFT JOIN `live_report` AS `l` "
            "ON t.`uid` = l.`uid` AND t.`id` = l.`id` "
            f"WHERE t.`uid` = {uid} "
            "ORDER BY t.`id`"
        )
        dynamic_update = await self.__query(
            "SELECT t.`uid`, t.`uname`, t.`room_id`, `type`, `num`, `enabled`, `message` "
            "FROM `targets` AS `t` LEFT JOIN `dynamic_update` AS `d` "
            "ON t.`uid` = d.`uid` AND t.`id` = d.`id` "
            f"WHERE t.`uid` = {uid} "
            "ORDER BY t.`id`"
        )

        targets = []
        for i, target in enumerate(live_on):
            if all((live_on[i]["enabled"], live_on[i]["message"])):
                on = LiveOn(**live_on[i])
            else:
                on = LiveOn()
            if all((live_off[i]["enabled"], live_off[i]["message"])):
                off = LiveOff(**live_off[i])
            else:
                off = LiveOff()
            if live_report[i]["enabled"]:
                report = LiveReport(**live_report[i])
            else:
                report = LiveReport()
            if all((dynamic_update[i]["enabled"], dynamic_update[i]["message"])):
                update = DynamicUpdate(**dynamic_update[i])
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

        return targets

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

                targets = await self.__load_targets(uid)

                ups.append(Up(uid=uid, targets=targets))

            self.bots.append(Bot(qq=bot, ups=ups))

        super().format_data()
        logger.success(f"成功从 MySQL 中导入了 {len(self.get_up_list())} 个 UP 主")

    async def reload_targets(self, up: Union[int, Up]):
        """
        重新从 MySQL 中读取特定 Up 的推送配置

        Args:
            up: 需要重载配置的 Up 实例或其 UID
        """
        if isinstance(up, int):
            try:
                up = self.get_up(up)
            except DataSourceException:
                logger.warning(f"重载配置时出现异常, UID: {up} 不存在")
                return

        logger.info(f"开始从 MySQL 中重载 {up.uname} (UID: {up.uid}, 房间号: {up.room_id}) 的推送配置")

        if not self.__pool:
            await self.__connect()

        up.targets = await self.__load_targets(up.uid)

        super().format_data()
        logger.success(f"已成功重载 {up.uname} (UID: {up.uid}, 房间号: {up.room_id}) 的推送配置")

        await up.auto_reload_connect()

    async def load_new(self, uid: int):
        """
        从 MySQL 中追加读取指定 UID 的用户

        Args:
            uid: 需要追加读取配置的 UID
        """
        if uid in self.get_uid_list():
            raise DataSourceException(f"载入 UID: {uid} 的推送配置失败, 不可重复载入")

        user = await self.__query(f"SELECT * FROM `bot` WHERE uid = {uid}")
        if len(user) == 0:
            logger.error(f"载入 UID: {uid} 的推送配置失败, UID 不存在")
            raise DataSourceException(f"载入 UID: {uid} 的推送配置失败, UID 不存在")

        qq = user[0].get("bot")
        targets = await self.__load_targets(uid)
        up = Up(uid=uid, targets=targets)
        bot = self.get_bot(qq)
        bot.ups.append(up)
        up.inject_bot(bot)
        super().format_data()
        logger.success(f"已成功载入 UID: {uid} 的推送配置")

        await up.connect()
