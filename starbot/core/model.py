import time
from enum import Enum
from typing import List, Optional, Any

from graia.ariadne.message.chain import MessageChain
from graia.ariadne.message.element import Plain, At, AtAll, Image
from pydantic import BaseModel, PrivateAttr

from ..exception import DataSourceException


class LiveOn(BaseModel):
    """
    开播推送配置
    可使用构造方法手动传入所需的各项配置
    或使用 LiveOn.default() 获取功能全部开启的默认配置
    """

    DEFAULT_MESSAGE: Optional[str] = "{uname} 正在直播 {title}\n{url}{next}{cover}"
    """默认消息模板"""

    enabled: Optional[bool] = False
    """是否启用开播推送。默认：False"""

    message: Optional[str] = ""
    """
    开播推送内容模板。
    专用占位符：{uname} 主播昵称，{title} 直播间标题，{url} 直播间链接，{cover} 直播间封面图。
    通用占位符：{next} 消息分条，{atall} @全体成员，{at114514} @指定QQ号，{urlpic=链接} 网络图片，{pathpic=路径} 本地图片，{base64pic=base64字符串} base64图片。
    默认：""
    """

    @classmethod
    def default(cls):
        """
        获取功能全部开启的默认 LiveOn 实例
        默认配置：启用开播推送，推送内容模板为 "{uname} 正在直播 {title}\n{url}{next}{cover}"
        """
        return LiveOn(enabled=True, message=LiveOn.DEFAULT_MESSAGE)

    def __str__(self):
        return f"启用: {self.enabled}\n推送内容:\n{self.message}"


class LiveOff(BaseModel):
    """
    下播推送配置
    可使用构造方法手动传入所需的各项配置
    或使用 LiveOff.default() 获取功能全部开启的默认配置
    """

    DEFAULT_MESSAGE: Optional[str] = "{uname} 直播结束了"
    """默认消息模板"""

    enabled: Optional[bool] = False
    """是否启用下播推送。默认：False"""

    message: Optional[str] = ""
    """
    下播推送内容模板。
    专用占位符：{uname}主播昵称。
    通用占位符：{next} 消息分条，{atall} @全体成员，{at114514} @指定QQ号，{urlpic=链接} 网络图片，{pathpic=路径} 本地图片，{base64pic=base64字符串} base64图片。
    默认：""
    """

    @classmethod
    def default(cls):
        """
        获取功能全部开启的默认 LiveOff 实例
        默认配置：启用下播推送，推送内容模板为 "{uname} 直播结束了\n{time}{next}{danmu_count}{danmu_mvp}{box_profit}"
        """
        return LiveOff(enabled=True, message=LiveOff.DEFAULT_MESSAGE)

    def __str__(self):
        return f"启用: {self.enabled}\n推送内容:\n{self.message}"


class LiveReport(BaseModel):
    """
    直播报告配置，直播报告会在下播推送后发出，下播推送是否开启不会影响直播报告的推送
    可使用构造方法手动传入所需的各项配置
    或使用 LiveReport.default() 获取功能全部开启的默认配置
    """

    enabled: Optional[bool] = False
    """是否启用直播报告。默认：False"""

    logo: Optional[str] = None
    """主播立绘的路径，会绘制在直播报告右上角合适位置。默认：None"""

    logo_base64: Optional[str] = None
    """主播立绘的 Base64 字符串，会绘制在直播报告右上角合适位置，立绘路径不为空时优先使用路径。默认：None"""

    time: Optional[bool] = False
    """是否展示本场直播直播时间段和直播时长。默认：False"""

    fans_change: Optional[bool] = False
    """是否展示本场直播粉丝变动。默认：False"""

    fans_medal_change: Optional[bool] = False
    """是否展示本场直播粉丝团（粉丝勋章数）变动。默认：False"""

    guard_change: Optional[bool] = False
    """是否展示本场直播大航海变动。默认：False"""

    danmu: Optional[bool] = False
    """是否展示本场直播收到弹幕数、发送弹幕人数。默认：False"""

    box: Optional[bool] = False
    """是否展示本场直播收到盲盒数、送出盲盒人数、盲盒盈亏。默认：False"""

    gift: Optional[bool] = False
    """是否展示本场直播礼物收益、送礼物人数。默认：False"""

    sc: Optional[bool] = False
    """是否展示本场直播 SC（醒目留言）收益、发送 SC（醒目留言）人数。默认：False"""

    guard: Optional[bool] = False
    """是否展示本场直播开通大航海数。默认：False"""

    danmu_ranking = 0
    """展示本场直播弹幕排行榜的前多少名，0 为不展示。默认：0"""

    box_ranking = 0
    """展示本场直播盲盒数量排行榜的前多少名，0 为不展示。默认：0"""

    box_profit_ranking = 0
    """展示本场直播盲盒盈亏排行榜的前多少名，0 为不展示。默认：0"""

    gift_ranking = 0
    """展示本场直播礼物排行榜的前多少名，0 为不展示。默认：0"""

    sc_ranking = 0
    """展示本场直播 SC（醒目留言）排行榜的前多少名，0 为不展示。默认：0"""

    guard_list = False
    """是否展示本场直播开通大航海观众列表。默认：False"""

    box_profit_diagram = False
    """是否展示本场直播的盲盒盈亏曲线图。默认：False"""

    danmu_diagram = False
    """是否展示本场直播的弹幕互动曲线图。默认：False"""

    box_diagram = False
    """是否展示本场直播的盲盒互动曲线图。默认：False"""

    gift_diagram = False
    """是否展示本场直播的礼物互动曲线图。默认：False"""

    sc_diagram = False
    """是否展示本场直播的 SC（醒目留言）互动曲线图。默认：False"""

    guard_diagram = False
    """是否展示本场直播的开通大航海互动曲线图。默认：False"""

    danmu_cloud: Optional[bool] = False
    """是否生成本场直播弹幕词云。默认：False。默认：False"""

    @classmethod
    def default(cls):
        """
        获取功能全部开启的默认 LiveReport 实例
        默认配置：启用直播报告，无主播立绘，展示直播时间段和直播时长，展示粉丝变动，展示粉丝团（粉丝勋章数）变动，展示大航海变动
                展示收到弹幕数、发送弹幕人数，展示收到盲盒数、送出盲盒人数、盲盒盈亏，展示礼物收益、送礼物人数
                展示 SC（醒目留言）收益、发送 SC（醒目留言）人数，展示开通大航海数
                展示弹幕排行榜前 3 名，展示盲盒数量排行榜前 3 名，展示盲盒盈亏排行榜前 3 名，展示礼物排行榜前 3 名
                展示 SC（醒目留言）排行榜前 3 名，展示开通大航海观众列表
                展示盲盒盈亏曲线图，展示弹幕互动曲线图，展示盲盒互动曲线图，展示礼物互动曲线图，
                展示 SC（醒目留言）互动曲线图，展示开通大航海互动曲线图，
                生成弹幕词云
        """
        return LiveReport(enabled=True, logo=None, logo_base64=None,
                          time=True, fans_change=True, fans_medal_change=True, guard_change=True,
                          danmu=True, box=True, gift=True, sc=True, guard=True,
                          danmu_ranking=3, box_ranking=3, box_profit_ranking=3, gift_ranking=3, sc_ranking=3,
                          guard_list=True, box_profit_diagram=True,
                          danmu_diagram=True, box_diagram=True, gift_diagram=True, sc_diagram=True, guard_diagram=True,
                          danmu_cloud=True)

    def __str__(self):
        return (f"启用: {self.enabled}\n直播时长数据: {self.time}\n粉丝变动数据: {self.fans_change}\n"
                f"粉丝团变动数据: {self.fans_medal_change}\n大航海变动数据: {self.guard_change}\n"
                f"弹幕相关数据: {self.danmu}\n盲盒相关数据: {self.box}\n礼物相关数据: {self.gift}\n"
                f"SC 相关数据: {self.sc}\n大航海相关数据: {self.guard}\n"
                f"弹幕排行榜: {f'前 {self.danmu_ranking} 名' if self.danmu_ranking else False}\n"
                f"盲盒数量排行榜: {f'前 {self.box_ranking} 名' if self.box_ranking else False}\n"
                f"盲盒盈亏排行榜: {f'前 {self.box_profit_ranking} 名' if self.box_profit_ranking else False}\n"
                f"礼物排行榜: {f'前 {self.gift_ranking} 名' if self.gift_ranking else False}\n"
                f"SC 排行榜: {f'前 {self.sc_ranking} 名' if self.sc_ranking else False}\n"
                f"开通大航海观众列表: {self.guard_list}\n盲盒盈亏曲线图: {self.box_profit_diagram}\n"
                f"弹幕互动曲线图: {self.danmu_diagram}\n盲盒互动曲线图: {self.box_diagram}\n"
                f"礼物互动曲线图: {self.gift_diagram}\nSC 互动曲线图: {self.sc_diagram}\n"
                f"开通大航海互动曲线图: {self.guard_diagram}\n"
                f"生成弹幕词云: {self.danmu_cloud}")


class DynamicUpdate(BaseModel):
    """
    动态推送配置
    可使用构造方法手动传入所需的各项配置
    或使用 DynamicUpdate.default() 获取功能全部开启的默认配置
    """

    DEFAULT_MESSAGE: Optional[str] = "{uname} {action}\n{url}{next}{picture}"
    """默认消息模板"""

    enabled: Optional[bool] = False
    """是否启用动态推送。默认：False"""

    message: Optional[str] = ""
    """
    动态推送内容模板。
    专用占位符：{uname}主播昵称，{action}动态操作类型（发表了新动态，转发了新动态，投稿了新视频...），{url}动态链接（若为发表视频、专栏等则为视频、专栏等对应的链接），{picture}动态图片。
    通用占位符：{next} 消息分条，{atall} @全体成员，{at114514} @指定QQ号，{urlpic=链接} 网络图片，{pathpic=路径} 本地图片，{base64pic=base64字符串} base64图片。
    默认：""
    """

    @classmethod
    def default(cls):
        """
        获取功能全部开启的默认 DynamicUpdate 实例
        默认配置：启用动态推送，推送内容模板为 "{uname} {action}\n{url}"
        """
        return DynamicUpdate(enabled=True, message=DynamicUpdate.DEFAULT_MESSAGE)

    def __str__(self):
        return f"启用: {self.enabled}\n推送内容:\n{self.message}"


class PushType(Enum):
    """
    推送目标 PushTarget 所对应推送类型

    推送类型，0 为私聊推送，1 为群聊推送
    + Friend : 0
    + Group  : 1
    """
    Friend = 0
    Group = 1


class PushTarget(BaseModel):
    """
    需要推送的推送目标，可选 QQ 好友或 QQ 群推送
    可额外使用推送 Key 将推送姬扩展至其他业务，通过调用 HTTP API 进行消息推送
    """

    id: int
    """QQ 号或群号"""

    type: Optional[PushType] = PushType.Group
    """推送类型，可选 QQ 好友或 QQ 群推送。默认：PushType.Group"""

    live_on: Optional[LiveOn] = LiveOn()
    """开播推送配置。默认：LiveOn()"""

    live_off: Optional[LiveOff] = LiveOff()
    """下播推送配置。默认：LiveOff()"""

    live_report: Optional[LiveReport] = LiveReport()
    """直播报告配置。默认：LiveReport()"""

    dynamic_update: Optional[DynamicUpdate] = DynamicUpdate()
    """动态推送配置。默认：DynamicUpdate()"""

    def __init__(self, **data: Any):
        super().__init__(**data)
        self.__raise_for_not_invalid_placeholders()

    def __raise_for_not_invalid_placeholders(self):
        """
        使用不合法的占位符时抛出异常
        """
        if self.type == PushType.Friend:
            if "{at" in self.live_on.message or "{at" in self.live_off.message or "{at" in self.dynamic_update.message:
                raise DataSourceException(f"好友类型的推送目标 (QQ: {self.id}) 推送内容中不能含有 @ 消息, 请检查配置后重试")

    def __eq__(self, other):
        if isinstance(other, PushTarget):
            return self.id == other.id and self.type == other.type
        return False

    def __hash__(self):
        return hash(self.id) ^ hash(self.type.value)


class Message(BaseModel):
    """
    消息封装类
    """

    id: int
    """目标 QQ 号或目标群号"""

    content: str
    """原始消息内容，可包含 {next}、{atall} 等占位符"""

    type: Optional[PushType] = PushType.Group
    """发送目标类型，PushType.Friend 为私聊消息，PushType.Group 为群聊消息。默认：PushType.Group"""

    __time: Optional[int] = PrivateAttr()
    """消息创建时间戳"""

    __chains: Optional[List[MessageChain]] = PrivateAttr()
    """原始消息内容自动处理后的消息链列表"""

    def __init__(self, **data: Any):
        super().__init__(**data)
        self.__time = int(time.time())
        self.__chains = Message.gen_message_chains(self.content)

    def get_time(self) -> int:
        return self.__time

    def get_message_chains(self) -> List[MessageChain]:
        return self.__chains

    @classmethod
    def gen_message_chains(cls, raw_msg: str) -> List[MessageChain]:
        """
        转换 {next}，{atall}，{at}，{pic_url}，{pic_path} 元素，将原始消息内容转换为可发送的消息链

        Args:
            raw_msg: 原始消息文本

        Returns:
            可直接发送的消息链列表
        """
        chains = []

        raw_msgs = raw_msg.split("{next}")
        for msg in raw_msgs:
            chain = MessageChain([])
            next_code = msg.find("{")
            while msg != "":
                if next_code == -1:
                    chain.append(Plain(msg))
                    msg = ""
                elif next_code != 0:
                    chain.append(Plain(msg[:next_code]))
                    msg = msg[next_code:]
                    next_code = msg.find("{")
                else:
                    code_end = msg.find("}")
                    if code_end == -1:
                        chain.append(Plain(msg))
                        msg = ""
                    else:
                        if msg[1:3] == "at":
                            at_target = msg[3:code_end]
                            if at_target == "all":
                                chain.append(AtAll())
                            elif at_target.isdigit():
                                chain.append((At(int(at_target))))
                                chain.append(Plain(" "))
                            else:
                                chain.append(Plain("[无效的@参数]"))
                        elif msg[1:7] == "urlpic":
                            pic_url = msg[8:code_end]
                            if pic_url != "":
                                chain.append(Image(url=pic_url))
                        elif msg[1:8] == "pathpic":
                            pic_path = msg[9:code_end]
                            if pic_path != "":
                                chain.append(Image(path=pic_path))
                        elif msg[1:10] == "base64pic":
                            pic_base64 = msg[11:code_end]
                            if pic_base64 != "":
                                chain.append(Image(base64=pic_base64))
                        else:
                            chain.append(Plain(msg[:code_end + 1]))
                        msg = msg[code_end + 1:]
                        next_code = msg.find("{")
            if len(chain) != 0:
                chains.append(chain)

        return chains
