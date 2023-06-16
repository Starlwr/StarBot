import asyncio

from aiohttp import ClientOSError, ServerDisconnectedError, ClientPayloadError
from loguru import logger

from .datasource import DataSource
from ..exception import ResponseCodeException, DataSourceException, NetworkException
from ..utils import config, redis
from ..utils.network import request
from ..utils.utils import get_credential


async def dynamic_spider(datasource: DataSource):
    running_tasks = set()

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
        except TimeoutError:
            continue
        except ClientPayloadError:
            continue
        except Exception as ex:
            logger.exception("动态推送任务抓取最新动态异常", ex)
            continue

        if "new_num" not in latest_dynamic:
            continue

        new_num = latest_dynamic["new_num"]
        if new_num > 0:
            logger.debug(f"检测到新动态个数: {new_num}")

        for i in range(new_num + 3):
            try:
                detail = latest_dynamic["cards"][i]
            except IndexError:
                break

            dynamic_id = detail['desc']['dynamic_id']
            if await redis.exists_dynamic(dynamic_id):
                continue
            await redis.add_dynamic(dynamic_id)

            try:
                up = datasource.get_up(detail["desc"]["uid"])
            except DataSourceException:
                logger.debug(f"不存在于推送配置中的动态: {dynamic_id}, 跳过推送")
                continue

            task = asyncio.create_task(up.dynamic_update(detail))
            running_tasks.add(task)
            task.add_done_callback(lambda t: running_tasks.remove(t))
