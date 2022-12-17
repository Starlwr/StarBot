import base64
import os
from enum import Enum
from io import BytesIO
from typing import Optional, Union, Tuple, List, Dict, Any

from PIL import Image, ImageDraw, ImageFont

from ..core.model import LiveReport
from ..utils import config


class Color(Enum):
    """
    常用颜色 RGB 枚举

    + BLACK: 黑色
    + WHITE: 白色
    + GRAY: 灰色
    + RED: 红色
    + GREEN: 绿色
    + CRIMSON: 总督红
    + FUCHSIA: 提督紫
    + DEEPSKYBLUE: 舰长蓝
    + LINK: 超链接蓝
    """
    BLACK = (0, 0, 0)
    WHITE = (255, 255, 255)
    GRAY = (169, 169, 169)
    RED = (150, 0, 0)
    GREEN = (0, 150, 0)
    CRIMSON = (220, 20, 60)
    FUCHSIA = (255, 0, 255)
    DEEPSKYBLUE = (0, 191, 255)
    LINK = (6, 69, 173)


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
        self.width = width
        self.height = height
        self.__canvas = Image.new("RGBA", (self.width, self.height))
        self.__draw = ImageDraw.Draw(self.__canvas)

        font_base_path = os.path.dirname(os.path.dirname(__file__))
        self.__chapter_font = ImageFont.truetype(f"{font_base_path}/font/{bold_font}", 50)
        self.__section_font = ImageFont.truetype(f"{font_base_path}/font/{bold_font}", 40)
        self.__tip_font = ImageFont.truetype(f"{font_base_path}/font/{normal_font}", 25)
        self.__text_font = ImageFont.truetype(f"{font_base_path}/font/{normal_font}", 30)

        self.__xy = 0, 0
        self.__ROW_SPACE = 25

    def set_pos(self, x: int, y: int):
        """
        设置绘图坐标

        Args:
            x: X 坐标
            y: Y 坐标
        """
        self.__xy = x, y
        return self

    def move_pos(self, x: int, y: int):
        """
        移动绘图坐标

        Args:
            x: X 偏移量
            y: Y 偏移量
        """
        self.__xy = self.__xy[0] + x, self.__xy[1] + y
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
            radius: 边框圆角半径。默认：20
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
            x = self.__xy[0]
            for i in range(len(texts)):
                self.__draw.text(self.__xy, texts[i], colors[i], self.__text_font)
                self.move_pos(int(self.__draw.textlength(texts[i], self.__text_font)), 0)
            self.move_pos(x - self.__xy[0], self.__text_font.size + self.__ROW_SPACE)
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
        height = cls.__calc_height(param, model)
        top_blank = 75

        generator = PicGenerator(width, height)
        pic = (generator.set_pos(50, 125)
               .draw_rounded_rectangle(0, top_blank, width, height - top_blank, 35, Color.WHITE))

        # 标题
        pic.draw_chapter("直播报告")

        # 防止后续绘图覆盖主播立绘
        logo_limit = (0, 0)

        # 主播立绘
        if model.logo:
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
            (danmu_count, danmu_person_count,
             box_count, box_person_count, box_profit, box_beat_percent,
             gift_profit, gift_person_count,
             sc_profit, sc_person_count,
             captain_count, commander_count, governor_count) = cls.__get_live_params(param)

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

        # 弹幕词云
        if model.danmu_cloud:
            base64_str = param.get('danmu_cloud', "")
            if base64_str != "":
                pic.draw_section("弹幕词云")

                img_bytes = BytesIO(base64.b64decode(base64_str))
                img = pic.auto_size_img_by_limit(Image.open(img_bytes), logo_limit)
                pic.draw_img_with_border(img)

        # 底部信息
        pic.draw_text_right(50, "Designed By StarBot", Color.GRAY, logo_limit)
        pic.draw_text_right(50, "https://github.com/Starlwr/StarBot", Color.LINK, logo_limit)

        return pic.base64()

    @classmethod
    def __calc_height(cls, param: Dict[str, Any], model: LiveReport) -> int:
        """
        根据传入直播报告参数计算直播报告图片高度

        Args:
            param: 直播报告参数
            model: 直播报告配置实例

        Returns:
            直播报告图片高度
        """
        x = 50
        y = 0
        width = 1000
        height = 0

        chapter_font_size = 50
        section_font_size = 40
        tip_font_size = 25
        text_font_size = 30
        text_row_space = 25

        # 图片上边距
        top_blank = 75
        top_base_margin = 50
        y += (top_blank + top_base_margin)
        height = max(height, y)

        # 标题
        y += (chapter_font_size + text_row_space)
        height = max(height, y)

        # 主播立绘
        logo_limit = (0, 0)
        if model.logo:
            logo = cls.__get_logo(model)
            height = max(height, logo.height)

            base_left = 650
            logo_left = base_left + int((width - base_left - logo.width) / 2)
            if logo_left < base_left:
                logo_left = base_left
            logo_limit = (logo_left, logo.height)

        # 主播信息
        y += (tip_font_size + text_row_space)
        height = max(height, y)

        # 直播时长
        if model.time:
            y += (tip_font_size + text_row_space)
            height = max(height, y)

        # 基础数据
        if model.fans_change or model.fans_medal_change or model.guard_change:
            y += (section_font_size + text_row_space)
            height = max(height, y)

            if model.fans_change:
                y += (text_font_size + text_row_space)
                height = max(height, y)

            if model.fans_medal_change:
                y += (text_font_size + text_row_space)
                height = max(height, y)

            if model.guard_change:
                y += (text_font_size + text_row_space)
                height = max(height, y)

        # 直播数据
        if model.danmu or model.box or model.gift or model.sc or model.guard:
            (danmu_count, danmu_person_count,
             box_count, box_person_count, box_profit, box_beat_percent,
             gift_profit, gift_person_count,
             sc_profit, sc_person_count,
             captain_count, commander_count, governor_count) = cls.__get_live_params(param)

            if any([danmu_count > 0, box_count > 0, gift_profit > 0, sc_profit > 0,
                    captain_count > 0, commander_count > 0, governor_count > 0]):
                y += (section_font_size + text_row_space)
                height = max(height, y)

                if model.danmu and danmu_count > 0:
                    y += (text_font_size + text_row_space)
                    height = max(height, y)

                if model.box and box_count > 0:
                    y += (text_font_size + text_row_space) * 2
                    height = max(height, y)

                if model.gift and gift_profit > 0:
                    y += (text_font_size + text_row_space)
                    height = max(height, y)

                if model.sc and sc_profit > 0:
                    y += (text_font_size + text_row_space)
                    height = max(height, y)

                if model.guard and any([captain_count > 0, commander_count > 0, governor_count > 0]):
                    y += (text_font_size + text_row_space)
                    height = max(height, y)

        # 弹幕词云
        if model.danmu_cloud:
            base64_str = param.get('danmu_cloud', "")
            if base64_str != "":
                y += (section_font_size + text_row_space)
                height = max(height, y)

                img_bytes = BytesIO(base64.b64decode(base64_str))
                img = PicGenerator.auto_size_img_by_limit_cls(Image.open(img_bytes), logo_limit, (x, y))

                y += (img.height + text_row_space)
                height = max(height, y)

        # 底部信息
        height += (text_font_size + text_row_space) * 2

        return height

    @classmethod
    def __get_logo(cls, model: LiveReport) -> Image:
        """
        从直播报告实例中读取主播立绘图片

        Args:
            model: 直播报告配置实例

        Returns:
            主播立绘图片
        """
        logo_bytes = BytesIO(base64.b64decode(model.logo))
        logo = Image.open(logo_bytes)
        logo = logo.crop(logo.getbbox())

        logo_height = 800
        logo_width = int(logo.width * (logo_height / logo.height))
        logo = logo.resize((logo_width, logo_height))

        return logo

    @classmethod
    def __get_live_params(cls, param: Dict[str, Any]) -> Tuple:
        """
        从传入直播报告参数中取出直播相关参数

        Args:
            param: 直播报告参数

        Returns:
            直播相关参数
        """
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

        return (danmu_count, danmu_person_count,
                box_count, box_person_count, box_profit, box_beat_percent,
                gift_profit, gift_person_count,
                sc_profit, sc_person_count,
                captain_count, commander_count, governor_count)
