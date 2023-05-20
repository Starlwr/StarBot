"""
没有管理员权限异常
"""

from .ApiException import ApiException


class NoPermissionException(ApiException):
    """
    没有管理员权限异常
    """

    def __init__(self):
        super().__init__()
        self.msg = "没有管理员权限"
