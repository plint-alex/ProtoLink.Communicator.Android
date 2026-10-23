package ru.protolink.communicator.util

/**
 * Change-password copy + client validation (docs/features/change-password;
 * same rules as web Register min 5). Parity with Windows ChangePasswordStrings.
 */
object ChangePasswordStrings {
    const val MinPasswordLength = 5

    fun title(lang: String?): String =
        if (ShareSheetCloudStrings.isRussian(lang)) "Смена пароля" else "Change password"

    fun menuLabel(lang: String?): String =
        if (ShareSheetCloudStrings.isRussian(lang)) "Сменить пароль" else "Change password"

    fun menuHint(lang: String?): String =
        if (ShareSheetCloudStrings.isRussian(lang))
            "Обновить пароль аккаунта"
        else
            "Update your account password"

    fun fieldCurrent(lang: String?): String =
        if (ShareSheetCloudStrings.isRussian(lang)) "Текущий пароль" else "Current password"

    fun fieldNew(lang: String?): String =
        if (ShareSheetCloudStrings.isRussian(lang)) "Новый пароль" else "New password"

    fun fieldConfirm(lang: String?): String =
        if (ShareSheetCloudStrings.isRussian(lang)) "Подтвердите новый пароль" else "Confirm new password"

    fun submit(lang: String?): String =
        if (ShareSheetCloudStrings.isRussian(lang)) "Сохранить пароль" else "Save password"

    fun cancel(lang: String?): String =
        if (ShareSheetCloudStrings.isRussian(lang)) "Отмена" else "Cancel"

    fun success(lang: String?): String =
        if (ShareSheetCloudStrings.isRussian(lang)) "Пароль обновлён." else "Password updated."

    fun successHint(lang: String?): String =
        if (ShareSheetCloudStrings.isRussian(lang))
            "На других устройствах может потребоваться повторный вход."
        else
            "Other devices may need to sign in again."

    fun errorCurrent(lang: String?): String =
        if (ShareSheetCloudStrings.isRussian(lang))
            "Неверный текущий пароль."
        else
            "Current password is incorrect."

    fun validationRequired(lang: String?): String =
        if (ShareSheetCloudStrings.isRussian(lang)) "Обязательное поле" else "Required"

    fun validationMin(lang: String?): String =
        if (ShareSheetCloudStrings.isRussian(lang))
            "Пароль не короче 5 символов"
        else
            "Password must be at least 5 characters"

    fun validationMismatch(lang: String?): String =
        if (ShareSheetCloudStrings.isRussian(lang)) "Пароли не совпадают" else "Passwords do not match"

    /** Client-side validation before Api call. Null = OK. */
    fun validate(
        currentPassword: String?,
        newPassword: String?,
        confirmPassword: String?,
        lang: String?
    ): String? {
        if (currentPassword.isNullOrEmpty()) return validationRequired(lang)
        if (newPassword.isNullOrEmpty()) return validationRequired(lang)
        if (confirmPassword.isNullOrEmpty()) return validationRequired(lang)
        if (newPassword.length < MinPasswordLength) return validationMin(lang)
        if (newPassword != confirmPassword) return validationMismatch(lang)
        return null
    }

    /** Prefer stable wrong-current copy when Api message matches. */
    fun mapApiError(raw: String?, lang: String?): String {
        val err = raw?.trim().orEmpty()
        if (err.isEmpty()) return errorCurrent(lang)
        if (err.contains("incorrect", ignoreCase = true) || err.contains("неверн", ignoreCase = true)) {
            return errorCurrent(lang)
        }
        return err
    }
}
