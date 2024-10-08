package kz.qwertukg

/**
 * Класс для хранения состояния капчи каждого пользователя.
 *
 * @property userName Имя пользователя.
 * @property userId Идентификатор пользователя.
 * @property attemptsRemaining Оставшееся количество попыток.
 * @property captchaMessageId Идентификатор сообщения с капчей.
 */
data class UserCaptchaState(
    val userName: String,
    val userId: Long,
    var attemptsRemaining: Int,
    var captchaMessageId: Long?
)