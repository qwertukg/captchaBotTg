package kz.qwertukg

/**
 * Класс для хранения информации об изображении.
 *
 * @property path Путь к изображению.
 * @property number Номер изображения.
 * @property isCorrect Флаг, является ли изображение правильным.
 */
data class Image(val path: String, val number: Int, val isCorrect: Boolean)