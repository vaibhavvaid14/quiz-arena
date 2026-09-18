"""Domain errors that map directly onto HTTP responses."""


class ApiError(Exception):
    """An expected failure with an HTTP status and a message safe to show users."""

    status = 400
    code = "bad_request"

    def __init__(self, message, *, status=None, code=None):
        super().__init__(message)
        self.message = message
        if status is not None:
            self.status = status
        if code is not None:
            self.code = code


class ValidationError(ApiError):
    status = 400
    code = "invalid_input"


class Unauthorized(ApiError):
    status = 401
    code = "unauthorized"


class NotFound(ApiError):
    status = 404
    code = "not_found"


class Conflict(ApiError):
    status = 409
    code = "conflict"
