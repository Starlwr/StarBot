"""
数据源异常
"""

from .ApiException import ApiException


class DataSourceException(ApiException):
    """
    数据源异常
    """

    def __init__(self, msg: str):
        super().__init__()
        self.msg = msg
