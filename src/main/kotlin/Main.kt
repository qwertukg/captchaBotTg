package kz.qwertukg

import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.callbackQuery
import com.github.kotlintelegrambot.dispatcher.newChatMembers
import com.github.kotlintelegrambot.entities.*
import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
import com.github.kotlintelegrambot.network.fold
import java.awt.Color
import java.awt.Font
import java.awt.Graphics
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

data class Image(val path: String, val number: Int, val isRight: Boolean)

fun main() {
    val botToken = System.getenv("BOT_TOKEN") ?: throw RuntimeException("TOKEN not found.")
    val question = System.getenv("QUESTION") ?: throw RuntimeException("QUESTION not found.")
    val path = System.getenv("IMAGES_PATH") ?: throw RuntimeException("IMAGES_PATH not found.")
    val attempts = System.getenv("ATTEMPTS")?.toInt() ?: throw RuntimeException("ATTEMPTS not found.")
    val columnsCount = System.getenv("COLUMNS_COUNT")?.toInt() ?: throw RuntimeException("COLUMNS_COUNT not found.")
    val banTimeSeconds = System.getenv("BAN_TIME_SECONDS")?.toInt() ?: throw RuntimeException("BAN_TIME_SECONDS not found.")

    // Хранение идентификатора пользователя и соответствующей капчи
    val userCaptchaMap = mutableMapOf<Long, Long>()
    val userAttemptsMap = mutableMapOf<Long, Int>() // Хранение оставшихся попыток для каждого пользователя

    // Создаем бота
    val bot = bot {
        token = botToken

        dispatch {
            // Обработчик добавления нового пользователя в группу
            newChatMembers {
                val newUser = update.message?.newChatMembers?.firstOrNull()

                if (newUser != null) {
                    val userId = newUser.id
                    val chatId = update.message!!.chat.id
                    val userPath = "$path/user_$userId"

                    // Ограничиваем возможность отправки сообщений для нового пользователя
                    bot.restrictChatMember(
                        chatId = ChatId.fromId(chatId),
                        userId = userId,
                        chatPermissions = ChatPermissions(canSendMessages = false)
                    )

                    // Создаем вложенную папку для пользователя
                    File(userPath).mkdirs()

                    // Загружаем и обрабатываем изображения для конкретного пользователя
                    val imageList = loadAndProcessImages(path, userPath)

                    if (imageList.isNotEmpty()) {
                        // Устанавливаем количество попыток для пользователя
                        userAttemptsMap[userId] = attempts

                        // Генерируем вопрос с изображениями
                        val correctImage = imageList.first { it.isRight }
                        val incorrectImages = imageList.filter { !it.isRight }.shuffled().take(5)

                        val imagesForCaptcha = (listOf(correctImage) + incorrectImages).shuffled()

                        // Склеиваем изображения в одно с шириной максимум columnsCount
                        val combinedImage = combineImages(imagesForCaptcha, columnsCount)
                        val combinedImagePath = "$userPath/combined_image.png"
                        ImageIO.write(combinedImage, "png", File(combinedImagePath))



                        // Создаем клавиатуру с вариантами ответа, динамически создавая ряды кнопок
                        val inlineKeyboardButtons = imagesForCaptcha.map { image ->
                            InlineKeyboardButton.CallbackData(
                                text = image.number.toString(),
                                callbackData = image.number.toString()
                            )
                        }

                        // Разделяем кнопки на ряды
                        val inlineKeyboardMarkup = InlineKeyboardMarkup.create(
                            inlineKeyboardButtons.chunked(columnsCount) // Создаем ряды кнопок, соответствующие количеству изображений в ряду
                        )

                        // Отправляем склеенное изображение пользователю с подписью и кнопками
                        val telegramFile = TelegramFile.ByFile(File(combinedImagePath))
                        val sendPhotoResult = bot.sendPhoto(
                            chatId = ChatId.fromId(chatId),
                            photo = telegramFile,
                            caption = "Привет, ${newUser.firstName}! $question",
                            replyMarkup = inlineKeyboardMarkup
                        )

                        // Получаем messageId из результата
                        sendPhotoResult.fold(
                            response = { messageResponse ->
                                val currentMessageId = messageResponse?.result?.messageId
                                if (currentMessageId != null) {
                                    userCaptchaMap[userId] = currentMessageId
                                }
                            },
                            error = { error ->
                                println("Ошибка при отправке фото: $error")
                            }
                        )
                    }
                }
            }

            // Обработчик для нажатия кнопок
            callbackQuery {
                val callbackQuery = update.callbackQuery!!
                val userId = callbackQuery.from.id
                val answer = callbackQuery.data.toIntOrNull()
                val chatId = callbackQuery.message!!.chat.id
                val messageId = callbackQuery.message!!.messageId

                // Получаем список изображений для текущего пользователя
                val imageList = loadAndProcessImages(path, "$path/user_$userId")

                if (!callbackQuery.from.isBot && imageList.isNotEmpty() && userCaptchaMap.containsKey(userId) && userCaptchaMap[userId] == callbackQuery.message?.messageId) {
                    val isCorrect = answer == imageList.firstOrNull { it.isRight }?.number
                    val remainingAttempts = userAttemptsMap[userId] ?: 0

                    if (isCorrect) {
                        // Пользователь прошел капчу успешно
                        bot.answerCallbackQuery(
                            callbackQuery.id,
                            text = "Правильно! Добро пожаловать в группу.",
                            showAlert = true
                        )

                        // Снимаем ограничения с пользователя
                        bot.restrictChatMember(
                            chatId = ChatId.fromId(chatId),
                            userId = userId,
                            chatPermissions = ChatPermissions(canSendMessages = true)
                        )

                        // Удаляем сообщение с капчей
                        bot.deleteMessage(
                            chatId = ChatId.fromId(chatId),
                            messageId = messageId
                        )

                        // Удаляем все сгенерированные изображения
                        deleteGeneratedImages(path, userId)

                        // Удаляем записи о пользователе
                        userCaptchaMap.remove(userId)
                        userAttemptsMap.remove(userId)
                    } else {
                        // Неверный ответ
                        if (remainingAttempts > 1) {
                            // Уменьшаем количество оставшихся попыток
                            userAttemptsMap[userId] = remainingAttempts - 1
                            bot.answerCallbackQuery(
                                callbackQuery.id,
                                text = "Неправильно! Попробуйте снова. Осталось попыток: ${remainingAttempts - 1}",
                                showAlert = true
                            )
                        } else {
                            // Удаляем пользователя из группы и блокируем его, если ответ неправильный и попытки закончились
                            bot.answerCallbackQuery(
                                callbackQuery.id,
                                text = "Вы исчерпали все попытки. Вы будете заблокированы.",
                                showAlert = true
                            )

                            // Блокируем и удаляем пользователя из группы
                            bot.banChatMember(
                                chatId = ChatId.fromId(chatId),
                                userId = userId,
                                untilDate = System.currentTimeMillis() / 1000 + 60
                            )

                            // Удаляем сообщение с капчей
                            bot.deleteMessage(
                                chatId = ChatId.fromId(chatId),
                                messageId = messageId
                            )

                            // Удаляем все сгенерированные изображения
                            deleteGeneratedImages(path, userId)

                            // Удаляем записи о пользователе
                            userCaptchaMap.remove(userId)
                            userAttemptsMap.remove(userId)
                        }
                    }

                    // Подтверждаем обработку callback-запроса
                    bot.answerCallbackQuery(callbackQuery.id)
                } else {
                    // Отправляем уведомление, что только конкретный пользователь может решить капчу
                    bot.answerCallbackQuery(
                        callbackQuery.id,
                        text = "Эта капча не для вас.",
                        showAlert = true
                    )
                }
            }
        }
    }

    bot.startPolling()
}

// Функция для удаления сгенерированных изображений
fun deleteGeneratedImages(basePath: String, userId: Long) {
    val userPath = "$basePath/user_$userId"
    File(userPath).listFiles()?.forEach { file -> file.delete() }
    File(userPath).delete()
}

// Функция для загрузки изображений из папки и добавления на них номеров с использованием AWT
fun loadAndProcessImages(inputPath: String, outputPath: String): List<Image> {
    val dir = File(inputPath)
    val images = mutableListOf<Image>()
    if (dir.exists() && dir.isDirectory) {
        val files = dir.listFiles { file -> file.isFile }
        files?.forEachIndexed { index, file ->
            // Загружаем изображение с помощью ImageIO
            val img = ImageIO.read(file)

            // Создаем новое изображение с номером
            val newImg = addNumberToImage(img, index)

            // Сохраняем измененное изображение в папке пользователя
            val newOutputPath = "$outputPath/numbered_${index}.png"
            ImageIO.write(newImg, "png", File(newOutputPath))

            // Определяем, является ли изображение правильным по суффиксу '_correct'
            val isRight = file.name.contains("_correct")

            // Добавляем изображение в список, одно из них будет правильным
            images.add(Image(newOutputPath, index, isRight))
        }
    }
    return images
}

// Функция для изменения размера изображения, добавления номера и черной рамки
fun addNumberToImage(img: BufferedImage, number: Int): BufferedImage {
    // Определяем новый размер
    val targetWidth = 200
    val targetHeight = 200

    // Создаем изображение с новым размером
    val resizedImg = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB)
    val g = resizedImg.createGraphics()
    g.drawImage(img, 0, 0, targetWidth, targetHeight, null)
    g.dispose()

    // Создаем новое изображение для добавления рамки и номера
    val newImg = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB)
    val g2d = newImg.createGraphics()

    // Рисуем измененное изображение
    g2d.drawImage(resizedImg, 0, 0, null)

    // Устанавливаем цвет для рамки и рисуем черный прямоугольник по границе
    g2d.color = Color.BLACK
    g2d.drawRect(0, 0, targetWidth - 1, targetHeight - 1)

    // Устанавливаем цвет и шрифт для номера
    g2d.color = Color.RED
    g2d.font = Font("Arial", Font.BOLD, 20)

    // Добавляем номер изображения
    g2d.drawString(number.toString(), 10, 30)
    g2d.dispose()

    return newImg
}



// Функция для склеивания изображений с максимальной шириной в 3 изображения
fun combineImages(images: List<Image>, maxWidth: Int): BufferedImage {
    val bufferedImages = images.map { ImageIO.read(File(it.path)) }
    val imageWidth = bufferedImages[0].width
    val imageHeight = bufferedImages[0].height

    // Определяем размеры итогового изображения
    val rows = (bufferedImages.size + maxWidth - 1) / maxWidth
    val combinedWidth = maxWidth * imageWidth
    val combinedHeight = rows * imageHeight

    // Создаем пустое изображение для склейки
    val combinedImage = BufferedImage(combinedWidth, combinedHeight, BufferedImage.TYPE_INT_ARGB)
    val g: Graphics = combinedImage.graphics

    // Рисуем изображения в итоговом изображении
    bufferedImages.forEachIndexed { index, img ->
        val x = (index % maxWidth) * imageWidth
        val y = (index / maxWidth) * imageHeight
        g.drawImage(img, x, y, null)
    }

    g.dispose()
    return combinedImage
}
