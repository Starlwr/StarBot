from typing import Union, Tuple, List

from PIL import Image, ImageDraw

from .PicGenerator import Color, PicGenerator
from ..utils.utils import mask_round


class RankingGenerator:
    """
    排行榜图片生成器
    """

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
    def get_ranking(cls,
                    row_space: int,
                    faces: List[Image.Image],
                    unames: List[str],
                    counts: Union[List[int], List[float]],
                    width: int,
                    start_color: Union[Color, Tuple[int, int, int]] = Color.DEEPBLUE,
                    end_color: Union[Color, Tuple[int, int, int]] = Color.LIGHTBLUE) -> Image:
        """
        绘制排行榜

        Args:
            row_space: 行间距
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

        chart = PicGenerator(width, (face_size * count) + (row_space * (count - 1)))
        chart.set_row_space(row_space)
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
    def get_double_ranking(cls,
                           row_space: int,
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
            row_space: 行间距
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

        chart = PicGenerator(width, (face_size * count) + (row_space * (count - 1)))
        chart.set_row_space(row_space)
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
