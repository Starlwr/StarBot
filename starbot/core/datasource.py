import abc
from typing import Union, List, Dict

from loguru import logger
from pydantic import ValidationError

from .model import Up, Bot
from ..exception.DataSourceException import DataSourceException


class DataSource(metaclass=abc.ABCMeta):
    """
    Bot 推送配置数据源基类
    """

    def __init__(self):
        self.bots = {}
        self.__up_list = []
        self.__uid_list = []
        self.__up_map = {}

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
        self.__up_list = [x for up in map(lambda bot: bot.ups, self.bots.values()) for x in up]
        self.__uid_list = list(map(lambda up: up.uid, self.__up_list))
        if len(set(self.__uid_list)) < len(self.__uid_list):
            raise DataSourceException("配置中不可含有重复的 UID")
        self.__up_map = dict(zip(map(lambda up: up.uid, self.__up_list), self.__up_list))

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
        logger.info("已选用 Dict 作为 Bot 数据源")
        self.__config = dict_config

        if isinstance(self.__config, dict):
            self.__config = [self.__config]

    async def load(self):
        """
        从配置字典中初始化配置

        Raises:
            DataSourceException: 配置字典格式错误或缺少必要参数
        """
        logger.info("开始从 Dict 中初始化 Bot 配置")

        for bot in self.__config:
            if "qq" not in bot:
                raise DataSourceException("提供的配置字典中未提供 Bot 的 QQ 号参数")
            try:
                self.bots.update({bot["qq"]: Bot(**bot)})
            except ValidationError as ex:
                raise DataSourceException(f"提供的配置字典中缺少必须的 {ex.errors()[0].get('loc')[-1]} 参数")

        super().format_data()
        logger.success(f"成功从 Dict 中初始化了 {len(self.get_up_list())} 个 UP 主")
