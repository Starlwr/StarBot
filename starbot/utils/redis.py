from typing import Any, Optional, Union, Tuple, List

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

async def lrange(key: str, start: int, end: int) -> List:
    return list(map(lambda x: x.decode(), await __redis.lrange(key, start, end)))


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

async def zcard(key: str) -> int:
    return await __redis.zcard(key)


async def zrank(key: str, member: str) -> int:
    rank = await __redis.zrank(key, member)
    if rank is None:
        return 0
    return rank


async def zrevrangewithscoresi(key: str, start: int, end: int) -> List[Tuple[str, int]]:
    return list(map(lambda x: (x[0].decode(), int(x[1])), await __redis.zrevrange(key, start, end, True)))


async def zrevrangewithscoresf1(key: str, start: int, end: int) -> List[Tuple[str, float]]:
    return list(map(
        lambda x: (x[0].decode(), float("{:.1f}".format(float(x[1])))), await __redis.zrevrange(key, start, end, True)
    ))


async def zadd(key: str, member: str, score: Union[int, float]):
    await __redis.zadd(key, {member: score})


async def zincrby(key: str, member: Union[str, int], score: Optional[Union[int, float]] = 1) -> float:
    return await __redis.zincrby(key, score, member)


async def zunionstore(dest: str, source: Union[str, List[str]]):
    if isinstance(source, str):
        await __redis.zunionstore(dest, [dest, source])
    if isinstance(source, list):
        await __redis.zunionstore(dest, source)
