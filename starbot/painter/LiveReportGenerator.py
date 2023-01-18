import base64
import os
from io import BytesIO
from typing import Union, Tuple, List, Dict, Any

from PIL import Image, ImageDraw

from .PicGenerator import Color, PicGenerator
from ..core.model import LiveReport
from ..utils.utils import split_list, limit_str_length, mask_round


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