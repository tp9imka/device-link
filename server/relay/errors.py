class RelayError(Exception):
    def __init__(self, status: int, code: str):
        self.status = status
        self.code = code
