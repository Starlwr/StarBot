import base64
import os
from enum import Enum
from io import BytesIO
from typing import Optional, Union, Tuple, List

from PIL import Image, ImageDraw, ImageFont

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
    基于 Pillow 的绘图器，可使用链式调用方法
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
        绘制结束后，传入图片会被自动调用 close 方法关闭

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

        img.close()

        return self

    def draw_img_alpha(self, img: Union[str, Image.Image], xy: Optional[Tuple[int, int]] = None):
        """
        在当前绘图坐标绘制一张透明背景图片，会自动移动绘图坐标至下次绘图适合位置
        也可手动传入绘图坐标，手动传入时不会移动绘图坐标
        绘制结束后，传入图片会被自动调用 close 方法关闭

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

        img.close()

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
        绘制结束后，传入图片会被自动调用 close 方法关闭

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

    def draw_text_multiline(self,
                            margin: int,
                            texts: Union[str, List[str]],
                            colors: Optional[
                                Union[Color, Tuple[int, int, int], List[Union[Color, Tuple[int, int, int]]]]
                            ] = None,
                            xy: Optional[Tuple[int, int]] = None):
        """
        在当前绘图坐标绘制多行文本，文本到达边界处会自动换行，绘制结束后会自动移动绘图坐标至下次绘图适合位置
        也可手动传入绘图坐标，手动传入时不会移动绘图坐标
        传入文本列表和颜色列表可将多行文本绘制为不同颜色，文本列表和颜色列表需一一对应
        颜色列表少于文本列表时将使用默认黑色 (0, 0, 0)，颜色列表多于文本列表时将舍弃多余颜色

        Args:
            margin: 外边距
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
                for c in texts[i]:
                    length = int(self.__draw.textlength(c, self.__text_font))
                    if self.x + length > self.width - margin:
                        self.move_pos(x - self.x, self.__text_font.size + self.__ROW_SPACE)
                    self.__draw.text(self.__xy, c, colors[i], self.__text_font)
                    self.move_pos(int(self.__draw.textlength(c, self.__text_font)), 0)
            self.move_pos(x - self.x, self.__text_font.size + self.__ROW_SPACE)
        else:
            x = xy[0]
            for i in range(len(texts)):
                for c in texts[i]:
                    length = int(self.__draw.textlength(c, self.__text_font))
                    if xy[0] + length > self.width - margin:
                        xy = x, xy[1] + self.__text_font.size + self.__ROW_SPACE
                    self.__draw.text(xy, c, colors[i], self.__text_font)
                    xy = xy[0] + self.__draw.textlength(c, self.__text_font), xy[1]
        return self

    def show(self):
        """
        显示图片
        """
        self.__canvas.show()
        return self

    def save(self, path: str):
        """
        保存图片，终端操作，保存完成后会关闭图片，无法再对图片进行操作

        Args:
            path: 保存路径
        """
        self.__canvas.save(path)
        self.__canvas.close()

    def base64(self) -> str:
        """
        获取 Base64 字符串，终端操作，获取后会关闭图片，无法再对图片进行操作

        Returns:
            Base64 字符串
        """
        io = BytesIO()
        self.__canvas.save(io, format="PNG")
        self.__canvas.close()

        return base64.b64encode(io.getvalue()).decode()

    def save_and_get_base64(self, path: str) -> str:
        """
        保存图片并获取 Base64 字符串，终端操作，保存完成后会关闭图片，无法再对图片进行操作

        Args:
            path: 保存路径

        Returns:
            Base64 字符串
        """
        self.__canvas.save(path)

        io = BytesIO()
        self.__canvas.save(io, format="PNG")
        self.__canvas.close()

        return base64.b64encode(io.getvalue()).decode()
