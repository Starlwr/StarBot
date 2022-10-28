"""
Credential 类未提供 BUVID3 时的异常
"""

from .ApiException import ApiException


class CredentialNoBuvid3Exception(ApiException):
    """
    Credential 类未提供 BUVID3 时的异常
    """

    def __init__(self):
        super().__init__()
        self.msg = "Credential 类未提供 BUVID3"
