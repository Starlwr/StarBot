"""
@ 全体成员次数达到上限异常
"""

from .ApiException import ApiException


class AtAllLimitedException(ApiException):
    """
    @ 全体成员次数达到上限异常
    """

    def __init__(self):
        super().__init__()
        self.msg = "今日 @ 全体成员次数已达到上限"
