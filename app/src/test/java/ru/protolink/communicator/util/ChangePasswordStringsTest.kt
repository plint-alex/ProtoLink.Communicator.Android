package ru.protolink.communicator.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Client validation + RU/EN copy for Settings Change password (docs/features/change-password). */
class ChangePasswordStringsTest {
    @Test
    fun validate_requiredFields() {
        assertThat(ChangePasswordStrings.validate(null, "abcde", "abcde", "en-US"))
            .isEqualTo(ChangePasswordStrings.validationRequired("en-US"))
        assertThat(ChangePasswordStrings.validate("old", "", "abcde", "en-US"))
            .isEqualTo(ChangePasswordStrings.validationRequired("en-US"))
        assertThat(ChangePasswordStrings.validate("old", "abcde", "", "en-US"))
            .isEqualTo(ChangePasswordStrings.validationRequired("en-US"))
    }

    @Test
    fun validate_minLength() {
        assertThat(ChangePasswordStrings.validate("old", "abcd", "abcd", "en-US"))
            .isEqualTo(ChangePasswordStrings.validationMin("en-US"))
        assertThat(ChangePasswordStrings.validate("old", "12", "12", "ru-RU"))
            .isEqualTo(ChangePasswordStrings.validationMin("ru-RU"))
    }

    @Test
    fun validate_mismatch() {
        assertThat(ChangePasswordStrings.validate("old", "abcde", "abcde!", "en-US"))
            .isEqualTo(ChangePasswordStrings.validationMismatch("en-US"))
    }

    @Test
    fun validate_ok() {
        assertThat(ChangePasswordStrings.validate("old", "abcde", "abcde", "en-US")).isNull()
    }

    @Test
    fun copy_enAndRu() {
        assertThat(ChangePasswordStrings.menuLabel("ru-RU")).isEqualTo("Сменить пароль")
        assertThat(ChangePasswordStrings.title("ru-RU")).isEqualTo("Смена пароля")
        assertThat(ChangePasswordStrings.menuLabel("en-US")).isEqualTo("Change password")
        assertThat(ChangePasswordStrings.errorCurrent("en-US"))
            .isEqualTo("Current password is incorrect.")
        assertThat(ChangePasswordStrings.errorCurrent("ru-RU"))
            .isEqualTo("Неверный текущий пароль.")
    }

    @Test
    fun mapApiError_prefersStableWrongCurrent() {
        assertThat(ChangePasswordStrings.mapApiError("Current password is incorrect.", "en-US"))
            .isEqualTo(ChangePasswordStrings.errorCurrent("en-US"))
        assertThat(ChangePasswordStrings.mapApiError("Неверный текущий пароль.", "ru-RU"))
            .isEqualTo(ChangePasswordStrings.errorCurrent("ru-RU"))
        assertThat(ChangePasswordStrings.mapApiError(null, "en-US"))
            .isEqualTo(ChangePasswordStrings.errorCurrent("en-US"))
        assertThat(ChangePasswordStrings.mapApiError("Network down", "en-US"))
            .isEqualTo("Network down")
    }
}
