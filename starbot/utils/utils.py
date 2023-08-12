"""
通用工具库
"""
import asyncio
import bisect
import json
import os
import time
from io import BytesIO
from typing import Tuple, List, Dict, Sized, Optional, Any, Union

from PIL import Image, ImageDraw

from . import config
from .Credential import Credential
from .network import get_session, request
from ..exception import ResponseCodeException


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
    mask.close()
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
    mask.close()
    return img


async def get_live_info_by_uids(uids: List[int]) -> Dict[str, Any]:
    """
    根据 UID 列表批量获取直播间信息

    Args:
        uids: UID 列表

    Returns:
        直播间信息
    """
    infos = {}
    info_url = "https://api.live.bilibili.com/room/v1/Room/get_status_info_by_uids?uids[]="
    uids = [str(u) for u in uids]
    uid_lists = split_list(uids, 100)
    for lst in uid_lists:
        infos.update(await request("GET", info_url + "&uids[]=".join(lst)))
    return infos


async def get_unames_and_faces_by_uids(uids: List[str]) -> Tuple[List[str], List[Image.Image]]:
    """
    根据 UID 列表批量获取昵称和头像图片

    Args:
        uids: UID 列表

    Returns:
        昵称列表和头像图片列表组成的元组
    """
    resource_base_path = os.path.dirname(os.path.dirname(__file__))

    async def illegal_face():
        return Image.open(f"{resource_base_path}/resource/face.png")

    infos_list = []
    uid_lists = split_list(uids, 10)
    for lst in uid_lists:
        user_info_url = f"https://api.vc.bilibili.com/account/v1/user/cards?uids={','.join(lst)}"
        try:
            infos_list.extend(await request("GET", user_info_url, credential=get_credential()))
        except ResponseCodeException:
            failed_uids = []
            failed_faces = []
            for i in range(len(uids)):
                failed_uids.append(f"昵称获取失败({uids[i]})")
                failed_faces.append(await illegal_face())
            return failed_uids, failed_faces
    infos = dict(zip([x["mid"] for x in infos_list], infos_list))
    unames = [infos[int(uid)]["name"] if int(uid) in infos else "" for uid in uids]
    download_face_tasks = [
        open_url_image(infos[int(uid)]["face"]) if int(uid) in infos else illegal_face() for uid in uids
    ]
    faces = list(await asyncio.gather(*download_face_tasks, return_exceptions=True))
    for i in range(len(faces)):
        if isinstance(faces[i], Exception):
            faces[i] = await illegal_face()

    return unames, faces


def remove_command_param_placeholder(param: str) -> str:
    """
    移除命令参数中括号占位符

    Args:
        param: 传入参数

    Returns:
        处理后的参数
    """
    return param.replace("[", "").replace("]", "").replace("［", "").replace("］", "").replace("【", "").replace("】", "")


def get_parallel_ranking(score: Union[int, float],
                         scores: List[Union[int, float]]) -> Tuple[int, int, Optional[Union[int, float]]]:
    """
    获取分数在分数列表中的排名，存在并列情况优先取高名次

    Args:
        score: 分数
        scores: 从小到大有序分数列表

    Returns:
        名次、参与排名元素个数和距离上一名的差值组成的元组
    """
    index = bisect.bisect_right(scores, score)
    total = len(scores)
    rank = total - index + 1
    diff = scores[index] - score if index < total else None
    if isinstance(diff,float):
        diff = float("{:.1f}".format(diff))
    return rank, total, diff


def get_ratio(count: Union[int, float], total: Union[int, float]) -> str:
    """
    获取数量在总数量中所占比例

    Args:
        count: 数量
        total: 总数量

    Returns:
        百分比字符串，精确到两位小数
    """
    return "{:.2f}".format(count / total * 100) + " %"
