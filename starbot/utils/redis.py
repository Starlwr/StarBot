from typing import Any, Union, Optional, List

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

async def delete(key: str):
    await __redis.delete(key)


# List

async def rpush(key: str, value: Any):
    await __redis.rpush(key, value)


# Hash

async def hexists(key: str, hkey: Union[str, int]) -> bool:
    return await __redis.hexists(key, hkey)


async def hgeti(key: str, hkey: Union[str, int]) -> int:
    result = await __redis.hget(key, hkey)
    if result is None:
        return 0
    return int(result)


async def hgetf1(key: str, hkey: Union[str, int]) -> float:
    result = await __redis.hget(key, hkey)
    if result is None:
        return 0.0
    return float("{:.1f}".format(float(result)))


async def hset(key: str, hkey: Union[str, int], value: Any):
    await __redis.hset(key, hkey, value)


async def hincrby(key: str, hkey: Union[str, int], value: Optional[int] = 1) -> int:
    return await __redis.hincrby(key, hkey, value)


async def hincrbyfloat(key: str, hkey: Union[str, int], value: Optional[float] = 1.0) -> float:
    return await __redis.hincrbyfloat(key, hkey, value)


# Zset

async def zunionstore(dest: str, source: Union[str, List[str]]):
    if isinstance(source, str):
        await __redis.zunionstore(dest, [dest, source])
    if isinstance(source, list):
        await __redis.zunionstore(dest, source)
