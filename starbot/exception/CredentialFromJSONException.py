from .ApiException import ApiException


class CredentialFromJSONException(ApiException):
    """
    从JSON文件读取Credential时发生的异常
    """

    def __init__(self, msg: str):
        super().__init__()
        self.msg = msg
