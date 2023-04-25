from typing import Optional

import aiohttp
from aiohttp import web
from aiohttp.web_routedef import RouteTableDef
from graia.ariadne.exception import UnknownTarget
from loguru import logger

from .datasource import DataSource
from .model import Message, PushType
from .user import User, RelationType
from ..exception import DataSourceException
from ..utils import config
from ..utils.utils import get_credential

routes = web.RouteTableDef()
datasource: Optional[DataSource] = None


@routes.get("/send/{type}/{key}/{message}")
async def send(request: aiohttp.web.Request, qq: int = None) -> aiohttp.web.Response:
    if len(datasource.bots) == 1:
        bot = datasource.get_bot()
    else:
        if qq is None:
            qq = config.get("HTTP_API_DEAFULT_BOT")
            if qq is None:
                logger.warning("HTTP API 推送失败, 多 Bot 推送时使用 HTTP API 需填写 HTTP_API_DEAFULT_BOT 配置项")
                return web.Response(text="fail")

        try:
            bot = datasource.get_bot(qq)
        except DataSourceException:
            logger.warning("HTTP API 推送失败, 填写的 HTTP_API_DEAFULT_BOT 配置项不正确")
            return web.Response(text="fail")

    if not str(request.match_info['key']).isdigit():
        logger.warning("HTTP API 推送失败, 传入的 QQ 或群号格式不正确")
        return web.Response(text="fail")

    type_map = {
        "friend": PushType.Friend,
        "group": PushType.Group
    }
    _type = type_map.get(str(request.match_info['type']), None)
    if _type is None:
        logger.warning("HTTP API 推送失败, 传入的推送类型格式不正确")
        return web.Response(text="fail")

    key = int(request.match_info['key'])
    message = Message(id=key, content=str(request.match_info['message']), type=_type)

    try:
        await bot.send_message(message)
    except UnknownTarget:
        pass

    return web.Response(text="success")


@routes.get("/send/{bot}/{type}/{key}/{message}")
async def send_by_bot(request: aiohttp.web.Request) -> aiohttp.web.Response:
    if not str(request.match_info['bot']).isdigit():
        logger.warning("HTTP API 推送失败, 传入的 Bot QQ 格式不正确")
        return web.Response(text="fail")

    return await send(request, int(request.match_info['bot']))


@routes.get("/user/follow/{uid}")
async def follow(request: aiohttp.web.Request) -> aiohttp.web.Response:
    if not str(request.match_info['uid']).isdigit():
        logger.warning("关注用户失败, 传入的 UID 格式不正确")
        return web.Response(text="fail")

    uid = int(request.match_info['uid'])
    u = User(uid, get_credential())
    await u.modify_relation(RelationType.SUBSCRIBE)


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
    port = config.get("HTTP_API_PORT")

    logger.info("开始启动 HTTP API 推送服务")

    app = web.Application()
    app.add_routes(routes)
    runner = web.AppRunner(app)
    await runner.setup()
    site = web.TCPSite(runner, 'localhost', port)
    try:
        await site.start()
    except OSError:
        logger.error(f"设定的 HTTP API 端口 {port} 已被占用, HTTP API 推送服务启动失败")
        return
    logger.success(f"成功启动 HTTP API 推送服务: http://localhost:{port}")
