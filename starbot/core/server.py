from typing import Optional

import aiohttp
from aiohttp import web
from aiohttp.web_routedef import RouteTableDef
from loguru import logger

from .datasource import DataSource
from .model import Message
from ..exception import DataSourceException
from ..utils import config

routes = web.RouteTableDef()
datasource: Optional[DataSource] = None


@routes.get("/send/{key}/{message}")
async def send(request: aiohttp.web.Request) -> aiohttp.web.Response:
    key = request.match_info['key']
    message = request.match_info['message']

    try:
        target = datasource.get_target_by_key(key)
        bot = datasource.get_bot_by_key(key)
        msg = Message(id=target.id, content=message, type=target.type)
        bot.send_message(msg)
        return web.Response(text="success")
    except DataSourceException:
        logger.warning(f"HTTP API 推送失败, 不存在的推送 key: {key}")
        return web.Response(text="fail")


def get_routes() -> RouteTableDef:
    """
    获取路由，可用于外部扩展功能

    Returns:
        路由实例
    """
    return routes


async def http_init(source: DataSource):
    global datasource
    datasource = source

    logger.info("开始启动 HTTP API 推送服务")

    app = web.Application()
    app.add_routes(routes)
    runner = web.AppRunner(app)
    await runner.setup()
    site = web.TCPSite(runner, 'localhost', config.get("HTTP_API_PORT"))
    await site.start()
    logger.success("成功启动 HTTP API 推送服务")
