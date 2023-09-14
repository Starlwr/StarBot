from typing import Any

DEFAULT_CONFIG = {
    # 是否检测最新 StarBot 版本
    "CHECK_VERSION": True,

    # Redis 连接配置 ( 必须 )
    # Redis 地址
    "REDIS_HOST": "localhost",
    # Redis 端口
    "REDIS_PORT": 6379,
    # Redis 数据库号
    "REDIS_DB": 0,
    #  Redis 用户名
    "REDIS_USERNAME": None,
    #  Redis 密码
    "REDIS_PASSWORD": None,

    # MySQL 数据源连接配置 ( 可选 )
    # MySQL 地址
    "MYSQL_HOST": "localhost",
    # MySQL 端口
    "MYSQL_PORT": 3306,
    # MySQL 用户名
    "MYSQL_USERNAME": "root",
    # MySQL 密码
    "MYSQL_PASSWORD": "123456",
    # MySQL 数据库名
    "MYSQL_DB": "starbot",

    # Mirai API HTTP 连接地址
    "MIRAI_HOST": "localhost",
    # Mirai API HTTP 连接端口
    "MIRAI_PORT": 7827,
    # Mirai API HTTP Key
    "MIRAI_KEY": "StarBot",

    # 登录 B 站账号所需 Cookie 数据 ( 不登录账号将有部分功能不可用 ) 各字段获取方式查看：https://bot.starlwr.com/depoly/document
    "SESSDATA": None,
    "BILI_JCT": None,
    "BUVID3": None,

    # 是否启用备用轮询式直播推送，建议仅在默认直播推送出现问题时启用
    "BACKUP_LIVE_PUSH": False,

    # 是否自动关注打开了动态推送但没有关注的用户，推荐打开，否则无法获取未关注用户的动态更新信息
    "AUTO_FOLLOW_OPENED_DYNAMIC_UPDATE_UP": True,

    # 是否将日志同时输出到文件中
    "LOG_TO_FILE": True,

    # 连接每个直播间的间隔等待时长，用于避免连接大量直播间时的并发过多异常 too many file descriptors in select()，单位：秒
    "CONNECTION_INTERVAL": 0.2,
    # 成功连接所有主播直播间的最大等待时长，可使得日志输出顺序更加易读，一般无需修改此处，设置为 0 会自适应计算，单位：秒
    "WAIT_FOR_ALL_CONNECTION_TIMEOUT": 0,

    # 是否自动判断仅连接必要的直播间，即当某直播间的开播、下播、直播报告开关均未开启时，自动跳过连接直播间，以节省性能
    "ONLY_CONNECT_NECESSARY_ROOM": False,
    # 是否自动判断仅处理必要的直播事件，例如当某直播间的下播推送和直播报告中均不包含弹幕相关功能，则不再处理此直播间的弹幕事件，以节省性能
    "ONLY_HANDLE_NECESSARY_EVENT": False,

    # 主播下播后再开播视为主播网络波动断线重连的时间间隔，在此时间内重新开播不会重新计算本次直播数据，且不重复 @全体成员，单位：秒
    "UP_DISCONNECT_CONNECT_INTERVAL": 120,
    # 视为主播网络波动断线重连时，需发送的额外提示消息
    "UP_DISCONNECT_CONNECT_MESSAGE": "检测到下播后短时间内重新开播,可能是由于主播网络波动引起,本次开播不再重复通知",

    # 当日 @全体成员 达到上限后，需发送的额外提示消息
    "AT_ALL_LIMITED_MESSAGE": "今日@全体成员次数已达上限,将尝试@指定成员,请需要接收单独@消息的群员使用\"开播@我\"命令进行订阅",
    # 设置了 @全体成员 但没有管理员权限时，需发送的额外提示消息
    "NO_PERMISSION_MESSAGE": "已设置@全体成员但没有管理员权限,将尝试@指定成员,请需要接收单独@消息的群员使用\"开播@我\"命令进行订阅",

    # 动态推送抓取频率和视为新动态的时间间隔，单位：秒
    "DYNAMIC_INTERVAL": 10,

    # 绘图器普通字体路径，如需自定义，请将字体放入 resource 文件夹中后，修改配置中的 normal.ttf 为您的字体文件名
    "PAINTER_NORMAL_FONT": "normal.ttf",
    # 绘图器粗体字体路径，如需自定义，请将字体放入 resource 文件夹中后，修改配置中的 bold.ttf 为您的字体文件名
    "PAINTER_BOLD_FONT": "bold.ttf",
    # 绘图器自适应不覆盖已绘制图形的间距，单位：像素
    "PAINTER_AUTO_SIZE_BY_LIMIT_MARGIN": 10,

    # 弹幕词云字体路径，如需自定义，请将字体放入 resource 文件夹中后，修改配置中的 cloud.ttf 为您的字体文件名
    "DANMU_CLOUD_FONT": "cloud.ttf",
    # 弹幕词云图片背景色
    "DANMU_CLOUD_BACKGROUND_COLOR": "white",
    # 弹幕词云最大字号
    "DANMU_CLOUD_MAX_FONT_SIZE": 200,
    # 弹幕词云最多词数
    "DANMU_CLOUD_MAX_WORDS": 80,
    # 弹幕词云停用词路径，存储时每行一个停用词，以纯文本方式存储，可过滤这些词使其不出现在词云中
    "DANMU_CLOUD_STOP_WORDS": "",
    # 弹幕词云自定义词典路径，存储时每行一个词，以纯文本方式存储，在对弹幕进行切词时，词典中的词不会被切分开
    "DANMU_CLOUD_DICT": "",

    # 需加载的用户自定义命令包
    "CUSTOM_COMMANDS_PACKAGE": None,

    # Bot 主人 QQ，用于接收部分 Bot 异常通知等
    "MASTER_QQ": None,

    # HTTP 代理
    "PROXY": "",

    # 是否使用 HTTP API 推送
    "USE_HTTP_API": False,
    # HTTP API 端口
    "HTTP_API_PORT": 8088,
    # 默认 HTTP API 推送 Bot QQ，多 Bot 推送时必填
    "HTTP_API_DEAFULT_BOT": None,

    # 命令触发前缀
    "COMMAND_PREFIX": "",
    # 每个群开播 @ 我命令人数上限，单次 @ 人数过多容易被风控，不推荐修改
    "COMMAND_LIVE_ON_AT_ME_LIMIT": 20,
    # 每个群动态 @ 我命令人数上限，单次 @ 人数过多容易被风控，不推荐修改
    "COMMAND_DYNAMIC_AT_ME_LIMIT": 20,

    # 被风控需通过验证码解决时，发送给主人 QQ 的提醒消息
    "BAN_NOTICE": "发送消息失败, 请手动通过验证码验证~",
    # 是否启用风控消息补发，启用后，因风控导致发送失败的推送消息会被暂存，解除风控后使用 ”补发“ 命令可以补发暂存的消息
    "BAN_RESEND": True,
    # 启用风控消息补发时，被风控时是否继续尝试发送消息，关闭后，发生风控时，后续需要发送的消息将不再尝试发送，而是被直接暂存，需使用 ”补发“ 命令后恢复正常，用于防止风控期间频繁尝试发送消息导致更严重的冻结
    "BAN_CONTINUE_SEND_MESSAGE": True,
    # 补发每条消息的间隔时间，频率太快可能会被再次风控，建议设置为 3 秒以上，单位：秒
    "RESEND_INTERVAL": 3,
    # 风控发送失败消息滞留时间上限，消息因风控滞留超出此时长不会进行补发，0 为无限制，单位：秒
    "RESEND_TIME_LIMIT": 0,
    # 是否补发开播推送、下播推送、直播报告、动态推送中的 @全体成员 和 @群成员 消息，可能造成不必要的打扰，不推荐开启
    "RESEND_AT_MESSAGE": False
}

user_config = {}


def use(**config: Any):
    """
    使用用户自定义配置，可以选择自带配置后，传入部分自定义配置覆盖原有配置

    Args:
        config: 自定义配置字典
    """
    user_config.update(config)


def set_credential(sessdata: str, bili_jct: str, buvid3: str):
    """
    设置登录 B 站账号所需 Cookie 数据，各字段获取方式查看：https://bot.starlwr.com/depoly/document

    Args:
        sessdata: SESSDATA
        bili_jct: BILI_JCT
        buvid3: BUVID3
    """
    set("SESSDATA", sessdata)
    set("BILI_JCT", bili_jct)
    set("BUVID3", buvid3)


def get(key: str) -> Any:
    """
    获取配置项的值

    Args:
        key: 配置项

    Returns:
        配置项的值
    """
    return user_config[key] if key in user_config else DEFAULT_CONFIG[key]


def set(key: str, value: Any):
    """
    手动设置配置项

    Args:
        key: 配置项
        value: 值
    """
    user_config[key] = value
