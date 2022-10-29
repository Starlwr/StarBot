"""
Redis 异常
"""

from .ApiException import ApiException


class RedisException(ApiException):
    """
    Redis 异常
    """

    def __init__(self, msg: str):
        super().__init__()
        self.msg = msg
