import asyncio
import base64
import json
import os
from enum import Enum
from io import BytesIO
from typing import Optional, Union, Tuple, List, Dict, Any

from PIL import Image, ImageDraw, ImageFont
from PIL.Image import Resampling
from emoji import is_emoji

from .network import request
from .utils import open_url_image, timestamp_format, split_list, limit_str_length, mask_round, mask_rounded_rectangle
from ..core.model import LiveReport
from ..utils import config


class Color(Enum):
    """
    常用颜色 RGB 枚举

    + BLACK: 黑色
    + WHITE: 白色
    + GRAY: 灰色
    + LIGHTGRAY: 淡灰色
    + RED: 红色
    + GREEN: 绿色
    + DEEPBLUE: 深蓝色
    + LIGHTBLUE: 浅蓝色
    + DEEPRED: 深红色
    + LIGHTRED: 浅红色
    + DEEPGREEN: 深绿色
    + LIGHTGREEN: 浅绿色
    + CRIMSON: 总督红
    + FUCHSIA: 提督紫
    + DEEPSKYBLUE: 舰长蓝
    + LINK: 超链接蓝
    + PINK: 大会员粉
    """
    BLACK = (0, 0, 0)
    WHITE = (255, 255, 255)
    GRAY = (169, 169, 169)
    LIGHTGRAY = (244, 244, 244)
    RED = (150, 0, 0)
    GREEN = (0, 150, 0)
    DEEPBLUE = (55, 187, 248)
    LIGHTBLUE = (175, 238, 238)
    DEEPRED = (240, 128, 128)
    LIGHTRED = (255, 220, 220)
    DEEPGREEN = (0, 255, 0)
    LIGHTGREEN = (184, 255, 184)
    CRIMSON = (220, 20, 60)
    FUCHSIA = (255, 0, 255)
    DEEPSKYBLUE = (0, 191, 255)
    LINK = (23, 139, 207)
    PINK = (251, 114, 153)


class PicGenerator:
    """
    基于 Pillow 的绘图器，可使用流式调用方法
    """

    def __init__(self,
                 width: int,
                 height: int,
                 normal_font: str = config.get("PAINTER_NORMAL_FONT"),
                 bold_font: str = config.get("PAINTER_BOLD_FONT")):
        """
        初始化绘图器

        Args:
            width: 画布宽度
            height: 画布高度
            normal_font: 普通字体路径。默认：config.get("PAINTER_NORMAL_FONT") = "normal.ttf"
            bold_font: 粗体字体路径。默认：config.get("PAINTER_BOLD_FONT") = "bold.ttf"
        """
        self.__width = width
        self.__height = height
        self.__canvas = Image.new("RGBA", (self.width, self.height))
        self.__draw = ImageDraw.Draw(self.__canvas)

        resource_base_path = os.path.dirname(os.path.dirname(__file__))
        self.__chapter_font = ImageFont.truetype(f"{resource_base_path}/resource/{bold_font}", 50)
        self.__section_font = ImageFont.truetype(f"{resource_base_path}/resource/{bold_font}", 40)
        self.__tip_font = ImageFont.truetype(f"{resource_base_path}/resource/{normal_font}", 25)
        self.__text_font = ImageFont.truetype(f"{resource_base_path}/resource/{normal_font}", 30)

        self.__xy = 0, 0
        self.__ROW_SPACE = 25

        self.__bottom_pic = None

    @property
    def width(self):
        return self.__width

    @property
    def height(self):
        return self.__height

    @property
    def x(self):
        return self.__xy[0]

    @property
    def y(self):
        return self.__xy[1]

    @property
    def xy(self):
        return self.__xy

    @property
    def row_space(self):
        return self.__ROW_SPACE

    @property
    def img(self):
        return self.__canvas

    def set_row_space(self, row_space: int):
        """
        设置默认行距

        Args:
            row_space: 行距
        """
        self.__ROW_SPACE = row_space
        return self

    def set_pos(self, x: Optional[int] = None, y: Optional[int] = None):
        """
        设置绘图坐标

        Args:
            x: X 坐标
            y: Y 坐标
        """
        x = x if x is not None else self.x
        y = y if y is not None else self.y
        self.__xy = x, y
        return self

    def move_pos(self, x: int, y: int):
        """
        移动绘图坐标

        Args:
            x: X 偏移量
            y: Y 偏移量
        """
        self.__xy = self.x + x, self.y + y
        return self

    def copy_bottom(self, height: int):
        """
        拷贝指定高度的画布底部图片
        与 crop_and_paste_bottom() 函数配合
        在事先不能确定画布高度时，先创建一个高度极大的画布，内容绘制结束时剪裁底部多余区域并将底部图片粘贴回原位

        Args:
            height: 拷贝高度，从图片底部计算
        """
        self.__bottom_pic = self.__canvas.crop((0, self.height - height, self.width, self.height))
        return self

    def crop_and_paste_bottom(self):
        """
        内容绘图结束后，根据当前绘图坐标剪裁多余的底部区域，并将事先拷贝的底部图片粘贴回底部
        """
        if self.__bottom_pic is None:
            self.__canvas = self.__canvas.crop((0, 0, self.width, self.y))
            return self

        self.__canvas = self.__canvas.crop((0, 0, self.width, self.y + self.__bottom_pic.height))
        self.draw_img(self.__bottom_pic, (0, self.y))
        return self

    def draw_rectangle(self,
                       x: int,
                       y: int,
                       width: int,
                       height: int,
                       color: Union[Color, Tuple[int, int, int]]):
        """
        绘制一个矩形，此方法不会移动绘图坐标

        Args:
            x: 矩形左上角的 x 坐标
            y: 矩形左上角的 y 坐标
            width: 矩形的宽度
            height: 矩形的高度
            color: 矩形的背景颜色
        """
        if isinstance(color, Color):
            color = color.value

        self.__draw.rectangle(((x, y), (x + width, y + height)), color)
        return self

    def draw_rounded_rectangle(self,
                               x: int,
                               y: int,
                               width: int,
                               height: int,
                               radius: int,
                               color: Union[Color, Tuple[int, int, int]]):
        """
        绘制一个圆角矩形，此方法不会移动绘图坐标

        Args:
            x: 圆角矩形左上角的 x 坐标
            y: 圆角矩形左上角的 y 坐标
            width: 圆角矩形的宽度
            height: 圆角矩形的高度
            radius: 圆角矩形的圆角半径
            color: 圆角矩形的背景颜色
        """
        if isinstance(color, Color):
            color = color.value

        self.__draw.rounded_rectangle(((x, y), (x + width, y + height)), radius, color)
        return self

    def auto_size_img_by_limit(self,
                               img: Image.Image,
                               xy_limit: Tuple[int, int],
                               xy: Optional[Tuple[int, int]] = None) -> Image:
        """
        将指定的图片限制为不可覆盖指定的点，若其将要覆盖指定的点，会自适应缩小图片至不会覆盖指定的点

        Args:
            img: 要限制的图片
            xy_limit: 指定不可被覆盖的点
            xy: 图片将要被绘制到的坐标。默认：自适应的当前绘图坐标
        """
        if xy is None:
            xy = self.__xy

        return self.auto_size_img_by_limit_cls(img, xy_limit, xy)

    @classmethod
    def auto_size_img_by_limit_cls(cls, img: Image.Image, xy_limit: Tuple[int, int], xy: Tuple[int, int]) -> Image:
        """
        将指定的图片限制为不可覆盖指定的点，若其将要覆盖指定的点，会自适应缩小图片至不会覆盖指定的点

        Args:
            img: 要限制的图片
            xy_limit: 指定不可被覆盖的点
            xy: 图片将要被绘制到的坐标
        """
        cover_margin = config.get("PAINTER_AUTO_SIZE_BY_LIMIT_MARGIN")
        xy_limit = xy_limit[0] - cover_margin, xy_limit[1] + cover_margin

        x_cover = xy[0] + img.width - xy_limit[0]

        if xy[1] >= xy_limit[1] or x_cover <= 0:
            return img

        width = img.width - x_cover
        height = int(img.height * (width / img.width))
        return img.resize((width, height))

    def draw_img(self, img: Union[str, Image.Image], xy: Optional[Tuple[int, int]] = None):
        """
        在当前绘图坐标绘制一张图片，会自动移动绘图坐标至下次绘图适合位置
        也可手动传入绘图坐标，手动传入时不会移动绘图坐标

        Args:
            img: 图片路径或 Image 图片实例
            xy: 绘图坐标。默认：自适应绘图坐标
        """
        if isinstance(img, str):
            img = Image.open(img)

        if xy is None:
            self.__canvas.paste(img, self.__xy)
            self.move_pos(0, img.height + self.__ROW_SPACE)
        else:
            self.__canvas.paste(img, xy)
        return self

    def draw_img_alpha(self, img: Union[str, Image.Image], xy: Optional[Tuple[int, int]] = None):
        """
        在当前绘图坐标绘制一张透明背景图片，会自动移动绘图坐标至下次绘图适合位置
        也可手动传入绘图坐标，手动传入时不会移动绘图坐标

        Args:
            img: 透明背景图片路径或 Image 图片实例
            xy: 绘图坐标。默认：自适应绘图坐标
        """
        if isinstance(img, str):
            img = Image.open(img)

        if xy is None:
            self.__canvas.paste(img, self.__xy, img)
            self.move_pos(0, img.height + self.__ROW_SPACE)
        else:
            self.__canvas.paste(img, xy, img)
        return self

    def draw_img_with_border(self,
                             img: Union[str, Image.Image],
                             xy: Optional[Tuple[int, int]] = None,
                             color: Optional[Union[Color, Tuple[int, int, int]]] = Color.BLACK,
                             radius: int = 10,
                             width: int = 1):
        """
        在当前绘图坐标绘制一张图片并附带圆角矩形边框，会自动移动绘图坐标至下次绘图适合位置
        也可手动传入绘图坐标，手动传入时不会移动绘图坐标

        Args:
            img: 图片路径或 Image 图片实例
            xy: 绘图坐标。默认：自适应绘图坐标
            color: 边框颜色。默认：黑色 (0, 0, 0)
            radius: 边框圆角半径。默认：10
            width: 边框粗细。默认：1
        """
        if isinstance(img, str):
            img = Image.open(img)

        if isinstance(color, Color):
            color = color.value

        if xy is None:
            xy = self.__xy
            self.draw_img(img)
        else:
            self.draw_img(img, xy)

        border = Image.new("RGBA", (img.width + (width * 2), img.height + (width * 2)))
        ImageDraw.Draw(border).rounded_rectangle((0, 0, img.width, img.height), radius, (0, 0, 0, 0), color, width)
        self.draw_img_alpha(border, (xy[0] - width, xy[1] - width))
        return self

    def draw_chapter(self,
                     chapter: str,
                     color: Union[Color, Tuple[int, int, int]] = Color.BLACK,
                     xy: Optional[Tuple[int, int]] = None):
        """
        在当前绘图坐标绘制一个章节标题，会自动移动绘图坐标至下次绘图适合位置
        也可手动传入绘图坐标，手动传入时不会移动绘图坐标

        Args:
            chapter: 章节名称
            color: 字体颜色。默认：黑色 (0, 0, 0)
            xy: 绘图坐标。默认：自适应绘图坐标
        """
        if isinstance(color, Color):
            color = color.value

        if xy is None:
            self.__draw.text(self.__xy, chapter, color, self.__chapter_font)
            self.move_pos(0, self.__chapter_font.size + self.__ROW_SPACE)
        else:
            self.__draw.text(xy, chapter, color, self.__chapter_font)
        return self

    def get_chapter_length(self, s: str) -> int:
        """
        获取绘制指定字符串的章节标题所需长度（像素数）

        Args:
            s: 要绘制的字符串
        """
        return int(self.__draw.textlength(s, self.__chapter_font))

    def draw_section(self,
                     section: str,
                     color: Union[Color, Tuple[int, int, int]] = Color.BLACK,
                     xy: Optional[Tuple[int, int]] = None):
        """
        在当前绘图坐标绘制一个小节标题，会自动移动绘图坐标至下次绘图适合位置
        也可手动传入绘图坐标，手动传入时不会移动绘图坐标

        Args:
            section: 小节名称
            color: 字体颜色。默认：黑色 (0, 0, 0)
            xy: 绘图坐标。默认：自适应绘图坐标
        """
        if isinstance(color, Color):
            color = color.value

        if xy is None:
            self.__draw.text(self.__xy, section, color, self.__section_font)
            self.move_pos(0, self.__section_font.size + self.__ROW_SPACE)
        else:
            self.__draw.text(xy, section, color, self.__section_font)
        return self

    def get_section_length(self, s: str) -> int:
        """
        获取绘制指定字符串的小节标题所需长度（像素数）

        Args:
            s: 要绘制的字符串
        """
        return int(self.__draw.textlength(s, self.__section_font))

    def draw_tip(self,
                 tip: str,
                 color: Union[Color, Tuple[int, int, int]] = Color.GRAY,
                 xy: Optional[Tuple[int, int]] = None):
        """
        在当前绘图坐标绘制一个提示，会自动移动绘图坐标至下次绘图适合位置
        也可手动传入绘图坐标，手动传入时不会移动绘图坐标

        Args:
            tip: 提示内容
            color: 字体颜色。默认：灰色 (169, 169, 169)
            xy: 绘图坐标。默认：自适应绘图坐标
        """
        if isinstance(color, Color):
            color = color.value

        if xy is None:
            self.__draw.text(self.__xy, tip, color, self.__tip_font)
            self.move_pos(0, self.__tip_font.size + self.__ROW_SPACE)
        else:
            self.__draw.text(xy, tip, color, self.__tip_font)
        return self

    def get_tip_length(self, s: str) -> int:
        """
        获取绘制指定字符串的提示所需长度（像素数）

        Args:
            s: 要绘制的字符串
        """
        return int(self.__draw.textlength(s, self.__tip_font))

    def draw_text(self,
                  texts: Union[str, List[str]],
                  colors: Optional[Union[Color, Tuple[int, int, int], List[Union[Color, Tuple[int, int, int]]]]] = None,
                  xy: Optional[Tuple[int, int]] = None):
        """
        在当前绘图坐标绘制一行文本，会自动移动绘图坐标至下次绘图适合位置
        也可手动传入绘图坐标，手动传入时不会移动绘图坐标
        传入文本列表和颜色列表可将一行文本绘制为不同颜色，文本列表和颜色列表需一一对应
        颜色列表少于文本列表时将使用默认黑色 (0, 0, 0)，颜色列表多于文本列表时将舍弃多余颜色

        Args:
            texts: 文本内容
            colors: 字体颜色。默认：黑色 (0, 0, 0)
            xy: 绘图坐标。默认：自适应绘图坐标
        """
        if colors is None:
            colors = []

        if isinstance(texts, str):
            texts = [texts]

        if isinstance(colors, (Color, tuple)):
            colors = [colors]

        for i in range(len(texts) - len(colors)):
            colors.append(Color.BLACK)

        for i in range(len(colors)):
            if isinstance(colors[i], Color):
                colors[i] = colors[i].value

        if xy is None:
            x = self.x
            for i in range(len(texts)):
                self.__draw.text(self.__xy, texts[i], colors[i], self.__text_font)
                self.move_pos(int(self.__draw.textlength(texts[i], self.__text_font)), 0)
            self.move_pos(x - self.x, self.__text_font.size + self.__ROW_SPACE)
        else:
            for i in range(len(texts)):
                self.__draw.text(xy, texts[i], colors[i], self.__text_font)
                xy = xy[0] + self.__draw.textlength(texts[i], self.__text_font), xy[1]
        return self

    def draw_text_right(self,
                        margin_right: int,
                        texts: Union[str, List[str]],
                        colors: Optional[Union[Color, Tuple[int, int, int],
                                               List[Union[Color, Tuple[int, int, int]]]]] = None,
                        xy_limit: Optional[Tuple[int, int]] = (0, 0)):
        """
        在当前绘图坐标绘制一行右对齐文本，会自动移动绘图坐标保证不会覆盖指定的点，会自动移动绘图坐标至下次绘图适合位置
        传入文本列表和颜色列表可将一行文本绘制为不同颜色，文本列表和颜色列表需一一对应
        颜色列表少于文本列表时将使用默认黑色 (0, 0, 0)，颜色列表多于文本列表时将舍弃多余颜色

        Args:
            margin_right: 右边距
            texts: 文本内容
            colors: 字体颜色。默认：黑色 (0, 0, 0)
            xy_limit: 指定不可被覆盖的点。默认：(0, 0)
        """
        xy = self.__xy

        x = self.width - self.__draw.textlength("".join(texts), self.__text_font) - margin_right

        cover_margin = config.get("PAINTER_AUTO_SIZE_BY_LIMIT_MARGIN")
        xy_limit = xy_limit[0] - cover_margin, xy_limit[1] + cover_margin
        y = max(xy[1], xy_limit[1])

        self.draw_text(texts, colors, (x, y))
        self.set_pos(xy[0], y + self.__text_font.size + self.__ROW_SPACE)

        return self

    def get_text_length(self, s: str) -> int:
        """
        获取绘制指定字符串的文本所需长度（像素数）

        Args:
            s: 要绘制的字符串
        """
        return int(self.__draw.textlength(s, self.__text_font))

    def show(self):
        """
        显示图片
        """
        self.__canvas.show()
        return self

    def save(self, path: str):
        """
        保存图片

        Args:
            path: 保存路径
        """
        self.__canvas.save(path)
        return self

    def base64(self) -> str:
        """
        结束绘图，获取 Base64 字符串

        Returns:
            Base64 字符串
        """
        io = BytesIO()
        self.__canvas.save(io, format="PNG")

        return base64.b64encode(io.getvalue()).decode()


class LiveReportGenerator:
    """
    直播报告生成器
    """

    @classmethod
    def generate(cls, param: Dict[str, Any], model: LiveReport) -> str:
        """
        根据传入直播报告参数生成直播报告图片

        Args:
            param: 直播报告参数
            model: 直播报告配置实例

        Returns:
            直播报告图片的 Base64 字符串
        """
        width = 1000
        height = 10000
        top_blank = 75
        margin = 50

        generator = PicGenerator(width, height)
        pic = (generator.set_pos(margin, top_blank + margin)
               .draw_rounded_rectangle(0, top_blank, width, height - top_blank, 35, Color.WHITE)
               .copy_bottom(35))

        # 标题
        pic.draw_chapter("直播报告")

        # 防止后续绘图覆盖主播立绘
        logo_limit = (0, 0)

        # 主播立绘
        if model.logo or model.logo_base64:
            logo = cls.__get_logo(model)

            base_left = 650
            logo_left = base_left + int((width - base_left - logo.width) / 2)
            if logo_left < base_left:
                logo_left = base_left
            pic.draw_img_alpha(logo, (logo_left, 0))

            logo_limit = (logo_left, logo.height)

        # 主播信息
        uname = param.get('uname', '')
        room_id = param.get('room_id', 0)
        pic.draw_tip(f"{uname} ({room_id})")

        # 直播时长
        if model.time:
            start_time = param.get('start_time', '')
            end_time = param.get('end_time', '')
            hour = param.get('hour', 0)
            minute = param.get('minute', 0)
            second = param.get('second', 0)
            live_time_str = ""
            if hour != 0:
                live_time_str += f"{hour} 时 "
            if minute != 0:
                live_time_str += f"{minute} 分 "
            if second != 0:
                live_time_str += f"{second} 秒"
            if live_time_str == "":
                live_time_str = "0 秒"
            pic.draw_tip(f"{start_time} ~ {end_time} ({live_time_str.strip()})")

        # 基础数据
        if model.fans_change or model.fans_medal_change or model.guard_change:
            pic.draw_section("基础数据")

            if model.fans_change:
                fans_before = param.get('fans_before', 0)
                fans_after = param.get('fans_after', 0)
                if fans_before == -1:
                    fans_before = "?"
                    diff = 0
                else:
                    diff = fans_after - fans_before
                if diff > 0:
                    pic.draw_text([f"粉丝: {fans_before} → {fans_after} ", f"(+{diff})"], [Color.BLACK, Color.RED])
                elif diff < 0:
                    pic.draw_text([f"粉丝: {fans_before} → {fans_after} ", f"({diff})"], [Color.BLACK, Color.GREEN])
                else:
                    pic.draw_text([f"粉丝: {fans_before} → {fans_after} ", f"(+0)"], [Color.BLACK, Color.GRAY])

            if model.fans_medal_change:
                medal_before = param.get('fans_medal_before', 0)
                medal_after = param.get('fans_medal_after', 0)
                if medal_before == -1:
                    medal_before = "?"
                    diff = 0
                else:
                    diff = medal_after - medal_before
                if diff > 0:
                    pic.draw_text([f"粉丝团: {medal_before} → {medal_after} ", f"(+{diff})"], [Color.BLACK, Color.RED])
                elif diff < 0:
                    pic.draw_text([f"粉丝团: {medal_before} → {medal_after} ", f"({diff})"], [Color.BLACK, Color.GREEN])
                else:
                    pic.draw_text([f"粉丝团: {medal_before} → {medal_after} ", f"(+0)"], [Color.BLACK, Color.GRAY])

            if model.guard_change:
                guard_before = param.get('guard_before', 0)
                guard_after = param.get('guard_after', 0)
                if guard_before == -1:
                    guard_before = "?"
                    diff = 0
                else:
                    diff = guard_after - guard_before
                if diff > 0:
                    pic.draw_text([f"大航海: {guard_before} → {guard_after} ", f"(+{diff})"], [Color.BLACK, Color.RED])
                elif diff < 0:
                    pic.draw_text([f"大航海: {guard_before} → {guard_after} ", f"({diff})"], [Color.BLACK, Color.GREEN])
                else:
                    pic.draw_text([f"大航海: {guard_before} → {guard_after} ", f"(+0)"], [Color.BLACK, Color.GRAY])

        # 直播数据
        if model.danmu or model.box or model.gift or model.sc or model.guard:
            # 弹幕相关
            danmu_count = param.get('danmu_count', 0)
            danmu_person_count = param.get('danmu_person_count', 0)
            # 盲盒相关
            box_count = param.get('box_count', 0)
            box_person_count = param.get('box_person_count', 0)
            box_profit = param.get('box_profit', 0.0)
            box_beat_percent = param.get('box_beat_percent', 0.0)
            # 礼物相关
            gift_profit = param.get('gift_profit', 0.0)
            gift_person_count = param.get('gift_person_count', 0)
            # SC（醒目留言）相关
            sc_profit = param.get('sc_profit', 0)
            sc_person_count = param.get('sc_person_count', 0)
            # 大航海相关
            captain_count = param.get('captain_count', 0)
            commander_count = param.get('commander_count', 0)
            governor_count = param.get('governor_count', 0)

            if any([danmu_count > 0, box_count > 0, gift_profit > 0, sc_profit > 0,
                    captain_count > 0, commander_count > 0, governor_count > 0]):
                pic.draw_section("直播数据")

                if model.danmu and danmu_count > 0:
                    pic.draw_text(f"弹幕: {danmu_count} 条 ({danmu_person_count} 人)")

                if model.box and box_count > 0:
                    pic.draw_text(f"盲盒: {box_count} 个 ({box_person_count} 人)")
                    if box_profit > 0:
                        pic.draw_text(["盲盒赚了: ", f"{box_profit}", " 元 (击败了 ", f"{box_beat_percent}% ", "的直播间)"],
                                      [Color.BLACK, Color.RED, Color.BLACK, Color.RED, Color.BLACK])
                    elif box_profit < 0:
                        pic.draw_text(["盲盒亏了: ", f"{abs(box_profit)}", " 元 (击败了 ", f"{box_beat_percent}% ", "的直播间)"],
                                      [Color.BLACK, Color.GREEN, Color.BLACK, Color.GREEN, Color.BLACK])
                    else:
                        pic.draw_text(["盲盒不赚不亏 (击败了 ", f"{box_beat_percent}% ", "的直播间)"],
                                      [Color.BLACK, Color.GRAY, Color.BLACK])

                if model.gift and gift_profit > 0:
                    pic.draw_text(f"礼物: {gift_profit} 元 ({gift_person_count} 人)")

                if model.sc and sc_profit > 0:
                    pic.draw_text(f"SC (醒目留言): {sc_profit} 元 ({sc_person_count} 人)")

                if model.guard and any([captain_count > 0, commander_count > 0, governor_count > 0]):
                    texts = ["大航海: "]
                    colors = [Color.BLACK]
                    if captain_count > 0:
                        texts.append(f"舰长 × {captain_count} ")
                        colors.append(Color.DEEPSKYBLUE)
                    if commander_count > 0:
                        texts.append(f"提督 × {commander_count} ")
                        colors.append(Color.FUCHSIA)
                    if governor_count > 0:
                        texts.append(f"总督 × {governor_count} ")
                        colors.append(Color.CRIMSON)
                    pic.draw_text(texts, colors)

        # 弹幕排行
        if model.danmu_ranking:
            faces = param.get("danmu_ranking_faces", [])
            unames = param.get("danmu_ranking_unames", [])
            counts = param.get("danmu_ranking_counts", [])

            if counts:
                pic.draw_section(f"弹幕排行 (Top {len(counts)})")

                ranking_img = cls.__get_ranking(pic, faces, unames, counts, pic.width - (margin * 2))
                pic.draw_img_alpha(pic.auto_size_img_by_limit(ranking_img, logo_limit))

        # 盲盒数量排行
        if model.box_ranking:
            faces = param.get("box_ranking_faces", [])
            unames = param.get("box_ranking_unames", [])
            counts = param.get("box_ranking_counts", [])

            if counts:
                pic.draw_section(f"盲盒数量排行 (Top {len(counts)})")

                ranking_img = cls.__get_ranking(pic, faces, unames, counts, pic.width - (margin * 2))
                pic.draw_img_alpha(pic.auto_size_img_by_limit(ranking_img, logo_limit))

        # 盲盒盈亏排行
        if model.box_profit_ranking:
            faces = param.get("box_profit_ranking_faces", [])
            unames = param.get("box_profit_ranking_unames", [])
            counts = param.get("box_profit_ranking_counts", [])

            if counts:
                pic.draw_section(f"盲盒盈亏排行 (Top {len(counts)})")

                ranking_img = cls.__get_double_ranking(pic, faces, unames, counts, pic.width - (margin * 2))
                pic.draw_img_alpha(pic.auto_size_img_by_limit(ranking_img, logo_limit))

        # 礼物排行
        if model.gift_ranking:
            faces = param.get("gift_ranking_faces", [])
            unames = param.get("gift_ranking_unames", [])
            counts = param.get("gift_ranking_counts", [])

            if counts:
                pic.draw_section(f"礼物排行 (Top {len(counts)})")

                ranking_img = cls.__get_ranking(pic, faces, unames, counts, pic.width - (margin * 2))
                pic.draw_img_alpha(pic.auto_size_img_by_limit(ranking_img, logo_limit))

        # SC（醒目留言）排行
        if model.sc_ranking:
            faces = param.get("sc_ranking_faces", [])
            unames = param.get("sc_ranking_unames", [])
            counts = param.get("sc_ranking_counts", [])

            if counts:
                pic.draw_section(f"SC(醒目留言)排行 (Top {len(counts)})")

                ranking_img = cls.__get_ranking(pic, faces, unames, counts, pic.width - (margin * 2))
                pic.draw_img_alpha(pic.auto_size_img_by_limit(ranking_img, logo_limit))

        # 开通大航海观众列表
        if model.guard_list:
            captain_infos = param.get("captain_infos", [])
            commander_infos = param.get("commander_infos", [])
            governor_infos = param.get("governor_infos", [])

            if any([captain_infos, commander_infos, governor_infos]):
                pic.draw_section("本场开通大航海观众")

                guard_list_img = cls.__get_guard_list(
                    pic, captain_infos, commander_infos, governor_infos, pic.width - (margin * 2)
                )
                pic.draw_img_alpha(pic.auto_size_img_by_limit(guard_list_img, logo_limit))

        # 弹幕词云
        if model.danmu_cloud:
            base64_str = param.get('danmu_cloud', "")
            if base64_str != "":
                pic.draw_section("弹幕词云")

                img_bytes = BytesIO(base64.b64decode(base64_str))
                img = pic.auto_size_img_by_limit(Image.open(img_bytes), logo_limit)
                pic.draw_img_with_border(img)

        # 底部版权信息，请务必保留此处
        pic.set_row_space(10)
        pic.draw_text_right(50, "Designed By StarBot", Color.GRAY, logo_limit)
        pic.draw_text_right(50, "https://github.com/Starlwr/StarBot", Color.LINK, logo_limit)
        pic.crop_and_paste_bottom()

        return pic.base64()

    @classmethod
    def __get_logo(cls, model: LiveReport) -> Image:
        """
        从直播报告实例中读取主播立绘图片

        Args:
            model: 直播报告配置实例

        Returns:
            主播立绘图片
        """
        if model.logo:
            logo = Image.open(model.logo)
        else:
            logo_bytes = BytesIO(base64.b64decode(model.logo_base64))
            logo = Image.open(logo_bytes)

        logo = logo.crop(logo.getbbox())

        logo_height = 800
        logo_width = int(logo.width * (logo_height / logo.height))
        logo = logo.resize((logo_width, logo_height))

        return logo

    @classmethod
    def __get_rank_bar_pic(cls,
                           width: int,
                           height: int,
                           start_color: Union[Color, Tuple[int, int, int]] = Color.DEEPBLUE,
                           end_color: Union[Color, Tuple[int, int, int]] = Color.LIGHTBLUE,
                           reverse: bool = False) -> Image:
        """
        生成排行榜中排行条图片

        Args:
            width: 排行条长度
            height: 排行条宽度
            start_color: 排行条渐变起始颜色。默认：深蓝色 (57, 119, 230)
            end_color: 排行条渐变终止颜色。默认：浅蓝色 (55, 187, 248)
            reverse: 是否生成反向排行条，用于双向排行榜的负数排行条。默认：False
        """
        if isinstance(start_color, Color):
            start_color = start_color.value
        if isinstance(end_color, Color):
            end_color = end_color.value
        if reverse:
            start_color, end_color = end_color, start_color

        r_step = (end_color[0] - start_color[0]) / width
        g_step = (end_color[1] - start_color[1]) / width
        b_step = (end_color[2] - start_color[2]) / width

        now_color = [start_color[0], start_color[1], start_color[2]]

        bar = Image.new("RGBA", (width, 1))
        draw = ImageDraw.Draw(bar)

        for i in range(width):
            draw.point((i, 0), (int(now_color[0]), int(now_color[1]), int(now_color[2])))
            now_color[0] += r_step
            now_color[1] += g_step
            now_color[2] += b_step

        bar = bar.resize((width, height))

        mask = Image.new("L", (width, height), 255)
        mask_draw = ImageDraw.Draw(mask)
        if not reverse:
            mask_draw.polygon(((width - height, height), (width, 0), (width, height)), 0)
        else:
            mask_draw.polygon(((0, 0), (0, height), (height, height)), 0)
        bar.putalpha(mask)

        return bar

    @classmethod
    def __get_ranking(cls,
                      pic: PicGenerator,
                      faces: List[Image.Image],
                      unames: List[str],
                      counts: Union[List[int], List[float]],
                      width: int,
                      start_color: Union[Color, Tuple[int, int, int]] = Color.DEEPBLUE,
                      end_color: Union[Color, Tuple[int, int, int]] = Color.LIGHTBLUE) -> Image:
        """
        绘制排行榜

        Args:
            pic: 绘图器实例
            faces: 头像图片列表，按照数量列表降序排序
            unames: 昵称列表，按照数量列表降序排序
            counts: 数量列表，降序排序
            width: 排行榜图片宽度
            start_color: 排行条渐变起始颜色。默认：深蓝色 (57, 119, 230)
            end_color: 排行条渐变终止颜色。默认：浅蓝色 (55, 187, 248)
        """
        count = len(counts)
        if count == 0 or len(faces) != len(unames) or len(unames) != len(counts):
            raise ValueError

        face_size = 100
        offset = 10
        bar_height = 30

        bar_x = face_size - offset
        top_bar_width = width - face_size + offset
        top_count = counts[0]

        chart = PicGenerator(width, (face_size * count) + (pic.row_space * (count - 1)))
        chart.set_row_space(pic.row_space)
        for i in range(count):
            bar_width = int(counts[i] / top_count * top_bar_width)
            if bar_width != 0:
                bar = cls.__get_rank_bar_pic(bar_width, bar_height, start_color, end_color)
                chart.draw_img_alpha(bar, (bar_x, chart.y + int((face_size - bar_height) / 2)))
            chart.draw_tip(unames[i], Color.BLACK, (bar_x + (offset * 2), chart.y))
            count_pos = (max(chart.x + bar_width, bar_x + (offset * 3) + chart.get_tip_length(unames[i])), chart.y)
            chart.draw_tip(str(counts[i]), xy=count_pos)
            chart.draw_img_alpha(mask_round(faces[i].resize((face_size, face_size)).convert("RGBA")))

        return chart.img

    @classmethod
    def __get_double_ranking(cls,
                             pic: PicGenerator,
                             faces: List[Image.Image],
                             unames: List[str],
                             counts: Union[List[int], List[float]],
                             width: int,
                             start_color: Union[Color, Tuple[int, int, int]] = Color.DEEPRED,
                             end_color: Union[Color, Tuple[int, int, int]] = Color.LIGHTRED,
                             reverse_start_color: Union[Color, Tuple[int, int, int]] = Color.DEEPGREEN,
                             reverse_end_color: Union[Color, Tuple[int, int, int]] = Color.LIGHTGREEN) -> Image:
        """
        绘制双向排行榜

        Args:
            pic: 绘图器实例
            faces: 头像图片列表，按照数量列表降序排序
            unames: 昵称列表，按照数量列表降序排序
            counts: 数量列表，降序排序
            width: 排行榜图片宽度
            start_color: 正向排行条渐变起始颜色，数量为正时使用。默认：深红色 (57, 119, 230)
            end_color: 正向排行条渐变终止颜色，数量为正时使用。默认：浅红色 (55, 187, 248)
            reverse_start_color: 反向排行条渐变起始颜色，数量为负时使用。默认：深绿色 (57, 119, 230)
            reverse_end_color: 反向排行条渐变终止颜色，数量为负时使用。默认：浅绿色 (55, 187, 248)
        """
        count = len(counts)
        if count == 0 or len(faces) != len(unames) or len(unames) != len(counts):
            raise ValueError

        face_size = 100
        offset = 10
        bar_height = 30

        face_x = int((width - face_size) / 2)
        bar_x = face_x + face_size - offset
        reverse_bar_x = face_x + offset
        top_bar_width = (width - face_size) / 2 + offset
        top_count = max(max(counts), abs(min(counts)))

        chart = PicGenerator(width, (face_size * count) + (pic.row_space * (count - 1)))
        chart.set_row_space(pic.row_space)
        for i in range(count):
            bar_width = int(abs(counts[i]) / top_count * top_bar_width)
            if counts[i] > 0:
                bar = cls.__get_rank_bar_pic(bar_width, bar_height, start_color, end_color)
                chart.draw_img_alpha(bar, (bar_x, chart.y + int((face_size - bar_height) / 2)))
            elif counts[i] < 0:
                bar = cls.__get_rank_bar_pic(bar_width, bar_height, reverse_start_color, reverse_end_color, True)
                chart.draw_img_alpha(bar, (reverse_bar_x - bar_width, chart.y + int((face_size - bar_height) / 2)))
            if counts[i] >= 0:
                chart.draw_tip(unames[i], Color.BLACK, (bar_x + (offset * 2), chart.y))
                count_pos = (max(face_x + bar_width, bar_x + (offset * 3) + chart.get_tip_length(unames[i])), chart.y)
                chart.draw_tip(str(counts[i]), xy=count_pos)
            else:
                uname_length = chart.get_tip_length(unames[i])
                count_length = chart.get_tip_length(str(counts[i]))
                chart.draw_tip(unames[i], Color.BLACK, (reverse_bar_x - (offset * 2) - uname_length, chart.y))
                count_pos = (min(face_x + face_size - bar_width - count_length,
                                 reverse_bar_x - (offset * 3) - uname_length - count_length), chart.y)
                chart.draw_tip(str(counts[i]), xy=count_pos)
            chart.set_pos(x=face_x).draw_img_alpha(mask_round(faces[i].resize((face_size, face_size)).convert("RGBA")))
            chart.set_pos(x=0)

        return chart.img

    @classmethod
    def __get_guard_line_pic(cls,
                             pic: PicGenerator,
                             width: int,
                             face_size: int,
                             faces: List[Image.Image],
                             unames: List[str],
                             counts: List[int],
                             icon: Image.Image,
                             color: Union[Color, Tuple[int, int, int]]) -> Image:
        """
        生成大航海列表中每行图片

        Args:
            pic: 绘图器实例
            width: 大航海列表中每行图片长度
            face_size: 头像尺寸
            faces: 头像图片列表，按照数量列表降序排序
            unames: 昵称列表，按照数量列表降序排序
            counts: 数量列表，降序排序
            icon: 大航海图标
        """
        count = len(counts)
        if count == 0 or len(faces) != len(unames) or len(unames) != len(counts):
            raise ValueError

        text_size = 30
        icon_size = int(face_size * 1.5)
        face_padding = int((icon_size - face_size) / 2)
        margin = int((width - (icon_size * count)) / (count + 1))
        xs = [margin + (i * (icon_size + margin)) for i in range(count)]

        line = PicGenerator(width, icon_size + int(pic.row_space * 2.5) + (text_size * 2))
        line.set_row_space(pic.row_space)

        icon = icon.resize((icon_size, icon_size))
        for i, x in enumerate(xs):
            line.draw_img_alpha(mask_round(faces[i].resize((face_size, face_size))), (x + face_padding, face_padding))
            if i != count - 1:
                line.draw_img_alpha(icon, (x, 0))
            else:
                line.set_pos(x=x).draw_img_alpha(icon).set_pos(x=0)

        for i, x in enumerate(xs):
            uname = limit_str_length(unames[i], 8)
            uname_length = line.get_text_length(uname)
            uname_x_offset = int((icon_size - uname_length) / 2)
            count = f"{counts[i]} 月"
            count_length = line.get_text_length(count)
            count_x_offset = int((icon_size - count_length) / 2)

            line.draw_text(uname, color, (x + uname_x_offset, line.y))
            line.draw_text(count, Color.BLACK, (x + count_x_offset, line.y + text_size + int(line.row_space / 2)))

        return line.img

    @classmethod
    def __get_guard_list(cls,
                         pic: PicGenerator,
                         captains: List[List[Union[Image.Image, str, int]]],
                         commanders: List[List[Union[Image.Image, str, int]]],
                         governors: List[List[Union[Image.Image, str, int]]],
                         width: int) -> Image:
        """
        绘制大航海列表

        Args:
            pic: 绘图器实例
            captains: 舰长信息
            commanders: 提督信息
            governors: 总督信息
            width: 大航海列表图片宽度
        """
        face_size = 150
        icon_size = int(face_size * 1.5)
        text_size = 30
        line_count = 3
        line_height = icon_size + int(pic.row_space * 2.5) + (text_size * 2)

        resource_base_path = os.path.dirname(os.path.dirname(__file__))
        icon_map = {
            0: Image.open(f"{resource_base_path}/resource/governor.png").convert("RGBA"),
            1: Image.open(f"{resource_base_path}/resource/commander.png").convert("RGBA"),
            2: Image.open(f"{resource_base_path}/resource/captain.png").convert("RGBA")
        }
        color_map = {
            0: Color.CRIMSON,
            1: Color.FUCHSIA,
            2: Color.DEEPSKYBLUE
        }

        captains = split_list(captains, line_count)
        commanders = split_list(commanders, line_count)
        governors = split_list(governors, line_count)

        img = PicGenerator(width, (len(governors) + len(commanders) + len(captains)) * line_height)
        img.set_row_space(pic.row_space)

        for i, lst in enumerate([governors, commanders, captains]):
            if not lst:
                continue

            for line in lst:
                faces = [x[0] for x in line]
                unames = [x[1] for x in line]
                counts = [x[2] for x in line]
                img.draw_img_alpha(
                    cls.__get_guard_line_pic(pic, width, face_size, faces, unames, counts, icon_map[i], color_map[i])
                ).move_pos(0, -pic.row_space)

        return img.img


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
        height = 10000
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

        return pic.base64()

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
        face = face.resize(face_size, Resampling.LANCZOS)
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

        modules_url = f"https://api.bilibili.com/x/polymer/web-dynamic/v1/detail?timezone_offset=-480&id={dynamic_id}"
        modules = (await request("GET", modules_url))["item"]["modules"]["module_dynamic"]["desc"]
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
            summary_param = [{"type": "RICH_TEXT_NODE_TYPE_TEXT", "text": limit_str_length(card['summary'], 60)}]
            await cls.__draw_content(pic, summary_param, text_margin, forward)
        elif dynamic_type == 256:
            # 音频
            await cls.__draw_content(pic, modules, text_margin, forward)
            title = limit_str_length(card["title"], 15)
            await cls.__draw_audio_area(pic, card["cover"], title, card["typeInfo"], text_margin, forward)
        elif dynamic_type == 4200:
            # 直播
            title = limit_str_length(card["title"], 15)
            await cls.__draw_live_area(pic, card["cover"], title, card["area_v2_name"], text_margin, forward)
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
                for char in module["text"]:
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
                for char in module["text"]:
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
        elif picture_count == 2 or picture_count == 4:
            line_count = 2
        else:
            line_count = 3

        download_picture_tasks = []
        for picture in pictures:
            if line_count == 1:
                download_picture_tasks.append(open_url_image(f"{picture['img_src']}@518w.webp"))
            elif line_count == 2:
                src = picture['img_src']
                size = int((pic.width - (img_margin * 3)) / 2)
                if picture["img_height"] / picture["img_width"] >= 3:
                    download_picture_tasks.append(open_url_image(f"{src}@{size}w_{size}h_!header.webp"))
                else:
                    download_picture_tasks.append(open_url_image(f"{src}@{size}w_{size}h_1e_1c.webp"))
            else:
                src = picture['img_src']
                size = int((pic.width - (img_margin * 4)) / 3)
                if picture["img_height"] / picture["img_width"] >= 3:
                    download_picture_tasks.append(open_url_image(f"{src}@{size}w_{size}h_!header.webp"))
                else:
                    download_picture_tasks.append(open_url_image(f"{src}@{size}w_{size}h_1e_1c.webp"))
        imgs = await asyncio.gather(*download_picture_tasks)

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
        pic.set_pos(x=margin)

        cover = await open_url_image(cover_url)
        cover_size = int(pic.width / 4)
        cover = cover.resize((cover_size, cover_size), Resampling.LANCZOS)
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
        pic.draw_tip(audio_type, xy=(x + cover_size + margin, y + int(cover_size / 5 * 3)))

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
                desc = limit_str_length(vote["desc"], 16)
                join_num = vote["join_num"]
                icon = Image.open(f"{cls.__resource_base_path}/resource/tick_big.png")
                icon = mask_rounded_rectangle(icon.resize((img_height, img_height), Resampling.LANCZOS))

                x, y = pic.xy
                pic.draw_img(icon)
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
                title = base["title"]
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
