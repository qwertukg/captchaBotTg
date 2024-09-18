package kz.qwertukg

import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.callbackQuery
import com.github.kotlintelegrambot.dispatcher.newChatMembers
import java.util.logging.Logger

/**
 * Главная функция, инициализирующая бота и запускающая его.
 */
fun main() {
    // Инициализация логгера
    val logger = Logger.getLogger("CaptchaBot")

    // Получаем значения переменных окружения или выбрасываем ошибку, если они не найдены
    val botToken = System.getenv("BOT_TOKEN") ?: error("BOT_TOKEN не найден.")
    val question = System.getenv("QUESTION") ?: error("QUESTION не найден.")
    val imagesPath = System.getenv("IMAGES_PATH") ?: error("IMAGES_PATH не найден.")
    val maxAttempts = System.getenv("ATTEMPTS")?.toIntOrNull() ?: error("ATTEMPTS не найден.")
    val columnsCount = System.getenv("COLUMNS_COUNT")?.toIntOrNull() ?: error("COLUMNS_COUNT не найден.")
    val banTimeSeconds = System.getenv("BAN_TIME_SECONDS")?.toIntOrNull() ?: error("BAN_TIME_SECONDS не найден.")

    // Создаем экземпляр менеджера капчи, который будет обрабатывать логику капчи
    val captchaManager = CaptchaManager(
        imagesPath = imagesPath,
        question = question,
        maxAttempts = maxAttempts,
        columnsCount = columnsCount,
        banTimeSeconds = banTimeSeconds,
        logger = logger
    )

    // Создаем бота и настраиваем диспетчеры событий
    val bot = bot {
        token = botToken

        dispatch {
            // Обработчик для новых участников чата
            newChatMembers {
                val newUser = update.message?.newChatMembers?.firstOrNull() ?: return@newChatMembers
                val chatId = update.message?.chat?.id ?: return@newChatMembers

                // Логирование действия пользователя: добавление в чат
                logger.info("Пользователь ${newUser.id} был добавлен в чат $chatId.")

                // Передаем обработку нового пользователя менеджеру капчи
                captchaManager.handleNewUser(bot, chatId, newUser)
            }

            // Обработчик для обратных вызовов (нажатия на кнопки)
            callbackQuery {
                val callbackQuery = update.callbackQuery ?: return@callbackQuery
                val userId = callbackQuery.from.id
                val chatId = callbackQuery.message?.chat?.id ?: return@callbackQuery
                val messageId = callbackQuery.message?.messageId ?: return@callbackQuery

                // Передаем обработку обратного вызова менеджеру капчи
                captchaManager.handleCallbackQuery(bot, callbackQuery, userId, chatId, messageId)
            }
        }
    }

    // Запускаем бота
    bot.startPolling()
}