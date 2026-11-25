// PortedFrom: MC1/Extensions/Errors/KeychainError+UserFacingMessage.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Native adaptation: real storage problem/operation families without fabricated Apple OSStatus or secret-bearing provider copy.
package com.meshcoreone.android.core.ui

import com.meshcoreone.android.core.datastore.StorageFailure
import com.meshcoreone.android.core.datastore.StorageOperation
import com.meshcoreone.android.core.datastore.StorageProblem

enum class NativeStorageErrorFamily { ENCODING, STORAGE, RETRIEVAL, DELETION }

val StorageFailure.nativeFamily: NativeStorageErrorFamily get() = when {
    problem == StorageProblem.InvalidSecretIdentity || problem == StorageProblem.InvalidSecretValue -> NativeStorageErrorFamily.ENCODING
    operation == StorageOperation.DELETE -> NativeStorageErrorFamily.DELETION
    operation == StorageOperation.WRITE || operation == StorageOperation.ROTATE -> NativeStorageErrorFamily.STORAGE
    else -> NativeStorageErrorFamily.RETRIEVAL
}

fun storageErrorPresentation(failure: StorageFailure): UiErrorMapping {
    val family = when (failure.nativeFamily) {
        NativeStorageErrorFamily.ENCODING -> R.string.ui_storage_encoding_failed
        NativeStorageErrorFamily.STORAGE -> R.string.ui_storage_save_failed
        NativeStorageErrorFamily.RETRIEVAL -> R.string.ui_storage_read_failed
        NativeStorageErrorFamily.DELETION -> R.string.ui_storage_delete_failed
    }
    val detail: UiText = when (val problem = failure.problem) {
        StorageProblem.LockedBeforeFirstUnlock -> UiText.Resource(R.string.ui_storage_first_unlock)
        StorageProblem.DeviceLocked -> UiText.Resource(R.string.ui_storage_locked)
        StorageProblem.UserStorageInaccessible -> UiText.Resource(R.string.ui_storage_inaccessible)
        StorageProblem.PermissionDenied -> UiText.Resource(R.string.ui_storage_permission_denied)
        StorageProblem.IoFailure -> UiText.Resource(R.string.ui_storage_io_failed)
        StorageProblem.CorruptPreferences -> UiText.Resource(R.string.ui_storage_corrupt_preferences)
        StorageProblem.CorruptSecretState -> UiText.Resource(R.string.ui_storage_corrupt_state)
        StorageProblem.CorruptEnvelope -> UiText.Resource(R.string.ui_storage_corrupt_envelope)
        StorageProblem.AuthenticationFailed -> UiText.Resource(R.string.ui_storage_authentication_failed)
        StorageProblem.FormerKeyMissing -> UiText.Resource(R.string.ui_storage_key_missing)
        StorageProblem.KeyPermanentlyInvalidated -> UiText.Resource(R.string.ui_storage_key_invalidated)
        StorageProblem.CorruptKeystoreKey -> UiText.Resource(R.string.ui_storage_key_corrupt)
        StorageProblem.ProviderFailure -> UiText.Resource(R.string.ui_storage_provider_failed)
        StorageProblem.DuplicateOwner -> UiText.Resource(R.string.ui_storage_owner_duplicate)
        StorageProblem.OwnerClosed -> UiText.Resource(R.string.ui_storage_owner_closed)
        StorageProblem.InvalidSecretIdentity -> UiText.Resource(R.string.ui_storage_invalid_identity)
        StorageProblem.InvalidSecretValue -> UiText.Resource(R.string.ui_storage_invalid_value)
        StorageProblem.SecretAlreadyExists -> UiText.Resource(R.string.ui_storage_already_exists)
        StorageProblem.SecretNotFound -> UiText.Resource(R.string.ui_storage_not_found)
        is StorageProblem.UnsupportedVersion -> formattedText(R.string.ui_storage_unsupported_version,
            UiFormatArgument.Integer(problem.version.toLong()))
        is StorageProblem.StateTooLarge -> formattedText(R.string.ui_storage_too_large,
            UiFormatArgument.Integer(problem.maximumBytes.toLong()))
        is StorageProblem.PreferenceTypeMismatch -> UiText.Resource(R.string.ui_storage_preference_type)
        is StorageProblem.PreferenceHasNoDefault -> UiText.Resource(R.string.ui_storage_preference_no_default)
        is StorageProblem.InvalidPreference -> UiText.Resource(R.string.ui_storage_preference_invalid)
    }
    val copy = generatedText("WP-304.NativeStorage.${failure.operation}.${failure.problem.javaClass.simpleName}") {
        it.getString(family) + "\n" + detail.resolve(it)
    }
    val recovery = when (failure.problem) {
        StorageProblem.LockedBeforeFirstUnlock, StorageProblem.DeviceLocked -> UiRecovery.UNLOCK_DEVICE
        StorageProblem.FormerKeyMissing, StorageProblem.KeyPermanentlyInvalidated,
        StorageProblem.CorruptKeystoreKey, StorageProblem.CorruptPreferences,
        StorageProblem.CorruptSecretState, StorageProblem.CorruptEnvelope,
        StorageProblem.AuthenticationFailed -> UiRecovery.RESTORE_SECURE_DATA
        StorageProblem.IoFailure -> UiRecovery.RETRY
        StorageProblem.InvalidSecretIdentity, StorageProblem.InvalidSecretValue,
        is StorageProblem.InvalidPreference, is StorageProblem.PreferenceTypeMismatch,
        is StorageProblem.StateTooLarge -> UiRecovery.FIX_INPUT
        else -> UiRecovery.RECOVER_STORAGE
    }
    return UiErrorMapping(copy, recovery)
}
