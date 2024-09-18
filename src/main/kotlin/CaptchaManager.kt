package kz.qwertukg

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.*
import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
import com.github.kotlintelegrambot.network.fold
import java.awt.Color
import java.awt.Font
import java.awt.Graphics
import java.awt.image.BufferedImage
import java.io.File
import java.util.logging.Logger
import javax.imageio.ImageIO

/**
 * Класс, отвечающий за управление логикой капчи и состоянием пользователей.
 *
 * @property imagesPath Путь к папке с изображениями.
 * @property question Вопрос для капчи.
 * @property maxAttempts Максимальное количество попыток.
 * @property columnsCount Количество столбцов для отображения изображений.
 * @property banTimeSeconds Время бана в секундах при исчерпании попыток.
 * @property logger Логгер для записи действий пользователя.
 */
class CaptchaManager(
    private val imagesPath: String,
    private val question: String,
    private val maxAttempts: Int,
    private val columnsCount: Int,
    private val banTimeSeconds: Int,
    private val logger: Logger
) {
    // Карта для хранения состояния капчи каждого пользователя
    private val userStates = mutableMapOf<Long, UserCaptchaState>()

    /**
     * Обрабатывает нового пользователя, добавленного в чат.
     *
     * @param bot Экземпляр бота.
     * @param chatId Идентификатор чата.
     * @param user Пользователь.
     */
    fun handleNewUser(bot: Bot, chatId: Long, user: User) {
        // Ограничиваем пользователя от отправки сообщений
        bot.restrictChatMember(
            chatId = ChatId.fromId(chatId),
            userId = user.id,
            chatPermissions = ChatPermissions(canSendMessages = false)
        )

        // Создаем состояние пользователя и добавляем в карту
        val userState = UserCaptchaState(
            userName = user.firstName,
            userId = user.id,
            attemptsRemaining = maxAttempts,
            captchaMessageId = null
        )
        userStates[user.id] = userState

        // Создаем уникальную папку для изображений пользователя
        val userImagesPath = "$imagesPath/user_${user.id}"
        File(userImagesPath).mkdirs()

        // Загружаем и обрабатываем изображения для капчи
        val imageList = loadAndProcessImages(imagesPath, userImagesPath)
        if (imageList.isNotEmpty()) {
            // Отправляем капчу пользователю
            sendCaptcha(bot, chatId, userState, imageList, userImagesPath)
        } else {
            // Если изображения не загружены, отправляем сообщение об ошибке
            bot.sendMessage(ChatId.fromId(chatId), "Ошибка при загрузке изображений.")
        }
    }

    /**
     * Обрабатывает обратный вызов (нажатие на кнопку) от пользователя.
     *
     * @param bot Экземпляр бота.
     * @param callbackQuery Объект обратного вызова.
     * @param userId Идентификатор пользователя.
     * @param chatId Идентификатор чата.
     * @param messageId Идентификатор сообщения с капчей.
     */
    fun handleCallbackQuery(
        bot: Bot,
        callbackQuery: CallbackQuery,
        userId: Long,
        chatId: Long,
        messageId: Long
    ) {
        val userState = userStates[userId]
        if (userState == null || userState.captchaMessageId != messageId) {
            // Если капча не соответствует пользователю или сообщение не совпадает, отправляем уведомление
            bot.answerCallbackQuery(
                callbackQuery.id,
                text = "Эта капча не для вас.",
                showAlert = true
            )
            return
        }

        val userAnswer = callbackQuery.data.toIntOrNull()
        if (userAnswer == null) {
            // Если ответ не является числом, отправляем уведомление об ошибке
            bot.answerCallbackQuery(
                callbackQuery.id,
                text = "Неверный формат ответа.",
                showAlert = true
            )
            return
        }

        // Логирование действия пользователя: выбор ответа
        logger.info("Пользователь $userId выбрал ответ: $userAnswer.")

        // Путь к папке с изображениями пользователя
        val userImagesPath = "$imagesPath/user_$userId"
        val imageList = loadAndProcessImages(imagesPath, userImagesPath)
        val correctAnswer = imageList.firstOrNull { it.isCorrect }?.number

        if (userAnswer == correctAnswer) {
            // Если ответ правильный, приветствуем пользователя и снимаем ограничения
            bot.answerCallbackQuery(
                callbackQuery.id,
                text = "Правильно! Добро пожаловать в группу.",
                showAlert = true
            )

            bot.restrictChatMember(
                chatId = ChatId.fromId(chatId),
                userId = userId,
                chatPermissions = ChatPermissions(canSendMessages = true)
            )

            // Удаляем сообщение с капчей
            bot.deleteMessage(ChatId.fromId(chatId), messageId)
            // Удаляем изображения пользователя
            deleteUserImages(userImagesPath)
            // Удаляем состояние пользователя
            userStates.remove(userId)

            // Логирование действия пользователя: успешно прошел капчу
            logger.info("Пользователь $userId успешно прошел капчу.")
        } else {
            // Если ответ неправильный, уменьшаем количество попыток
            userState.attemptsRemaining -= 1

            // Логирование действия пользователя: неправильный ответ
            logger.info("Пользователь $userId дал неправильный ответ. Осталось попыток: ${userState.attemptsRemaining}.")

            if (userState.attemptsRemaining > 0) {
                // Если попытки еще остались, уведомляем пользователя и отправляем капчу заново
                bot.answerCallbackQuery(
                    callbackQuery.id,
                    text = "Неправильно! Осталось попыток: ${userState.attemptsRemaining}",
                    showAlert = true
                )
                resendCaptcha(bot, chatId, userState, imageList, userImagesPath)
            } else {
                // Если попытки закончились, баним пользователя
                bot.answerCallbackQuery(
                    callbackQuery.id,
                    text = "Вы исчерпали все попытки. Вы будете заблокированы.",
                    showAlert = true
                )

                bot.banChatMember(
                    chatId = ChatId.fromId(chatId),
                    userId = userId,
                    untilDate = System.currentTimeMillis() / 1000 + banTimeSeconds
                )

                // Удаляем сообщение с капчей и данные пользователя
                bot.deleteMessage(ChatId.fromId(chatId), messageId)
                deleteUserImages(userImagesPath)
                userStates.remove(userId)

                // Логирование действия пользователя: исчерпал попытки и был заблокирован
                logger.info("Пользователь $userId исчерпал все попытки и был заблокирован.")
            }
        }
    }

    /**
     * Отправляет капчу пользователю.
     *
     * @param bot Экземпляр бота.
     * @param chatId Идентификатор чата.
     * @param userState Состояние пользователя.
     * @param imageList Список изображений для капчи.
     * @param userImagesPath Путь к папке с изображениями пользователя.
     */
    private fun sendCaptcha(
        bot: Bot,
        chatId: Long,
        userState: UserCaptchaState,
        imageList: List<Image>,
        userImagesPath: String
    ) {
        // Создаем изображение капчи и клавиатуру с вариантами ответа
        val captchaResult = createCaptchaImage(imageList, userImagesPath)
        if (captchaResult == null) {
            bot.sendMessage(ChatId.fromId(chatId), "Ошибка при создании капчи.")
            return
        }

        val (combinedImagePath, inlineKeyboardMarkup) = captchaResult

        // Отправляем сообщение с капчей и сохраняем идентификатор сообщения
        val sendPhotoResult = bot.sendPhoto(
            chatId = ChatId.fromId(chatId),
            photo = TelegramFile.ByFile(File(combinedImagePath)),
            caption = "Привет ${userState.userName}! $question\nОсталось попыток: ${userState.attemptsRemaining}",
            replyMarkup = inlineKeyboardMarkup
        )

        sendPhotoResult.fold(
            response = { message ->
                userState.captchaMessageId = message?.result?.messageId
            },
            error = { error ->
                logger.warning("Ошибка при отправке капчи: $error")
            }
        )
    }

    /**
     * Повторно отправляет капчу пользователю после неправильного ответа.
     *
     * @param bot Экземпляр бота.
     * @param chatId Идентификатор чата.
     * @param userState Состояние пользователя.
     * @param imageList Список изображений для капчи.
     * @param userImagesPath Путь к папке с изображениями пользователя.
     */
    private fun resendCaptcha(
        bot: Bot,
        chatId: Long,
        userState: UserCaptchaState,
        imageList: List<Image>,
        userImagesPath: String
    ) {
        // Удаляем предыдущее сообщение с капчей
        userState.captchaMessageId?.let { messageId ->
            bot.deleteMessage(ChatId.fromId(chatId), messageId)
        }
        // Отправляем новую капчу
        sendCaptcha(bot, chatId, userState, imageList, userImagesPath)
    }

    /**
     * Создает изображение капчи и клавиатуру с вариантами ответа.
     *
     * @param imageList Список изображений для капчи.
     * @param userImagesPath Путь к папке с изображениями пользователя.
     * @return Пара из пути к изображению капчи и клавиатуры, или null при ошибке.
     */
    private fun createCaptchaImage(
        imageList: List<Image>,
        userImagesPath: String
    ): Pair<String, InlineKeyboardMarkup>? {
        val correctImage = imageList.firstOrNull { it.isCorrect } ?: return null
        val incorrectImages = imageList.filter { !it.isCorrect }.shuffled().take(5)
        val imagesForCaptcha = (listOf(correctImage) + incorrectImages).shuffled()

        // Комбинируем изображения в одно
        val combinedImage = combineImages(imagesForCaptcha, columnsCount)
        val combinedImagePath = "$userImagesPath/combined_image.png"
        ImageIO.write(combinedImage, "png", File(combinedImagePath))

        // Создаем клавиатуру с номерами изображений
        val inlineKeyboardButtons = imagesForCaptcha.map { image ->
            InlineKeyboardButton.CallbackData(
                text = image.number.toString(),
                callbackData = image.number.toString()
            )
        }.chunked(columnsCount)

        val inlineKeyboardMarkup = InlineKeyboardMarkup.create(inlineKeyboardButtons)
        return Pair(combinedImagePath, inlineKeyboardMarkup)
    }

    /**
     * Удаляет все изображения пользователя.
     *
     * @param userImagesPath Путь к папке с изображениями пользователя.
     */
    private fun deleteUserImages(userImagesPath: String) {
        File(userImagesPath).deleteRecursively()
    }

    /**
     * Загружает и обрабатывает изображения для капчи.
     *
     * @param inputPath Путь к исходным изображениям.
     * @param outputPath Путь для сохранения обработанных изображений.
     * @return Список объектов Image с информацией об изображениях.
     */
    private fun loadAndProcessImages(inputPath: String, outputPath: String): List<Image> {
        val dir = File(inputPath)
        val images = mutableListOf<Image>()

        if (!dir.exists() || !dir.isDirectory) return images

        val files = dir.listFiles { file -> file.isFile }?.toList()?.shuffled() ?: return images

        files.forEachIndexed { index, file ->
            val img = ImageIO.read(file) ?: return@forEachIndexed

            val numberedImg = addNumberToImage(img, index)
            val outputFilePath = "$outputPath/numbered_$index.png"
            ImageIO.write(numberedImg, "png", File(outputFilePath))

            val isCorrect = file.name.contains("_correct")
            images.add(Image(outputFilePath, index, isCorrect))
        }

        return images
    }

    /**
     * Добавляет номер к изображению и изменяет его размер.
     *
     * @param img Исходное изображение.
     * @param number Номер для добавления на изображение.
     * @return Обработанное изображение с номером.
     */
    private fun addNumberToImage(img: BufferedImage, number: Int): BufferedImage {
        val targetSize = 200
        // Изменяем размер изображения
        val resizedImg = BufferedImage(targetSize, targetSize, BufferedImage.TYPE_INT_ARGB)
        val g = resizedImg.createGraphics()
        g.drawImage(img, 0, 0, targetSize, targetSize, null)
        g.dispose()

        // Добавляем рамку и номер
        val newImg = BufferedImage(targetSize, targetSize, BufferedImage.TYPE_INT_ARGB)
        val g2d = newImg.createGraphics()
        g2d.drawImage(resizedImg, 0, 0, null)
        g2d.color = Color.BLACK
        g2d.drawRect(0, 0, targetSize - 1, targetSize - 1)
        g2d.color = Color.RED
        g2d.font = Font("Arial", Font.BOLD, 20)
        g2d.drawString(number.toString(), 10, 30)
        g2d.dispose()

        return newImg
    }

    /**
     * Комбинирует список изображений в одно изображение с заданным количеством столбцов.
     *
     * @param images Список изображений для комбинирования.
     * @param maxColumns Максимальное количество столбцов.
     * @return Объединенное изображение.
     */
    private fun combineImages(images: List<Image>, maxColumns: Int): BufferedImage {
        val bufferedImages = images.map { ImageIO.read(File(it.path)) }
        val imageWidth = bufferedImages.first().width
        val imageHeight = bufferedImages.first().height

        val rows = (bufferedImages.size + maxColumns - 1) / maxColumns
        val combinedWidth = maxColumns * imageWidth
        val combinedHeight = rows * imageHeight

        val combinedImage = BufferedImage(combinedWidth, combinedHeight, BufferedImage.TYPE_INT_ARGB)
        val g: Graphics = combinedImage.graphics

        bufferedImages.forEachIndexed { index, img ->
            val x = (index % maxColumns) * imageWidth
            val y = (index / maxColumns) * imageHeight
            g.drawImage(img, x, y, null)
        }

        g.dispose()
        return combinedImage
    }
}