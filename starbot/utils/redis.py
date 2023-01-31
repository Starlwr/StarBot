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

async def lrange(key: str, start: int, end: int) -> List[str]:
    return [x.decode() for x in await __redis.lrange(key, start, end)]


async def lrangei(key: str, start: int, end: int) -> List[float]:
    return [int(x) for x in await __redis.lrange(key, start, end)]


async def lrangef1(key: str, start: int, end: int) -> List[float]:
    return [float("{:.1f}".format(float(x))) for x in await __redis.lrange(key, start, end)]


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


async def hgetalltuplei(key: str) -> List[Tuple[str, int]]:
    result = await __redis.hgetall(key)
    return [(x.decode(), int(result[x])) for x in result]


async def hgetalltuplef1(key: str) -> List[Tuple[str, float]]:
    result = await __redis.hgetall(key)
    return [(x.decode(), float("{:.1f}".format(float(result[x])))) for x in result]


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
    return [(x[0].decode(), int(x[1])) for x in await __redis.zrevrange(key, start, end, True)]


async def zrevrangewithscoresf1(key: str, start: int, end: int) -> List[Tuple[str, float]]:
    return [
        (x[0].decode(), float("{:.1f}".format(float(x[1])))) for x in await __redis.zrevrange(key, start, end, True)
    ]


async def zadd(key: str, member: str, score: Union[int, float]):
    await __redis.zadd(key, {member: score})


async def zincrby(key: str, member: Union[str, int], score: Optional[Union[int, float]] = 1) -> float:
    return await __redis.zincrby(key, score, member)


async def zunionstore(dest: str, source: Union[str, List[str]]):
    if isinstance(source, str):
        await __redis.zunionstore(dest, [dest, source])
    if isinstance(source, list):
        await __redis.zunionstore(dest, source)


# StarBot

# 直播间状态，0：未开播，1：正在直播，2：轮播

async def get_live_status(room_id: int) -> int:
    return await hgeti("LiveStatus", room_id)


async def set_live_status(room_id: int, status: int):
    await hset("LiveStatus", room_id, status)


# 直播开始时间

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

async def accumulate_room_danmu_total(room_id: int) -> int:
    return await hincrby("RoomDanmuTotal", room_id, await get_room_danmu_count(room_id))


# 用户弹幕数量

async def len_user_danmu_count(room_id: int) -> int:
    return await zcard(f"UserDanmuCount:{room_id}")


async def get_user_danmu_count(room_id: int, start: int = 0, end: int = -1) -> List[Tuple[str, int]]:
    return await zrevrangewithscoresi(f"UserDanmuCount:{room_id}", start, end)


async def incr_user_danmu_count(room_id: int, uid: int) -> float:
    return await zincrby(f"UserDanmuCount:{room_id}", uid)


async def delete_user_danmu_count(room_id: int):
    await delete(f"UserDanmuCount:{room_id}")


# 用户累计弹幕数量

async def accumulate_user_danmu_total(room_id: int):
    await zunionstore(f"UserDanmuTotal:{room_id}", f"UserDanmuCount:{room_id}")


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

async def accumulate_room_box_total(room_id: int) -> int:
    return await hincrby("RoomBoxTotal", room_id, await get_room_box_count(room_id))


# 用户盲盒数量

async def len_user_box_count(room_id: int) -> int:
    return await zcard(f"UserBoxCount:{room_id}")


async def get_user_box_count(room_id: int, start: int = 0, end: int = -1) -> List[Tuple[str, int]]:
    return await zrevrangewithscoresi(f"UserBoxCount:{room_id}", start, end)


async def incr_user_box_count(room_id: int, uid: int, count: int) -> float:
    return await zincrby(f"UserBoxCount:{room_id}", uid, count)


async def delete_user_box_count(room_id: int):
    await delete(f"UserBoxCount:{room_id}")


# 用户累计盲盒数量

async def accumulate_user_box_total(room_id: int):
    await zunionstore(f"UserBoxTotal:{room_id}", f"UserBoxCount:{room_id}")


# 房间盲盒盈亏

async def get_room_box_profit(room_id: int) -> float:
    return await hgetf1("RoomBoxProfit", room_id)


async def incr_room_box_profit(room_id: int, profit: float) -> float:
    return await hincrbyfloat("RoomBoxProfit", room_id, profit)


async def reset_room_box_profit(room_id: int):
    await hset("RoomBoxProfit", room_id, 0)


# 房间累计盲盒盈亏

async def accumulate_room_box_profit_total(room_id: int) -> float:
    return await hincrbyfloat("RoomBoxProfitTotal", room_id, await get_room_box_profit(room_id))


# 用户盲盒盈亏

async def get_user_box_profit(room_id: int, start: int = 0, end: int = -1) -> List[Tuple[str, float]]:
    return await zrevrangewithscoresf1(f"UserBoxProfit:{room_id}", start, end)


async def incr_user_box_profit(room_id: int, uid: int, profit: float) -> float:
    return await zincrby(f"UserBoxProfit:{room_id}", uid, profit)


async def delete_user_box_profit(room_id: int):
    await delete(f"UserBoxProfit:{room_id}")


# 用户累计盲盒盈亏

async def accumulate_user_box_profit_total(room_id: int):
    await zunionstore(f"UserBoxProfitTotal:{room_id}", f"UserBoxProfit:{room_id}")


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

async def accumulate_room_gift_total(room_id: int) -> float:
    return await hincrbyfloat("RoomGiftTotal", room_id, await get_room_gift_profit(room_id))


# 用户礼物价值

async def len_user_gift_profit(room_id: int) -> int:
    return await zcard(f"UserGiftProfit:{room_id}")


async def get_user_gift_profit(room_id: int, start: int = 0, end: int = -1) -> List[Tuple[str, float]]:
    return await zrevrangewithscoresf1(f"UserGiftProfit:{room_id}", start, end)


async def incr_user_gift_profit(room_id: int, uid: int, price: float) -> float:
    return await zincrby(f"UserGiftProfit:{room_id}", uid, price)


async def delete_user_gift_profit(room_id: int):
    await delete(f"UserGiftProfit:{room_id}")


# 用户累计礼物价值

async def accumulate_user_gift_total(room_id: int):
    await zunionstore(f"UserGiftTotal:{room_id}", f"UserGiftProfit:{room_id}")


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

async def accumulate_room_sc_total(room_id: int) -> int:
    return await hincrby("RoomScTotal", room_id, await get_room_sc_profit(room_id))


# 用户 SC 价值

async def len_user_sc_profit(room_id: int) -> int:
    return await zcard(f"UserScProfit:{room_id}")


async def get_user_sc_profit(room_id: int, start: int = 0, end: int = -1) -> List[Tuple[str, int]]:
    return await zrevrangewithscoresi(f"UserScProfit:{room_id}", start, end)


async def incr_user_sc_profit(room_id: int, uid: int, price: int) -> float:
    return await zincrby(f"UserScProfit:{room_id}", uid, price)


async def delete_user_sc_profit(room_id: int):
    await delete(f"UserScProfit:{room_id}")


# 用户累计 SC 价值

async def accumulate_user_sc_total(room_id: int):
    await zunionstore(f"UserScTotal:{room_id}", f"UserScProfit:{room_id}")


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

async def accumulate_room_guard_total(room_id: int):
    await hincrby("RoomCaptainTotal", room_id, await get_room_captain_count(room_id))
    await hincrby("RoomCommanderTotal", room_id, await get_room_commander_count(room_id))
    await hincrby("RoomGovernorTotal", room_id, await get_room_governor_count(room_id))


# 用户大航海数量

async def get_user_captain_count(room_id: int, start: int = 0, end: int = -1) -> List[Tuple[str, int]]:
    return await zrevrangewithscoresi(f"UserCaptainCount:{room_id}", start, end)


async def get_user_commander_count(room_id: int, start: int = 0, end: int = -1) -> List[Tuple[str, int]]:
    return await zrevrangewithscoresi(f"UserCommanderCount:{room_id}", start, end)


async def get_user_governor_count(room_id: int, start: int = 0, end: int = -1) -> List[Tuple[str, int]]:
    return await zrevrangewithscoresi(f"UserGovernorCount:{room_id}", start, end)


async def incr_user_guard_count(type_str: str, room_id: int, uid: int, month: int) -> float:
    return await zincrby(f"User{type_str}Count:{room_id}", uid, month)


async def delete_user_guard_count(room_id: int):
    await delete(f"UserCaptainCount:{room_id}")
    await delete(f"UserCommanderCount:{room_id}")
    await delete(f"UserGovernorCount:{room_id}")


# 用户累计大航海数量

async def accumulate_user_guard_total(room_id: int):
    await zunionstore(f"UserCaptainTotal:{room_id}", f"UserCaptainCount:{room_id}")
    await zunionstore(f"UserCommanderTotal:{room_id}", f"UserCommanderCount:{room_id}")
    await zunionstore(f"UserGovernorTotal:{room_id}", f"UserGovernorCount:{room_id}")


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
