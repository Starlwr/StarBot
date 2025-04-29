import asyncio
import json
import os
from datetime import datetime
from typing import Optional, Union, Tuple, List, Dict, Any

from PIL import Image, ImageDraw, ImageFont
from PIL.Image import Resampling
from emoji import is_emoji

from .PicGenerator import Color, PicGenerator
from ..utils import config
from ..utils.network import request
from ..utils.utils import open_url_image, timestamp_format, split_list, limit_str_length, \
    mask_round, mask_rounded_rectangle, get_credential


class DynamicPicGenerator:
    """
    动态图片生成器
    """
    __resource_base_path = os.path.dirname(os.path.dirname(__file__))

    @classmethod
    async def generate(cls, param: Dict[str, Any]) -> str:
        """
        根据传入动态信息生成动态图片

        Args:
            param: 动态信息

        Returns:
            动态图片的 Base64 字符串
        """
        width = 740
        height = 100000
        text_margin = 25
        img_margin = 10
        generator = PicGenerator(width, height)
        pic = generator.set_pos(175, 60).draw_rounded_rectangle(0, 0, width, height, 35, Color.WHITE).copy_bottom(35)

        # 提取参数
        desc = param["desc"]
        dynamic_id = desc["dynamic_id"]
        origin_dynamic_id = desc["orig_dy_id"] if "orig_dy_id" in desc else None
        dynamic_type = desc["type"]

        user_profile = desc["user_profile"]
        card = json.loads(param["card"])
        display = param["display"]

        # 动态头部
        face, pendant = await asyncio.gather(open_url_image(user_profile["info"]["face"]),
                                             open_url_image(user_profile["pendant"]["image"]))
        official = user_profile["card"]["official_verify"]["type"]
        vip = user_profile["vip"]["nickname_color"] != ""
        uname = user_profile["info"]["uname"]
        timestamp = desc["timestamp"]

        await cls.__draw_header(pic, face, pendant, official, vip, uname, timestamp)

        # 动态主体
        pic.set_pos(x=text_margin)
        pic.set_row_space(10)

        await cls.__draw_by_type(pic, dynamic_type, card, dynamic_id, display,
                                 text_margin, img_margin, False, origin_dynamic_id)

        # 底部版权信息，请务必保留此处
        pic.move_pos(0, 15)
        pic.draw_text_right(25, "Designed By StarBot", Color.GRAY)
        pic.draw_text_right(25, "https://github.com/Starlwr/StarBot", Color.LINK)
        pic.crop_and_paste_bottom()

        if config.get("SAVE_DYNAMIC_IMAGE"):
            dynamic_dir = os.path.join(os.getcwd(), "dynamic")
            if not os.path.exists(dynamic_dir):
                os.makedirs(dynamic_dir)

            return pic.save_and_get_base64(
                os.path.join(dynamic_dir, f"{uname}_{dynamic_id}_{datetime.now().strftime('%Y-%m-%d-%H-%M-%S')}.png")
            )
        else:
            return pic.base64()

    @classmethod
    def __remove_illegal_char(cls, s: str) -> str:
        """
        移除动态中的非法字符

        Args:
            s: 源字符串

        Returns:
            移除非法字符后的字符串
        """
        return s.replace(chr(8203), "").replace(chr(65039), "")

    @classmethod
    async def __draw_header(cls,
                            pic: PicGenerator,
                            face: Image.Image,
                            pendant: Image.Image,
                            official: int,
                            vip: bool,
                            uname: str,
                            timestamp: int) -> PicGenerator:
        """
        绘制动态头部

        Args:
            pic: 绘图器实例
            face: 头像图片
            pendant: 头像挂件图片
            official: 认证类型
            vip: 是否为大会员
            uname: 昵称
            timestamp: 动态时间戳
        """
        face_size = (100, 100)
        face = face.resize(face_size, Resampling.LANCZOS).convert("RGBA")
        face = mask_round(face)
        pic.draw_img_alpha(face, (50, 50))

        if pendant is not None:
            pendant_size = (170, 170)
            pendant = pendant.resize(pendant_size, Resampling.LANCZOS).convert('RGBA')
            pic.draw_img_alpha(pendant, (15, 15))
            pic.move_pos(15, 0)

        if official == 0:
            pic.draw_img_alpha(Image.open(f"{cls.__resource_base_path}/resource/personal.png"), (118, 118))
        elif official == 1:
            pic.draw_img_alpha(Image.open(f"{cls.__resource_base_path}/resource/business.png"), (118, 118))
        elif vip:
            pic.draw_img_alpha(Image.open(f"{cls.__resource_base_path}/resource/vip.png"), (118, 118))

        if vip:
            pic.draw_text(uname, Color.PINK)
        else:
            pic.draw_text(uname, Color.BLACK)
        pic.draw_tip(timestamp_format(timestamp, "%Y-%m-%d %H:%M"))

        pic.set_pos(y=200)

        return pic

    @classmethod
    async def __draw_by_type(cls,
                             pic: PicGenerator,
                             dynamic_type: int,
                             card: Dict[str, Any],
                             dynamic_id: int,
                             display: Dict[str, Any],
                             text_margin: int,
                             img_margin: int,
                             forward: bool,
                             origin_dynamic_id: Optional[int] = None):
        """
        根据动态类型绘制动态图片

        Args:
            pic: 绘图器实例
            dynamic_type: 动态类型
            card: 动态信息
            dynamic_id: 动态 ID
            display: 动态绘制附加信息
            text_margin: 文字外边距
            img_margin: 图片外边距
            forward: 当前是否为转发动态的源动态
        """

        async def download_img(mod: Dict[str, Any]):
            """
            下载表情图片

            Args:
                mod: 表情区块字典
            """
            mod["img"] = await open_url_image(mod["emoji"]["icon_url"])
        
        if dynamic_type == 2 or dynamic_type == 4:
            # 有些动态带有标题，不显示标题会缺少上下文
            modules_url =f"https://api.bilibili.com/x/polymer/web-dynamic/v1/detail?timezone_offset=-480&id={dynamic_id}&features=itemOpusStyle,opusBigCover,onlyfansVote"
            modules = (await request("GET", modules_url, credential=get_credential()))["item"]["modules"]["module_dynamic"]["major"]["opus"]
            title = modules["title"]
            modules = modules["summary"]
            if modules:
                modules = modules["rich_text_nodes"]
                if title is not None:
                    modules.insert(0, {
                        'orig_text': f"{title}\n",
                        'text': f"{title}\n",
                        'type': 'RICH_TEXT_NODE_TYPE_TEXT'
                    })
            else:
                modules = []
        else :
            modules_url = f"https://api.bilibili.com/x/polymer/web-dynamic/v1/detail?timezone_offset=-480&id={dynamic_id}"
            modules = (await request("GET", modules_url, credential=get_credential()))["item"]["modules"]["module_dynamic"]["desc"]
            modules = modules["rich_text_nodes"] if modules else []

        # 下载表情
        download_picture_tasks = []
        for module in modules:
            if module["type"] == "RICH_TEXT_NODE_TYPE_EMOJI":
                download_picture_tasks.append(download_img(module))
        await asyncio.gather(*download_picture_tasks)

        if dynamic_type == 1:
            # 转发动态
            await cls.__draw_content(pic, modules, text_margin, forward)

            if "origin" in card:
                origin = json.loads(card["origin"])
                origin_type = card["item"]["orig_type"]
                origin_display = display["origin"]

                origin_name = card["origin_user"]["info"]["uname"]
                origin_name_at_param = [{"type": "RICH_TEXT_NODE_TYPE_AT", "text": f"@{origin_name}"}]
                await cls.__draw_content(pic, origin_name_at_param, text_margin, True)

                await cls.__draw_by_type(pic, origin_type, origin, origin_dynamic_id, origin_display,
                                         text_margin, img_margin, True)
        elif dynamic_type == 2:
            # 带图动态
            await cls.__draw_content(pic, modules, text_margin, forward)
            if card["item"]["pictures"]:
                await cls.__draw_picture_area(pic, card["item"]["pictures"], img_margin, forward)
        elif dynamic_type == 4:
            # 纯文字动态
            await cls.__draw_content(pic, modules, text_margin, forward)
        elif dynamic_type == 8:
            # 视频
            await cls.__draw_content(pic, modules, text_margin, forward)
            await cls.__draw_video_cover(pic, card["pic"], card["duration"], img_margin, forward)
            title_param = [{"type": "RICH_TEXT_NODE_TYPE_TEXT", "text": card["title"]}]
            await cls.__draw_content(pic, title_param, img_margin, forward)
        elif dynamic_type == 64:
            # 专栏
            title_param = [{"type": "RICH_TEXT_NODE_TYPE_TEXT", "text": card["title"]}]
            await cls.__draw_content(pic, title_param, text_margin, forward)
            await cls.__draw_article_cover(pic, card["origin_image_urls"], img_margin, forward)
            summary = limit_str_length(card['summary'].replace("\n", " "), 60)
            summary_param = [{"type": "RICH_TEXT_NODE_TYPE_TEXT", "text": summary}]
            await cls.__draw_content(pic, summary_param, text_margin, forward)
        elif dynamic_type == 256:
            # 音频
            await cls.__draw_content(pic, modules, text_margin, forward)
            title = limit_str_length(card["title"], 15)
            await cls.__draw_audio_area(pic, card["cover"], title, card["typeInfo"], text_margin, forward)
        elif dynamic_type == 2048:
            # 分享
            await cls.__draw_content(pic, modules, text_margin, forward)
            title = limit_str_length(card["sketch"]["title"], 15)
            desc = limit_str_length(card["sketch"]["desc_text"], 18)
            await cls.__draw_share_area(pic, card["sketch"]["cover_url"], title, desc, text_margin, forward)
        elif dynamic_type == 4200:
            # 直播
            title = limit_str_length(card["title"], 14)
            desc = f"{card['area_v2_name']} · {card['watched_show']}"
            await cls.__draw_live_area(pic, card["cover"], title, desc, text_margin, forward)
        elif dynamic_type == 4300:
            # 收藏
            title = limit_str_length(card["title"], 14)
            desc = limit_str_length(f"{card['media_count']}个内容", 17)
            await cls.__draw_live_area(pic, card["cover"], title, desc, text_margin, forward)
        elif dynamic_type == 4308:
            # 直播
            base = card["live_play_info"]
            title = limit_str_length(base["title"], 14)
            desc = f"{base['area_name']} {base['online']}人气"
            await cls.__draw_live_area(pic, base["cover"], title, desc, text_margin, forward)
        else:
            notice_param = [{"type": "RICH_TEXT_NODE_TYPE_TEXT", "text": "暂不支持的动态类型"}]
            await cls.__draw_content(pic, notice_param, text_margin, forward)

        # 附加卡片
        add_on_card = display["add_on_card_info"] if "add_on_card_info" in display else []

        await cls.__draw_add_on_card(pic, add_on_card, text_margin, forward)

    @classmethod
    async def __get_content_line_imgs(cls, modules: List[Dict[str, Any]], width: int) -> List[Image.Image]:
        """
        逐行绘制动态内容主体

        Args:
            modules: 动态内容各区块信息列表
            width: 每行最大宽度

        Returns:
            绘制好的每行文字的透明背景图片列表
        """
        imgs = []

        if not modules:
            return imgs

        line_height = 40
        text_img_size = (40, 40)

        img = Image.new("RGBA", (width, line_height))
        draw = ImageDraw.Draw(img)
        normal_font = config.get("PAINTER_NORMAL_FONT")
        font = ImageFont.truetype(f"{cls.__resource_base_path}/resource/{normal_font}", 30)
        emoji_font = ImageFont.truetype(f"{cls.__resource_base_path}/resource/emoji.ttf", 109)
        x, y = 0, 0

        def next_line():
            """
            换行
            """
            nonlocal img, draw, x, y

            imgs.append(img)
            img = Image.new("RGBA", (width, line_height))
            draw = ImageDraw.Draw(img)
            x, y = 0, 0

        def auto_next_line(next_element_width: int):
            """
            超出画布宽度自动换行

            Args:
                next_element_width: 下一绘制元素宽度
            """
            if x + next_element_width > width:
                next_line()

        def draw_pic(pic: Image, size: Optional[Tuple[int, int]] = None):
            """
            绘制图片

            Args:
                pic: 要绘制的图片
                size: 图片尺寸
            """
            nonlocal img, draw, x, y

            if size is None:
                size = pic.size
            else:
                pic = pic.resize(size, Resampling.LANCZOS).convert('RGBA')

            auto_next_line(size[0])
            img.paste(pic, (x, y), pic)
            pic.close()
            x = int(x + size[0])

        def draw_char(c: str, color: Union[Color, Tuple[int, int, int]] = Color.BLACK):
            """
            绘制字符

            Args:
                c: 要绘制的字符
                color: 字符颜色
            """
            nonlocal x

            if isinstance(color, Color):
                color = color.value

            if c == "\n":
                next_line()
            else:
                if is_emoji(c):
                    emoji_img = Image.new("RGBA", (130, 130))
                    emoji_draw = ImageDraw.Draw(emoji_img)
                    emoji_draw.text((0, 0), c, font=emoji_font, embedded_color=True)
                    emoji_img = emoji_img.resize((30, 30), Resampling.LANCZOS)
                    draw_pic(emoji_img)
                else:
                    text_width = draw.textlength(c, font)
                    auto_next_line(text_width)
                    draw.text((x, y), c, color, font)
                    x = int(x + text_width)

        for module in modules:
            module_type = module["type"]

            if module_type == "RICH_TEXT_NODE_TYPE_TEXT":
                for char in cls.__remove_illegal_char(module["text"]):
                    draw_char(char)
            elif module_type == "RICH_TEXT_NODE_TYPE_EMOJI":
                draw_pic(module["img"], text_img_size)
            elif module_type in ["RICH_TEXT_NODE_TYPE_AT", "RICH_TEXT_NODE_TYPE_WEB", "RICH_TEXT_NODE_TYPE_BV",
                                 "RICH_TEXT_NODE_TYPE_TOPIC", "RICH_TEXT_NODE_TYPE_LOTTERY",
                                 "RICH_TEXT_NODE_TYPE_VOTE", "RICH_TEXT_NODE_TYPE_GOODS"]:
                if module_type == "RICH_TEXT_NODE_TYPE_WEB":
                    draw_pic(Image.open(f"{cls.__resource_base_path}/resource/link.png"), text_img_size)
                elif module_type == "RICH_TEXT_NODE_TYPE_BV":
                    draw_pic(Image.open(f"{cls.__resource_base_path}/resource/video.png"), text_img_size)
                elif module_type == "RICH_TEXT_NODE_TYPE_LOTTERY":
                    draw_pic(Image.open(f"{cls.__resource_base_path}/resource/box.png"), text_img_size)
                elif module_type == "RICH_TEXT_NODE_TYPE_VOTE":
                    draw_pic(Image.open(f"{cls.__resource_base_path}/resource/tick.png"), text_img_size)
                elif module_type == "RICH_TEXT_NODE_TYPE_GOODS":
                    draw_pic(Image.open(f"{cls.__resource_base_path}/resource/tb.png"), text_img_size)
                for char in cls.__remove_illegal_char(module["text"]):
                    draw_char(char, Color.LINK)

        imgs.append(img)

        return imgs

    @classmethod
    async def __draw_content(cls,
                             pic: PicGenerator,
                             modules: List[Dict[str, Any]],
                             text_margin: int,
                             forward: bool) -> PicGenerator:
        """
        绘制动态主体内容

        Args:
            pic: 绘图器实例
            modules: @ 信息
            text_margin: 文字外边距
            forward: 当前是否为转发动态的源动态
        """
        pic.set_pos(x=text_margin)

        content_imgs = await cls.__get_content_line_imgs(modules, pic.width - (text_margin * 2))

        if forward and content_imgs:
            heights = (content_imgs[0].height + pic.row_space) * len(content_imgs)
            pic.draw_rectangle(0, pic.y, pic.width, heights, Color.LIGHTGRAY)

        for img in content_imgs:
            pic.draw_img_alpha(img)
        return pic

    @classmethod
    async def __draw_picture_area(cls,
                                  pic: PicGenerator,
                                  pictures: List[Dict[str, Any]],
                                  img_margin: int,
                                  forward: bool) -> PicGenerator:
        """
        绘制动态主体下方图片区域

        Args:
            pic: 绘图器实例
            pictures: 图片信息字典
            img_margin: 图片外边距
            forward: 当前是否为转发动态的源动态
        """
        pic.set_pos(x=img_margin)

        # 下载图片
        picture_count = len(pictures)
        if picture_count == 1:
            line_count = 1
            size = 0
        elif picture_count == 2 or picture_count == 4:
            line_count = 2
            size = int((pic.width - (img_margin * 3)) / 2)
        else:
            line_count = 3
            size = int((pic.width - (img_margin * 4)) / 3)

        download_picture_tasks = []
        for picture in pictures:
            if line_count == 1:
                download_picture_tasks.append(open_url_image(f"{picture['img_src']}@518w.webp"))
            elif line_count == 2:
                src = picture['img_src']
                if picture["img_height"] / picture["img_width"] >= 3:
                    download_picture_tasks.append(open_url_image(f"{src}@{size}w_{size}h_!header.webp"))
                else:
                    download_picture_tasks.append(open_url_image(f"{src}@{size}w_{size}h_1e_1c.webp"))
            else:
                src = picture['img_src']
                if picture["img_height"] / picture["img_width"] >= 3:
                    download_picture_tasks.append(open_url_image(f"{src}@{size}w_{size}h_!header.webp"))
                else:
                    download_picture_tasks.append(open_url_image(f"{src}@{size}w_{size}h_1e_1c.webp"))
        imgs = await asyncio.gather(*download_picture_tasks)

        img_list = []
        for i, img in enumerate(imgs):
            if size != 0 and (img.width != size or img.height != size):
                if img.width == img.height:
                    img_list.append(img.resize((size, size)))
                elif img.width > img.height:
                    img = img.resize((int(img.width * (size / img.height)), size))
                    crop_area = (int((img.width - size) / 2), 0, int((img.width - size) / 2) + size, size)
                    img_list.append(img.crop(crop_area))
                else:
                    img = img.resize((size, int(img.height * (size / img.width))))
                    crop_area = (0, int((img.height - size) / 2), size, int((img.height - size) / 2) + size)
                    img_list.append(img.crop(crop_area))
            else:
                img_list.append(img)
        imgs = tuple(img_list)

        if picture_count == 1:
            img = imgs[0]
            img = img.resize(((pic.width - (img_margin * 2)),
                              int((pic.width - (img_margin * 4)) / (img.size[0] / img.size[1]))),
                             Resampling.LANCZOS)
            imgs = [img]

        # 绘制图片
        imgs = split_list(imgs, line_count)

        if forward:
            heights = (imgs[0][0].height + pic.row_space) * len(imgs)
            pic.draw_rectangle(0, pic.y, pic.width, heights, Color.LIGHTGRAY)

        for line in imgs:
            for index, img in enumerate(line):
                if index == len(line) - 1:
                    pic.draw_img(img).set_pos(x=img_margin)
                else:
                    pic.draw_img(img, (pic.x, pic.y)).move_pos(img.width + img_margin, 0)
        return pic

    @classmethod
    async def __draw_video_cover(cls,
                                 pic: PicGenerator,
                                 url: str,
                                 duration: int,
                                 img_margin: int,
                                 forward: bool) -> PicGenerator:
        """
        绘制视频封面

        Args:
            pic: 绘图器实例
            url: 视频封面 URL
            duration: 视频时长
            img_margin: 图片外边距
            forward: 当前是否为转发动态的源动态
        """
        pic.set_pos(x=img_margin)

        cover = await open_url_image(f"{url}@480w.webp")
        cover = cover.resize(((pic.width - (img_margin * 2)),
                              int((pic.width - (img_margin * 4)) / (cover.size[0] / cover.size[1]))),
                             Resampling.LANCZOS)
        cover = mask_rounded_rectangle(cover)

        mask = Image.open(f"{cls.__resource_base_path}/resource/mask.png")
        mask = mask.crop((0, 0, cover.width, mask.height))
        time = Image.open(f"{cls.__resource_base_path}/resource/time.png")
        tv = Image.open(f"{cls.__resource_base_path}/resource/tv.png")

        cover.paste(mask, (0, cover.height - mask.height - 1), mask)
        cover.paste(time, (13, cover.height - time.height - 14), time)
        cover.paste(tv, (cover.width - tv.width - 16, cover.height - tv.height - 5), tv)

        mask.close()
        time.close()
        tv.close()

        cover_draw = ImageDraw.Draw(cover)
        normal_font = config.get("PAINTER_NORMAL_FONT")
        time_font = ImageFont.truetype(f"{cls.__resource_base_path}/resource/{normal_font}", 25)
        cover_draw.text((21, cover.height - time.height - 11),
                        timestamp_format(duration + 57600, "%H:%M:%S"), Color.WHITE.value, time_font)

        if forward:
            cover_height = cover.height + pic.row_space
            pic.draw_rectangle(0, pic.y, pic.width, cover_height, Color.LIGHTGRAY)

        pic.draw_img_alpha(cover)
        return pic

    @classmethod
    async def __draw_article_cover(cls,
                                   pic: PicGenerator,
                                   urls: List[str],
                                   img_margin: int,
                                   forward: bool) -> PicGenerator:
        """
        绘制专栏封面

        Args:
            pic: 绘图器实例
            urls: 专栏封面 URL 列表
            img_margin: 图片外边距
            forward: 当前是否为转发动态的源动态
        """
        pic.set_pos(x=img_margin)

        img_count = len(urls)

        download_picture_tasks = []
        for url in urls:
            if img_count == 1:
                download_picture_tasks.append(open_url_image(f"{url}@518w.webp"))
            else:
                size = int((pic.width - (img_margin * 4)) / 3)
                download_picture_tasks.append(open_url_image(f"{url}@{size}w_{size}h_1e_1c.webp"))
        imgs = await asyncio.gather(*download_picture_tasks)

        if img_count == 1:
            img = imgs[0]
            img = img.resize(((pic.width - (img_margin * 2)),
                              int((pic.width - (img_margin * 4)) / (img.size[0] / img.size[1]))),
                             Resampling.LANCZOS)
            imgs = [img]

        if forward:
            heights = imgs[0].height + pic.row_space
            pic.draw_rectangle(0, pic.y, pic.width, heights, Color.LIGHTGRAY)

        for index, img in enumerate(imgs):
            if index == len(imgs) - 1:
                pic.draw_img(img).set_pos(x=img_margin)
            else:
                pic.draw_img(img, (pic.x, pic.y)).move_pos(img.width + img_margin, 0)

        return pic

    @classmethod
    async def __draw_audio_area(cls,
                                pic: PicGenerator,
                                cover_url: str,
                                title: str,
                                audio_type: str,
                                margin: int,
                                forward: bool) -> PicGenerator:
        """
        绘制音频卡片

        Args:
            pic: 绘图器实例
            cover_url: 音频封面 URL
            title: 音频标题
            audio_type: 音频类型
            margin: 外边距
            forward: 当前是否为转发动态的源动态
        """
        return await cls.__draw_share_area(pic, cover_url, title, audio_type, margin, forward)

    @classmethod
    async def __draw_share_area(cls,
                                pic: PicGenerator,
                                cover_url: str,
                                title: str,
                                desc: str,
                                margin: int,
                                forward: bool) -> PicGenerator:
        """
        绘制分享卡片

        Args:
            pic: 绘图器实例
            cover_url: 封面 URL
            title: 标题
            desc: 描述
            margin: 外边距
            forward: 当前是否为转发动态的源动态
        """
        pic.set_pos(x=margin)

        cover = await open_url_image(cover_url)
        cover_size = int(pic.width / 4)
        cover = cover.resize((cover_size, cover_size), Resampling.LANCZOS).convert("RGBA")
        cover = mask_rounded_rectangle(cover)

        if forward:
            heights = cover.height + pic.row_space
            pic.draw_rectangle(0, pic.y, pic.width, heights, Color.LIGHTGRAY)

        border = Image.new("RGBA", (pic.width - (margin * 2) + 2, cover_size + 2))
        ImageDraw.Draw(border).rounded_rectangle((0, 0, border.width - 1, border.height - 1),
                                                 10, (0, 0, 0, 0), Color.GRAY.value, 1)
        pic.draw_img_alpha(border, (pic.x - 1, pic.y - 1))

        x, y = pic.xy
        pic.draw_img_alpha(cover)
        pic.draw_text(title, Color.BLACK, (x + cover_size + margin, y + int(cover_size / 5)))
        pic.draw_tip(desc, xy=(x + cover_size + margin, y + int(cover_size / 5 * 3)))

        return pic

    @classmethod
    async def __draw_live_area(cls,
                               pic: PicGenerator,
                               cover_url: str,
                               title: str,
                               area: str,
                               margin: int,
                               forward: bool) -> PicGenerator:
        """
        绘制直播卡片

        Args:
            pic: 绘图器实例
            cover_url: 直播封面 URL
            title: 直播标题
            area: 直播分区
            margin: 外边距
            forward: 当前是否为转发动态的源动态
        """
        pic.set_pos(x=margin)

        cover = await open_url_image(f"{cover_url}@203w_127h_1e_1c.webp")

        if forward:
            heights = cover.height + pic.row_space
            pic.draw_rectangle(0, pic.y, pic.width, heights, Color.LIGHTGRAY)

        back = Image.new("RGBA", (pic.width - (margin * 2), cover.height))
        ImageDraw.Draw(back).rectangle((0, 0, back.width, back.height), Color.WHITE.value)
        pic.draw_img_alpha(back, (pic.x, pic.y))

        x, y = pic.xy
        pic.draw_img(cover)
        pic.draw_text(title, Color.BLACK, (x + cover.width + margin, y + int(cover.height / 5)))
        pic.draw_tip(area, xy=(x + cover.width + margin, y + int(cover.height / 5 * 3)))

        return pic

    @classmethod
    async def __draw_add_on_card(cls,
                                 pic: PicGenerator,
                                 infos: List[Dict[str, Any]],
                                 margin: int,
                                 forward: bool) -> PicGenerator:
        """
        绘制附加卡片

        Args:
            pic: 绘图器实例
            infos: 附加卡片信息
            margin: 外边距
            forward: 当前是否为转发动态的源动态
        """
        pic.set_pos(x=margin)

        card_height = int(pic.width / 4)

        padding = 10
        img_height = card_height - padding * 2
        edge = pic.width - margin - padding

        for info in infos:
            # 绘制背景
            back_color = Color.LIGHTGRAY
            if forward:
                pic.draw_rectangle(0, pic.y, pic.width, card_height + pic.row_space + padding, Color.LIGHTGRAY)
                back_color = Color.WHITE

            pic.move_pos(0, padding)
            back = Image.new("RGBA", (pic.width - (margin * 2), card_height))
            ImageDraw.Draw(back).rectangle((0, 0, back.width, back.height), back_color.value)
            back = mask_rounded_rectangle(back)
            pic.draw_img_alpha(back, (pic.x, pic.y))
            pic.move_pos(padding, padding)

            # 根据类型绘制卡片
            card_type = info["add_on_card_show_type"]
            if card_type == 1:
                # 淘宝
                goods = json.loads(info["goods_card"])["list"]

                download_picture_tasks = []
                for good in goods:
                    url = good["img"]
                    download_picture_tasks.append(open_url_image(f"{url}@{img_height}w_{img_height}h_1e_1c.webp"))
                imgs = await asyncio.gather(*download_picture_tasks)

                if len(goods) == 1:
                    img = mask_rounded_rectangle(imgs[0])
                    x, y = pic.xy
                    pic.draw_img(img)

                    name = limit_str_length(goods[0]["name"], 15)
                    price = goods[0]["priceStr"]
                    price_length = pic.get_text_length(price)
                    pic.draw_text(name, Color.BLACK, (x + img.width + margin, y + int(img_height / 5)))
                    pic.draw_text(price, Color.LINK, (x + img.width + margin, y + int(img_height / 5 * 3)))
                    pic.draw_tip("起", xy=(x + img.width + margin + price_length, y + int(img_height / 5 * 3) + 5))
                else:
                    for index, img in enumerate(imgs):
                        overflow = False
                        img = mask_rounded_rectangle(img)
                        if pic.x + img.width > edge:
                            overflow = True
                            img = img.crop((0, 0, edge - pic.x, img.height))

                        if index == len(imgs) - 1 or overflow:
                            pic.draw_img(img).set_pos(x=margin)
                            if overflow:
                                break
                        else:
                            pic.draw_img(img, pic.xy).move_pos(img.height + margin, 0)
            elif card_type == 2:
                # 充电、相关装扮、相关游戏
                base = info["attach_card"]

                if base["type"] in ["decoration", "game"]:
                    url = base["cover_url"]
                    title = base["title"]
                    desc_first = base["desc_first"]
                    desc_second = base["desc_second"]

                    cover = await open_url_image(f"{url}@{img_height}w_{img_height}h_1e_1c.webp")
                    cover = mask_rounded_rectangle(cover)

                    x, y = pic.xy
                    pic.draw_img(cover)
                    pic.draw_text(title, Color.BLACK, (x + cover.width + margin, y + int(img_height / 10)))
                    pic.draw_tip(desc_first, xy=(x + cover.width + margin, y + int(img_height / 7 * 3)))
                    pic.draw_tip(desc_second, xy=(x + cover.width + margin, y + int(img_height / 7 * 5)))
                elif base["type"] == "lottery":
                    title = base["title"]
                    desc_first = limit_str_length(base["desc_first"], 24)
                    icon = Image.open(f"{cls.__resource_base_path}/resource/box.png")

                    x, y = pic.xy
                    pic.draw_text(title, Color.BLACK, (x, y + int(img_height / 5)))
                    pic.draw_img_alpha(icon, (x, y + int(img_height / 5 * 3)))
                    pic.draw_tip(desc_first, Color.LINK, (x + icon.width + padding, y + int(img_height / 5 * 3)))
                    pic.set_pos(x=margin).move_pos(0, img_height + pic.row_space)
            elif card_type == 3:
                # 投票
                vote = json.loads(info["vote_card"])
                desc = limit_str_length(vote["desc"], 15)
                join_num = vote["join_num"]
                icon = Image.open(f"{cls.__resource_base_path}/resource/tick_big.png")
                icon = mask_rounded_rectangle(icon.resize((img_height, img_height), Resampling.LANCZOS))

                x, y = pic.xy
                pic.draw_img_alpha(icon)
                pic.draw_text(desc, Color.BLACK, (x + img_height + margin, y + int(img_height / 5)))
                pic.draw_tip(f"{join_num}人参与", xy=(x + img_height + margin, y + int(img_height / 5 * 3)))
            elif card_type == 5:
                # 视频分享
                base = info["ugc_attach_card"]
                url = base["image_url"]
                title = limit_str_length(base["title"], 12)
                desc_second = base["desc_second"]
                cover = await open_url_image(f"{url}@480w.webp")
                cover = cover.resize((int(img_height / cover.height * cover.width), img_height), Resampling.LANCZOS)
                cover = mask_rounded_rectangle(cover)

                x, y = pic.xy
                pic.draw_img(cover)
                pic.draw_text(title, Color.BLACK, (x + cover.width + margin, y + int(img_height / 5)))
                pic.draw_tip(desc_second, xy=(x + cover.width + margin, y + int(img_height / 5 * 3)))
            elif card_type == 6:
                # 视频、直播预约
                base = info["reserve_attach_card"]
                title = cls.__remove_illegal_char(base["title"])
                desc_first = base["desc_first"]["text"]
                desc_second = base["desc_second"]
                desc = f"{desc_first}   {desc_second}"
                lottery = limit_str_length(base["reserve_lottery"]["text"], 24) if "reserve_lottery" in base else None

                if lottery:
                    pic.draw_text(title, Color.BLACK, (pic.x, pic.y + int(img_height / 10)))
                    pic.draw_tip(desc, xy=(pic.x, pic.y + int(img_height / 7 * 3)))
                    icon = Image.open(f"{cls.__resource_base_path}/resource/box.png")
                    pic.draw_img_alpha(icon, (pic.x, pic.y + int(img_height / 7 * 5)))
                    pic.draw_tip(lottery, Color.LINK, (pic.x + icon.width + padding, pic.y + int(img_height / 7 * 5)))
                else:
                    pic.draw_text(title, Color.BLACK, (pic.x, pic.y + int(img_height / 5)))
                    pic.draw_tip(desc, xy=(pic.x, pic.y + int(img_height / 5 * 3)))

                pic.set_pos(x=margin).move_pos(0, img_height + pic.row_space)

        return pic
