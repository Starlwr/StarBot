"""
与网络请求相关的模块。能对会话进行管理（复用 TCP 连接）
"""

import asyncio
import atexit
import json
import random
import re
from typing import Any, Union, Dict

import aiohttp
from aiohttp import TCPConnector, ServerDisconnectedError

from . import config
from .Credential import Credential
from ..exception import ResponseCodeException, ResponseException, NetworkException

__session_pool = {}


@atexit.register
def __clean():
    """
    程序退出清理操作
    """
    try:
        loop = asyncio.get_running_loop()
    except RuntimeError:
        return

    async def __clean_task():
        await __session_pool[loop].close()

    if loop.is_closed():
        loop.run_until_complete(__clean_task())
    else:
        loop.create_task(__clean_task())


async def request(method: str,
                  url: str,
                  params: dict = None,
                  data: Any = None,
                  credential: Credential = None,
                  no_csrf: bool = False,
                  json_body: bool = False,
                  **kwargs) -> Union[Dict, None]:
    """
    向接口发送请求

    Args:
        method: 请求方法
        url: 请求 URL
        params: 请求参数。默认：None
        data: 请求载荷。默认：None
        credential: Credential 实例。默认：None
        no_csrf: 不要自动添加 CSRF。默认：False
        json_body: 载荷是否为 JSON。默认：False
        kwargs: 暂不使用

    Returns:
        接口未返回数据时，返回 None，否则返回该接口提供的 data 或 result 字段的数据
    """
    if credential is None:
        credential = Credential()

    method = method.upper()
    # 请求为非 GET 且 no_csrf 不为 True 时要求 bili_jct
    if method != 'GET' and not no_csrf:
        credential.raise_for_no_bili_jct()

    # 使用 Referer 和 UA 请求头以绕过反爬虫机制
    default_headers = {
        "Referer": "https://www.bilibili.com",
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/94.0.4606.71 Safari/537.36 Core/1.94.218.400 QQBrowser/12.1.5496.400"
    }
    headers = default_headers

    if params is None:
        params = {}

    # 自动添加 csrf
    if not no_csrf and method in ['POST', 'DELETE', 'PATCH']:
        if data is None:
            data = {}
        data['csrf'] = credential.bili_jct
        data['csrf_token'] = credential.bili_jct

    # jsonp
    if params.get("jsonp", "") == "jsonp":
        params["callback"] = "callback"

    args = {
        "method": method,
        "url": url,
        "params": params,
        "data": data,
        "headers": headers,
        "cookies": credential.get_cookies()
    }

    args.update(kwargs)

    if json_body:
        args["headers"]["Content-Type"] = "application/json"
        args["data"] = json.dumps(args["data"])

    session = get_session()

    for i in range(3):
        # 如果用户提供代理则设置代理
        proxy = config.get("PROXY")
        if isinstance(proxy, str):
            args["proxy"] = proxy
        elif isinstance(proxy, list):
            args["proxy"] = random.choice(proxy)

        try:
            async with session.request(**args) as resp:

                # 检查状态码
                try:
                    resp.raise_for_status()
                except aiohttp.ClientResponseError as e:
                    raise NetworkException(e.status, e.message)

                # 检查响应头 Content-Length
                content_length = resp.headers.get("content-length")
                if content_length and int(content_length) == 0:
                    return None

                # 检查响应头 Content-Type
                content_type = resp.headers.get("content-type")

                # 不是 application/json
                if content_type.lower().index("application/json") == -1:
                    raise ResponseException("响应不是 application/json 类型")

                raw_data = await resp.text()
                resp_data: dict

                if 'callback' in params:
                    # JSONP 请求
                    resp_data = json.loads(
                        re.match("^.*?({.*}).*$", raw_data, re.S).group(1))
                else:
                    # JSON
                    resp_data = json.loads(raw_data)

                # 检查 code
                code = resp_data.get("code", None)

                if code is None:
                    raise ResponseCodeException(-1, "API 返回数据未含 code 字段", resp_data)

                if code != 0:
                    # 4101131: 加载错误，请稍后再试, 22015: 您的账号异常，请稍后再试
                    if code == 4101131 or code == 22015:
                        await asyncio.sleep(10)
                        continue

                    msg = resp_data.get('msg', None)
                    if msg is None:
                        msg = resp_data.get('message', None)
                    if msg is None:
                        msg = "接口未返回错误信息"
                    raise ResponseCodeException(code, msg, resp_data)

                real_data = resp_data.get("data", None)
                if real_data is None:
                    real_data = resp_data.get("result", None)
                return real_data
        except ServerDisconnectedError:
            await asyncio.sleep(0.5)
            continue
        except NetworkException:
            await asyncio.sleep(0.5)
            continue


def get_session() -> aiohttp.ClientSession:
    """
    获取当前模块的 aiohttp.ClientSession 对象，用于自定义请求

    Returns:
        ClientSession 实例
    """
    loop = asyncio.get_running_loop()
    session = __session_pool.get(loop, None)
    if session is None:
        session = aiohttp.ClientSession(
            headers={
                "User-Agent": "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/94.0.4606.71 Safari/537.36 Core/1.94.201.400 QQBrowser/11.9.5325.400"
            }, loop=loop, connector=TCPConnector(loop=loop, limit=0, verify_ssl=False)
        )
        __session_pool[loop] = session

    return session


def set_session(session: aiohttp.ClientSession):
    """
    用户手动设置 Session

    Args:
        session: aiohttp.ClientSession 实例
    """
    loop = asyncio.get_running_loop()
    __session_pool[loop] = session
