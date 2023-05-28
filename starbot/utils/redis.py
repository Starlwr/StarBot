from typing import Any, Union, Tuple, List, Set

from loguru import logger
from redis import asyncio as aioredis

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

async def expire(key: str, seconds: int):
    await __redis.expire(key, seconds)


async def exists(key: str) -> bool:
    return bool(await __redis.exists(key))


async def get(key: str) -> str:
    result = await __redis.get(key)
    if result is None:
        return ""
    return result.decode()


async def geti(key: str) -> int:
    result = await __redis.get(key)
    if result is None:
        return 0
    return int(result)


async def incr(key: str, value: int = 1) -> int:
    return await __redis.incr(key, value)


async def set_(key: str, value: Union[str, int]):
    await __redis.set(key, value)


async def delete(key: str):
    await __redis.delete(key)


# List

async def lrange(key: str, start: int, end: int) -> List[str]:
    return [x.decode() for x in await __redis.lrange(key, start, end)]


async def lrangei(key: str, start: int, end: int) -> List[int]:
    return [int(x) for x in await __redis.lrange(key, start, end)]


async def lrangef1(key: str, start: int, end: int) -> List[float]:
    return [float("{:.1f}".format(float(x))) for x in await __redis.lrange(key, start, end)]


async def rpush(key: str, value: Any):
    await __redis.rpush(key, value)


# Hash

async def hexists(key: str, hkey: Union[str, int]) -> bool:
    return await __redis.hexists(key, hkey)


async def hget(key: str, hkey: Union[str, int]) -> str:
    result = await __redis.hget(key, hkey)
    if result is None:
        return ""
    return result.decode()


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


async def hgetalltuplei(key: str) -> List[Tuple[str, int]]:
    result = await __redis.hgetall(key)
    return [(x.decode(), int(result[x])) for x in result]


async def hgetalltuplef1(key: str) -> List[Tuple[str, float]]:
    result = await __redis.hgetall(key)
    return [(x.decode(), float("{:.1f}".format(float(result[x])))) for x in result]


async def hset(key: str, hkey: Union[str, int], value: Any):
    await __redis.hset(key, hkey, value)


async def hincrby(key: str, hkey: Union[str, int], value: int = 1) -> int:
    return await __redis.hincrby(key, hkey, value)


async def hincrbyfloat(key: str, hkey: Union[str, int], value: float = 1.0) -> float:
    return await __redis.hincrbyfloat(key, hkey, value)


async def hdel(key: str, hkey: Union[str, int]):
    await __redis.hdel(key, hkey)


# Set

async def scard(key: str) -> int:
    return await __redis.scard(key)


async def sismember(key: str, member: Union[str, int]) -> bool:
    return await __redis.sismember(key, member)


async def smembers(key: str) -> Set[int]:
    return {int(x) for x in await __redis.smembers(key)}


async def sadd(key: str, member: Union[str, int]):
    await __redis.sadd(key, member)


async def srem(key: str, member: Union[str, int]):
    await __redis.srem(key, member)


# Zset

async def zcard(key: str) -> int:
    return await __redis.zcard(key)


async def zrank(key: str, member: str) -> int:
    rank = await __redis.zrank(key, member)
    if rank is None:
        return 0
    return rank


async def zscore(key: str, member: Union[str, int]) -> float:
    score = await __redis.zscore(key, member)
    if score is None:
        return 0.0
    return score


async def zrange(key: str, start: int, end: int) -> List[str]:
    return [x.decode() for x in await __redis.zrange(key, start, end)]


async def zrangewithscoresi(key: str, start: int, end: int) -> List[Tuple[str, int]]:
    return [(x[0].decode(), int(x[1])) for x in await __redis.zrange(key, start, end, withscores=True)]


async def zrangewithscoresf1(key: str, start: int, end: int) -> List[Tuple[str, float]]:
    return [
        (x[0].decode(), float("{:.1f}".format(float(x[1]))))
        for x in await __redis.zrange(key, start, end, withscores=True)
    ]


async def zrevrangewithscoresi(key: str, start: int, end: int) -> List[Tuple[str, int]]:
    return [(x[0].decode(), int(x[1])) for x in await __redis.zrevrange(key, start, end, True)]


async def zrevrangewithscoresf1(key: str, start: int, end: int) -> List[Tuple[str, float]]:
    return [
        (x[0].decode(), float("{:.1f}".format(float(x[1])))) for x in await __redis.zrevrange(key, start, end, True)
    ]


async def zadd(key: str, member: str, score: Union[int, float]):
    await __redis.zadd(key, {member: score})


async def zincrby(key: str, member: Union[str, int], score: Union[int, float] = 1) -> float:
    return await __redis.zincrby(key, score, member)


async def zunionstore(dest: str, source: Union[str, List[str]]):
    if isinstance(source, str):
        await __redis.zunionstore(dest, [dest, source])
    if isinstance(source, list):
        await __redis.zunionstore(dest, source)


# StarBot

# 直播间状态，0：未开播，1：正在直播，2：轮播

async def exists_live_status(room_id: int) -> bool:
    return await hexists("LiveStatus", room_id)


async def get_live_status(room_id: int) -> int:
    return await hgeti("LiveStatus", room_id)


async def set_live_status(room_id: int, status: int):
    await hset("LiveStatus", room_id, status)


# 直播开始时间

async def exists_live_start_time(room_id: int) -> bool:
    return await hexists("StartTime", room_id)


async def get_live_start_time(room_id: int) -> int:
    return await hgeti("StartTime", room_id)


async def set_live_start_time(room_id: int, start_time: int):
    await hset("StartTime", room_id, start_time)


# 直播结束时间

async def get_live_end_time(room_id: int) -> int:
    return await hgeti("EndTime", room_id)


async def set_live_end_time(room_id: int, end_time: int):
    await hset("EndTime", room_id, end_time)


# 粉丝数

async def exists_fans_count(room_id: int, start_time: int) -> bool:
    return await hexists(f"FansCount:{room_id}", start_time)


async def get_fans_count(room_id: int, start_time: int) -> int:
    return await hgeti(f"FansCount:{room_id}", start_time)


async def set_fans_count(room_id: int, start_time: int, fans_count: int):
    await hset(f"FansCount:{room_id}", start_time, fans_count)


# 粉丝团人数

async def exists_fans_medal_count(room_id: int, start_time: int) -> bool:
    return await hexists(f"FansMedalCount:{room_id}", start_time)


async def get_fans_medal_count(room_id: int, start_time: int) -> int:
    return await hgeti(f"FansMedalCount:{room_id}", start_time)


async def set_fans_medal_count(room_id: int, start_time: int, fans_medal_count: int):
    await hset(f"FansMedalCount:{room_id}", start_time, fans_medal_count)


# 大航海人数

async def exists_guard_count(room_id: int, start_time: int) -> bool:
    return await hexists(f"GuardCount:{room_id}", start_time)


async def get_guard_count(room_id: int, start_time: int) -> int:
    return await hgeti(f"GuardCount:{room_id}", start_time)


async def set_guard_count(room_id: int, start_time: int, guard_count: int):
    await hset(f"GuardCount:{room_id}", start_time, guard_count)


# 房间弹幕数量

async def get_room_danmu_count(room_id: int) -> int:
    return await hgeti("RoomDanmuCount", room_id)


async def incr_room_danmu_count(room_id: int) -> int:
    return await hincrby("RoomDanmuCount", room_id)


async def reset_room_danmu_count(room_id: int):
    await hset("RoomDanmuCount", room_id, 0)


# 房间累计弹幕数量

async def get_room_danmu_total(room_id: int) -> int:
    return await hgeti("RoomDanmuTotal", room_id)


async def accumulate_room_danmu_total(room_id: int) -> int:
    return await hincrby("RoomDanmuTotal", room_id, await get_room_danmu_count(room_id))


# 房间总弹幕数量

async def get_room_danmu_all(room_id: int) -> int:
    return await get_room_danmu_count(room_id) + await get_room_danmu_total(room_id)


# 用户弹幕数量

async def get_user_danmu_count(room_id: int, uid: int) -> int:
    return int(await zscore(f"UserDanmuCount:{room_id}", uid))


async def len_user_danmu_count(room_id: int) -> int:
    return await zcard(f"UserDanmuCount:{room_id}")


async def range_user_danmu_count(room_id: int, start: int = 0, end: int = -1) -> List[Tuple[str, int]]:
    return await zrangewithscoresi(f"UserDanmuCount:{room_id}", start, end)


async def rev_range_user_danmu_count(room_id: int, start: int = 0, end: int = -1) -> List[Tuple[str, int]]:
    return await zrevrangewithscoresi(f"UserDanmuCount:{room_id}", start, end)


async def incr_user_danmu_count(room_id: int, uid: int) -> float:
    return await zincrby(f"UserDanmuCount:{room_id}", uid)


async def delete_user_danmu_count(room_id: int):
    await delete(f"UserDanmuCount:{room_id}")


# 用户累计弹幕数量

async def get_user_danmu_total(room_id: int, uid: int) -> int:
    return int(await zscore(f"UserDanmuTotal:{room_id}", uid))


async def accumulate_user_danmu_total(room_id: int):
    await zunionstore(f"UserDanmuTotal:{room_id}", f"UserDanmuCount:{room_id}")


# 用户总弹幕数量

async def len_user_danmu_all(room_id: int) -> int:
    return len(
        set(await zrange(f"UserDanmuCount:{room_id}", 0, -1))
        .union(set(await zrange(f"UserDanmuTotal:{room_id}", 0, -1)))
    )


async def get_user_danmu_all(room_id: int, uid: int) -> int:
    return await get_user_danmu_count(room_id, uid) + await get_user_danmu_total(room_id, uid)


async def range_user_danmu_all(room_id: int, start: int = 0, end: int = -1) -> List[Tuple[str, int]]:
    await zunionstore(f"TempDanmuTotal:{room_id}", [f"UserDanmuCount:{room_id}", f"UserDanmuTotal:{room_id}"])
    result = await zrangewithscoresi(f"TempDanmuTotal:{room_id}", start, end)
    await delete(f"TempDanmuTotal:{room_id}")
    return result


async def rev_range_user_danmu_all(room_id: int, start: int = 0, end: int = -1) -> List[Tuple[str, int]]:
    await zunionstore(f"TempDanmuTotal:{room_id}", [f"UserDanmuCount:{room_id}", f"UserDanmuTotal:{room_id}"])
    result = await zrevrangewithscoresi(f"TempDanmuTotal:{room_id}", start, end)
    await delete(f"TempDanmuTotal:{room_id}")
    return result


# 房间弹幕记录

async def get_room_danmu(room_id: int) -> List[str]:
    return await lrange(f"RoomDanmu:{room_id}", 0, -1)


async def add_room_danmu(room_id: int, content: str):
    await rpush(f"RoomDanmu:{room_id}", content)


async def delete_room_danmu(room_id: int):
    await delete(f"RoomDanmu:{room_id}")


# 房间弹幕时间分布

async def get_room_danmu_time(room_id: int) -> List[Tuple[str, int]]:
    return await hgetalltuplei(f"RoomDanmuTime:{room_id}")


async def incr_room_danmu_time(room_id: int, timestamp: int) -> int:
    return await hincrby(f"RoomDanmuTime:{room_id}", timestamp)


async def delete_room_danmu_time(room_id: int):
    await delete(f"RoomDanmuTime:{room_id}")


# 房间盲盒数量

async def get_room_box_count(room_id: int) -> int:
    return await hgeti("RoomBoxCount", room_id)


async def incr_room_box_count(room_id: int, count: int) -> int:
    return await hincrby("RoomBoxCount", room_id, count)


async def reset_room_box_count(room_id: int):
    await hset("RoomBoxCount", room_id, 0)


# 房间累计盲盒数量

async def get_room_box_total(room_id: int) -> int:
    return await hgeti("RoomBoxTotal", room_id)


async def accumulate_room_box_total(room_id: int) -> int:
    return await hincrby("RoomBoxTotal", room_id, await get_room_box_count(room_id))


# 房间总盲盒数量

async def get_room_box_all(room_id: int) -> int:
    return await get_room_box_count(room_id) + await get_room_box_total(room_id)


# 用户盲盒数量

async def get_user_box_count(room_id: int, uid: int) -> int:
    return int(await zscore(f"UserBoxCount:{room_id}", uid))


async def len_user_box_count(room_id: int) -> int:
    return await zcard(f"UserBoxCount:{room_id}")


async def range_user_box_count(room_id: int, start: int = 0, end: int = -1) -> List[Tuple[str, int]]:
    return await zrangewithscoresi(f"UserBoxCount:{room_id}", start, end)


async def rev_range_user_box_count(room_id: int, start: int = 0, end: int = -1) -> List[Tuple[str, int]]:
    return await zrevrangewithscoresi(f"UserBoxCount:{room_id}", start, end)


async def incr_user_box_count(room_id: int, uid: int, count: int) -> float:
    return await zincrby(f"UserBoxCount:{room_id}", uid, count)


async def delete_user_box_count(room_id: int):
    await delete(f"UserBoxCount:{room_id}")


# 用户累计盲盒数量

async def get_user_box_total(room_id: int, uid: int) -> int:
    return int(await zscore(f"UserBoxTotal:{room_id}", uid))


async def accumulate_user_box_total(room_id: int):
    await zunionstore(f"UserBoxTotal:{room_id}", f"UserBoxCount:{room_id}")


# 用户总盲盒数量

async def len_user_box_all(room_id: int) -> int:
    return len(
        set(await zrange(f"UserBoxCount:{room_id}", 0, -1))
        .union(set(await zrange(f"UserBoxTotal:{room_id}", 0, -1)))
    )


async def get_user_box_all(room_id: int, uid: int) -> int:
    return await get_user_box_count(room_id, uid) + await get_user_box_total(room_id, uid)


async def range_user_box_all(room_id: int, start: int = 0, end: int = -1) -> List[Tuple[str, int]]:
    await zunionstore(f"TempBoxTotal:{room_id}", [f"UserBoxCount:{room_id}", f"UserBoxTotal:{room_id}"])
    result = await zrangewithscoresi(f"TempBoxTotal:{room_id}", start, end)
    await delete(f"TempBoxTotal:{room_id}")
    return result


async def rev_range_user_box_all(room_id: int, start: int = 0, end: int = -1) -> List[Tuple[str, int]]:
    await zunionstore(f"TempBoxTotal:{room_id}", [f"UserBoxCount:{room_id}", f"UserBoxTotal:{room_id}"])
    result = await zrevrangewithscoresi(f"TempBoxTotal:{room_id}", start, end)
    await delete(f"TempBoxTotal:{room_id}")
    return result


# 房间盲盒盈亏

async def get_room_box_profit(room_id: int) -> float:
    return await hgetf1("RoomBoxProfit", room_id)


async def incr_room_box_profit(room_id: int, profit: float) -> float:
    return await hincrbyfloat("RoomBoxProfit", room_id, profit)


async def reset_room_box_profit(room_id: int):
    await hset("RoomBoxProfit", room_id, 0)


# 房间累计盲盒盈亏

async def get_room_box_profit_total(room_id: int) -> float:
    return await hgetf1("RoomBoxProfitTotal", room_id)


async def accumulate_room_box_profit_total(room_id: int) -> float:
    return await hincrbyfloat("RoomBoxProfitTotal", room_id, await get_room_box_profit(room_id))


# 房间总盲盒盈亏

async def get_room_box_profit_all(room_id: int) -> float:
    return float("{:.1f}".format(await get_room_box_profit(room_id) + await get_room_box_profit_total(room_id)))


# 用户盲盒盈亏

async def get_user_box_profit(room_id: int, uid: int) -> float:
    return float("{:.1f}".format(await zscore(f"UserBoxProfit:{room_id}", uid)))


async def range_user_box_profit(room_id: int, start: int = 0, end: int = -1) -> List[Tuple[str, float]]:
    return await zrangewithscoresf1(f"UserBoxProfit:{room_id}", start, end)


async def rev_range_user_box_profit(room_id: int, start: int = 0, end: int = -1) -> List[Tuple[str, float]]:
    return await zrevrangewithscoresf1(f"UserBoxProfit:{room_id}", start, end)


async def incr_user_box_profit(room_id: int, uid: int, profit: float) -> float:
    return await zincrby(f"UserBoxProfit:{room_id}", uid, profit)


async def delete_user_box_profit(room_id: int):
    await delete(f"UserBoxProfit:{room_id}")


# 用户累计盲盒盈亏

async def get_user_box_profit_total(room_id: int, uid: int) -> float:
    return float("{:.1f}".format(await zscore(f"UserBoxProfitTotal:{room_id}", uid)))


async def accumulate_user_box_profit_total(room_id: int):
    await zunionstore(f"UserBoxProfitTotal:{room_id}", f"UserBoxProfit:{room_id}")


# 用户总盲盒盈亏

async def get_user_box_profit_all(room_id: int, uid: int) -> float:
    return float("{:.1f}".format(
        await get_user_box_profit(room_id, uid) + await get_user_box_profit_total(room_id, uid)
    ))


async def range_user_box_profit_all(room_id: int, start: int = 0, end: int = -1) -> List[Tuple[str, float]]:
    await zunionstore(f"TempBoxProfitTotal:{room_id}", [f"UserBoxProfit:{room_id}", f"UserBoxProfitTotal:{room_id}"])
    result = await zrangewithscoresf1(f"TempBoxProfitTotal:{room_id}", start, end)
    await delete(f"TempBoxProfitTotal:{room_id}")
    return result


async def rev_range_user_box_profit_all(room_id: int, start: int = 0, end: int = -1) -> List[Tuple[str, float]]:
    await zunionstore(f"TempBoxProfitTotal:{room_id}", [f"UserBoxProfit:{room_id}", f"UserBoxProfitTotal:{room_id}"])
    result = await zrevrangewithscoresf1(f"TempBoxProfitTotal:{room_id}", start, end)
    await delete(f"TempBoxProfitTotal:{room_id}")
    return result


# 房间盲盒盈亏记录，用于绘制直播报告中盲盒盈亏曲线图

async def get_room_box_profit_record(room_id: int) -> List[float]:
    return await lrangef1(f"RoomBoxProfitRecord:{room_id}", 0, -1)


async def add_room_box_profit_record(room_id: int, profit: float):
    await rpush(f"RoomBoxProfitRecord:{room_id}", profit)


async def delete_room_box_profit_record(room_id: int):
    await delete(f"RoomBoxProfitRecord:{room_id}")


# 盲盒盈亏记录，用于计算直播报告中击败了百分之多少的直播间

async def len_box_profit_record() -> int:
    return await zcard("BoxProfitRecord")


async def rank_box_profit_record(start_time: int, uid: int, uname: str) -> int:
    return await zrank("BoxProfitRecord", f"{start_time}-{uid}-{uname}")


async def add_box_profit_record(start_time: int, uid: int, uname: str, profit: float):
    await zadd("BoxProfitRecord", f"{start_time}-{uid}-{uname}", profit)


# 房间盲盒时间分布

async def get_room_box_time(room_id: int) -> List[Tuple[str, int]]:
    return await hgetalltuplei(f"RoomBoxTime:{room_id}")


async def incr_room_box_time(room_id: int, timestamp: int) -> int:
    return await hincrby(f"RoomBoxTime:{room_id}", timestamp)


async def delete_room_box_time(room_id: int):
    await delete(f"RoomBoxTime:{room_id}")


# 房间礼物价值

async def get_room_gift_profit(room_id: int) -> float:
    return await hgetf1("RoomGiftProfit", room_id)


async def incr_room_gift_profit(room_id: int, price: float) -> float:
    return await hincrbyfloat("RoomGiftProfit", room_id, price)


async def reset_room_gift_profit(room_id: int):
    await hset("RoomGiftProfit", room_id, 0)


# 房间累计礼物价值

async def get_room_gift_total(room_id: int) -> float:
    return await hgetf1("RoomGiftTotal", room_id)


async def accumulate_room_gift_total(room_id: int) -> float:
    return await hincrbyfloat("RoomGiftTotal", room_id, await get_room_gift_profit(room_id))


# 房间总礼物价值

async def get_room_gift_all(room_id: int) -> float:
    return float("{:.1f}".format(await get_room_gift_profit(room_id) + await get_room_gift_total(room_id)))


# 用户礼物价值

async def get_user_gift_profit(room_id: int, uid: int) -> float:
    return float("{:.1f}".format(await zscore(f"UserGiftProfit:{room_id}", uid)))


async def len_user_gift_profit(room_id: int) -> int:
    return await zcard(f"UserGiftProfit:{room_id}")


async def range_user_gift_profit(room_id: int, start: int = 0, end: int = -1) -> List[Tuple[str, float]]:
    return await zrangewithscoresf1(f"UserGiftProfit:{room_id}", start, end)


async def rev_range_user_gift_profit(room_id: int, start: int = 0, end: int = -1) -> List[Tuple[str, float]]:
    return await zrevrangewithscoresf1(f"UserGiftProfit:{room_id}", start, end)


async def incr_user_gift_profit(room_id: int, uid: int, price: float) -> float:
    return await zincrby(f"UserGiftProfit:{room_id}", uid, price)


async def delete_user_gift_profit(room_id: int):
    await delete(f"UserGiftProfit:{room_id}")


# 用户累计礼物价值

async def get_user_gift_total(room_id: int, uid: int) -> float:
    return float("{:.1f}".format(await zscore(f"UserGiftTotal:{room_id}", uid)))


async def accumulate_user_gift_total(room_id: int):
    await zunionstore(f"UserGiftTotal:{room_id}", f"UserGiftProfit:{room_id}")


# 用户总礼物价值

async def len_user_gift_all(room_id: int) -> int:
    return len(
        set(await zrange(f"UserGiftProfit:{room_id}", 0, -1))
        .union(set(await zrange(f"UserGiftTotal:{room_id}", 0, -1)))
    )


async def get_user_gift_all(room_id: int, uid: int) -> float:
    return float("{:.1f}".format(
        await get_user_gift_profit(room_id, uid) + await get_user_gift_total(room_id, uid)
    ))


async def range_user_gift_all(room_id: int, start: int = 0, end: int = -1) -> List[Tuple[str, float]]:
    await zunionstore(f"TempGiftTotal:{room_id}", [f"UserGiftProfit:{room_id}", f"UserGiftTotal:{room_id}"])
    result = await zrangewithscoresf1(f"TempGiftTotal:{room_id}", start, end)
    await delete(f"TempGiftTotal:{room_id}")
    return result


async def rev_range_user_gift_all(room_id: int, start: int = 0, end: int = -1) -> List[Tuple[str, float]]:
    await zunionstore(f"TempGiftTotal:{room_id}", [f"UserGiftProfit:{room_id}", f"UserGiftTotal:{room_id}"])
    result = await zrevrangewithscoresf1(f"TempGiftTotal:{room_id}", start, end)
    await delete(f"TempGiftTotal:{room_id}")
    return result


# 房间礼物时间分布

async def get_room_gift_time(room_id: int) -> List[Tuple[str, float]]:
    return await hgetalltuplef1(f"RoomGiftTime:{room_id}")


async def incr_room_gift_time(room_id: int, timestamp: int, price: float) -> float:
    return await hincrbyfloat(f"RoomGiftTime:{room_id}", timestamp, price)


async def delete_room_gift_time(room_id: int):
    await delete(f"RoomGiftTime:{room_id}")


# 房间 SC 价值

async def get_room_sc_profit(room_id: int) -> int:
    return await hgeti("RoomScProfit", room_id)


async def incr_room_sc_profit(room_id: int, price: int) -> int:
    return await hincrby("RoomScProfit", room_id, price)


async def reset_room_sc_profit(room_id: int):
    await hset("RoomScProfit", room_id, 0)


# 房间累计 SC 价值

async def get_room_sc_total(room_id: int) -> int:
    return await hgeti("RoomScTotal", room_id)


async def accumulate_room_sc_total(room_id: int) -> int:
    return await hincrby("RoomScTotal", room_id, await get_room_sc_profit(room_id))


# 房间总 SC 价值

async def get_room_sc_all(room_id: int) -> int:
    return await get_room_sc_profit(room_id) + await get_room_sc_total(room_id)


# 用户 SC 价值

async def get_user_sc_profit(room_id: int, uid: int) -> int:
    return int(await zscore(f"UserScProfit:{room_id}", uid))


async def len_user_sc_profit(room_id: int) -> int:
    return await zcard(f"UserScProfit:{room_id}")


async def range_user_sc_profit(room_id: int, start: int = 0, end: int = -1) -> List[Tuple[str, int]]:
    return await zrangewithscoresi(f"UserScProfit:{room_id}", start, end)


async def rev_range_user_sc_profit(room_id: int, start: int = 0, end: int = -1) -> List[Tuple[str, int]]:
    return await zrevrangewithscoresi(f"UserScProfit:{room_id}", start, end)


async def incr_user_sc_profit(room_id: int, uid: int, price: int) -> float:
    return await zincrby(f"UserScProfit:{room_id}", uid, price)


async def delete_user_sc_profit(room_id: int):
    await delete(f"UserScProfit:{room_id}")


# 用户累计 SC 价值

async def get_user_sc_total(room_id: int, uid: int) -> int:
    return int(await zscore(f"UserScTotal:{room_id}", uid))


async def accumulate_user_sc_total(room_id: int):
    await zunionstore(f"UserScTotal:{room_id}", f"UserScProfit:{room_id}")


# 用户总 SC 价值

async def len_user_sc_all(room_id: int) -> int:
    return len(
        set(await zrange(f"UserScProfit:{room_id}", 0, -1))
        .union(set(await zrange(f"UserScTotal:{room_id}", 0, -1)))
    )


async def get_user_sc_all(room_id: int, uid: int) -> int:
    return await get_user_sc_profit(room_id, uid) + await get_user_sc_total(room_id, uid)


async def range_user_sc_all(room_id: int, start: int = 0, end: int = -1) -> List[Tuple[str, int]]:
    await zunionstore(f"TempScTotal:{room_id}", [f"UserScProfit:{room_id}", f"UserScTotal:{room_id}"])
    result = await zrangewithscoresi(f"TempScTotal:{room_id}", start, end)
    await delete(f"TempScTotal:{room_id}")
    return result


async def rev_range_user_sc_all(room_id: int, start: int = 0, end: int = -1) -> List[Tuple[str, int]]:
    await zunionstore(f"TempScTotal:{room_id}", [f"UserScProfit:{room_id}", f"UserScTotal:{room_id}"])
    result = await zrevrangewithscoresi(f"TempScTotal:{room_id}", start, end)
    await delete(f"TempScTotal:{room_id}")
    return result


# 房间 SC 时间分布

async def get_room_sc_time(room_id: int) -> List[Tuple[str, int]]:
    return await hgetalltuplei(f"RoomScTime:{room_id}")


async def incr_room_sc_time(room_id: int, timestamp: int, price: int) -> int:
    return await hincrby(f"RoomScTime:{room_id}", timestamp, price)


async def delete_room_sc_time(room_id: int):
    await delete(f"RoomScTime:{room_id}")


# 房间大航海数量

async def get_room_captain_count(room_id: int) -> int:
    return await hgeti("RoomCaptainCount", room_id)


async def get_room_commander_count(room_id: int) -> int:
    return await hgeti("RoomCommanderCount", room_id)


async def get_room_governor_count(room_id: int) -> int:
    return await hgeti("RoomGovernorCount", room_id)


async def incr_room_guard_count(type_str: str, room_id: int, month: int) -> int:
    return await hincrby(f"Room{type_str}Count", room_id, month)


async def reset_room_guard_count(room_id: int):
    await hset("RoomCaptainCount", room_id, 0)
    await hset("RoomCommanderCount", room_id, 0)
    await hset("RoomGovernorCount", room_id, 0)


# 房间累计大航海数量

async def get_room_captain_total(room_id: int) -> int:
    return await hgeti("RoomCaptainTotal", room_id)


async def get_room_commander_total(room_id: int) -> int:
    return await hgeti("RoomCommanderTotal", room_id)


async def get_room_governor_total(room_id: int) -> int:
    return await hgeti("RoomGovernorTotal", room_id)


async def accumulate_room_guard_total(room_id: int):
    await hincrby("RoomCaptainTotal", room_id, await get_room_captain_count(room_id))
    await hincrby("RoomCommanderTotal", room_id, await get_room_commander_count(room_id))
    await hincrby("RoomGovernorTotal", room_id, await get_room_governor_count(room_id))


# 房间总大航海数量

async def get_room_captain_all(room_id: int) -> int:
    return await get_room_captain_count(room_id) + await get_room_captain_total(room_id)


async def get_room_commander_all(room_id: int) -> int:
    return await get_room_commander_count(room_id) + await get_room_commander_total(room_id)


async def get_room_governor_all(room_id: int) -> int:
    return await get_room_governor_count(room_id) + await get_room_governor_total(room_id)


# 用户大航海数量

async def get_user_captain_count(room_id: int, uid: int) -> int:
    return int(await zscore(f"UserCaptainCount:{room_id}", uid))


async def get_user_commander_count(room_id: int, uid: int) -> int:
    return int(await zscore(f"UserCommanderCount:{room_id}", uid))


async def get_user_governor_count(room_id: int, uid: int) -> int:
    return int(await zscore(f"UserGovernorCount:{room_id}", uid))


async def len_user_captain_count(room_id: int) -> int:
    return await zcard(f"UserCaptainCount:{room_id}")


async def len_user_commander_count(room_id: int) -> int:
    return await zcard(f"UserCommanderCount:{room_id}")


async def len_user_governor_count(room_id: int) -> int:
    return await zcard(f"UserGovernorCount:{room_id}")


async def len_user_guard_count(room_id: int) -> int:
    return len(
        set(await zrange(f"UserCaptainCount:{room_id}", 0, -1))
        .union(set(await zrange(f"UserCommanderCount:{room_id}", 0, -1)))
        .union(set(await zrange(f"UserGovernorCount:{room_id}", 0, -1)))
    )


async def rev_range_user_captain_count(room_id: int, start: int = 0, end: int = -1) -> List[Tuple[str, int]]:
    return await zrevrangewithscoresi(f"UserCaptainCount:{room_id}", start, end)


async def rev_range_user_commander_count(room_id: int, start: int = 0, end: int = -1) -> List[Tuple[str, int]]:
    return await zrevrangewithscoresi(f"UserCommanderCount:{room_id}", start, end)


async def rev_range_user_governor_count(room_id: int, start: int = 0, end: int = -1) -> List[Tuple[str, int]]:
    return await zrevrangewithscoresi(f"UserGovernorCount:{room_id}", start, end)


async def incr_user_guard_count(type_str: str, room_id: int, uid: int, month: int) -> float:
    return await zincrby(f"User{type_str}Count:{room_id}", uid, month)


async def delete_user_guard_count(room_id: int):
    await delete(f"UserCaptainCount:{room_id}")
    await delete(f"UserCommanderCount:{room_id}")
    await delete(f"UserGovernorCount:{room_id}")


# 用户累计大航海数量

async def get_user_captain_total(room_id: int, uid: int) -> int:
    return int(await zscore(f"UserCaptainTotal:{room_id}", uid))


async def get_user_commander_total(room_id: int, uid: int) -> int:
    return int(await zscore(f"UserCommanderTotal:{room_id}", uid))


async def get_user_governor_total(room_id: int, uid: int) -> int:
    return int(await zscore(f"UserGovernorTotal:{room_id}", uid))


async def accumulate_user_guard_total(room_id: int):
    await zunionstore(f"UserCaptainTotal:{room_id}", f"UserCaptainCount:{room_id}")
    await zunionstore(f"UserCommanderTotal:{room_id}", f"UserCommanderCount:{room_id}")
    await zunionstore(f"UserGovernorTotal:{room_id}", f"UserGovernorCount:{room_id}")


# 用户总大航海数量

async def len_user_captain_all(room_id: int) -> int:
    return len(
        set(await zrange(f"UserCaptainCount:{room_id}", 0, -1))
        .union(set(await zrange(f"UserCaptainTotal:{room_id}", 0, -1)))
    )


async def len_user_commander_all(room_id: int) -> int:
    return len(
        set(await zrange(f"UserCommanderCount:{room_id}", 0, -1))
        .union(set(await zrange(f"UserCommanderTotal:{room_id}", 0, -1)))
    )


async def len_user_governor_all(room_id: int) -> int:
    return len(
        set(await zrange(f"UserGovernorCount:{room_id}", 0, -1))
        .union(set(await zrange(f"UserGovernorTotal:{room_id}", 0, -1)))
    )


async def len_user_guard_all(room_id: int) -> int:
    return len(
        set(await zrange(f"UserCaptainCount:{room_id}", 0, -1))
        .union(set(await zrange(f"UserCaptainTotal:{room_id}", 0, -1)))
        .union(set(await zrange(f"UserCommanderCount:{room_id}", 0, -1)))
        .union(set(await zrange(f"UserCommanderTotal:{room_id}", 0, -1)))
        .union(set(await zrange(f"UserGovernorCount:{room_id}", 0, -1)))
        .union(set(await zrange(f"UserGovernorTotal:{room_id}", 0, -1)))
    )


async def get_user_captain_all(room_id: int, uid: int) -> int:
    return await get_user_captain_count(room_id, uid) + await get_user_captain_total(room_id, uid)


async def get_user_commander_all(room_id: int, uid: int) -> int:
    return await get_user_commander_count(room_id, uid) + await get_user_commander_total(room_id, uid)


async def get_user_governor_all(room_id: int, uid: int) -> int:
    return await get_user_governor_count(room_id, uid) + await get_user_governor_total(room_id, uid)


# 房间大航海时间分布

async def get_room_guard_time(room_id: int) -> List[Tuple[str, int]]:
    return await hgetalltuplei(f"RoomGuardTime:{room_id}")


async def incr_room_guard_time(room_id: int, timestamp: int, month: int) -> int:
    return await hincrby(f"RoomGuardTime:{room_id}", timestamp, month)


async def delete_room_guard_time(room_id: int):
    await delete(f"RoomGuardTime:{room_id}")


# 累计和重置数据

async def accumulate_data(room_id: int):
    # 累计弹幕数
    await accumulate_room_danmu_total(room_id)
    await accumulate_user_danmu_total(room_id)

    # 累计盲盒数
    await accumulate_room_box_total(room_id)
    await accumulate_user_box_total(room_id)

    # 累计盲盒盈亏
    await accumulate_room_box_profit_total(room_id)
    await accumulate_user_box_profit_total(room_id)

    # 累计礼物收益
    await accumulate_room_gift_total(room_id)
    await accumulate_user_gift_total(room_id)

    # 累计 SC 收益
    await accumulate_room_sc_total(room_id)
    await accumulate_user_sc_total(room_id)

    # 累计大航海数
    await accumulate_room_guard_total(room_id)
    await accumulate_user_guard_total(room_id)


async def reset_data(room_id: int):
    # 清空弹幕记录
    await delete_room_danmu(room_id)

    # 清空数据分布
    await delete_room_danmu_time(room_id)
    await delete_room_box_time(room_id)
    await delete_room_gift_time(room_id)
    await delete_room_sc_time(room_id)
    await delete_room_guard_time(room_id)

    # 重置弹幕数
    await reset_room_danmu_count(room_id)
    await delete_user_danmu_count(room_id)

    # 重置盲盒数
    await reset_room_box_count(room_id)
    await delete_user_box_count(room_id)

    # 重置盲盒盈亏
    await reset_room_box_profit(room_id)
    await delete_user_box_profit(room_id)

    # 清空盲盒盈亏记录
    await delete_room_box_profit_record(room_id)

    # 重置礼物收益
    await reset_room_gift_profit(room_id)
    await delete_user_gift_profit(room_id)

    # 重置 SC 收益
    await reset_room_sc_profit(room_id)
    await delete_user_sc_profit(room_id)

    # 重置大航海数
    await reset_room_guard_count(room_id)
    await delete_user_guard_count(room_id)


# 用户绑定

async def get_bind_uid(qq: int) -> int:
    return await hgeti("BindUid", qq)


async def bind_uid(qq: int, uid: int):
    await hset("BindUid", qq, uid)


# 开播 @ 我

async def len_live_on_at(_id: int) -> int:
    return await scard(f"LiveOnAtMe:{_id}")


async def exists_live_on_at(_id: int, qq: int) -> bool:
    return await sismember(f"LiveOnAtMe:{_id}", qq)


async def range_live_on_at(_id: int) -> Set[int]:
    return await smembers(f"LiveOnAtMe:{_id}")


async def add_live_on_at(_id: int, qq: int):
    await sadd(f"LiveOnAtMe:{_id}", qq)


async def delete_live_on_at(_id: int, qq: int):
    await srem(f"LiveOnAtMe:{_id}", qq)


# 动态 @ 我

async def len_dynamic_at(_id: int) -> int:
    return await scard(f"DynamicAtMe:{_id}")


async def exists_dynamic_at(_id: int, qq: int) -> bool:
    return await sismember(f"DynamicAtMe:{_id}", qq)


async def range_dynamic_at(_id: int) -> Set[int]:
    return await smembers(f"DynamicAtMe:{_id}")


async def add_dynamic_at(_id: int, qq: int):
    await sadd(f"DynamicAtMe:{_id}", qq)


async def delete_dynamic_at(_id: int, qq: int):
    await srem(f"DynamicAtMe:{_id}", qq)


# 命令禁用

async def exists_disable_command(name: str, _id: int) -> bool:
    return await sismember(name, _id)


async def add_disable_command(name: str, _id: int):
    await sadd(name, _id)


async def delete_disable_command(name: str, _id: int):
    await srem(name, _id)


# 动态

async def exists_dynamic(_id: int):
    return await sismember("Dynamics", _id)


async def add_dynamic(_id: int):
    await sadd("Dynamics", _id)
