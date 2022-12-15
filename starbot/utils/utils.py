"""
通用工具库
"""

import json
import os
import time
from typing import Dict

from . import config
from .Credential import Credential


def get_api(field: str) -> Dict:
    """
    获取 API

    Args:
        field: API 所属分类，即 data/api 下的文件名（不含后缀名）

    Returns:
        该 API 的内容
    """
    path = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "api", f"{field.lower()}.json"))
    if os.path.exists(path):
        with open(path, encoding="utf8") as f:
            return json.loads(f.read())


def get_credential() -> Credential:
    """
    获取登录凭据

    Returns:
        Credential 实例
    """
    sessdata = config.get("SESSDATA")
    bili_jct = config.get("BILI_JCT")
    buvid3 = config.get("BUVID3")
    return Credential(sessdata, bili_jct, buvid3)


def timestamp_format(timestamp: int) -> str:
    """
    时间戳格式化为形如 11/04 00:00:00 的字符串形式

    Args:
        timestamp: 时间戳

    Returns:
        格式化后的字符串
    """
    return time.strftime("%m/%d %H:%M:%S", time.localtime(timestamp))
