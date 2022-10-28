from typing import Any

SIMPLE_CONFIG = {
    # 是否展示 StarBot Logo
    "SHOW_LOGO": True,
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

    # 登录 B 站账号所需 Cookie 数据 ( 不登录账号将有部分功能不可用 ) 各字段获取方式查看：https://bili.moyu.moe/#/get-credential.md
    "SESSDATA": None,
    "BILI_JCT": None,
    "BUVID3": None,

    # Bot 主人 QQ，用于接收部分 Bot 异常通知等
    "MASTER_QQ": None,

    # HTTP 代理
    "PROXY": "",

    # 是否使用 HTTP API 推送
    "USE_HTTP_API": False,
    # HTTP API 调用地址模板
    "HTTP_API_TEMPLATE": "",

    # 命令触发前缀
    "COMMAND_PREFIX": "",

    # 是否启用风控消息补发
    "BAN_RESEND": False,
    # 风控发送失败消息滞留时间上限，消息因风控滞留超出此时长不会进行补发，0 为无限制，单位：秒
    "RESEND_TIME_LIMIT": 0,
    # 是否补发开播推送、下播推送、直播报告、动态推送中的 @全体成员 和 @群成员 消息，可能造成不必要的打扰，不推荐开启
    "RESEND_AT_MESSAGE": False,
    # 是否补发除开播推送、下播推送、直播报告、动态推送外的其他消息，如群内命令所触发的回复消息
    "RESEND_ALL_MESSAGE": False
}

FULL_CONFIG = {
    # 是否展示 StarBot Logo
    "SHOW_LOGO": True,
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

    # 登录 B 站账号所需 Cookie 数据 ( 不登录账号将有部分功能不可用 ) 各字段获取方式查看：https://bili.moyu.moe/#/get-credential.md
    "SESSDATA": None,
    "BILI_JCT": None,
    "BUVID3": None,

    # Bot 主人 QQ，用于接收 Bot 异常通知等
    "MASTER_QQ": None,

    # HTTP 代理
    "PROXY": "",

    # 是否使用 HTTP API 推送
    "USE_HTTP_API": True,
    # HTTP API 调用地址模板
    "HTTP_API_TEMPLATE": "http://localhost/send?key={key}&data={data}",

    # 命令触发前缀
    "COMMAND_PREFIX": "",

    # 是否启用风控消息补发
    "BAN_RESEND": True,
    # 风控发送失败消息滞留时间上限，消息因风控滞留超出此时长不会进行补发，0 为无限制，单位：秒
    "RESEND_TIME_LIMIT": 0,
    # 是否补发开播推送、下播推送、直播报告、动态推送中的 @全体成员 和 @群成员 消息，可能造成不必要的打扰，不推荐开启
    "RESEND_AT_MESSAGE": False,
    # 是否补发除开播推送、下播推送、直播报告、动态推送外的其他消息，如群内命令所触发的回复消息
    "RESEND_ALL_MESSAGE": False
}

use_config = SIMPLE_CONFIG

user_config = {}


def use(**config: Any):
    """
    使用用户自定义配置，可以选择自带配置后，传入部分自定义配置覆盖原有配置

    Args:
        config: 自定义配置字典
    """
    user_config.update(config)


def use_simple_config():
    """
    使用最简配置，默认配置如下：

    展示 StarBot Logo
    自动检测最新版本
    使用 Redis 默认连接配置 (host: "localhost", port: 6379, db: 0, username: "", password: "")
    使用 MySQL 默认连接配置 (host: "localhost", port: 3306, db: "starbot", username: "root", password: "123456")
    不使用 HTTP 代理
    不开启 HTTP API 推送
    无命令触发前缀
    不开启风控消息补发
    """
    global use_config
    use_config = SIMPLE_CONFIG


def use_full_config():
    """
    使用推荐配置，默认配置如下：

    展示 StarBot Logo
    自动检测最新版本
    使用 Redis 默认连接配置 (host: "localhost", port: 6379, db: 0, username: "", password: "")
    使用 MySQL 默认连接配置 (host: "localhost", port: 3306, db: "starbot", username: "root", password: "123456")
    不使用 HTTP 代理
    开启 HTTP API 推送
    无命令触发前缀
    开启风控消息补发 仅补发推送消息
    """
    global use_config
    use_config = FULL_CONFIG


def set_credential(sessdata: str, bili_jct: str, buvid3: str):
    """
    设置登录 B 站账号所需 Cookie 数据，各字段获取方式查看：https://bili.moyu.moe/#/get-credential.md

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
    return user_config[key] if key in user_config else use_config[key]


def set(key: str, value: Any):
    """
    手动设置配置项

    Args:
        key: 配置项
        value: 值
    """
    user_config[key] = value
