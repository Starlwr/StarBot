import base64
import bisect
import io
import math
import os
from collections import Counter
from io import BytesIO
from typing import Union, Tuple, List, Dict, Any

import jieba
import numpy as np
from PIL import Image
from loguru import logger
from matplotlib import pyplot as plt
from mpl_toolkits import axisartist
from scipy.interpolate import make_interp_spline
from wordcloud import WordCloud

from .PicGenerator import Color, PicGenerator
from .RankingGenerator import RankingGenerator
from ..core.model import LiveReport
from ..utils import config
from ..utils.utils import split_list, limit_str_length, mask_round, timestamp_format

jieba.setLogLevel(jieba.logging.INFO)


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
        height = 100000
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
            pic.draw_img_alpha(logo, (base_left, 0))

            logo_limit = (base_left, logo.height)

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

                ranking_img = RankingGenerator.get_ranking(
                    pic.row_space, faces, unames, counts, pic.width - (margin * 2)
                )
                pic.draw_img_alpha(pic.auto_size_img_by_limit(ranking_img, logo_limit))

        # 盲盒数量排行
        if model.box_ranking:
            faces = param.get("box_ranking_faces", [])
            unames = param.get("box_ranking_unames", [])
            counts = param.get("box_ranking_counts", [])

            if counts:
                pic.draw_section(f"盲盒数量排行 (Top {len(counts)})")

                ranking_img = RankingGenerator.get_ranking(
                    pic.row_space, faces, unames, counts, pic.width - (margin * 2)
                )
                pic.draw_img_alpha(pic.auto_size_img_by_limit(ranking_img, logo_limit))

        # 盲盒盈亏排行
        if model.box_profit_ranking:
            faces = param.get("box_profit_ranking_faces", [])
            unames = param.get("box_profit_ranking_unames", [])
            counts = param.get("box_profit_ranking_counts", [])

            if counts:
                pic.draw_section(f"盲盒盈亏排行 (Top {len(counts)})")

                ranking_img = RankingGenerator.get_double_ranking(
                    pic.row_space, faces, unames, counts, pic.width - (margin * 2)
                )
                pic.draw_img_alpha(pic.auto_size_img_by_limit(ranking_img, logo_limit))

        # 礼物排行
        if model.gift_ranking:
            faces = param.get("gift_ranking_faces", [])
            unames = param.get("gift_ranking_unames", [])
            counts = param.get("gift_ranking_counts", [])

            if counts:
                pic.draw_section(f"礼物排行 (Top {len(counts)})")

                ranking_img = RankingGenerator.get_ranking(
                    pic.row_space, faces, unames, counts, pic.width - (margin * 2)
                )
                pic.draw_img_alpha(pic.auto_size_img_by_limit(ranking_img, logo_limit))

        # SC（醒目留言）排行
        if model.sc_ranking:
            faces = param.get("sc_ranking_faces", [])
            unames = param.get("sc_ranking_unames", [])
            counts = param.get("sc_ranking_counts", [])

            if counts:
                pic.draw_section(f"SC(醒目留言)排行 (Top {len(counts)})")

                ranking_img = RankingGenerator.get_ranking(
                    pic.row_space, faces, unames, counts, pic.width - (margin * 2)
                )
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

        # 盲盒盈亏折线图
        if model.box_profit_diagram:
            profits = param.get("box_profit_diagram", [])
            if profits:
                profits.insert(0, 0.0)

                pic.draw_section("盲盒盈亏折线图")

                diagram = cls.__get_box_profit_diagram(profits, pic.width - (margin * 2))
                pic.draw_img_alpha(pic.auto_size_img_by_limit(diagram, logo_limit))

        # 弹幕互动曲线图
        if model.danmu_diagram:
            start = param.get('start_timestamp', 0)
            end = param.get('end_timestamp', 0)

            times = param.get("danmu_diagram", [])

            if end - start >= 100 and times:
                pic.draw_section("弹幕互动曲线图")
                pic.draw_tip("收获弹幕数量在本场直播中的分布情况")

                diagram = cls.__get_interaction_diagram(times, start, end, pic.width - (margin * 2))
                pic.draw_img_alpha(pic.auto_size_img_by_limit(diagram, logo_limit))

        # 盲盒互动曲线图
        if model.box_diagram:
            start = param.get('start_timestamp', 0)
            end = param.get('end_timestamp', 0)

            times = param.get("box_diagram", [])

            if end - start >= 100 and times:
                pic.draw_section("盲盒互动曲线图")
                pic.draw_tip("收获盲盒数量在本场直播中的分布情况")

                diagram = cls.__get_interaction_diagram(times, start, end, pic.width - (margin * 2))
                pic.draw_img_alpha(pic.auto_size_img_by_limit(diagram, logo_limit))

        # 礼物互动曲线图
        if model.gift_diagram:
            start = param.get('start_timestamp', 0)
            end = param.get('end_timestamp', 0)

            times = param.get("gift_diagram", [])

            if end - start >= 100 and times:
                pic.draw_section("礼物互动曲线图")
                pic.draw_tip("收获礼物价值在本场直播中的分布情况")

                diagram = cls.__get_interaction_diagram(times, start, end, pic.width - (margin * 2))
                pic.draw_img_alpha(pic.auto_size_img_by_limit(diagram, logo_limit))

        # SC（醒目留言）互动曲线图
        if model.sc_diagram:
            start = param.get('start_timestamp', 0)
            end = param.get('end_timestamp', 0)

            times = param.get("sc_diagram", [])

            if end - start >= 100 and times:
                pic.draw_section("SC(醒目留言)互动曲线图")
                pic.draw_tip("收获SC(醒目留言)价值在本场直播中的分布情况")

                diagram = cls.__get_interaction_diagram(times, start, end, pic.width - (margin * 2))
                pic.draw_img_alpha(pic.auto_size_img_by_limit(diagram, logo_limit))

        # 开通大航海互动曲线图
        if model.guard_diagram:
            start = param.get('start_timestamp', 0)
            end = param.get('end_timestamp', 0)

            times = param.get("guard_diagram", [])

            if end - start >= 100 and times:
                pic.draw_section("开通大航海互动曲线图")
                pic.draw_tip("收获大航海开通时长在本场直播中的分布情况")

                diagram = cls.__get_interaction_diagram(times, start, end, pic.width - (margin * 2))
                pic.draw_img_alpha(pic.auto_size_img_by_limit(diagram, logo_limit))

        # 弹幕词云
        if model.danmu_cloud:
            all_danmu = param.get('all_danmu', [])

            if config.get("DANMU_CLOUD_DICT"):
                try:
                    jieba.load_userdict(config.get("DANMU_CLOUD_DICT"))
                except Exception:
                    logger.error("载入弹幕词云自定义词典失败, 请检查配置的词典路径是否正确")

            all_danmu_str = " ".join(all_danmu)
            words = list(jieba.cut(all_danmu_str))
            counts = dict(Counter(words))

            if config.get("DANMU_CLOUD_STOP_WORDS"):
                try:
                    with open(config.get("DANMU_CLOUD_STOP_WORDS"), "r", encoding="utf-8") as f:
                        stop_words = set(line.strip() for line in f)
                        for sw in stop_words:
                            counts.pop(sw, None)
                except Exception:
                    logger.error("载入弹幕词云停用词失败, 请检查配置的停用词路径是否正确")

            if len(counts) > 0:
                pic.draw_section("弹幕词云")

                font_base_path = os.path.dirname(os.path.dirname(__file__))
                word_cloud = WordCloud(width=900,
                                       height=450,
                                       font_path=f"{font_base_path}/resource/{config.get('DANMU_CLOUD_FONT')}",
                                       background_color=config.get("DANMU_CLOUD_BACKGROUND_COLOR"),
                                       max_font_size=config.get("DANMU_CLOUD_MAX_FONT_SIZE"),
                                       max_words=config.get("DANMU_CLOUD_MAX_WORDS"))
                word_cloud.generate_from_frequencies(counts)
                img = pic.auto_size_img_by_limit(word_cloud.to_image(), logo_limit)
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
            try:
                logo = Image.open(model.logo)
            except FileNotFoundError:
                logger.error(f"直播报告图片 {model.logo} 不存在")
                return Image.new("RGBA", (1, 1))
        else:
            logo_bytes = BytesIO(base64.b64decode(model.logo_base64))
            logo = Image.open(logo_bytes)

        logo = logo.convert("RGBA")
        logo = logo.crop(logo.getbbox())

        logo_width = 300
        logo_height = int(logo.height * (logo_width / logo.width))
        logo = logo.resize((logo_width, logo_height))

        return logo

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
            color: 大航海文字颜色
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
            line.draw_img_alpha(
                mask_round(faces[i].resize((face_size, face_size)).convert("RGBA")), (x + face_padding, face_padding)
            )
            if i != count - 1:
                line.draw_img_alpha(icon.copy(), (x, 0))
            else:
                line.set_pos(x=x).draw_img_alpha(icon.copy()).set_pos(x=0)

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
            0: f"{resource_base_path}/resource/governor.png",
            1: f"{resource_base_path}/resource/commander.png",
            2: f"{resource_base_path}/resource/captain.png"
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
                icon = Image.open(icon_map[i]).convert("RGBA")
                img.draw_img_alpha(
                    cls.__get_guard_line_pic(pic, width, face_size, faces, unames, counts, icon, color_map[i])
                ).move_pos(0, -pic.row_space)
                icon.close()

        return img.img

    @classmethod
    def __insert_zeros(cls, lst: List[float]) -> List[float]:
        """
        在列表正负变化处添加 0.0 值

        Args:
            lst: 源列表

        Returns:
            在正负变化处添加 0.0 后的列表
        """
        result = []
        for i in range(len(lst)):
            if i == 0:
                result.append(lst[i])
                continue
            if (lst[i - 1] < 0 < lst[i]) or (lst[i] < 0 < lst[i - 1]):
                result.append(0.0)
            result.append(lst[i])
        return result

    @classmethod
    def __smooth_xy(cls, lx: List[Any], ly: List[Any]) -> Tuple[Any, Any]:
        """
        平滑处理折线图数据

        Args:
            lx: x 轴数据
            ly: y 轴数据

        Returns:
            平滑处理后的 x 和 y 轴数据组成的元组
        """
        x = np.array(lx)
        y = np.array(ly)
        x_smooth = np.linspace(0, max(x), 300)
        y_smooth = make_interp_spline(x, y)(x_smooth)
        return x_smooth, y_smooth

    @classmethod
    def __get_line_diagram(cls,
                           xs: List[Any],
                           ys: List[Any],
                           xticks: List[Any],
                           yticks: List[Any],
                           xlabels: List[Any],
                           ylabels: List[Any],
                           xlimits: Tuple[Any, Any],
                           ylimits: Tuple[Any, Any],
                           width: int) -> Image:
        """
        绘制折线图

        Args:
            xs: x 轴数据
            ys: y 轴数据
            xticks: x 轴标签点
            yticks: y 轴标签点
            xlabels: x 轴标签
            ylabels: y 轴标签
            xlimits: x 轴范围
            ylimits: y 轴范围
            width: 折线图图片宽度
        """
        fig = plt.figure(figsize=(width / 100, width / 100 * 0.75))
        ax = axisartist.Subplot(fig, 111)
        fig.add_axes(ax)
        ax.axis[:].set_visible(False)
        ax.axis["x"] = ax.new_floating_axis(0, 0)
        ax.axis["y"] = ax.new_floating_axis(1, 0)
        ax.axis["x"].set_axis_direction('top')
        ax.axis["y"].set_axis_direction('left')
        ax.axis["x"].set_axisline_style("->", size=2.0)
        ax.axis["y"].set_axisline_style("->", size=2.0)

        ax.plot(xs, ys, color='red')
        ax.grid(True, linestyle='--', alpha=0.5)
        for i in range(len(ys) - 1):
            if ys[i] >= 0 and ys[i + 1] >= 0:
                plt.fill_between(xs[i:i + 2], 0, ys[i:i + 2], facecolor='red', alpha=0.3)
            else:
                plt.fill_between(xs[i:i + 2], 0, ys[i:i + 2], facecolor='green', alpha=0.3)

        ax.set_xticks(xticks)
        ax.set_yticks(yticks)

        if xlabels:
            ax.set_xticklabels(xlabels)
        if ylabels:
            ax.set_yticklabels(ylabels)

        ax.set_xlim(xlimits[0], xlimits[1])
        ax.set_ylim(ylimits[0], ylimits[1])

        buf = io.BytesIO()
        fig.savefig(buf)
        plt.close(fig)
        buf.seek(0)
        return Image.open(buf)

    @classmethod
    def __get_box_profit_diagram(cls, profits: List[float], width: int) -> Image:
        """
        绘制盲盒盈亏曲线图

        Args:
            profits: 盲盒盈亏记录
            width: 盲盒盈亏曲线图图片宽度
        """
        profits = cls.__insert_zeros(profits)
        length = len(profits)
        indexs = list(range(0, length))

        abs_max = math.ceil(max(max(profits), abs(min(profits))))
        start = -abs_max - (-abs_max % 10)
        end = abs_max + (-abs_max % 10)
        step = int((end - start) / 10)

        yticks = list(range(start, end)[::step]) if step != 0 else [0]
        yticks.append(end)
        return cls.__get_line_diagram(
            indexs, profits, [], yticks, [], [], (-1, length), (start, end), width
        )

    @classmethod
    def __calc_interaction_diagram_xy(cls,
                                      times: List[Tuple[str, Union[int, float]]],
                                      start: int,
                                      end: int) -> Tuple[List[int], List[int]]:
        """
        根据互动时间列表计算互动曲线图中 x 轴和 y 轴数据

        Args:
            times: 互动时间及权重列表
            start: 直播开始时间戳
            end: 直播结束时间戳

        Returns:
            x 轴和 y 轴数据组成的元组
        """
        count = 20
        step = (end - start) // count

        times = [(int(x[0]), x[1]) for x in times]

        divisions = [(start + i * step, start + (i + 1) * step - 1) for i in range(count)]
        divisions[-1] = (divisions[-1][0], end)
        results = [0] * count

        times.sort(key=lambda x: x[0])
        for i in range(len(times) - 1, -1, -1):
            if times[i][0] <= end:
                times = times[:i + 1]
                break

        for data in times:
            index = bisect.bisect_left([d[1] for d in divisions], data[0])
            results[index] += data[1]

        result = [d[0] for d in divisions], results
        result[0].append(start + count * step)
        result[1].append(0)
        return result

    @classmethod
    def __get_interaction_diagram(cls,
                                  times: List[Tuple[str, Union[int, float]]],
                                  start: int,
                                  end: int,
                                  width: int) -> Image:
        """
        绘制互动曲线图

        Args:
            times: 互动时间及权重列表
            start: 直播开始时间戳
            end: 直播结束时间戳
            width: 互动曲线图图片宽度
        """
        xs, ys = cls.__calc_interaction_diagram_xy(times, start, end)

        xs = [x - start for x in xs]
        max_x = max(xs)

        xs, ys = cls.__smooth_xy(xs, ys)
        max_y = math.ceil(max(ys))

        xticks = [x * max_x // 4 for x in range(1, 4)]
        xticks.insert(0, 0)
        xticks.append(max_x)

        xlabels = [timestamp_format(start + x * (end - start) // 4, "%H:%M:%S") for x in range(1, 4)]
        xlabels.insert(0, timestamp_format(start, "%H:%M:%S"))
        xlabels.append(timestamp_format(end, "%H:%M:%S"))

        ystep = max(max_y // 10, 1)
        yticks = list(range(0, max_y)[::ystep])
        yticks.append(yticks[-1] + ystep)

        return cls.__get_line_diagram(
            xs, ys, xticks, yticks, xlabels, [], (0, max_x), (0, yticks[-1]), width
        )
