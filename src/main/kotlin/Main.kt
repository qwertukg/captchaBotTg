package kz.qwertukg

import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.callbackQuery
import com.github.kotlintelegrambot.dispatcher.newChatMembers
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import com.github.kotlintelegrambot.entities.TelegramFile
import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
import org.opencv.core.Core
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File

data class Image(val path: String, val number: Int, val isRight: Boolean)

fun main() {
//    System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
    val botToken = System.getenv("BOT_TOKEN")

    // Создаем бота
    val bot = bot {
        token = botToken

        // Хранение идентификатора пользователя и соответствующей капчи
        val userCaptchaMap = mutableMapOf<Long, Long>()
        val imageList = loadAndProcessImages("/images")

        dispatch {
            // Обработчик добавления нового пользователя в группу
            newChatMembers {
                val newUser = update.message?.newChatMembers?.firstOrNull()

                if (newUser != null && imageList.isNotEmpty()) {
                    // Генерируем вопрос с изображениями
                    val correctImage = imageList.first { it.isRight }
                    val incorrectImages = imageList.filter { !it.isRight }.shuffled().take(5)

                    val imagesForCaptcha = (listOf(correctImage) + incorrectImages).shuffled()

                    val inlineKeyboardMarkup = InlineKeyboardMarkup.createSingleRowKeyboard(
                        imagesForCaptcha.map { image ->
                            InlineKeyboardButton.CallbackData(
                                text = image.number.toString(),
                                callbackData = image.number.toString()
                            )
                        }
                    )

                    // Отправляем сообщение с вопросом и кнопками новому пользователю
                    val messageId = bot.sendMessage(
                        chatId = ChatId.fromId(update.message!!.chat.id),
                        text = "Выберите правильное изображение.",
                        replyMarkup = inlineKeyboardMarkup
                    ).getOrNull()?.messageId

                    // Отправляем изображения пользователю
                    imagesForCaptcha.forEach { image ->
                        val telegramFile = TelegramFile.ByFile(File(image.path))
                        bot.sendPhoto(
                            chatId = ChatId.fromId(update.message!!.chat.id),
                            photo = telegramFile
                        )
                    }

                    // Сохраняем ID пользователя и ID сообщения с капчей
                    if (messageId != null) {
                        userCaptchaMap[newUser.id] = messageId
                    }
                }
            }

            // Обработчик для нажатия кнопок
            callbackQuery {
                val callbackQuery = update.callbackQuery!!
                val userId = callbackQuery.from.id
                val answer = callbackQuery.data.toIntOrNull()

                // Проверяем, что только пользователь, которому была отправлена капча, может на неё ответить
                if (userCaptchaMap.containsKey(userId) && userCaptchaMap[userId] == callbackQuery.message?.messageId) {
                    val responseText = if (answer == imageList.firstOrNull { it.isRight }?.number) {
                        "Правильно! Добро пожаловать в группу."
                    } else {
                        "Неправильно! Попробуйте снова."
                    }

                    // Отправляем ответ пользователю
                    bot.sendMessage(chatId = ChatId.fromId(callbackQuery.message!!.chat.id), text = responseText)

                    // Подтверждаем обработку callback-запроса
                    bot.answerCallbackQuery(callbackQuery.id)

                    // Удаляем пользователя из группы, если ответ неправильный
                    if (answer != imageList.firstOrNull { it.isRight }?.number) {
                        bot.banChatMember(
                            chatId = ChatId.fromId(callbackQuery.message!!.chat.id),
                            userId = userId
                        )
                    } else {
                        // Удаляем запись о пользователе, если ответ правильный
                        userCaptchaMap.remove(userId)
                    }
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

// Функция для загрузки изображений из папки и добавления на них номеров с использованием OpenCV
fun loadAndProcessImages(directoryPath: String): List<Image> {
    val dir = File(directoryPath)
    val images = mutableListOf<Image>()
    if (dir.exists() && dir.isDirectory) {
        val files = dir.listFiles { file -> file.isFile }
        files?.forEachIndexed { index, file ->
            // Загружаем изображение с помощью OpenCV
            val img = Imgcodecs.imread(file.path)

            // Добавляем номер на изображение
            Imgproc.putText(
                img,
                (index + 1).toString(),
                Point(10.0, 30.0),
                Imgproc.FONT_HERSHEY_SIMPLEX,
                1.0,
                Scalar(255.0, 0.0, 0.0),
                2
            )

            // Сохраняем измененное изображение
            val outputPath = "${file.parent}/numbered_${index + 1}.png"
            Imgcodecs.imwrite(outputPath, img)

            // Добавляем изображение в список, одно из них будет правильным
            images.add(Image(outputPath, index + 1, isRight = (index == 0))) // Первое изображение правильное
        }
    }
    return images
}