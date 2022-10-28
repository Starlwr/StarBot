"""
连接直播间期间发生的异常
"""

from .ApiException import ApiException


class LiveException(ApiException):
    """
    连接直播间期间发生的异常
    """

    def __init__(self, msg: str):
        super().__init__()
        self.msg = msg
