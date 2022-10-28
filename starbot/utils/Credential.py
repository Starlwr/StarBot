"""
凭据类，用于各种请求操作的验证
"""
from typing import Dict

from ..exception import CredentialNoBiliJctException, CredentialNoBuvid3Exception, CredentialNoSessdataException


class Credential:
    """
    凭据类，用于各种请求操作的验证
    """

    def __init__(self, sessdata: str = None, bili_jct: str = None, buvid3: str = None):
        """
        Args:
            sessdata: 浏览器 Cookies 中的 SESSDATA 字段值。默认：None
            bili_jct: 浏览器 Cookies 中的 bili_jct 字段值。默认：None
            buvid3: 浏览器 Cookies 中的 BUVID3 字段值。默认：None
        """
        self.sessdata = sessdata
        self.bili_jct = bili_jct
        self.buvid3 = buvid3

    def get_cookies(self) -> Dict:
        """
        获取请求 Cookies 字典

        Returns:
            请求 Cookies 字典
        """
        return {"SESSDATA": self.sessdata, "buvid3": self.buvid3, 'bili_jct': self.bili_jct}

    def has_sessdata(self) -> bool:
        """
        是否提供了 SESSDATA

        Returns:
            是否提供了 SESSDATA
        """
        return self.sessdata is not None

    def has_bili_jct(self) -> bool:
        """
        是否提供了 bili_jct

        Returns:
            是否提供了 bili_jct
        """
        return self.bili_jct is not None

    def has_buvid3(self) -> bool:
        """
        是否提供了 BUVID3

        Returns:
            是否提供了 BUVID3
        """
        return self.buvid3 is not None

    def raise_for_no_sessdata(self):
        """
        没有提供 SESSDATA 时抛出异常
        """
        if not self.has_sessdata():
            raise CredentialNoSessdataException()

    def raise_for_no_bili_jct(self):
        """
        没有提供 bili_jct 时抛出异常
        """
        if not self.has_bili_jct():
            raise CredentialNoBiliJctException()

    def raise_for_no_buvid3(self):
        """
        没有提供 BUVID3 时抛出异常
        """
        if not self.has_buvid3():
            raise CredentialNoBuvid3Exception()
