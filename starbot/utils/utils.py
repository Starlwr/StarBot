"""
通用工具库
"""
import asyncio
import json
import os
import time
from io import BytesIO
from typing import Tuple, List, Dict, Sized, Optional, Any

from PIL import Image, ImageDraw

from . import config
from .Credential import Credential
from .network import get_session, request


def get_api(field: str) -> Dict:
    """
    获取 API

    Args:
        field: API 所属分类，即 data/api 下的文件名（不含后缀名）

    Returns:
        该 API 的内容
    """
    path = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "api", f"{field.lower()}.json"))
    if os.path.exists(path):
        with open(path, encoding="utf8") as f:
            return json.loads(f.read())


def get_credential() -> Credential:
    """
    获取登录凭据

    Returns:
        Credential 实例
    """
    sessdata = config.get("SESSDATA")
    bili_jct = config.get("BILI_JCT")
    buvid3 = config.get("BUVID3")
    return Credential(sessdata, bili_jct, buvid3)


def timestamp_format(timestamp: int, format_str: str) -> str:
    """
    时间戳格式化为形如 11/04 00:00:00 的字符串形式

    Args:
        timestamp: 时间戳
        format_str: 格式化字符串

    Returns:
        格式化后的字符串
    """
    return time.strftime(format_str, time.localtime(timestamp))


async def open_url_image(url: str) -> Optional[Image.Image]:
    """
    读取网络图片

    Args:
        url: 图片 URL

    Returns:
        读取到的图片，URL 为空时返回 None
    """
    if not url:
        return None

    response = await get_session().get(url)
    image_data = await response.read()
    image = Image.open(BytesIO(image_data))
    response.close()

    return image


def split_list(lst: Sized, n: int) -> List[List[Any]]:
    """
    将传入列表划分为若干子列表，每个子列表包含 n 个元素

    Args:
        lst: 要划分的列表
        n: 每个子列表包含的元素数量

    Returns:
        划分后的若干子列表组成的列表
    """
    sub_lists = []
    for i in range(0, len(lst), n):
        sub_lists.append(lst[i:i+n])
    return sub_lists


def limit_str_length(origin_str: str, limit: int) -> str:
    """
    限制字符串最大长度，将超出长度的部分截去，并添加 "..."，未超出长度则返回原字符串

    Args:
        origin_str: 原字符串
        limit: 要限制的最大长度

    Returns:
        处理后的字符串
    """
    return f"{origin_str[:limit]}..." if len(origin_str) > limit else origin_str


def mask_round(img: Image.Image) -> Image.Image:
    """
    将图片转换为圆形

    Args:
        img: 原图片

    Returns:
        圆形图片
    """
    mask = Image.new("L", img.size)
    mask_draw = ImageDraw.Draw(mask)
    img_width, img_height = img.size
    mask_draw.ellipse((0, 0, img_width, img_height), fill=255)
    img.putalpha(mask)
    return img


def mask_rounded_rectangle(img: Image.Image, radius: int = 10) -> Image.Image:
    """
    对指定图片覆盖圆角矩形蒙版，使得图片圆角化

    Args:
        img: 原图片
        radius: 圆角半径。默认：10

    Returns:
        圆角化的图片
    """
    mask = Image.new("L", img.size)
    mask_draw = ImageDraw.Draw(mask)
    img_width, img_height = img.size
    mask_draw.rounded_rectangle((0, 0, img_width, img_height), radius, 255)
    img.putalpha(mask)
    return img


async def get_unames_and_faces_by_uids(uids: List[str]) -> Tuple[List[str], List[Image.Image]]:
    """
    根据 UID 列表批量获取昵称和头像图片

    Args:
        uids: UID 列表

    Returns:
        昵称列表和头像图片列表组成的元组
    """
    user_info_url = f"https://api.vc.bilibili.com/account/v1/user/cards?uids={','.join(uids)}"
    infos_list = await request("GET", user_info_url)
    infos = dict(zip([x["mid"] for x in infos_list], infos_list))
    unames = [infos[int(uid)]["name"] for uid in uids]
    download_face_tasks = [open_url_image(infos[int(uid)]["face"]) for uid in uids]
    faces = await asyncio.gather(*download_face_tasks)
    return (unames, faces)
