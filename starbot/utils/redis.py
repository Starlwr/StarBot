from typing import Any, Union

import aioredis
from loguru import logger

from ..exception.RedisException import RedisException
from ..utils import config

__redis: aioredis.client.Redis


async def init():
    global __redis
    logger.info("开始连接 Redis 数据库")
    host = config.get("REDIS_HOST")
    port = config.get("REDIS_PORT")
    db = config.get("REDIS_DB")
    username = config.get("REDIS_USERNAME")
    password = config.get("REDIS_PASSWORD")
    __redis = aioredis.from_url(f"redis://{host}:{port}/{db}", username=username, password=password)
    try:
        await __redis.ping()
    except Exception as ex:
        raise RedisException(f"连接 Redis 数据库失败, 请检查是否启动了 Redis 服务或提供的配置中连接参数是否正确 {ex}")
    logger.success("成功连接 Redis 数据库")


# String

async def get(key: str) -> str:
    return str(await __redis.get(key))


async def geti(key: str) -> int:
    return int(await __redis.get(key))


# Hash

async def hexists(key: str, hkey: Union[str, int]) -> bool:
    return await __redis.hexists(key, hkey)


async def hget(key: str, hkey: Union[str, int]) -> str:
    return str(await __redis.hget(key, hkey))


async def hgeti(key: str, hkey: Union[str, int]) -> int:
    return int(await __redis.hget(key, hkey))


async def hset(key: str, hkey: Union[str, int], value: Any):
    await __redis.hset(key, hkey, value)


async def hset_ifnotexists(key: str, hkey: Union[str, int], value: Any):
    if not await hexists(key, hkey):
        await hset(key, hkey, value)
