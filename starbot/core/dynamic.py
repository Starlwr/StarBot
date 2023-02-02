import asyncio
import time

from aiohttp import ClientOSError, ServerDisconnectedError
from loguru import logger

from .datasource import DataSource
from ..exception import ResponseCodeException, DataSourceException, NetworkException
from ..utils import config
from ..utils.network import request
from ..utils.utils import get_credential


async def dynamic_spider(datasource: DataSource):
    logger.success("动态推送模块已启动")

    dynamic_url = "https://api.vc.bilibili.com/dynamic_svr/v1/dynamic_svr/dynamic_new?type_list=268435455"
    credential = get_credential()
    dynamic_interval = config.get("DYNAMIC_INTERVAL")

    if dynamic_interval < 10:
        logger.warning("当前动态推送抓取频率设置过小, 可能会造成动态抓取 API 访问被暂时封禁, 推荐将其设置为 10 以上的数值")

    while True:
        await asyncio.sleep(dynamic_interval)

        latest_dynamic = {}
        try:
            latest_dynamic = await request("GET", dynamic_url, credential=credential)
        except ResponseCodeException as ex:
            if ex.code == -6:
                continue
            logger.error(f"动态推送任务抓取最新动态异常, HTTP 错误码: {ex.code} ({ex.msg})")
        except NetworkException:
            continue
        except ClientOSError:
            continue
        except ServerDisconnectedError:
            continue
        except Exception as ex:
            logger.exception("动态推送任务抓取最新动态异常", ex)
            continue

        if "new_num" not in latest_dynamic:
            continue

        new_num = latest_dynamic["new_num"]
        if new_num > 0:
            logger.debug(f"检测到新动态个数: {new_num}")
        for i in range(new_num):
            try:
                detail = latest_dynamic["cards"][i]
            except IndexError:
                break

            if int(time.time()) - detail["desc"]["timestamp"] > dynamic_interval:
                break

            try:
                up = datasource.get_up(detail["desc"]["uid"])
            except DataSourceException:
                continue

            up.dispatch("DYNAMIC_UPDATE", detail)
